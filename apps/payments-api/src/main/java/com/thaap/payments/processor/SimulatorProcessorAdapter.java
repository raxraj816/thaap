package com.thaap.payments.processor;

import com.thaap.payments.authorization.AuthorizationStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimulatorProcessorAdapter implements ProcessorAdapter {

    public static final String PROCESSOR_NAME = "THAAP_SIMULATOR";

    private final SimulatorProcessorCatalog simulatorProcessorCatalog;

    public SimulatorProcessorAdapter(SimulatorProcessorCatalog simulatorProcessorCatalog) {
        this.simulatorProcessorCatalog = simulatorProcessorCatalog;
    }

    @Override
    public ProcessorAuthorizationResult authorize(ProcessorAuthorizationRequest request) {
        SimulatorProcessorCatalog.SimulatorOutcome outcome = simulatorProcessorCatalog
                .resolve(request.cardBin(), request.cardLast4())
                .outcome();
        long authorizedAmount = outcome.status() == AuthorizationStatus.APPROVED ? request.amount() : 0L;
        return new ProcessorAuthorizationResult(PROCESSOR_NAME, newProcessorReferenceId(), outcome.responseCode(),
                outcome.networkStatus(), request.amount(), authorizedAmount, request.currency(), outcome.status(),
                outcome.cvcCheck(), outcome.avsLine1Check(), outcome.avsPostalCodeCheck(),
                outcome.threeDsResult(), outcome.responseCode());
    }

    private String newProcessorReferenceId() {
        return "auth_sim_" + UUID.randomUUID().toString().replace("-", "");
    }
}
