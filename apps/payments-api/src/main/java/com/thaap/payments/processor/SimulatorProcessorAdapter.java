package com.thaap.payments.processor;

import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
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
        if (request.authenticated() && outcome.status() == AuthorizationStatus.REQUIRES_ACTION
                && !"REPEATED_THREE_DS_REQUIRED".equals(outcome.responseCode())) {
            outcome = new SimulatorProcessorCatalog.SimulatorOutcome(
                    AuthorizationStatus.APPROVED, NetworkStatus.APPROVED, "APPROVED_AFTER_AUTHENTICATION",
                    VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                    ThreeDsResult.AUTHENTICATED);
        } else if (request.authenticated() && outcome.status() != AuthorizationStatus.REQUIRES_ACTION) {
            outcome = new SimulatorProcessorCatalog.SimulatorOutcome(
                    outcome.status(), outcome.networkStatus(), outcome.responseCode(),
                    outcome.cvcCheck(), outcome.avsLine1Check(), outcome.avsPostalCodeCheck(),
                    ThreeDsResult.AUTHENTICATED);
        }
        long authorizedAmount = outcome.status() == AuthorizationStatus.APPROVED ? request.amount() : 0L;
        return new ProcessorAuthorizationResult(PROCESSOR_NAME,
                processorReferenceId(request.paymentAttemptId()), outcome.responseCode(),
                outcome.networkStatus(), request.amount(), authorizedAmount, request.currency(), outcome.status(),
                outcome.cvcCheck(), outcome.avsLine1Check(), outcome.avsPostalCodeCheck(),
                outcome.threeDsResult(), outcome.responseCode());
    }

    private String processorReferenceId(java.util.UUID paymentAttemptId) {
        return "auth_sim_" + paymentAttemptId.toString().replace("-", "");
    }
}
