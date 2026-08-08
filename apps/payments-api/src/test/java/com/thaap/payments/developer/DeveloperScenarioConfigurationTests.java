package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.processor.SimulatorProcessorCatalog;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DeveloperScenarioConfigurationTests {

    @Test
    void disabledCreationFailsClosedBeforeDatabaseMutation() {
        DeveloperScenarioService service = new DeveloperScenarioService(
                mock(JdbcTemplate.class), new ObjectMapper(),
                mock(SimulatorProcessorCatalog.class), mock(AuditLogService.class),
                mock(DeveloperScenarioMetrics.class), mock(RiskOperationsEventProducer.class),
                DatabaseInstant.tick(Clock.systemUTC()), Duration.ofSeconds(45), false);
        MerchantContext context = new MerchantContext(UUID.randomUUID(), "developer-test",
                "test-prefix", TrafficOrigin.SYNTHETIC_SCENARIO);

        assertThatThrownBy(() -> service.create(context, DeveloperScenarioType.APPROVED_PAYMENT,
                "bounded-seed", "request-id"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void leaseConfigurationRejectsUnboundedDurations() {
        assertThatThrownBy(() -> new DeveloperScenarioService(
                mock(JdbcTemplate.class), new ObjectMapper(),
                mock(SimulatorProcessorCatalog.class), mock(AuditLogService.class),
                mock(DeveloperScenarioMetrics.class), mock(RiskOperationsEventProducer.class),
                DatabaseInstant.tick(Clock.systemUTC()), Duration.ofSeconds(4), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 seconds and 5 minutes");
        assertThatThrownBy(() -> new DeveloperScenarioService(
                mock(JdbcTemplate.class), new ObjectMapper(),
                mock(SimulatorProcessorCatalog.class), mock(AuditLogService.class),
                mock(DeveloperScenarioMetrics.class), mock(RiskOperationsEventProducer.class),
                DatabaseInstant.tick(Clock.systemUTC()), Duration.ofMinutes(6), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 seconds and 5 minutes");
    }
}
