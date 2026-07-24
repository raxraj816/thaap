package com.thaap.payments.riskcontext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.security.SensitiveValueHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskContextKeyConfigurationTests {

    @Test
    void retainedVersionMustBeConfiguredAtStartup() {
        RiskContextRepository repository = mock(RiskContextRepository.class);
        when(repository.findRequiredIdentityKeyVersions()).thenReturn(List.of("retired-v1"));
        RiskContextKeyConfiguration readiness = new RiskContextKeyConfiguration(
                repository, new SensitiveValueHasher("current", "v2", ""),
                new RiskContextMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(readiness::validateRequiredKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired-v1");
    }

    @Test
    void configuredPreviousVersionPreservesHistoricalContext() {
        RiskContextRepository repository = mock(RiskContextRepository.class);
        when(repository.findRequiredIdentityKeyVersions()).thenReturn(List.of("v1", "v2"));
        String old = Base64.getEncoder().encodeToString("old-pepper".getBytes());
        RiskContextKeyConfiguration readiness = new RiskContextKeyConfiguration(
                repository, new SensitiveValueHasher("current", "v2", "v1=" + old),
                new RiskContextMetrics(new SimpleMeterRegistry()));

        assertThatCode(readiness::validateRequiredKeys).doesNotThrowAnyException();
    }
}
