package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proof for {@code C52-SEC-001}.
 *
 * <p>Distinct canaries per prohibited class, scanned across every operational sink the integrated
 * workflow writes. One shared marker would let a leak of the wrong class pass as a leak of the
 * right one, which is the reasoning the chapter-level privacy suites already use.
 *
 * <p>The canaries are values the fixture genuinely holds — real merchant API keys, a real
 * operator credential, the card number that was tokenised, the contact address — rather than
 * invented strings. A scan for a value the system never had proves only that the scan runs.
 */
class PhaseFivePrivacySinkIntegrationTests extends PhaseFiveClosureFixture {

    /** Every table the integrated workflow writes that an operator or downstream reader sees. */
    private static final List<String> SINKS = List.of(
            "domain_events", "event_publications", "audit_logs", "payment_risk_projections",
            "merchant_risk_aggregates", "projection_stream_positions", "projection_rebuilds",
            "projection_rebuild_events", "projection_generations", "projection_reconciliations",
            "webhook_deliveries", "webhook_delivery_attempts");

    @Test
    void noProhibitedValueReachesAnyOperationalSinkAcrossTheIntegratedWorkflow() {
        runRebuildToActivation("closure-privacy");

        List<String> prohibited = List.of(
                first.apiKey(), second.apiKey(),
                first.manager().secret(), second.manager().secret(),
                dataOperatorKey, platformAdministratorKey,
                "4242424242424242",
                first.key() + "@example.test", second.key() + "@example.test");

        for (String sink : SINKS) {
            String contents = dump(sink);
            for (String canary : prohibited) {
                assertThat(contents)
                        .describedAs("%s leaked a prohibited value", sink)
                        .doesNotContain(canary);
            }
        }
    }

    /**
     * The scan must be looking at something. Without this the assertions above would hold just as
     * well against a set of empty tables, which is the failure mode the chapter privacy rows
     * already learned to guard against.
     */
    @Test
    void theSinksActuallyContainTheWorkflowTheScanClaimsToCover() {
        runRebuildToActivation("closure-privacy-canary");

        for (String sink : List.of("domain_events", "event_publications", "audit_logs",
                "payment_risk_projections", "projection_rebuilds", "projection_generations")) {
            assertThat(rowCount(sink))
                    .describedAs("%s is empty, so scanning it proves nothing", sink)
                    .isPositive();
        }
        // And the prohibited values genuinely exist in the system being scanned, so the scan is
        // looking for something that is really there to find.
        assertThat(first.apiKey()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_methods WHERE merchant_id = ?", Integer.class,
                first.merchantId())).isPositive();
    }

    @Test
    void theSafeApiViewOfARebuiltGenerationExposesNoProhibitedValue() throws Exception {
        runRebuildToActivation("closure-privacy-api");

        String analytics = mockMvc.perform(get("/risk/analytics/summary")
                        .header(RISK_KEY, first.manager().secret()))
                .andReturn().getResponse().getContentAsString();

        assertThat(analytics).doesNotContain(first.apiKey(), second.apiKey(),
                first.manager().secret(), dataOperatorKey, "4242424242424242",
                first.key() + "@example.test");
    }

    private String dump(String table) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table);
        StringBuilder contents = new StringBuilder();
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                if (value instanceof byte[] bytes) {
                    contents.append(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    contents.append(value);
                }
                contents.append('|');
            }
        }
        return contents.toString();
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
