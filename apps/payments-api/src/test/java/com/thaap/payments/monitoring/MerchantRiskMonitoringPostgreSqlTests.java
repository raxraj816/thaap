package com.thaap.payments.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MerchantRiskMonitoringPostgreSqlTests extends MerchantRiskMonitoringContractTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Override
    protected void replaceTaxonomyLabelForReadinessFixture(String displayLabel) {
        jdbcTemplate.execute("""
                ALTER TABLE fraud_taxonomy_definitions
                DISABLE TRIGGER trg_fraud_taxonomy_definitions_append_only
                """);
        try {
            super.replaceTaxonomyLabelForReadinessFixture(displayLabel);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE fraud_taxonomy_definitions
                    ENABLE TRIGGER trg_fraud_taxonomy_definitions_append_only
                    """);
        }
    }
}
