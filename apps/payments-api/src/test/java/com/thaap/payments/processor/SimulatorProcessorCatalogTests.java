package com.thaap.payments.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatorProcessorCatalogTests {

    private static final long REQUESTED_AMOUNT = 4200L;

    private final SimulatorProcessorCatalog catalog = new SimulatorProcessorCatalog();
    private final SimulatorProcessorAdapter adapter = new SimulatorProcessorAdapter(catalog);

    @Test
    void documentedCatalogMatchesEveryProcessorFixtureContract() {
        List<ExpectedFixture> expectedFixtures = List.of(
                new ExpectedFixture("approved", "0001", AuthorizationStatus.APPROVED,
                        NetworkStatus.APPROVED, "APPROVED", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, REQUESTED_AMOUNT),
                new ExpectedFixture("issuer_decline", "0002", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "ISSUER_DECLINE", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("insufficient_funds", "0003", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "INSUFFICIENT_FUNDS", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("suspected_fraud", "0004", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "SUSPECTED_FRAUD", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("cvc_failure", "0005", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "CVC_FAILURE", VerificationResult.FAIL,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("avs_line1_failure", "0006", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "AVS_LINE1_FAILURE", VerificationResult.PASS,
                        VerificationResult.FAIL, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("avs_postal_failure", "0007", AuthorizationStatus.DECLINED,
                        NetworkStatus.DECLINED, "AVS_POSTAL_FAILURE", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.FAIL,
                        ThreeDsResult.NOT_REQUIRED, 0L),
                new ExpectedFixture("three_ds_required", "0008", AuthorizationStatus.REQUIRES_ACTION,
                        NetworkStatus.REQUIRES_ACTION, "THREE_DS_REQUIRED", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.REQUIRED, 0L),
                new ExpectedFixture("repeated_three_ds_required", "0009", AuthorizationStatus.REQUIRES_ACTION,
                        NetworkStatus.REQUIRES_ACTION, "REPEATED_THREE_DS_REQUIRED", VerificationResult.PASS,
                        VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.REQUIRED, 0L));

        assertThat(catalog.documentedFixtures())
                .extracting(SimulatorProcessorCatalog.SimulatorFixture::fixtureId)
                .containsExactlyElementsOf(expectedFixtures.stream().map(ExpectedFixture::fixtureId).toList());

        for (ExpectedFixture expected : expectedFixtures) {
            ProcessorAuthorizationResult result = authorize("499999", expected.cardLast4());

            assertThat(result.status()).isEqualTo(expected.status());
            assertThat(result.networkStatus()).isEqualTo(expected.networkStatus());
            assertThat(result.responseCode()).isEqualTo(expected.responseCode());
            assertThat(result.simulatorOutcomeCode()).isEqualTo(expected.responseCode());
            assertThat(result.cvcCheck()).isEqualTo(expected.cvcCheck());
            assertThat(result.avsLine1Check()).isEqualTo(expected.avsLine1Check());
            assertThat(result.avsPostalCodeCheck()).isEqualTo(expected.avsPostalCodeCheck());
            assertThat(result.threeDsResult()).isEqualTo(expected.threeDsResult());
            assertThat(result.requestedAmount()).isEqualTo(REQUESTED_AMOUNT);
            assertThat(result.authorizedAmount()).isEqualTo(expected.authorizedAmount());
            assertThat(result.processorReferenceId()).startsWith("auth_sim_");
        }
    }

    @Test
    void unknownValidCardMetadataUsesExplicitDefaultApprovalWithoutChangingDocumentedCatalog() {
        ProcessorAuthorizationResult result = authorize("424242", "4242");

        assertThat(catalog.resolve("424242", "4242").fixtureId())
                .isEqualTo(SimulatorProcessorCatalog.FIXTURE_UNKNOWN_DEFAULT_APPROVAL);
        assertThat(result.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(result.networkStatus()).isEqualTo(NetworkStatus.APPROVED);
        assertThat(result.responseCode()).isEqualTo("APPROVED");
        assertThat(result.authorizedAmount()).isEqualTo(REQUESTED_AMOUNT);
        assertThat(result.threeDsResult()).isEqualTo(ThreeDsResult.NOT_REQUIRED);
        assertThat(catalog.documentedFixtures()).hasSize(9);
    }

    @Test
    void authenticatedContinuationPreservesAuthenticationEvidenceAcrossApprovalAndDecline() {
        ProcessorAuthorizationResult approval = authorizeAuthenticated("499999", "0008");
        ProcessorAuthorizationResult decline = authorizeAuthenticated("499999", "0003");
        ProcessorAuthorizationResult repeatedChallenge = authorizeAuthenticated("499999", "0009");

        assertThat(approval.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(approval.threeDsResult()).isEqualTo(ThreeDsResult.AUTHENTICATED);
        assertThat(decline.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(decline.threeDsResult()).isEqualTo(ThreeDsResult.AUTHENTICATED);
        assertThat(repeatedChallenge.status()).isEqualTo(AuthorizationStatus.REQUIRES_ACTION);
        assertThat(repeatedChallenge.threeDsResult()).isEqualTo(ThreeDsResult.REQUIRED);
    }

    @Test
    void documentedCatalogCannotBeMutatedByConsumers() {
        SimulatorProcessorCatalog.SimulatorFixture firstFixture = catalog.documentedFixtures().getFirst();

        assertThatThrownBy(() -> catalog.documentedFixtures().add(firstFixture))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void processorOperationIdentityIsStableForTheSameAttemptAndDistinctAcrossAttempts() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        UUID firstAttemptId = UUID.randomUUID();

        ProcessorAuthorizationResult first = adapter.authorize(new ProcessorAuthorizationRequest(
                merchantId, paymentIntentId, firstAttemptId, paymentMethodId,
                REQUESTED_AMOUNT, "USD", "424242", "4242"));
        ProcessorAuthorizationResult retry = adapter.authorize(new ProcessorAuthorizationRequest(
                merchantId, paymentIntentId, firstAttemptId, paymentMethodId,
                REQUESTED_AMOUNT, "USD", "424242", "4242"));
        ProcessorAuthorizationResult distinctAttempt = adapter.authorize(new ProcessorAuthorizationRequest(
                merchantId, paymentIntentId, UUID.randomUUID(), paymentMethodId,
                REQUESTED_AMOUNT, "USD", "424242", "4242"));

        assertThat(retry.processorReferenceId()).isEqualTo(first.processorReferenceId());
        assertThat(distinctAttempt.processorReferenceId()).isNotEqualTo(first.processorReferenceId());
        assertThat(first.processorReferenceId())
                .isEqualTo("auth_sim_" + firstAttemptId.toString().replace("-", ""));
    }

    private ProcessorAuthorizationResult authorize(String cardBin, String cardLast4) {
        return adapter.authorize(new ProcessorAuthorizationRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                REQUESTED_AMOUNT, "USD", cardBin, cardLast4));
    }

    private ProcessorAuthorizationResult authorizeAuthenticated(String cardBin, String cardLast4) {
        return adapter.authorize(new ProcessorAuthorizationRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                REQUESTED_AMOUNT, "USD", cardBin, cardLast4, true));
    }

    private record ExpectedFixture(String fixtureId, String cardLast4,
            AuthorizationStatus status, NetworkStatus networkStatus, String responseCode,
            VerificationResult cvcCheck, VerificationResult avsLine1Check,
            VerificationResult avsPostalCodeCheck, ThreeDsResult threeDsResult,
            long authorizedAmount) {
    }
}
