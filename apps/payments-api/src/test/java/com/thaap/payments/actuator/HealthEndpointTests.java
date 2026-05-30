package com.thaap.payments.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class HealthEndpointTests {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void healthEndpointReportsUp() {
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void livenessAndReadinessContributorsReportUp() {
        assertThat(healthEndpoint.healthForPath("livenessState").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("readinessState").getStatus()).isEqualTo(Status.UP);
    }
}
