package com.thaap.payments.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class RiskSimulationOperationalBoundaryTests {

    @Test
    void readinessRejectsCreationWithoutWorkerAndAcceptsDisabledOptionalCapability() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        assertThat(new RiskSimulationHealthIndicator(jdbcTemplate, false, true)
                .health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(new RiskSimulationHealthIndicator(jdbcTemplate, false, false)
                .health().getStatus()).isEqualTo(Status.UP);
        assertThat(new RiskSimulationHealthIndicator(jdbcTemplate, true, true)
                .health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void readinessFailsClosedWhenJdbcMetadataIsIncompleteOrUnavailable() {
        JdbcTemplate incomplete = mock(JdbcTemplate.class);
        when(incomplete.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("batch_step_execution"));
        JdbcTemplate unavailable = mock(JdbcTemplate.class);
        when(unavailable.queryForList(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("sensitive database diagnostic"));

        assertThat(new RiskSimulationHealthIndicator(incomplete, true, true)
                .health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(new RiskSimulationHealthIndicator(unavailable, true, true)
                .health().getDetails()).containsEntry("cause", "SIMULATION_SCHEMA_UNAVAILABLE")
                .doesNotContainValue("sensitive database diagnostic");
    }

    @Test
    void metricsUseOnlyClosedLowCardinalityDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RiskSimulationMetrics metrics = new RiskSimulationMetrics(registry);

        metrics.recordDispatch("launched");
        metrics.recordJob("completed", "succeeded");

        assertThat(registry.getMeters()).extracting(Meter::getId).allSatisfy(id -> {
            assertThat(id.getTags()).allSatisfy(tag ->
                    assertThat(tag.getKey()).isIn("result", "phase"));
            assertThat(id.getTags()).extracting(tag -> tag.getValue())
                    .allMatch(value -> value.matches("[a-z_]+"));
        });
    }
}
