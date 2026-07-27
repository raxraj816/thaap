package com.thaap.payments.simulation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RiskSimulationMetrics {

    private final MeterRegistry registry;

    public RiskSimulationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDispatch(String result) {
        registry.counter("thaap.risk.simulation.dispatch", "result", result).increment();
    }

    public void recordJob(String phase, String result) {
        registry.counter("thaap.risk.simulation.jobs", "phase", phase, "result", result).increment();
    }
}
