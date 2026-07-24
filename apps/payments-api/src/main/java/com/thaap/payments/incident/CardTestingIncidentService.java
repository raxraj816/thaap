package com.thaap.payments.incident;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CardTestingIncidentService {
    private final boolean enabled;
    private final Duration detectionWindow;
    private final int minimumAttempts;
    private final int minimumDeclines;
    private final int minimumDistinctCards;
    private final JdbcTemplate jdbcTemplate;
    private final CardTestingIncidentRepository incidentRepository;
    private final IncidentEvidenceLinkRepository evidenceRepository;
    private final CardTestingIncidentResolutionRepository resolutionRepository;
    private final RiskOperatorRepository operatorRepository;
    private final AuditLogService auditLogService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final CardTestingIncidentMetrics metrics;
    private final Clock clock;

    public CardTestingIncidentService(
            @Value("${thaap.card-testing-incidents.enabled:true}") boolean enabled,
            @Value("${thaap.card-testing-incidents.detection-window:10m}") Duration detectionWindow,
            @Value("${thaap.card-testing-incidents.minimum-attempts:5}") int minimumAttempts,
            @Value("${thaap.card-testing-incidents.minimum-declines:3}") int minimumDeclines,
            @Value("${thaap.card-testing-incidents.minimum-distinct-cards:3}") int minimumDistinctCards,
            JdbcTemplate jdbcTemplate, CardTestingIncidentRepository incidentRepository,
            IncidentEvidenceLinkRepository evidenceRepository,
            CardTestingIncidentResolutionRepository resolutionRepository,
            RiskOperatorRepository operatorRepository, AuditLogService auditLogService,
            WebhookDeliveryService webhookDeliveryService, CardTestingIncidentMetrics metrics, Clock clock) {
        if (detectionWindow.isNegative() || detectionWindow.isZero()
                || detectionWindow.compareTo(Duration.ofHours(24)) > 0
                || minimumAttempts < 2 || minimumDeclines < 1 || minimumDistinctCards < 2
                || minimumDeclines > minimumAttempts || minimumDistinctCards > minimumAttempts) {
            throw new IllegalArgumentException("Card-testing incident detection configuration is invalid");
        }
        this.enabled = enabled;
        this.detectionWindow = detectionWindow;
        this.minimumAttempts = minimumAttempts;
        this.minimumDeclines = minimumDeclines;
        this.minimumDistinctCards = minimumDistinctCards;
        this.jdbcTemplate = jdbcTemplate;
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
        this.resolutionRepository = resolutionRepository;
        this.operatorRepository = operatorRepository;
        this.auditLogService = auditLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public CardTestingIncident detectFromPersistedEvidence(MerchantContext context, String requestId) {
        if (!enabled) {
            return null;
        }
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 3401))",
                Object.class, context.merchantId().toString());
        Instant now = clock.instant();
        Instant windowStart = now.minus(detectionWindow);
        List<Evidence> recent = recentEvidence(context.merchantId(), windowStart);
        if (recent.isEmpty()) {
            metrics.recordAfterCompletion("detect", "NO_DURABLE_EVIDENCE");
            return null;
        }
        CardTestingIncident incident = incidentRepository.findOpenByMerchantIdForUpdate(context.merchantId())
                .orElse(null);
        List<Evidence> unlinked = recent.stream()
                .filter(evidence -> !evidenceRepository.existsByMerchantIdAndPaymentAttemptId(
                        context.merchantId(), evidence.paymentAttemptId()))
                .toList();
        if (unlinked.isEmpty()) {
            metrics.recordAfterCompletion("detect", incident == null ? "NO_NEW_EVIDENCE" : "UNCHANGED");
            return incident;
        }
        boolean created = incident == null;
        if (created) {
            CardTestingIncident.IncidentAggregate candidateAggregate = aggregate(unlinked);
            if (candidateAggregate.attemptCount() < minimumAttempts
                    || candidateAggregate.declineCount() < minimumDeclines
                    || candidateAggregate.distinctCardCount() < minimumDistinctCards) {
                metrics.recordAfterCompletion("detect", "BELOW_THRESHOLD");
                return null;
            }
            incident = incidentRepository.saveAndFlush(new CardTestingIncident(
                    context.merchantId(), windowStart, now, candidateAggregate, now));
        }
        for (Evidence evidence : unlinked) {
            evidenceRepository.save(new IncidentEvidenceLink(context.merchantId(), incident.getId(),
                    evidence.paymentAttemptId(), evidence.fraudDecisionId(), evidence.evidenceType(),
                    evidence.observedAt(), now));
        }
        evidenceRepository.flush();
        CardTestingIncident.IncidentAggregate durableAggregate = linkedAggregate(
                context.merchantId(), incident.getId());
        incident.applyAggregate(durableAggregate, now);
        incidentRepository.flush();
        if (created) {
            auditLogService.recordMerchantApiAction(context, requestId, "card_testing_incident.opened",
                    "card_testing_incident", incident.getId(),
                    "Suspected card-testing pattern derived from persisted THAAP control evidence",
                    null, state(incident));
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "card_testing_incident.opened", incident.getId(), context.trafficOrigin());
        }
        metrics.recordAfterCompletion("detect", created ? "OPENED" : "UPDATED");
        return incident;
    }

    @Transactional
    public ResolutionResult resolve(RiskOperatorContext context, UUID incidentId,
            IncidentMitigationCode mitigationCode, String requestId) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .filter(operator -> operator.getRole() == RiskOperatorRole.RISK_MANAGER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Risk resource was not found"));
        CardTestingIncident incident = incidentRepository.findByIdAndMerchantIdForUpdate(
                        incidentId, context.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "CardTestingIncident was not found"));
        CardTestingIncidentResolution existing = resolutionRepository
                .findByMerchantIdAndIncidentId(context.merchantId(), incidentId).orElse(null);
        if (existing != null) {
            if (existing.getMitigationCode() != mitigationCode) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "CardTestingIncident already has a different terminal mitigation");
            }
            metrics.recordAfterCompletion("resolve", "TERMINAL_REPLAY");
            return new ResolutionResult(incident, existing);
        }
        Instant now = clock.instant();
        incident.resolve(now);
        CardTestingIncidentResolution resolution = resolutionRepository.saveAndFlush(
                new CardTestingIncidentResolution(incident, actor, mitigationCode, now));
        incidentRepository.flush();
        auditLogService.recordRiskOperatorAction(context, requestId, "card_testing_incident.resolved",
                "card_testing_incident", incident.getId(),
                "Suspected card-testing incident resolved with closed mitigation code",
                Map.of("status", "OPEN"), Map.of("status", "RESOLVED",
                        "mitigationCode", mitigationCode.name(), "policyMutated", false));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "card_testing_incident.resolved", incident.getId(),
                com.thaap.payments.tenant.TrafficOrigin.MERCHANT_API);
        metrics.recordAfterCompletion("resolve", mitigationCode.name());
        return new ResolutionResult(incident, resolution);
    }

    private List<Evidence> recentEvidence(UUID merchantId, Instant windowStart) {
        return jdbcTemplate.query("""
                SELECT pa.id, fd.id, pm.card_fingerprint, pm.card_bin, rc.ip_identity_hmac,
                       pa.created_at,
                       CASE WHEN fd.action = 'BLOCK' THEN 'RISK_BLOCK'
                            WHEN a.status = 'DECLINED' THEN 'PROCESSOR_DECLINE'
                            ELSE 'DISTINCT_CARD_PATTERN' END AS evidence_type,
                       CASE WHEN fd.action = 'BLOCK' OR a.status = 'DECLINED' THEN 1 ELSE 0 END AS declined
                FROM payment_attempts pa
                JOIN fraud_decisions fd ON fd.merchant_id = pa.merchant_id
                    AND fd.payment_attempt_id = pa.id
                JOIN payment_methods pm ON pm.merchant_id = pa.merchant_id
                    AND pm.id = pa.payment_method_id
                LEFT JOIN authorizations a ON a.merchant_id = pa.merchant_id
                    AND a.payment_attempt_id = pa.id
                LEFT JOIN risk_contexts rc ON rc.merchant_id = pa.merchant_id
                    AND rc.payment_attempt_id = pa.id
                WHERE pa.merchant_id = ? AND pa.created_at >= ?
                ORDER BY pa.created_at, pa.id
                """, (resultSet, rowNumber) -> new Evidence(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getTimestamp(6).toInstant(),
                        IncidentEvidenceType.valueOf(resultSet.getString(7)), resultSet.getInt(8) == 1),
                merchantId, java.sql.Timestamp.from(windowStart));
    }

    private CardTestingIncident.IncidentAggregate linkedAggregate(UUID merchantId, UUID incidentId) {
        List<Evidence> evidence = jdbcTemplate.query("""
                SELECT pa.id, fd.id, pm.card_fingerprint, pm.card_bin, rc.ip_identity_hmac,
                       link.observed_at, link.evidence_type,
                       CASE WHEN link.evidence_type IN ('PROCESSOR_DECLINE', 'RISK_BLOCK') THEN 1 ELSE 0 END
                FROM incident_evidence_links link
                JOIN payment_attempts pa ON pa.merchant_id = link.merchant_id
                    AND pa.id = link.payment_attempt_id
                JOIN fraud_decisions fd ON fd.merchant_id = link.merchant_id
                    AND fd.id = link.fraud_decision_id
                JOIN payment_methods pm ON pm.merchant_id = pa.merchant_id
                    AND pm.id = pa.payment_method_id
                LEFT JOIN risk_contexts rc ON rc.merchant_id = pa.merchant_id
                    AND rc.payment_attempt_id = pa.id
                WHERE link.merchant_id = ? AND link.incident_id = ?
                ORDER BY link.observed_at, link.id
                """, (resultSet, rowNumber) -> new Evidence(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getTimestamp(6).toInstant(),
                        IncidentEvidenceType.valueOf(resultSet.getString(7)), resultSet.getInt(8) == 1),
                merchantId, incidentId);
        return aggregate(evidence);
    }

    private CardTestingIncident.IncidentAggregate aggregate(List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            throw new IllegalStateException("Card-testing incident aggregate requires durable evidence");
        }
        return new CardTestingIncident.IncidentAggregate(evidence.size(),
                Math.toIntExact(evidence.stream().filter(Evidence::declined).count()),
                Math.toIntExact(evidence.stream().map(Evidence::cardFingerprint).distinct().count()),
                Math.toIntExact(evidence.stream().map(Evidence::cardBin).distinct().count()),
                Math.toIntExact(evidence.stream().map(Evidence::ipHmac)
                        .filter(value -> value != null && !value.isBlank()).distinct().count()),
                evidence.getFirst().observedAt(), evidence.getLast().observedAt());
    }

    private Map<String, Object> state(CardTestingIncident incident) {
        return Map.of("status", incident.getStatus().name(), "triggerCode", incident.getTriggerCode(),
                "sourceAttestation", incident.getSourceAttestation(),
                "attemptCount", incident.getAttemptCount(), "declineCount", incident.getDeclineCount(),
                "distinctCardCount", incident.getDistinctCardCount(),
                "distinctBinCount", incident.getDistinctBinCount(),
                "distinctIpCount", incident.getDistinctIpCount(), "policyMutated", false);
    }

    private record Evidence(UUID paymentAttemptId, UUID fraudDecisionId, String cardFingerprint,
            String cardBin, String ipHmac, Instant observedAt, IncidentEvidenceType evidenceType,
            boolean declined) {}

    public record ResolutionResult(CardTestingIncident incident,
            CardTestingIncidentResolution resolution) {}
}
