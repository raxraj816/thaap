package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thaap.payments.projection.ProjectionNames;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proof for {@code C52-TEN-001}.
 *
 * <p>Two merchants and a platform operator share one database and one workflow. The claims are
 * that neither merchant can reach the other's resources with a copied identifier, that platform
 * authority cannot mutate merchant truth, and that merchant credentials cannot operate the
 * infrastructure the platform owns.
 */
class PhaseFiveTenantIsolationIntegrationTests extends PhaseFiveClosureFixture {

    @Test
    void aCopiedForeignIdentifierIsAbsentRatherThanForbidden() throws Exception {
        mockMvc.perform(get("/payment_intents/{id}", second.paymentIntentId())
                        .header(API_KEY, first.apiKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/payment_intents/{id}", first.paymentIntentId())
                        .header(API_KEY, second.apiKey()))
                .andExpect(status().isNotFound());
        // Each merchant still sees its own, so the refusal above is tenancy and not a broken id.
        mockMvc.perform(get("/payment_intents/{id}", first.paymentIntentId())
                        .header(API_KEY, first.apiKey()))
                .andExpect(status().isOk());
    }

    @Test
    void platformDataAuthorityCannotMutateMerchantTruthAndMerchantsCannotOperateRebuilds()
            throws Exception {
        long firstPayments = count("payment_intents", first.merchantId());

        // A platform data-operator credential is not a merchant API key.
        mockMvc.perform(post("/payment_intents").header(API_KEY, dataOperatorKey)
                        .header(IDEMPOTENCY_KEY, "platform-cannot-create")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + UUID.randomUUID()
                                + "\",\"amount\":100,\"currency\":\"USD\","
                                + "\"captureMethod\":\"AUTOMATIC\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(count("payment_intents", first.merchantId())).isEqualTo(firstPayments);

        // A platform administrator is not a data operator, so it cannot run the data plane.
        assertThatThrownBy(() -> rebuildControlService.start(platformAdministratorKey,
                "administrator-cannot-rebuild", ProjectionNames.PAYMENT_RISK, "ALL",
                "administrator-cannot-rebuild-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class, refusal ->
                        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // A merchant risk-operator credential is not platform authority either.
        assertThatThrownBy(() -> rebuildControlService.start(first.manager().secret(),
                "merchant-cannot-rebuild", ProjectionNames.PAYMENT_RISK, "ALL",
                "merchant-cannot-rebuild-request"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aRebuiltGenerationCarriesBothTenantsSeparatelyAndCompletelyForEach() {
        var rebuild = runRebuildToActivation("closure-tenant-isolation");

        long firstRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND merchant_id = ?
                """, Long.class, rebuild.generationId(), first.merchantId());
        long secondRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ? AND merchant_id = ?
                """, Long.class, rebuild.generationId(), second.merchantId());
        long foreignRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE projection_generation_id = ?
                  AND merchant_id NOT IN (?, ?)
                """, Long.class, rebuild.generationId(), first.merchantId(),
                second.merchantId());

        assertThat(firstRows).isEqualTo(count("payment_intents", first.merchantId()));
        assertThat(secondRows).isEqualTo(count("payment_intents", second.merchantId()));
        assertThat(foreignRows).isZero();
        // No projection row may carry one tenant's payment under another tenant's identity.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections projection
                JOIN payment_intents intent ON intent.id = projection.payment_intent_id
                WHERE projection.merchant_id <> intent.merchant_id
                """, Long.class)).isZero();
    }
}
