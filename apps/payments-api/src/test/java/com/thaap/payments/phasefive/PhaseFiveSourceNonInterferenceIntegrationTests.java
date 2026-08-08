package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.webhook.WebhookHttpTransport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proof for {@code C52-PAY-001}.
 *
 * <p>The integrated workflow — event, projection, rebuild, reconciliation, activation, rollback —
 * must not repeat a business mutation for either tenant. The comparison is a full-content digest
 * per source family per merchant, because the failure this row describes is a downstream stage
 * reusing live orchestration, which rewrites rows rather than adding them.
 */
class PhaseFiveSourceNonInterferenceIntegrationTests extends PhaseFiveClosureFixture {

    @MockitoSpyBean private ProcessorAdapter processorAdapter;
    @MockitoSpyBean private WebhookHttpTransport webhookTransport;

    @Test
    void theWholeRebuildAndActivationWorkflowRepeatsNoBusinessMutationForEitherTenant() {
        clearInvocations(processorAdapter, webhookTransport);
        Map<String, String> firstBefore = sourceTruth(first.merchantId());
        Map<String, String> secondBefore = sourceTruth(second.merchantId());
        long firstBalance = ledgerBalance(first.merchantId());
        long secondBalance = ledgerBalance(second.merchantId());

        // The fixture must actually carry the truth it claims to compare, or every assertion
        // below would pass equally well against a clean scan of nothing.
        for (String populated : java.util.List.of("payment_intents", "payment_attempts",
                "authorizations", "chargebacks", "fraud_decisions", "feature_snapshots",
                "fraud_labels", "webhook_endpoints", "webhook_events", "ledger_entries",
                "domain_events")) {
            assertThat(firstBefore.get(populated))
                    .describedAs("%s is empty, so comparing it proves nothing", populated)
                    .isNotEqualTo("empty");
        }
        assertThat(count("payment_intents", first.merchantId())).isPositive();
        assertThat(count("fraud_labels", first.merchantId())).isPositive();
        assertThat(count("ledger_entries", first.merchantId())).isPositive();
        assertThat(count("webhook_events", first.merchantId())).isPositive();

        runRebuildToActivation("closure-non-interference");

        assertThat(sourceTruth(first.merchantId())).isEqualTo(firstBefore);
        assertThat(sourceTruth(second.merchantId())).isEqualTo(secondBefore);
        assertThat(ledgerBalance(first.merchantId())).isEqualTo(firstBalance).isZero();
        assertThat(ledgerBalance(second.merchantId())).isEqualTo(secondBalance).isZero();
        verifyNoInteractions(processorAdapter, webhookTransport);
    }

    @Test
    void aRollbackAfterActivationAlsoRepeatsNoBusinessMutation() {
        runRebuildToActivation("closure-rollback-setup");
        clearInvocations(processorAdapter, webhookTransport);
        Map<String, String> firstBefore = sourceTruth(first.merchantId());
        Map<String, String> secondBefore = sourceTruth(second.merchantId());

        java.util.UUID baseline = java.util.UUID.fromString(
                "00000000-0000-0000-0000-000000000911");
        assertThat(rebuildControlService.rollback(dataOperatorKey, "closure-rollback",
                com.thaap.payments.projection.ProjectionNames.PAYMENT_RISK, baseline,
                "closure-rollback-request").activated()).isTrue();

        assertThat(sourceTruth(first.merchantId())).isEqualTo(firstBefore);
        assertThat(sourceTruth(second.merchantId())).isEqualTo(secondBefore);
        assertThat(ledgerBalance(first.merchantId())).isZero();
        verifyNoInteractions(processorAdapter, webhookTransport);
    }
}
