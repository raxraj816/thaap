package com.thaap.payments.fraud;

import com.thaap.payments.tenant.MerchantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FraudDecisionController {

    private final FraudDecisionRepository fraudDecisionRepository;
    private final FeatureSnapshotRepository featureSnapshotRepository;

    public FraudDecisionController(FraudDecisionRepository fraudDecisionRepository,
            FeatureSnapshotRepository featureSnapshotRepository) {
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
    }

    @GetMapping("/fraud/decisions")
    public List<FraudDecisionResponse> listDecisions(MerchantContext merchantContext) {
        return fraudDecisionRepository.findByMerchantIdOrderByCreatedAtAsc(merchantContext.merchantId()).stream()
                .map(FraudDecisionResponse::from)
                .toList();
    }

    @GetMapping("/fraud/decisions/{id}")
    public FraudDecisionDetailResponse getDecision(MerchantContext merchantContext, @PathVariable UUID id) {
        FraudDecision decision = fraudDecisionRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FraudDecision was not found"));
        FeatureSnapshotResponse featureSnapshot = featureSnapshotRepository
                .findByFraudDecisionIdAndMerchantId(decision.getId(), merchantContext.merchantId())
                .map(FeatureSnapshotResponse::from)
                .orElse(null);
        return FraudDecisionDetailResponse.from(decision, featureSnapshot);
    }

    public record FraudDecisionResponse(UUID id, UUID merchantId, UUID paymentAttemptId, BigDecimal riskScore,
            FraudRiskLevel riskLevel, FraudDecisionAction action, String modelVersion, String rulesetVersion,
            long latencyMs, Instant decisionStartedAt, Instant decisionCompletedAt,
            FeatureFreshnessStatus featureFreshnessStatus, long maxFeatureAgeMs,
            List<FraudDecisionReasonCode> reasonCodes, Instant createdAt) {
        static FraudDecisionResponse from(FraudDecision decision) {
            return new FraudDecisionResponse(decision.getId(), decision.getMerchantId(), decision.getPaymentAttemptId(),
                    decision.getRiskScore(), decision.getRiskLevel(), decision.getAction(), decision.getModelVersion(),
                    decision.getRulesetVersion(), decision.getLatencyMs(), decision.getDecisionStartedAt(),
                    decision.getDecisionCompletedAt(), decision.getFeatureFreshnessStatus(),
                    decision.getMaxFeatureAgeMs(), decision.getReasonCodes(), decision.getCreatedAt());
        }
    }

    public record FraudDecisionDetailResponse(FraudDecisionResponse decision, FeatureSnapshotResponse featureSnapshot) {
        static FraudDecisionDetailResponse from(FraudDecision decision, FeatureSnapshotResponse featureSnapshot) {
            return new FraudDecisionDetailResponse(FraudDecisionResponse.from(decision), featureSnapshot);
        }
    }

    public record FeatureSnapshotResponse(UUID id, UUID merchantId, UUID fraudDecisionId, UUID paymentAttemptId,
            String featureVersion, Map<String, Object> features, Instant computedAt, Instant oldestFeatureTimestamp,
            long maxFeatureAgeMs, FeatureFreshnessStatus freshnessStatus, List<String> staleFeatureNames,
            List<String> missingFeatureNames, Instant createdAt) {
        static FeatureSnapshotResponse from(FeatureSnapshot featureSnapshot) {
            return new FeatureSnapshotResponse(featureSnapshot.getId(), featureSnapshot.getMerchantId(),
                    featureSnapshot.getFraudDecisionId(), featureSnapshot.getPaymentAttemptId(),
                    featureSnapshot.getFeatureVersion(), featureSnapshot.getFeatures(), featureSnapshot.getComputedAt(),
                    featureSnapshot.getOldestFeatureTimestamp(), featureSnapshot.getMaxFeatureAgeMs(),
                    featureSnapshot.getFreshnessStatus(), featureSnapshot.getStaleFeatureNames(),
                    featureSnapshot.getMissingFeatureNames(), featureSnapshot.getCreatedAt());
        }
    }
}
