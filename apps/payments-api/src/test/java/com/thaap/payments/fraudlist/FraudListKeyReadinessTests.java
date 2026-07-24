package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.security.SensitiveValueHasher;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FraudListKeyReadinessTests {

    @Test
    void activeEntryWithUnavailableKeyFailsReadiness() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(List.of("retired-v1"));
        when(hasher.canHashWith("retired-v1")).thenReturn(false);

        FraudListKeyReadiness readiness = new FraudListKeyReadiness(jdbcTemplate, hasher);

        assertThatThrownBy(readiness::verifyActiveEntryKeyInventory)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired-v1");
    }

    @Test
    void retainedKeyInventoryPassesReadiness() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(List.of("v2", "v1"));
        when(hasher.canHashWith("v1")).thenReturn(true);
        when(hasher.canHashWith("v2")).thenReturn(true);

        new FraudListKeyReadiness(jdbcTemplate, hasher).verifyActiveEntryKeyInventory();
    }
}
