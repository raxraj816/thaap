package com.thaap.payments.riskpolicy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.FeatureValueType;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("merchantRiskPolicy")
public class MerchantRiskPolicyHealthIndicator implements HealthIndicator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;

    public MerchantRiskPolicyHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            int ambiguousProfiles = ambiguousCount("merchant_risk_profile_versions",
                    "merchant_risk_profile_events", "profile_version_id");
            int ambiguousPolicies = ambiguousCount("merchant_risk_policy_versions",
                    "merchant_risk_policy_events", "policy_version_id");
            List<ActivePolicy> activePolicies = activePolicies();
            for (ActivePolicy policy : activePolicies) {
                List<ThresholdInventory> thresholds = thresholds(policy);
                if (thresholds.isEmpty()) {
                    return down("ACTIVE_POLICY_HAS_NO_THRESHOLDS");
                }
                for (ThresholdInventory threshold : thresholds) {
                    if (!isSupported(threshold)) {
                        return down("ACTIVE_POLICY_THRESHOLD_UNSUPPORTED");
                    }
                }
            }
            if (ambiguousProfiles > 0 || ambiguousPolicies > 0) {
                return Health.down().withDetail("cause", "AMBIGUOUS_ACTIVE_CONFIGURATION")
                        .withDetail("ambiguousProfileMerchants", ambiguousProfiles)
                        .withDetail("ambiguousPolicyMerchants", ambiguousPolicies).build();
            }
            return Health.up().withDetail("mode", "READY")
                    .withDetail("activePolicies", activePolicies.size()).build();
        } catch (RuntimeException exception) {
            return down("CONFIGURATION_INVENTORY_UNAVAILABLE");
        }
    }

    private int ambiguousCount(String versionTable, String eventTable, String versionColumn) {
        String sql = "SELECT COUNT(*) FROM (SELECT version.merchant_id FROM " + versionTable + " version"
                + " WHERE EXISTS (SELECT 1 FROM " + eventTable + " activated"
                + " WHERE activated.merchant_id = version.merchant_id"
                + " AND activated." + versionColumn + " = version.id"
                + " AND activated.event_type = 'ACTIVATED')"
                + " AND NOT EXISTS (SELECT 1 FROM " + eventTable + " retired"
                + " WHERE retired.merchant_id = version.merchant_id"
                + " AND retired." + versionColumn + " = version.id"
                + " AND retired.event_type = 'RETIRED')"
                + " GROUP BY version.merchant_id HAVING COUNT(*) > 1) ambiguous";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private List<ActivePolicy> activePolicies() {
        return jdbcTemplate.query("""
                SELECT policy.merchant_id, policy.id
                  FROM merchant_risk_policy_versions policy
                 WHERE EXISTS (
                       SELECT 1 FROM merchant_risk_policy_events activated
                        WHERE activated.merchant_id = policy.merchant_id
                          AND activated.policy_version_id = policy.id
                          AND activated.event_type = 'ACTIVATED')
                   AND NOT EXISTS (
                       SELECT 1 FROM merchant_risk_policy_events retired
                        WHERE retired.merchant_id = policy.merchant_id
                          AND retired.policy_version_id = policy.id
                          AND retired.event_type = 'RETIRED')
                """, (resultSet, rowNumber) -> new ActivePolicy(
                        resultSet.getObject(1, java.util.UUID.class),
                        resultSet.getObject(2, java.util.UUID.class)));
    }

    private List<ThresholdInventory> thresholds(ActivePolicy policy) {
        return jdbcTemplate.query("""
                SELECT feature_code, feature_version, value_type, operator,
                       long_operand, boolean_operand, string_operands_json
                  FROM merchant_risk_policy_thresholds
                 WHERE merchant_id = ? AND policy_version_id = ?
                """, (resultSet, rowNumber) -> new ThresholdInventory(
                        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getObject(5, Long.class),
                        resultSet.getObject(6, Boolean.class), resultSet.getString(7)),
                policy.merchantId(), policy.policyId());
    }

    private boolean isSupported(ThresholdInventory threshold) {
        FeatureValueType expectedType = FeatureCatalogue.requiredType(threshold.featureCode());
        if (expectedType == null || expectedType == FeatureValueType.DECIMAL
                || !FeatureCatalogue.VERSION.equals(threshold.featureVersion())
                || !expectedType.name().equals(threshold.valueType())) {
            return false;
        }
        try {
            PolicyThresholdOperator operator = PolicyThresholdOperator.valueOf(threshold.operator());
            if (operator == PolicyThresholdOperator.GTE) {
                return expectedType == FeatureValueType.LONG && threshold.longOperand() != null;
            }
            if (operator == PolicyThresholdOperator.EQ && expectedType == FeatureValueType.LONG) {
                return threshold.longOperand() != null;
            }
            if (operator == PolicyThresholdOperator.EQ && expectedType == FeatureValueType.BOOLEAN) {
                return threshold.booleanOperand() != null;
            }
            if (expectedType != FeatureValueType.STRING || threshold.stringOperandsJson() == null) {
                return false;
            }
            List<String> values = OBJECT_MAPPER.readValue(threshold.stringOperandsJson(), STRING_LIST);
            int maximum = operator == PolicyThresholdOperator.EQ ? 1 : 32;
            return !values.isEmpty() && values.size() <= maximum
                    && new HashSet<>(values).size() == values.size()
                    && values.stream().allMatch(value -> value != null && value.matches("[A-Z0-9_]{1,64}"));
        } catch (Exception exception) {
            return false;
        }
    }

    private Health down(String cause) {
        return Health.down().withDetail("cause", cause).build();
    }

    private record ActivePolicy(java.util.UUID merchantId, java.util.UUID policyId) { }

    private record ThresholdInventory(String featureCode, String featureVersion, String valueType,
            String operator, Long longOperand, Boolean booleanOperand, String stringOperandsJson) { }
}
