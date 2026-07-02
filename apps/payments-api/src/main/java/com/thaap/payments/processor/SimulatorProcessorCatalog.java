package com.thaap.payments.processor;

import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SimulatorProcessorCatalog {

    public static final String FIXTURE_APPROVED = "approved";
    public static final String FIXTURE_ISSUER_DECLINE = "issuer_decline";
    public static final String FIXTURE_INSUFFICIENT_FUNDS = "insufficient_funds";
    public static final String FIXTURE_SUSPECTED_FRAUD = "suspected_fraud";
    public static final String FIXTURE_CVC_FAILURE = "cvc_failure";
    public static final String FIXTURE_AVS_LINE1_FAILURE = "avs_line1_failure";
    public static final String FIXTURE_AVS_POSTAL_FAILURE = "avs_postal_failure";
    public static final String FIXTURE_THREE_DS_REQUIRED = "three_ds_required";
    public static final String FIXTURE_UNKNOWN_DEFAULT_APPROVAL = "unknown_default_approval";

    private static final SimulatorOutcome DEFAULT_APPROVED_OUTCOME = approvedOutcome();

    private static final List<SimulatorFixture> FIXTURES = List.of(
            new SimulatorFixture(FIXTURE_APPROVED, "499999", "0001", approvedOutcome()),
            new SimulatorFixture(FIXTURE_ISSUER_DECLINE, "499999", "0002",
                    declinedOutcome("ISSUER_DECLINE", VerificationResult.PASS,
                            VerificationResult.PASS, VerificationResult.PASS)),
            new SimulatorFixture(FIXTURE_INSUFFICIENT_FUNDS, "499999", "0003",
                    declinedOutcome("INSUFFICIENT_FUNDS", VerificationResult.PASS,
                            VerificationResult.PASS, VerificationResult.PASS)),
            new SimulatorFixture(FIXTURE_SUSPECTED_FRAUD, "499999", "0004",
                    declinedOutcome("SUSPECTED_FRAUD", VerificationResult.PASS,
                            VerificationResult.PASS, VerificationResult.PASS)),
            new SimulatorFixture(FIXTURE_CVC_FAILURE, "499999", "0005",
                    declinedOutcome("CVC_FAILURE", VerificationResult.FAIL,
                            VerificationResult.PASS, VerificationResult.PASS)),
            new SimulatorFixture(FIXTURE_AVS_LINE1_FAILURE, "499999", "0006",
                    declinedOutcome("AVS_LINE1_FAILURE", VerificationResult.PASS,
                            VerificationResult.FAIL, VerificationResult.PASS)),
            new SimulatorFixture(FIXTURE_AVS_POSTAL_FAILURE, "499999", "0007",
                    declinedOutcome("AVS_POSTAL_FAILURE", VerificationResult.PASS,
                            VerificationResult.PASS, VerificationResult.FAIL)),
            new SimulatorFixture(FIXTURE_THREE_DS_REQUIRED, "499999", "0008",
                    new SimulatorOutcome(AuthorizationStatus.REQUIRES_ACTION, NetworkStatus.REQUIRES_ACTION,
                            "THREE_DS_REQUIRED", VerificationResult.PASS, VerificationResult.PASS,
                            VerificationResult.PASS, ThreeDsResult.REQUIRED)));

    private final Map<SimulatorCardKey, SimulatorFixture> fixturesByCardKey;

    public SimulatorProcessorCatalog() {
        this.fixturesByCardKey = FIXTURES.stream()
                .collect(Collectors.toUnmodifiableMap(
                        fixture -> new SimulatorCardKey(fixture.cardBin(), fixture.cardLast4()),
                        Function.identity()));
    }

    public SimulatorFixture resolve(String cardBin, String cardLast4) {
        return fixturesByCardKey.getOrDefault(
                new SimulatorCardKey(cardBin, cardLast4),
                new SimulatorFixture(FIXTURE_UNKNOWN_DEFAULT_APPROVAL, cardBin, cardLast4,
                        DEFAULT_APPROVED_OUTCOME));
    }

    public List<SimulatorFixture> documentedFixtures() {
        return FIXTURES;
    }

    private static SimulatorOutcome approvedOutcome() {
        return new SimulatorOutcome(AuthorizationStatus.APPROVED, NetworkStatus.APPROVED, "APPROVED",
                VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                ThreeDsResult.NOT_REQUIRED);
    }

    private static SimulatorOutcome declinedOutcome(String responseCode, VerificationResult cvcCheck,
            VerificationResult avsLine1Check, VerificationResult avsPostalCodeCheck) {
        return new SimulatorOutcome(AuthorizationStatus.DECLINED, NetworkStatus.DECLINED, responseCode,
                cvcCheck, avsLine1Check, avsPostalCodeCheck, ThreeDsResult.NOT_REQUIRED);
    }

    private record SimulatorCardKey(String cardBin, String cardLast4) {
    }

    public record SimulatorFixture(String fixtureId, String cardBin, String cardLast4,
            SimulatorOutcome outcome) {
    }

    public record SimulatorOutcome(AuthorizationStatus status, NetworkStatus networkStatus, String responseCode,
            VerificationResult cvcCheck, VerificationResult avsLine1Check,
            VerificationResult avsPostalCodeCheck, ThreeDsResult threeDsResult) {
    }
}
