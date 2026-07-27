package com.thaap.payments.controls;

import com.thaap.payments.security.SensitiveValueHasher;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GovernedControlHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveValueHasher hasher;
    private final AuthoredRuleCodec codec;

    public GovernedControlHealthIndicator(JdbcTemplate jdbcTemplate, SensitiveValueHasher hasher,
            AuthoredRuleCodec codec) {
        this.jdbcTemplate = jdbcTemplate;
        this.hasher = hasher;
        this.codec = codec;
    }

    @Override
    public Health health() {
        try {
            Integer schemaRows = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM authored_rule_versions
                     WHERE schema_version <> 'authored_rule_v1'
                    """, Integer.class);
            List<String> keyVersions = jdbcTemplate.query("""
                    SELECT DISTINCT subject_hmac_key_version
                      FROM risk_safety_exception_versions WHERE lifecycle_status = 'ACTIVE'
                    """, (resultSet, rowNumber) -> resultSet.getString(1));
            List<StoredRule> activeRules = jdbcTemplate.query("""
                    SELECT canonical_rule_json, rule_digest FROM authored_rule_versions
                     WHERE lifecycle_status = 'ACTIVE'
                    """, (resultSet, rowNumber) -> new StoredRule(
                            resultSet.getString(1), resultSet.getString(2)));
            boolean keysAvailable = keyVersions.stream().allMatch(hasher::canHashWith);
            if (schemaRows == null || schemaRows != 0 || !keysAvailable) {
                return Health.down().withDetail("cause", schemaRows != null && schemaRows != 0
                        ? "UNSUPPORTED_RULE_SCHEMA" : "EXCEPTION_HMAC_KEY_UNAVAILABLE").build();
            }
            for (StoredRule rule : activeRules) {
                codec.parseStored(rule.json(), rule.digest());
            }
            return Health.up().withDetail("ruleSchema", AuthoredRuleCodec.SCHEMA_VERSION)
                    .withDetail("activeExceptionKeyVersions", keyVersions.size()).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "CONTROL_SCHEMA_UNAVAILABLE").build();
        }
    }

    private record StoredRule(String json, String digest) { }
}
