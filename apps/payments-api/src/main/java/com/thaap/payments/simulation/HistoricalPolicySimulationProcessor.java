package com.thaap.payments.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.controls.AuthoredRuleCodec;
import com.thaap.payments.controls.AuthoredRuleEvaluator;
import com.thaap.payments.controls.AuthoredRuleSimulationSnapshot;
import com.thaap.payments.controls.SafetyExceptionSnapshot;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.riskpolicy.PolicyThresholdOperator;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.velocity.VelocityDimension;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

public class HistoricalPolicySimulationProcessor
        implements ItemProcessor<HistoricalSimulationCohortItem, HistoricalSimulationResult> {

    private final JdbcTemplate jdbcTemplate;
    private final UUID runId;
    private final RiskSimulationCandidateSnapshotService snapshotService;
    private final AuthoredRuleCodec authoredRuleCodec;
    private final AuthoredRuleEvaluator authoredRuleEvaluator;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final ObjectMapper objectMapper;
    private RunContext run;
    private String replacedRuleCode;

    public HistoricalPolicySimulationProcessor(JdbcTemplate jdbcTemplate, UUID runId,
            RiskSimulationCandidateSnapshotService snapshotService, AuthoredRuleCodec authoredRuleCodec,
            AuthoredRuleEvaluator authoredRuleEvaluator, SensitiveValueHasher sensitiveValueHasher,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.runId = runId;
        this.snapshotService = snapshotService;
        this.authoredRuleCodec = authoredRuleCodec;
        this.authoredRuleEvaluator = authoredRuleEvaluator;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.objectMapper = objectMapper;
    }

    @Override
    public HistoricalSimulationResult process(HistoricalSimulationCohortItem item) {
        RunContext context = runContext();
        RiskSimulationLabelOutcome label = label(item.paymentAttemptId(), context);
        if (!FeatureCatalogue.VERSION.equals(context.featureCatalogueVersion())) {
            return excluded(item, RiskSimulationExclusionReason.UNSUPPORTED_CATALOGUE, label);
        }
        Map<String, Feature> features = loadFeatures(context.merchantId(), item.paymentAttemptId());
        Evaluation candidate = evaluateCandidate(context, features, item);
        if (candidate.exclusionReason() != null) {
            return excluded(item, candidate.exclusionReason(), label);
        }
        FraudDecisionAction baseline = baselineAction(context, item);
        FraudDecisionAction action = candidate.suppressesAuthoredSource()
                ? baseline : stronger(baseline, candidate.action());
        boolean protectedSuppressionAttempted = false;
        return new HistoricalSimulationResult(item.paymentAttemptId(), item.fraudDecisionId(),
                item.decisionCreatedAt(), item.trafficOrigin(), item.originalAction(), action, true,
                null, label, protectedSuppressionAttempted, item.authorizationAmount(),
                item.captureAmount(), item.refundAmount());
    }

    private RunContext runContext() {
        if (run != null) {
            return run;
        }
        run = jdbcTemplate.query("""
                SELECT merchant_id, candidate_type, candidate_json, candidate_digest,
                       label_cutoff, feature_catalogue_version, execution_token
                  FROM risk_simulation_runs WHERE id = ?
                """, resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Simulation run is unavailable to its processor");
                    }
                    RiskSimulationCandidateType type = RiskSimulationCandidateType.valueOf(resultSet.getString(2));
                    return new RunContext(resultSet.getObject(1, UUID.class), type,
                            parseCandidate(type, resultSet.getString(3), resultSet.getString(4)),
                            resultSet.getTimestamp(5).toInstant(), resultSet.getString(6),
                            resultSet.getObject(7, UUID.class));
                }, runId);
        return run;
    }

    private Object parseCandidate(RiskSimulationCandidateType type, String json, String digest) {
        if (type == RiskSimulationCandidateType.MERCHANT_RISK_POLICY) {
            return snapshotService.parseAndVerify(json, digest);
        }
        if (type == RiskSimulationCandidateType.AUTHORED_RULE) {
            return snapshotService.parseAndVerifyAuthoredRule(json, digest);
        }
        if (!SecretHasher.sha256(json).equals(digest)) {
            throw new IllegalStateException("Safety exception digest does not match frozen bytes");
        }
        try {
            SafetyExceptionSnapshot snapshot = objectMapper.readValue(json, SafetyExceptionSnapshot.class);
            if (!SafetyExceptionSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
                throw new IllegalStateException("Safety exception schema is unsupported");
            }
            authoredRuleCodec.parseStored(snapshot.sourceRuleJson(), snapshot.sourceRuleDigest());
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Safety exception bytes are unreadable", exception);
        }
    }

    private Evaluation evaluateCandidate(RunContext context, Map<String, Feature> features,
            HistoricalSimulationCohortItem item) {
        Map<String, Object> typed = new HashMap<>();
        features.forEach((code, feature) -> typed.put(code, feature.typedValue()));
        if (context.type() == RiskSimulationCandidateType.AUTHORED_RULE) {
            AuthoredRuleSimulationSnapshot snapshot = (AuthoredRuleSimulationSnapshot) context.candidate();
            AuthoredRuleCodec.EncodedRule rule = authoredRuleCodec.parseStored(
                    snapshot.ruleJson(), snapshot.ruleDigest());
            return new Evaluation(authoredRuleEvaluator.matches(rule.node(), typed)
                    ? snapshot.candidateAction() : FraudDecisionAction.ALLOW, null, false);
        }
        if (context.type() == RiskSimulationCandidateType.SAFETY_EXCEPTION) {
            SafetyExceptionSnapshot exception = (SafetyExceptionSnapshot) context.candidate();
            HistoricalAuthoredEvidence source = historicalAuthoredEvidence(
                    context, item, exception.sourceRuleVersionId());
            boolean inWindow = !item.decisionCreatedAt().isBefore(exception.validFrom())
                    && item.decisionCreatedAt().isBefore(exception.expiresAt());
            boolean subjectMatches = sensitiveValueHasher.hmacSha256("risk_exception.card_fingerprint",
                    item.paymentMethodFingerprint(), exception.subjectHmacKeyVersion())
                    .equals(exception.subjectIdentityHmac());
            FraudDecisionAction historicalAction = source == null || source.effectiveAction() == null
                    ? FraudDecisionAction.ALLOW : source.effectiveAction();
            boolean suppressesHistoricalSource = source != null && source.matched()
                    && inWindow && subjectMatches;
            return new Evaluation(historicalAction, null, suppressesHistoricalSource);
        }
        return evaluatePolicy((RiskSimulationCandidateSnapshot) context.candidate(), features);
    }

    private HistoricalAuthoredEvidence historicalAuthoredEvidence(RunContext context,
            HistoricalSimulationCohortItem item, UUID sourceRuleVersionId) {
        List<HistoricalAuthoredEvidence> evidence = jdbcTemplate.query("""
                SELECT matched, effective_action
                  FROM authored_rule_evaluations
                 WHERE merchant_id = ? AND fraud_decision_id = ? AND rule_version_id = ?
                """, (resultSet, rowNumber) -> {
                    String effectiveAction = resultSet.getString(2);
                    return new HistoricalAuthoredEvidence(resultSet.getBoolean(1),
                            effectiveAction == null ? null : FraudDecisionAction.valueOf(effectiveAction));
                }, context.merchantId(), item.fraudDecisionId(), sourceRuleVersionId);
        if (evidence.size() > 1) {
            throw new IllegalStateException(
                    "Historical decision contains duplicate authored-rule evidence");
        }
        return evidence.isEmpty() ? null : evidence.getFirst();
    }

    private Evaluation evaluatePolicy(RiskSimulationCandidateSnapshot snapshot, Map<String, Feature> features) {
        FraudDecisionAction strongest = FraudDecisionAction.ALLOW;
        for (RiskSimulationCandidateSnapshot.Threshold threshold : snapshot.thresholds()) {
            Feature feature = features.get(threshold.featureCode());
            if (feature == null) {
                return new Evaluation(null, RiskSimulationExclusionReason.MISSING_FEATURE, false);
            }
            if (!threshold.featureVersion().equals(feature.version()) || threshold.valueType() != feature.type()) {
                return new Evaluation(null, RiskSimulationExclusionReason.UNSUPPORTED_CATALOGUE, false);
            }
            if (threshold.currencyScope() != null) {
                Feature currency = features.get("payment.currency");
                if (currency == null) {
                    return new Evaluation(null, RiskSimulationExclusionReason.MISSING_FEATURE, false);
                }
                if (currency.type() != FeatureValueType.STRING
                        || !threshold.currencyScope().equals(currency.stringValue())) {
                    continue;
                }
            }
            if (matches(threshold, feature)) {
                strongest = stronger(strongest, threshold.candidateAction());
            }
        }
        return new Evaluation(strongest, null, false);
    }

    private Map<String, Feature> loadFeatures(UUID merchantId, UUID attemptId) {
        List<Feature> values = jdbcTemplate.query("""
                SELECT feature_code, feature_version, value_type, long_value, boolean_value, string_value
                  FROM feature_values WHERE merchant_id = ? AND payment_attempt_id = ?
                """, (resultSet, rowNumber) -> new Feature(resultSet.getString(1), resultSet.getString(2),
                        FeatureValueType.valueOf(resultSet.getString(3)),
                        resultSet.getObject(4, Long.class), resultSet.getObject(5, Boolean.class),
                        resultSet.getString(6)), merchantId, attemptId);
        Map<String, Feature> byCode = new HashMap<>();
        values.forEach(value -> byCode.put(value.code(), value));
        return Map.copyOf(byCode);
    }

    private boolean matches(RiskSimulationCandidateSnapshot.Threshold threshold, Feature feature) {
        PolicyThresholdOperator operator = threshold.operator();
        return switch (feature.type()) {
            case LONG -> operator == PolicyThresholdOperator.GTE
                    ? feature.longValue() >= threshold.longOperand()
                    : feature.longValue().equals(threshold.longOperand());
            case BOOLEAN -> feature.booleanValue().equals(threshold.booleanOperand());
            case STRING -> operator == PolicyThresholdOperator.IN
                    ? threshold.stringOperands().contains(feature.stringValue())
                    : threshold.stringOperands().size() == 1
                            && threshold.stringOperands().getFirst().equals(feature.stringValue());
            case DECIMAL -> false;
        };
    }

    private FraudDecisionAction baselineAction(RunContext context, HistoricalSimulationCohortItem item) {
        List<FraudDecisionAction> candidates = new ArrayList<>();
        candidates.addAll(jdbcTemplate.query("""
                SELECT action FROM rule_execution_results
                 WHERE merchant_id = ? AND fraud_decision_id = ? AND matched = TRUE
                """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                context.merchantId(), item.fraudDecisionId()));
        candidates.addAll(jdbcTemplate.query("""
                SELECT candidate_action FROM fraud_list_evaluations
                 WHERE merchant_id = ? AND fraud_decision_id = ? AND matched = TRUE
                """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                context.merchantId(), item.fraudDecisionId()));
        List<VelocityEvidence> velocity = jdbcTemplate.query("""
                SELECT dimension, availability, other_attempt_count FROM velocity_observations
                 WHERE merchant_id = ? AND fraud_decision_id = ?
                """, (resultSet, rowNumber) -> new VelocityEvidence(resultSet.getString(1),
                        resultSet.getString(2), resultSet.getObject(3, Long.class)),
                context.merchantId(), item.fraudDecisionId());
        if (velocity.stream().anyMatch(evidence -> "UNAVAILABLE".equals(evidence.availability())
                || (evidence.otherAttemptCount() != null && evidence.otherAttemptCount()
                        >= VelocityDimension.valueOf(evidence.dimension()).reviewThreshold()))) {
            candidates.add(FraudDecisionAction.REVIEW);
        }
        if (context.type() != RiskSimulationCandidateType.MERCHANT_RISK_POLICY) {
            candidates.addAll(jdbcTemplate.query("""
                    SELECT candidate_action FROM risk_control_evaluations
                     WHERE merchant_id = ? AND fraud_decision_id = ?
                       AND control_type = 'POLICY_THRESHOLD' AND matched = TRUE
                       AND candidate_action IS NOT NULL
                    """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                    context.merchantId(), item.fraudDecisionId()));
        }
        if (context.type() == RiskSimulationCandidateType.MERCHANT_RISK_POLICY) {
            candidates.addAll(jdbcTemplate.query("""
                    SELECT effective_action FROM authored_rule_evaluations
                     WHERE merchant_id = ? AND fraud_decision_id = ?
                       AND effective_action IS NOT NULL
                    """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                    context.merchantId(), item.fraudDecisionId()));
        } else if (context.type() == RiskSimulationCandidateType.AUTHORED_RULE) {
            String replacedFamily = replacedRuleCode(context);
            candidates.addAll(jdbcTemplate.query("""
                    SELECT evaluation.effective_action
                      FROM authored_rule_evaluations evaluation
                      JOIN authored_rule_versions rule_version
                        ON rule_version.merchant_id = evaluation.merchant_id
                       AND rule_version.id = evaluation.rule_version_id
                     WHERE evaluation.merchant_id = ?
                       AND evaluation.fraud_decision_id = ?
                       AND evaluation.effective_action IS NOT NULL
                       AND rule_version.rule_code <> ?
                    """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                    context.merchantId(), item.fraudDecisionId(), replacedFamily));
        } else {
            UUID sourceRuleVersionId = ((SafetyExceptionSnapshot) context.candidate()).sourceRuleVersionId();
            candidates.addAll(jdbcTemplate.query("""
                    SELECT effective_action FROM authored_rule_evaluations
                     WHERE merchant_id = ? AND fraud_decision_id = ?
                       AND rule_version_id <> ? AND effective_action IS NOT NULL
                    """, (resultSet, rowNumber) -> FraudDecisionAction.valueOf(resultSet.getString(1)),
                    context.merchantId(), item.fraudDecisionId(), sourceRuleVersionId));
        }
        return candidates.stream().max(Comparator.comparingInt(this::strength)).orElse(FraudDecisionAction.ALLOW);
    }

    private String replacedRuleCode(RunContext context) {
        if (context.type() != RiskSimulationCandidateType.AUTHORED_RULE) {
            return null;
        }
        if (replacedRuleCode != null) {
            return replacedRuleCode;
        }
        UUID sourceVersionId = ((AuthoredRuleSimulationSnapshot) context.candidate()).ruleVersionId();
        replacedRuleCode = jdbcTemplate.queryForObject("""
                SELECT rule_code FROM authored_rule_versions
                 WHERE merchant_id = ? AND id = ?
                """, String.class, context.merchantId(), sourceVersionId);
        if (replacedRuleCode == null) {
            throw new IllegalStateException(
                    "Simulation candidate lost its immutable authored-rule family");
        }
        return replacedRuleCode;
    }

    private RiskSimulationLabelOutcome label(UUID attemptId, RunContext context) {
        List<String> labels = jdbcTemplate.query("""
                SELECT label.label_value FROM fraud_labels label
                 WHERE label.merchant_id = ? AND label.payment_attempt_id = ? AND label.available_at <= ?
                   AND NOT EXISTS (SELECT 1 FROM fraud_labels successor
                        WHERE successor.merchant_id = label.merchant_id
                          AND successor.supersedes_label_id = label.id AND successor.available_at <= ?)
                 ORDER BY label.available_at, label.id
                """, (resultSet, rowNumber) -> resultSet.getString(1), context.merchantId(), attemptId,
                Timestamp.from(context.labelCutoff()), Timestamp.from(context.labelCutoff()));
        boolean fraud = labels.contains("FRAUD");
        boolean legitimate = labels.contains("LEGITIMATE");
        if (fraud && legitimate) {
            return RiskSimulationLabelOutcome.CONTRADICTORY;
        }
        return fraud ? RiskSimulationLabelOutcome.FRAUD
                : legitimate ? RiskSimulationLabelOutcome.LEGITIMATE : RiskSimulationLabelOutcome.UNLABELED;
    }

    private HistoricalSimulationResult excluded(HistoricalSimulationCohortItem item,
            RiskSimulationExclusionReason reason, RiskSimulationLabelOutcome label) {
        return new HistoricalSimulationResult(item.paymentAttemptId(), item.fraudDecisionId(),
                item.decisionCreatedAt(), item.trafficOrigin(), item.originalAction(), null, false,
                reason, label, false, item.authorizationAmount(), item.captureAmount(), item.refundAmount());
    }

    private FraudDecisionAction stronger(FraudDecisionAction left, FraudDecisionAction right) {
        return strength(left) >= strength(right) ? left : right;
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }

    private record RunContext(UUID merchantId, RiskSimulationCandidateType type, Object candidate,
            Instant labelCutoff, String featureCatalogueVersion, UUID executionToken) { }

    private record Feature(String code, String version, FeatureValueType type,
            Long longValue, Boolean booleanValue, String stringValue) {
        Object typedValue() {
            return switch (type) {
                case LONG -> longValue;
                case BOOLEAN -> booleanValue;
                case STRING -> stringValue;
                case DECIMAL -> null;
            };
        }
    }

    private record Evaluation(FraudDecisionAction action, RiskSimulationExclusionReason exclusionReason,
            boolean suppressesAuthoredSource) { }

    private record HistoricalAuthoredEvidence(boolean matched, FraudDecisionAction effectiveAction) { }

    private record VelocityEvidence(String dimension, String availability, Long otherAttemptCount) { }
}
