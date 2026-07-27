package com.thaap.payments.simulation;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Test interception point at the chunk transaction's result/progress commit edge. */
@Component
public class RiskSimulationExecutionProbe {

    public void afterResultWritesBeforeProgress(UUID runId) {
        // Production is deliberately a no-op. Tests inject a failure before transaction commit.
    }
}
