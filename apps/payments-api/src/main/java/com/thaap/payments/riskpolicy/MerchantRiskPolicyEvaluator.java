package com.thaap.payments.riskpolicy;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FeatureValue;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.MaterializedFeatureSet;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantRiskPolicyEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final MerchantRepository merchantRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MerchantRiskPolicyMetrics metrics;

    public MerchantRiskPolicyEvaluator(MerchantRepository merchantRepository, JdbcTemplate jdbcTemplate,
            MerchantRiskPolicyMetrics metrics) {
        this.merchantRepository = merchantRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public MerchantRiskConfiguration loadConfiguration(UUID merchantId, Instant evaluatedAt) {
        Merchant merchant = merchantRepository.findByIdForRiskControlRead(merchantId)
                .filter(Merchant::isActive)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.RISK_EVALUATION_MERCHANT_INACTIVE, "Active merchant is required for risk evaluation"));
        List<ProfileRow> profiles = jdbcTemplate.query("""
                SELECT profile.id, profile.risk_tier
                  FROM merchant_risk_profile_versions profile
                 WHERE profile.merchant_id = ?
                   AND EXISTS (
                       SELECT 1 FROM merchant_risk_profile_events activation
                        WHERE activation.merchant_id = profile.merchant_id
                          AND activation.profile_version_id = profile.id
                          AND activation.event_type = 'ACTIVATED'
                          AND activation.occurred_at <= ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM merchant_risk_profile_events retirement
                        WHERE retirement.merchant_id = profile.merchant_id
                          AND retirement.profile_version_id = profile.id
                          AND retirement.event_type = 'RETIRED'
                          AND retirement.occurred_at <= ?)
                 ORDER BY profile.version_number
                """, (resultSet, rowNumber) -> new ProfileRow(resultSet.getObject(1, UUID.class),
                        MerchantRiskTier.valueOf(resultSet.getString(2))),
                merchant.getId(), Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt));
        List<PolicyRow> policies = jdbcTemplate.query("""
                SELECT policy.id, policy.policy_code, policy.version_number
                  FROM merchant_risk_policy_versions policy
                 WHERE policy.merchant_id = ?
                   AND EXISTS (
                       SELECT 1 FROM merchant_risk_policy_events activation
                        WHERE activation.merchant_id = policy.merchant_id
                          AND activation.policy_version_id = policy.id
                          AND activation.event_type = 'ACTIVATED'
                          AND activation.occurred_at <= ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM merchant_risk_policy_events retirement
                        WHERE retirement.merchant_id = policy.merchant_id
                          AND retirement.policy_version_id = policy.id
                          AND retirement.event_type = 'RETIRED'
                          AND retirement.occurred_at <= ?)
                 ORDER BY policy.policy_code, policy.version_number
                """, (resultSet, rowNumber) -> new PolicyRow(resultSet.getObject(1, UUID.class),
                        resultSet.getString(2), resultSet.getInt(3)),
                merchant.getId(), Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt));
        if (profiles.size() > 1 || policies.size() > 1) {
            return MerchantRiskConfiguration.unavailable(merchantId, evaluatedAt);
        }
        ProfileRow profile = profiles.isEmpty() ? null : profiles.getFirst();
        PolicyRow policy = policies.isEmpty() ? null : policies.getFirst();
        List<MerchantPolicyThreshold> thresholds = policy == null ? List.of() : loadThresholds(merchantId, policy.id());
        if (policy != null && thresholds.isEmpty()) {
            return MerchantRiskConfiguration.unavailable(merchantId, evaluatedAt);
        }
        MerchantRiskConfigurationStatus status = profile == null && policy == null
                ? MerchantRiskConfigurationStatus.APPLICATION_DEFAULT
                : MerchantRiskConfigurationStatus.ACTIVE;
        return new MerchantRiskConfiguration(merchantId, status,
                profile == null ? null : profile.id(),
                profile == null ? MerchantRiskTier.STANDARD : profile.tier(),
                profile == null ? MerchantRiskProfileSource.APPLICATION_DEFAULT
                        : MerchantRiskProfileSource.RISK_MANAGER,
                policy == null ? null : policy.id(), policy == null ? null : policy.code(),
                policy == null ? 0 : policy.versionNumber(), thresholds, evaluatedAt);
    }

    public MerchantPolicyAssessment evaluate(MerchantRiskConfiguration configuration,
            MaterializedFeatureSet features, String currency) {
        long startedAt = System.nanoTime();
        if (configuration.status() == MerchantRiskConfigurationStatus.UNAVAILABLE) {
            MerchantPolicyAssessment assessment = new MerchantPolicyAssessment(configuration, List.of());
            metrics.recordAssessment(assessment, System.nanoTime() - startedAt);
            return assessment;
        }
        Map<String, FeatureValue> valuesByCode = new HashMap<>();
        for (FeatureValue value : features.values()) {
            valuesByCode.put(value.getFeatureCode(), value);
        }
        List<MerchantPolicyEvaluation> evaluations = new ArrayList<>();
        for (MerchantPolicyThreshold threshold : configuration.thresholds()) {
            FeatureValue value = valuesByCode.get(threshold.featureCode());
            boolean matched = (threshold.currencyScope() == null || threshold.currencyScope().equals(currency))
                    && matches(threshold, value);
            evaluations.add(new MerchantPolicyEvaluation(threshold, value, matched));
        }
        MerchantPolicyAssessment assessment = new MerchantPolicyAssessment(configuration, evaluations);
        metrics.recordAssessment(assessment, System.nanoTime() - startedAt);
        return assessment;
    }

    @Transactional
    public void persist(MerchantPolicyAssessment assessment, PaymentAttempt attempt, UUID fraudDecisionId,
            MaterializedFeatureSet features, Instant evaluatedAt, TrafficOrigin trafficOrigin) {
        FeatureValue tierValue = features.values().stream()
                .filter(value -> "merchant.risk_tier".equals(value.getFeatureCode()))
                .findFirst().orElseThrow(() -> new IllegalStateException("merchant.risk_tier feature is missing"));
        MerchantRiskConfiguration configuration = assessment.configuration();
        jdbcTemplate.update("""
                INSERT INTO risk_control_evaluations
                    (id, merchant_id, payment_attempt_id, fraud_decision_id, control_type,
                     configuration_status, profile_version_id, policy_version_id,
                     policy_threshold_id, feature_value_id, feature_definition_id,
                     feature_code, feature_version, value_type, observed_long,
                     observed_boolean, observed_string, operator, operand_long,
                     operand_boolean, operand_strings_json, matched, candidate_action,
                     priority, reason_code, currency_scope, evaluated_at, traffic_origin, created_at)
                VALUES (?, ?, ?, ?, 'PROFILE', ?, ?, NULL, NULL, ?, ?, ?, ?, ?,
                        NULL, NULL, ?, NULL, NULL, NULL, NULL, TRUE, NULL, NULL, NULL,
                        NULL, ?, ?, ?)
                """, UUID.randomUUID(), configuration.merchantId(), attempt.getId(), fraudDecisionId,
                configuration.status().name(), configuration.profileVersionId(), tierValue.getId(),
                tierValue.getFeatureDefinitionId(), tierValue.getFeatureCode(), tierValue.getFeatureVersion(),
                tierValue.getValueType().name(), tierValue.getStringValue(), Timestamp.from(evaluatedAt),
                trafficOrigin.name(), Timestamp.from(evaluatedAt));
        for (MerchantPolicyEvaluation evaluation : assessment.evaluations()) {
            MerchantPolicyThreshold threshold = evaluation.threshold();
            FeatureValue value = evaluation.featureValue();
            jdbcTemplate.update("""
                    INSERT INTO risk_control_evaluations
                        (id, merchant_id, payment_attempt_id, fraud_decision_id, control_type,
                         configuration_status, profile_version_id, policy_version_id,
                         policy_threshold_id, feature_value_id, feature_definition_id,
                         feature_code, feature_version, value_type, observed_long,
                         observed_boolean, observed_string, operator, operand_long,
                         operand_boolean, operand_strings_json, matched, candidate_action,
                         priority, reason_code, currency_scope, evaluated_at, traffic_origin, created_at)
                    VALUES (?, ?, ?, ?, 'POLICY_THRESHOLD', 'ACTIVE', NULL, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), configuration.merchantId(), attempt.getId(), fraudDecisionId,
                    threshold.policyVersionId(), threshold.id(), value == null ? null : value.getId(),
                    threshold.featureDefinitionId(), threshold.featureCode(), threshold.featureVersion(),
                    threshold.valueType().name(), value == null ? null : value.getLongValue(),
                    value == null ? null : value.getBooleanValue(), value == null ? null : value.getStringValue(),
                    threshold.operator().name(), threshold.longOperand(), threshold.booleanOperand(),
                    stringsJson(threshold.stringOperands()), evaluation.matched(),
                    threshold.candidateAction().name(), threshold.priority(), threshold.reasonCode().name(),
                    threshold.currencyScope(), Timestamp.from(evaluatedAt), trafficOrigin.name(),
                    Timestamp.from(evaluatedAt));
        }
    }

    private List<MerchantPolicyThreshold> loadThresholds(UUID merchantId, UUID policyVersionId) {
        try {
            return jdbcTemplate.query("""
                    SELECT threshold.id, threshold.policy_version_id, threshold.threshold_code,
                           threshold.feature_definition_id, threshold.feature_code,
                           threshold.feature_version, threshold.value_type, threshold.operator,
                           threshold.long_operand, threshold.boolean_operand,
                           threshold.string_operands_json, threshold.candidate_action,
                           threshold.priority, threshold.reason_code, threshold.currency_scope
                      FROM merchant_risk_policy_thresholds threshold
                     WHERE threshold.merchant_id = ? AND threshold.policy_version_id = ?
                     ORDER BY threshold.priority DESC, threshold.threshold_code
                    """, (resultSet, rowNumber) -> threshold(resultSet), merchantId, policyVersionId);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<MerchantPolicyThreshold> thresholdsForPolicy(UUID merchantId, UUID policyVersionId) {
        return loadThresholds(merchantId, policyVersionId);
    }

    private MerchantPolicyThreshold threshold(ResultSet resultSet) throws SQLException {
        String json = resultSet.getString(11);
        List<String> strings;
        try {
            strings = json == null ? List.of() : OBJECT_MAPPER.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Stored merchant policy operand is malformed", exception);
        }
        return new MerchantPolicyThreshold(resultSet.getObject(1, UUID.class),
                resultSet.getObject(2, UUID.class), resultSet.getString(3),
                resultSet.getObject(4, UUID.class), resultSet.getString(5), resultSet.getString(6),
                FeatureValueType.valueOf(resultSet.getString(7)),
                PolicyThresholdOperator.valueOf(resultSet.getString(8)),
                nullableLong(resultSet, 9), nullableBoolean(resultSet, 10), strings,
                FraudDecisionAction.valueOf(resultSet.getString(12)), resultSet.getInt(13),
                PolicyThresholdReasonCode.valueOf(resultSet.getString(14)), resultSet.getString(15));
    }

    private Long nullableLong(ResultSet resultSet, int index) throws SQLException {
        long value = resultSet.getLong(index);
        return resultSet.wasNull() ? null : value;
    }

    private Boolean nullableBoolean(ResultSet resultSet, int index) throws SQLException {
        boolean value = resultSet.getBoolean(index);
        return resultSet.wasNull() ? null : value;
    }

    private boolean matches(MerchantPolicyThreshold threshold, FeatureValue value) {
        if (value == null || value.getValueType() != threshold.valueType()) {
            return false;
        }
        return switch (threshold.operator()) {
            case GTE -> value.getLongValue() != null && value.getLongValue() >= threshold.longOperand();
            case EQ -> switch (threshold.valueType()) {
                case LONG -> value.getLongValue().equals(threshold.longOperand());
                case BOOLEAN -> value.getBooleanValue().equals(threshold.booleanOperand());
                case STRING -> threshold.stringOperands().size() == 1
                        && value.getStringValue().equals(threshold.stringOperands().getFirst());
                case DECIMAL -> false;
            };
            case IN -> value.getStringValue() != null && threshold.stringOperands().contains(value.getStringValue());
        };
    }

    public boolean matchesFixture(MerchantPolicyThreshold threshold, Object value, String currency) {
        if (threshold.currencyScope() != null && !threshold.currencyScope().equals(currency)) {
            return false;
        }
        if (value == null) {
            return false;
        }
        return switch (threshold.operator()) {
            case GTE -> value instanceof Long longValue && longValue >= threshold.longOperand();
            case EQ -> switch (threshold.valueType()) {
                case LONG -> value instanceof Long longValue && longValue.equals(threshold.longOperand());
                case BOOLEAN -> value instanceof Boolean booleanValue
                        && booleanValue.equals(threshold.booleanOperand());
                case STRING -> value instanceof String stringValue && threshold.stringOperands().size() == 1
                        && stringValue.equals(threshold.stringOperands().getFirst());
                case DECIMAL -> false;
            };
            case IN -> value instanceof String stringValue && threshold.stringOperands().contains(stringValue);
        };
    }

    private String stringsJson(List<String> strings) {
        if (strings.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(strings);
        } catch (Exception exception) {
            throw new IllegalStateException("Merchant policy operand could not be serialized", exception);
        }
    }

    private record ProfileRow(UUID id, MerchantRiskTier tier) { }
    private record PolicyRow(UUID id, String code, int versionNumber) { }
}
