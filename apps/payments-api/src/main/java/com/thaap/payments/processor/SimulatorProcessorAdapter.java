package com.thaap.payments.processor;

import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimulatorProcessorAdapter implements ProcessorAdapter {

    public static final String PROCESSOR_NAME = "THAAP_SIMULATOR";

    private static final Map<String, SimulatorOutcome> OUTCOMES = Map.of(
            simulatorCatalogKey("499999", "0001"), SimulatorOutcome.approved("APPROVED"),
            simulatorCatalogKey("499999", "0002"), SimulatorOutcome.declined("ISSUER_DECLINE", VerificationResult.PASS,
                    VerificationResult.PASS, VerificationResult.PASS),
            simulatorCatalogKey("499999", "0003"), SimulatorOutcome.declined("INSUFFICIENT_FUNDS", VerificationResult.PASS,
                    VerificationResult.PASS, VerificationResult.PASS),
            simulatorCatalogKey("499999", "0004"), SimulatorOutcome.declined("SUSPECTED_FRAUD", VerificationResult.PASS,
                    VerificationResult.PASS, VerificationResult.PASS),
            simulatorCatalogKey("499999", "0005"), SimulatorOutcome.declined("CVC_FAILURE", VerificationResult.FAIL,
                    VerificationResult.PASS, VerificationResult.PASS),
            simulatorCatalogKey("499999", "0006"), SimulatorOutcome.declined("AVS_LINE1_FAILURE", VerificationResult.PASS,
                    VerificationResult.FAIL, VerificationResult.PASS),
            simulatorCatalogKey("499999", "0007"), SimulatorOutcome.declined("AVS_POSTAL_FAILURE", VerificationResult.PASS,
                    VerificationResult.PASS, VerificationResult.FAIL),
            simulatorCatalogKey("499999", "0008"), SimulatorOutcome.requiresAction("THREE_DS_REQUIRED"));

    @Override
    public ProcessorAuthorizationResult authorize(ProcessorAuthorizationRequest request) {
        SimulatorOutcome outcome = OUTCOMES.getOrDefault(simulatorCatalogKey(request.cardBin(), request.cardLast4()),
                SimulatorOutcome.approved("APPROVED"));
        return outcome.toResult(request);
    }

    private static String simulatorCatalogKey(String cardBin, String cardLast4) {
        return cardBin + ":" + cardLast4;
    }

    private record SimulatorOutcome(AuthorizationStatus status, NetworkStatus networkStatus, String responseCode,
            VerificationResult cvcCheck, VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck,
            ThreeDsResult threeDsResult) {

        static SimulatorOutcome approved(String responseCode) {
            return new SimulatorOutcome(AuthorizationStatus.APPROVED, NetworkStatus.APPROVED, responseCode,
                    VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                    ThreeDsResult.NOT_REQUIRED);
        }

        static SimulatorOutcome declined(String responseCode, VerificationResult cvcCheck,
                VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck) {
            return new SimulatorOutcome(AuthorizationStatus.DECLINED, NetworkStatus.DECLINED, responseCode,
                    cvcCheck, avsLine1Check, avsPostalCodeCheck, ThreeDsResult.NOT_REQUIRED);
        }

        static SimulatorOutcome requiresAction(String responseCode) {
            return new SimulatorOutcome(AuthorizationStatus.REQUIRES_ACTION, NetworkStatus.REQUIRES_ACTION,
                    responseCode, VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                    ThreeDsResult.REQUIRED);
        }

        ProcessorAuthorizationResult toResult(ProcessorAuthorizationRequest request) {
            long authorizedAmount = status == AuthorizationStatus.APPROVED ? request.amount() : 0L;
            return new ProcessorAuthorizationResult(PROCESSOR_NAME, newProcessorReferenceId(), responseCode,
                    networkStatus, request.amount(), authorizedAmount, request.currency(), status, cvcCheck,
                    avsLine1Check, avsPostalCodeCheck, threeDsResult, responseCode);
        }

        private String newProcessorReferenceId() {
            return "auth_sim_" + UUID.randomUUID().toString().replace("-", "");
        }
    }
}
