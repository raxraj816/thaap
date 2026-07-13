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
    private final RuleExecutionResultRepository ruleExecutionResultRepository;

    public FraudDecisionController(FraudDecisionRepository fraudDecisionRepository,
            FeatureSnapshotRepository featureSnapshotRepository,
            RuleExecutionResultRepository ruleExecutionResultRepository) {
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.ruleExecutionResultRepository = ruleExecutionResultRepository;
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
        List<RuleExecutionResultResponse> ruleResults = ruleExecutionResultRepository
                .findByFraudDecisionIdAndMerchantIdOrderByPriorityAscRuleCodeAsc(
                        decision.getId(), merchantContext.merchantId())
                .stream().map(RuleExecutionResultResponse::from).toList();
        return FraudDecisionDetailResponse.from(decision, featureSnapshot, ruleResults);
    }

    public record FraudDecisionResponse(UUID id, UUID merchantId, UUID paymentAttemptId, BigDecimal riskScore,
            FraudRiskLevel riskLevel, FraudDecisionAction action, String modelVersion, String rulesetVersion,
            long latencyMs, Instant decisionStartedAt, Instant decisionCompletedAt,
            FeatureFreshnessStatus featureFreshnessStatus, long maxFeatureAgeMs,
            List<FraudDecisionReasonCode> reasonCodes, com.thaap.payments.tenant.TrafficOrigin trafficOrigin,
            Instant createdAt) {
        static FraudDecisionResponse from(FraudDecision decision) {
            return new FraudDecisionResponse(decision.getId(), decision.getMerchantId(), decision.getPaymentAttemptId(),
                    decision.getRiskScore(), decision.getRiskLevel(), decision.getAction(), decision.getModelVersion(),
                    decision.getRulesetVersion(), decision.getLatencyMs(), decision.getDecisionStartedAt(),
                    decision.getDecisionCompletedAt(), decision.getFeatureFreshnessStatus(),
                    decision.getMaxFeatureAgeMs(), decision.getReasonCodes(), decision.getTrafficOrigin(),
                    decision.getCreatedAt());
        }
    }

    public record FraudDecisionDetailResponse(FraudDecisionResponse decision, FeatureSnapshotResponse featureSnapshot,
            List<RuleExecutionResultResponse> ruleExecutionResults) {
        static FraudDecisionDetailResponse from(FraudDecision decision, FeatureSnapshotResponse featureSnapshot,
                List<RuleExecutionResultResponse> ruleExecutionResults) {
            return new FraudDecisionDetailResponse(FraudDecisionResponse.from(decision), featureSnapshot,
                    List.copyOf(ruleExecutionResults));
        }
    }

    public record RuleExecutionResultResponse(UUID id, String ruleCode, String ruleVersion, int priority,
            boolean matched, FraudDecisionAction action, FraudDecisionReasonCode reasonCode,
            RuleEvaluationOutcome evaluationOutcome, long latencyUs,
            com.thaap.payments.tenant.TrafficOrigin trafficOrigin, Instant createdAt) {
        static RuleExecutionResultResponse from(RuleExecutionResult result) {
            return new RuleExecutionResultResponse(result.getId(), result.getRuleCode(), result.getRuleVersion(),
                    result.getPriority(), result.isMatched(), result.getAction(), result.getReasonCode(),
                    result.getEvaluationOutcome(), result.getLatencyUs(), result.getTrafficOrigin(),
                    result.getCreatedAt());
        }
    }

    public record FeatureSnapshotResponse(UUID id, UUID merchantId, UUID fraudDecisionId, UUID paymentAttemptId,
            String featureVersion, Map<String, Object> features, Instant computedAt, Instant oldestFeatureTimestamp,
            long maxFeatureAgeMs, FeatureFreshnessStatus freshnessStatus, List<String> staleFeatureNames,
            List<String> missingFeatureNames, com.thaap.payments.tenant.TrafficOrigin trafficOrigin,
            Instant createdAt) {
        static FeatureSnapshotResponse from(FeatureSnapshot featureSnapshot) {
            return new FeatureSnapshotResponse(featureSnapshot.getId(), featureSnapshot.getMerchantId(),
                    featureSnapshot.getFraudDecisionId(), featureSnapshot.getPaymentAttemptId(),
                    featureSnapshot.getFeatureVersion(), featureSnapshot.getFeatures(), featureSnapshot.getComputedAt(),
                    featureSnapshot.getOldestFeatureTimestamp(), featureSnapshot.getMaxFeatureAgeMs(),
                    featureSnapshot.getFreshnessStatus(), featureSnapshot.getStaleFeatureNames(),
                    featureSnapshot.getMissingFeatureNames(), featureSnapshot.getTrafficOrigin(),
                    featureSnapshot.getCreatedAt());
        }
    }
}
