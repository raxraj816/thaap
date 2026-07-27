package com.thaap.payments.monitoring;

import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("merchantRiskMonitoring")
public class MerchantRiskMonitoringHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public MerchantRiskMonitoringHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            List<FraudTaxonomyCatalogue.Definition> definitions = jdbcTemplate.query("""
                    SELECT source_type, source_code, fraud_family, fraud_type, display_label
                      FROM fraud_taxonomy_definitions
                     WHERE taxonomy_version = ?
                    """, (resultSet, rowNumber) -> new FraudTaxonomyCatalogue.Definition(
                            new FraudTaxonomyCatalogue.Key(
                                    FraudTaxonomySourceType.valueOf(resultSet.getString(1)),
                                    resultSet.getString(2)),
                            resultSet.getString(3), resultSet.getString(4), resultSet.getString(5)),
                    MerchantRiskMonitoringQueryService.TAXONOMY_VERSION);
            Map<FraudTaxonomyCatalogue.Key, FraudTaxonomyCatalogue.Definition> actualDefinitions =
                    definitions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                            FraudTaxonomyCatalogue.Definition::key,
                            java.util.function.Function.identity()));
            if (!FraudTaxonomyCatalogue.definitionsByKey().equals(actualDefinitions)) {
                return Health.down().withDetail("cause", "TAXONOMY_INVENTORY_MISMATCH").build();
            }
            Integer snapshotTable = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM merchant_risk_metric_snapshots WHERE 1 = 0
                    """, Integer.class);
            if (snapshotTable == null) {
                return Health.down().withDetail("cause", "SNAPSHOT_SCHEMA_UNAVAILABLE").build();
            }
            return Health.up().withDetail("mode", "READY")
                    .withDetail("taxonomyVersion",
                            MerchantRiskMonitoringQueryService.TAXONOMY_VERSION)
                    .withDetail("taxonomyEntries",
                            FraudTaxonomyCatalogue.definitions().size()).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "MONITORING_INVENTORY_UNAVAILABLE").build();
        }
    }
}
