package com.thaap.payments.projection.rebuild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads authoritative source tables without mutating or locking payment truth. */
@Component
public class ProjectionBootstrapSourceReader {

    private static final BigDecimal BASIS_POINTS = new BigDecimal("10000");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProjectionBootstrapSourceReader(JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ProjectionBootstrapPaymentFact> readLegacyPayments(Instant sourceCutoffAt) {
        List<PaymentBase> payments = jdbcTemplate.query("""
                SELECT intent.merchant_id, intent.id, intent.amount, intent.currency,
                       intent.capture_method, intent.traffic_origin, intent.created_at,
                       intent.status
                FROM payment_intents intent
                WHERE NOT EXISTS (
                    SELECT 1 FROM domain_events event
                    WHERE event.merchant_id = intent.merchant_id
                      AND event.stream_type = 'PAYMENT'
                      AND event.stream_id = intent.id
                      AND event.event_type = 'payment_intent.created')
                ORDER BY intent.merchant_id, intent.id
                """, (resultSet, rowNumber) -> new PaymentBase(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getLong(3),
                        resultSet.getString(4), resultSet.getString(5),
                        resultSet.getString(6),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 7),
                        resultSet.getString(8)));
        List<ProjectionBootstrapPaymentFact> facts = new ArrayList<>(payments.size());
        for (PaymentBase payment : payments) {
            facts.add(readPayment(payment, sourceCutoffAt));
        }
        return List.copyOf(facts);
    }

    public byte[] encode(ProjectionBootstrapPaymentFact fact) {
        try {
            return objectMapper.writeValueAsBytes(fact);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Projection bootstrap fact encoding failed", exception);
        }
    }

    public ProjectionBootstrapPaymentFact decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > 32_768) {
            throw new IllegalStateException("Projection bootstrap fact size is invalid");
        }
        try {
            return objectMapper.readValue(bytes, ProjectionBootstrapPaymentFact.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Projection bootstrap fact is invalid", exception);
        }
    }

    private ProjectionBootstrapPaymentFact readPayment(PaymentBase payment,
            Instant sourceCutoffAt) {
        List<Attempt> attempts = jdbcTemplate.query("""
                SELECT attempt.attempt_number, attempt.status,
                       auth.processor_response_code
                FROM payment_attempts attempt
                LEFT JOIN authorizations auth
                  ON auth.merchant_id = attempt.merchant_id
                 AND auth.payment_attempt_id = attempt.id
                WHERE attempt.merchant_id = ? AND attempt.payment_intent_id = ?
                ORDER BY attempt.attempt_number, attempt.id
                """, (resultSet, rowNumber) -> new Attempt(resultSet.getInt(1),
                        resultSet.getString(2), resultSet.getString(3)),
                payment.merchantId(), payment.paymentIntentId());
        Attempt latestAttempt = attempts.isEmpty() ? null : attempts.getLast();
        long declineCount = attempts.stream().filter(attempt -> isDecline(
                attempt.outcome())).count();
        long unknownOutcomeCount = attempts.stream().filter(attempt ->
                !isDecline(attempt.outcome()) && !isKnownSuccess(attempt.outcome())).count();

        List<Decision> decisions = jdbcTemplate.query("""
                SELECT decision.id, snapshot.id, decision.action, decision.risk_level,
                       decision.risk_score,
                       decision.reason_codes, decision.ruleset_version,
                       snapshot.feature_version, attempt.attempt_number
                FROM fraud_decisions decision
                JOIN payment_attempts attempt
                  ON attempt.merchant_id = decision.merchant_id
                 AND attempt.id = decision.payment_attempt_id
                LEFT JOIN feature_snapshots snapshot
                  ON snapshot.merchant_id = decision.merchant_id
                 AND snapshot.fraud_decision_id = decision.id
                WHERE attempt.merchant_id = ? AND attempt.payment_intent_id = ?
                ORDER BY attempt.attempt_number, decision.created_at, decision.id
                """, (resultSet, rowNumber) -> new Decision(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getBigDecimal(5),
                        normalizeJson(resultSet.getString(6)), resultSet.getString(7),
                        resultSet.getString(8)),
                payment.merchantId(), payment.paymentIntentId());
        Decision latestDecision = decisions.isEmpty() ? null : decisions.getLast();

        List<AuthorizationSummary> authorizations = jdbcTemplate.query("""
                SELECT auth.authorized_amount
                FROM authorizations auth
                JOIN payment_attempts attempt
                  ON attempt.merchant_id = auth.merchant_id
                 AND attempt.id = auth.payment_attempt_id
                WHERE attempt.merchant_id = ? AND attempt.payment_intent_id = ?
                  AND auth.status = 'APPROVED'
                ORDER BY auth.created_at, auth.id
                """, (resultSet, rowNumber) -> new AuthorizationSummary(
                        resultSet.getLong(1)), payment.merchantId(),
                payment.paymentIntentId());
        long authorizedAmount = authorizations.stream().mapToLong(
                AuthorizationSummary::authorizedAmountMinor).sum();

        List<Review> reviews = jdbcTemplate.query("""
                SELECT review.status, resolution.outcome, resolution.fraud_type
                FROM manual_review_cases review
                LEFT JOIN review_resolutions resolution
                  ON resolution.merchant_id = review.merchant_id
                 AND resolution.manual_review_case_id = review.id
                WHERE review.merchant_id = ? AND review.payment_intent_id = ?
                ORDER BY review.created_at, review.id
                """, (resultSet, rowNumber) -> new Review(resultSet.getString(1),
                        resultSet.getString(2), resultSet.getString(3)),
                payment.merchantId(), payment.paymentIntentId());
        Review latestReview = reviews.isEmpty() ? null : reviews.getLast();

        List<Label> labels = jdbcTemplate.query("""
                SELECT label_value, fraud_type, source, available_at
                FROM fraud_labels
                WHERE merchant_id = ? AND payment_intent_id = ?
                ORDER BY available_at, id
                """, (resultSet, rowNumber) -> new Label(resultSet.getString(1),
                        resultSet.getString(2), resultSet.getString(3),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 4)),
                payment.merchantId(),
                payment.paymentIntentId());
        Label latestLabel = labels.isEmpty() ? null : labels.getLast();

        long captured = money("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
                WHERE merchant_id = ? AND payment_intent_id = ?
                  AND source_type = 'PAYMENT_INTENT_CAPTURE'
                  AND account = 'CUSTOMER_RECEIVABLE' AND direction = 'DEBIT'
                """, payment);
        long refunded = money("""
                SELECT COALESCE(SUM(amount), 0) FROM refunds
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, payment);
        MoneyCount chargebacks = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(allocated_amount), 0), COUNT(*)
                FROM chargebacks WHERE merchant_id = ? AND payment_intent_id = ?
                """, (resultSet, rowNumber) -> new MoneyCount(resultSet.getLong(1),
                        resultSet.getLong(2)), payment.merchantId(),
                payment.paymentIntentId());
        EventBase eventBase = jdbcTemplate.query("""
                SELECT head.current_version, event.id, event.event_record_sha256
                FROM event_stream_heads head
                JOIN domain_events event
                  ON event.merchant_id = head.merchant_id
                 AND event.stream_type = head.stream_type
                 AND event.stream_id = head.stream_id
                 AND event.stream_version = head.current_version
                WHERE head.merchant_id = ? AND head.stream_type = 'PAYMENT'
                  AND head.stream_id = ?
                """, resultSet -> resultSet.next() ? new EventBase(
                        resultSet.getLong(1), resultSet.getObject(2, UUID.class),
                        resultSet.getString(3)) : EventBase.empty(),
                payment.merchantId(), payment.paymentIntentId());
        MoneyCount safeChargebacks = chargebacks == null ? new MoneyCount(0, 0) : chargebacks;
        return new ProjectionBootstrapPaymentFact(
                ProjectionBootstrapPaymentFact.CURRENT_SCHEMA_VERSION,
                payment.merchantId(), payment.paymentIntentId(), payment.amountMinor(),
                payment.currency(), payment.captureMethod(), payment.trafficOrigin(),
                payment.createdAt(), payment.lifecycleStatus(),
                latestAttempt == null ? null : latestAttempt.number(),
                latestAttempt == null ? null : latestAttempt.outcome(),
                latestAttempt == null ? null : latestAttempt.processorCode(),
                attempts.size(), declineCount, unknownOutcomeCount,
                latestDecision == null ? null : latestDecision.action(),
                latestDecision == null ? null : latestDecision.riskLevel(),
                latestDecision == null ? null : riskScore(latestDecision.riskScore()),
                latestDecision == null ? null : latestDecision.reasonCodesJson(),
                latestDecision == null ? null : latestDecision.rulesetVersion(),
                latestDecision == null ? null : latestDecision.featureCatalogueVersion(),
                latestDecision == null ? null : latestDecision.fraudDecisionId(),
                latestDecision == null ? null : latestDecision.featureSnapshotId(),
                authorizations.size(), authorizedAmount,
                latestReview == null ? null : latestReview.status(),
                latestReview == null ? null : latestReview.outcome(), reviews.size(),
                captured, refunded, safeChargebacks.amountMinor(),
                safeChargebacks.count(),
                latestLabel == null ? null : latestLabel.value(),
                latestLabel == null ? null : latestLabel.fraudType(),
                latestLabel == null ? null : latestLabel.source(),
                latestLabel == null ? null : latestLabel.availableAt(),
                eventBase.streamVersion(), eventBase.terminalEventId(),
                eventBase.terminalEventRecordSha256(), sourceCutoffAt);
    }

    private long money(String sql, PaymentBase payment) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, payment.merchantId(),
                payment.paymentIntentId());
        return value == null ? 0 : value;
    }

    private int riskScore(BigDecimal value) {
        return value.multiply(BASIS_POINTS).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
    }

    private String normalizeJson(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value == null ? "[]" : value);
            return objectMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Source risk reason codes are invalid", exception);
        }
    }

    private boolean isDecline(String outcome) {
        return outcome != null && (outcome.contains("DECLIN") || outcome.contains("FAIL")
                || outcome.contains("BLOCK"));
    }

    private boolean isKnownSuccess(String outcome) {
        return outcome != null && (outcome.contains("SUCCEED") || outcome.contains("APPROV")
                || outcome.contains("CAPTUR") || outcome.contains("REQUIRES_ACTION")
                || outcome.contains("AUTHORIZED"));
    }

    private record PaymentBase(UUID merchantId, UUID paymentIntentId, long amountMinor,
            String currency, String captureMethod, String trafficOrigin, Instant createdAt,
            String lifecycleStatus) {
    }

    private record Attempt(int number, String outcome, String processorCode) {
    }

    private record Decision(UUID fraudDecisionId, UUID featureSnapshotId, String action,
            String riskLevel, BigDecimal riskScore, String reasonCodesJson, String rulesetVersion,
            String featureCatalogueVersion) {
    }

    private record AuthorizationSummary(long authorizedAmountMinor) {
    }

    private record Review(String status, String outcome, String fraudType) {
    }

    private record Label(String value, String fraudType, String source, Instant availableAt) {
    }

    private record MoneyCount(long amountMinor, long count) {
    }

    private record EventBase(long streamVersion, UUID terminalEventId,
            String terminalEventRecordSha256) {
        private static EventBase empty() {
            return new EventBase(0, null, null);
        }
    }
}
