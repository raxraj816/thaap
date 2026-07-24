package com.thaap.payments.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class MerchantRiskPolicyHealthIndicatorTests {

    @Test
    void noMerchantConfigurationIsAHealthyApplicationDefault() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()))
                .thenReturn(java.util.List.of());
        MerchantRiskPolicyHealthIndicator indicator = new MerchantRiskPolicyHealthIndicator(jdbcTemplate);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("mode", "READY");
    }

    @Test
    void inventoryDependencyFailureMakesReadinessDownWithoutLeakingExceptionData() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new IllegalStateException("secret-canary"));
        MerchantRiskPolicyHealthIndicator indicator = new MerchantRiskPolicyHealthIndicator(jdbcTemplate);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
                .containsEntry("cause", "CONFIGURATION_INVENTORY_UNAVAILABLE")
                .doesNotContainValue("secret-canary");
    }
}
