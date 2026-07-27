package com.thaap.payments.controls;

import com.thaap.payments.fraud.FraudDecisionAction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class GovernedControlMetrics {

    private final MeterRegistry registry;

    public GovernedControlMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluation(boolean matched, FraudDecisionAction action, String exceptionDisposition) {
        registry.counter("thaap.governed_controls.evaluations",
                "matched", Boolean.toString(matched),
                "action", action.name(),
                "exception", exceptionDisposition).increment();
    }

    public void recordLifecycle(String family, String transition) {
        registry.counter("thaap.governed_controls.lifecycle",
                "family", family, "transition", transition).increment();
    }
}
