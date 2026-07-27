package com.thaap.payments.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MetricResult;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringRequest;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.SummaryView;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.riskpolicy.MerchantRiskProfileReasonCode;
import com.thaap.payments.riskpolicy.MerchantRiskTier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantRiskMonitoringSnapshotService {

    private static final long MINIMUM_RATE_DENOMINATOR = 20;

    private final MerchantRiskMonitoringQueryService queryService;
    private final MerchantRiskMetricSnapshotRepository snapshotRepository;
    private final MerchantRiskProfileRecommendationRepository recommendationRepository;
    private final RiskOperatorRepository operatorRepository;
    private final AuditLogService auditLogService;
    private final MerchantRiskMonitoringMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantRiskMonitoringSnapshotService(MerchantRiskMonitoringQueryService queryService,
            MerchantRiskMetricSnapshotRepository snapshotRepository,
            MerchantRiskProfileRecommendationRepository recommendationRepository,
            RiskOperatorRepository operatorRepository, AuditLogService auditLogService,
            MerchantRiskMonitoringMetrics metrics, ObjectMapper objectMapper, Clock clock) {
        this.queryService = queryService;
        this.snapshotRepository = snapshotRepository;
        this.recommendationRepository = recommendationRepository;
        this.operatorRepository = operatorRepository;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public SnapshotView create(RiskOperatorContext context, MonitoringRequest request, String requestId) {
        requireManager(context);
        SummaryView summary = queryService.summary(context, request);
        String resultJson = write(summary);
        Instant now = clock.instant();
        MerchantRiskMetricSnapshot snapshot = snapshotRepository.saveAndFlush(
                new MerchantRiskMetricSnapshot(context.merchantId(), context.operatorId(),
                        summary.definitionVersion(), summary.windowStart(), summary.windowEnd(),
                        summary.asOf(), summary.trafficOriginPolicy(), resultJson,
                        sha256(resultJson), now));
        RecommendationView recommendation = createRecommendationIfNeeded(snapshot, summary, now);
        auditLogService.recordRiskOperatorAction(context, requestId,
                "merchant_risk_monitoring.snapshot_created", "merchant_risk_metric_snapshot",
                snapshot.getId(), "Immutable denominator-defined merchant risk monitoring snapshot",
                null, Map.of("definitionVersion", summary.definitionVersion(),
                        "trafficOriginPolicy", summary.trafficOriginPolicy().name(),
                        "recommendationCreated", recommendation != null));
        metrics.recordSnapshotAfterCompletion(recommendation == null
                ? "SNAPSHOT_ONLY" : "SNAPSHOT_WITH_RECOMMENDATION");
        return view(snapshot, summary, recommendation);
    }

    @Transactional(readOnly = true)
    public SnapshotView get(RiskOperatorContext context, UUID snapshotId) {
        MerchantRiskMetricSnapshot snapshot = snapshotRepository.findByIdAndMerchantId(
                        snapshotId, context.merchantId())
                .orElseThrow(() -> notFound());
        SummaryView summary = read(snapshot.getResultJson());
        RecommendationView recommendation = recommendationRepository
                .findByMerchantIdAndMetricSnapshotId(context.merchantId(), snapshotId)
                .map(this::view).orElse(null);
        return view(snapshot, summary, recommendation);
    }

    private void requireManager(RiskOperatorContext context) {
        RiskOperator operator = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .filter(candidate -> candidate.getRole() == RiskOperatorRole.RISK_MANAGER)
                .orElseThrow(() -> notFound());
        if (!operator.getOperatorCode().equals(context.operatorCode())) {
            throw notFound();
        }
    }

    private RecommendationView createRecommendationIfNeeded(MerchantRiskMetricSnapshot snapshot,
            SummaryView summary, Instant now) {
        if (summary.trafficOriginPolicy() != MonitoringTrafficOriginPolicy.MERCHANT_API_ONLY) {
            return null;
        }
        RecommendationCandidate candidate = recommendation(summary);
        if (candidate == null) {
            return null;
        }
        MerchantRiskProfileRecommendation recommendation = recommendationRepository.saveAndFlush(
                new MerchantRiskProfileRecommendation(snapshot.getMerchantId(), snapshot.getId(),
                        candidate.tier(), candidate.metricCode(),
                        candidate.metricCode() == MerchantRiskMetricCode.REVIEW_BACKLOG
                                ? MerchantRiskProfileReasonCode.MERCHANT_RISK_CHANGE
                                : MerchantRiskProfileReasonCode.FRAUD_EXPOSURE_CHANGE,
                        now));
        return view(recommendation);
    }

    private RecommendationCandidate recommendation(SummaryView summary) {
        RecommendationCandidate high = firstMatch(summary, MerchantRiskTier.HIGH,
                new Threshold(MerchantRiskMetricCode.CONFIRMED_FRAUD_RATE,
                        new BigDecimal("0.050000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.CHARGEBACK_COUNT_RATE,
                        new BigDecimal("0.030000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.ENUMERATION_RATIO,
                        new BigDecimal("0.200000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.REVIEW_BACKLOG,
                        new BigDecimal("25"), 0));
        if (high != null) {
            return high;
        }
        return firstMatch(summary, MerchantRiskTier.ELEVATED,
                new Threshold(MerchantRiskMetricCode.CONFIRMED_FRAUD_RATE,
                        new BigDecimal("0.020000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.CHARGEBACK_COUNT_RATE,
                        new BigDecimal("0.010000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.ENUMERATION_RATIO,
                        new BigDecimal("0.100000"), MINIMUM_RATE_DENOMINATOR),
                new Threshold(MerchantRiskMetricCode.REVIEW_BACKLOG,
                        new BigDecimal("10"), 0));
    }

    private RecommendationCandidate firstMatch(SummaryView summary, MerchantRiskTier tier,
            Threshold... thresholds) {
        for (Threshold threshold : thresholds) {
            MetricResult metric = summary.metrics().stream()
                    .filter(candidate -> candidate.metricCode() == threshold.metricCode())
                    .findFirst().orElseThrow();
            boolean coverageValid = metric.coverageStatus() == MonitoringCoverageStatus.COMPLETE;
            long denominator = metric.denominator() == null
                    ? 0 : metric.denominator().longValueExact();
            if (coverageValid && metric.value() != null
                    && denominator >= threshold.minimumDenominator()
                    && metric.value().compareTo(threshold.minimumValue()) >= 0) {
                return new RecommendationCandidate(tier, threshold.metricCode());
            }
        }
        return null;
    }

    private SnapshotView view(MerchantRiskMetricSnapshot snapshot, SummaryView summary,
            RecommendationView recommendation) {
        return new SnapshotView(snapshot.getId(), snapshot.getMerchantId(),
                snapshot.getCreatedByOperatorId(), snapshot.getResultSha256(),
                snapshot.getCreatedAt(), summary, recommendation);
    }

    private RecommendationView view(MerchantRiskProfileRecommendation recommendation) {
        return new RecommendationView(recommendation.getId(), recommendation.getRecommendedTier(),
                recommendation.getReasonMetricCode(), recommendation.getReasonCode(),
                recommendation.getCreatedAt(), false);
    }

    private String write(SummaryView summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Merchant risk monitoring result could not be serialized", exception);
        }
    }

    private SummaryView read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, SummaryView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Merchant risk monitoring result could not be read", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Merchant risk monitoring resource was not found");
    }

    public record SnapshotView(UUID id, UUID merchantId, UUID createdByOperatorId,
            String resultSha256, Instant createdAt, SummaryView summary,
            RecommendationView recommendation) { }

    public record RecommendationView(UUID id, MerchantRiskTier recommendedTier,
            MerchantRiskMetricCode reasonMetricCode, MerchantRiskProfileReasonCode reasonCode,
            Instant createdAt, boolean automaticallyActivated) { }

    private record Threshold(MerchantRiskMetricCode metricCode, BigDecimal minimumValue,
            long minimumDenominator) { }

    private record RecommendationCandidate(MerchantRiskTier tier,
            MerchantRiskMetricCode metricCode) { }
}
