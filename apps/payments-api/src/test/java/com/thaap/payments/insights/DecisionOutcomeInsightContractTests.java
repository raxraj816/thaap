package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C56-OUT-001. Decision and outcome insights are read from the active projection, so every
 * published number must reconcile against the projection rows themselves rather than against
 * another derived figure. The failure this guards against is quiet: a distribution that
 * double-counts, or money inferred from events instead of reconciled source columns, produces a
 * confident total nobody can trace back to a payment.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class DecisionOutcomeInsightContractTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("decision-outcome");
        manager = scenario.operator(merchant, "decision-outcome-manager", "RISK_MANAGER");
    }

    @Test
    void theDecisionDistributionReconcilesRowForRowAgainstTheProjection() throws Exception {
        scenario.allowedPayment(merchant, "out-allow-a");
        scenario.allowedPayment(merchant, "out-allow-b");
        scenario.blockedPayment(merchant, "out-block");
        scenario.challengedPayment(merchant, "out-challenge");
        scenario.drainProjection();
        backdate();

        Map<String, Long> published = new HashMap<>();
        decisionOutcomes().get("decisionDistribution").forEach(row ->
                published.put(row.get("code").asText(), row.get("count").asLong()));

        List<Map<String, Object>> projected = jdbcTemplate.queryForList("""
                SELECT COALESCE(fraud_action, 'UNKNOWN') AS action, COUNT(*) AS total
                FROM payment_risk_projections WHERE merchant_id = ?
                GROUP BY fraud_action
                """, merchant.id());
        assertThat(projected).as("the reconciliation is vacuous with no projected rows")
                .isNotEmpty();

        Map<String, Long> expected = new HashMap<>();
        projected.forEach(row -> expected.put((String) row.get("action"),
                ((Number) row.get("total")).longValue()));
        assertThat(published)
                .as("every published bucket must equal its projection row count exactly")
                .isEqualTo(expected);
    }

    @Test
    void theOriginPolicyPartitionsThePopulationWithoutOverlapOrLoss() throws Exception {
        scenario.allowedPayment(merchant, "out-origin-a");
        scenario.allowedPayment(merchant, "out-origin-b");
        scenario.drainProjection();
        backdate();

        long all = population("ALL");
        long live = population("LIVE_ONLY");
        long synthetic = population("SYNTHETIC_ONLY");

        // Live and synthetic are disjoint and exhaustive over the traffic origins the fixture
        // produces, so a payment counted in both would inflate every downstream rate.
        assertThat(live + synthetic)
                .as("origin partitions must sum to the whole population, never double-count")
                .isEqualTo(all);
        assertThat(all).isGreaterThan(0);
    }

    @Test
    void moneyReconcilesAgainstTheProjectionColumnsRatherThanBeingInferredFromEvents()
            throws Exception {
        scenario.payment(merchant, "out-money-usd-a", 5_000L, "USD", 1);
        scenario.payment(merchant, "out-money-usd-b", 7_500L, "USD", 1);
        scenario.payment(merchant, "out-money-eur", 9_000L, "EUR", 1);
        scenario.drainProjection();
        backdate();

        JsonNode money = decisionOutcomes().get("moneyByCurrency");
        assertThat(money).isNotEmpty();

        for (JsonNode row : money) {
            String currency = row.get("currency").asText();
            Map<String, Object> projected = jdbcTemplate.queryForMap("""
                    SELECT COALESCE(SUM(captured_amount_minor), 0) AS captured,
                           COALESCE(SUM(refunded_amount_minor), 0) AS refunded,
                           COALESCE(SUM(disputed_amount_minor), 0) AS disputed
                    FROM payment_risk_projections
                    WHERE merchant_id = ? AND currency = ?
                    """, merchant.id(), currency);
            assertThat(row.get("capturedAmountMinor").asLong())
                    .as("%s captured must equal the projection sum exactly", currency)
                    .isEqualTo(((Number) projected.get("captured")).longValue());
            assertThat(row.get("refundedAmountMinor").asLong())
                    .isEqualTo(((Number) projected.get("refunded")).longValue());
            assertThat(row.get("disputedAmountMinor").asLong())
                    .isEqualTo(((Number) projected.get("disputed")).longValue());
        }
    }

    @Test
    void readingInsightsMutatesNoSourceOrProjectionRow() throws Exception {
        scenario.reviewedPayment(merchant, "out-readonly");
        scenario.drainProjection();
        backdate();

        String before = domainFingerprint();
        decisionOutcomes();
        decisionOutcomes();
        assertThat(domainFingerprint())
                .as("an insight read that writes would make every later number unreproducible")
                .isEqualTo(before);
    }

    @Test
    void theResponseNamesItsProjectionGenerationSoANumberCanBeTracedToItsSource()
            throws Exception {
        scenario.allowedPayment(merchant, "out-generation");
        scenario.drainProjection();
        backdate();

        JsonNode freshness = decisionOutcomes().get("freshness");
        String activeGeneration = jdbcTemplate.queryForObject("""
                SELECT CAST(generation.id AS VARCHAR)
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                 AND generation.projection_definition_id = definition.id
                WHERE definition.projection_name = 'payment-risk-v1'
                  AND generation.lifecycle_status = 'ACTIVE'
                """, String.class);
        assertThat(freshness.get("generationId").asText())
                .as("without naming its generation, a number cannot be reproduced later")
                .isEqualTo(activeGeneration);
        assertThat(freshness.hasNonNull("sourceCutoff")).isTrue();
        assertThat(freshness.hasNonNull("state")).isTrue();
    }

    @Test
    void everyDrillThroughDescriptorIsTenantFreeAndReplayableAgainstTheSearchRoute()
            throws Exception {
        scenario.allowedPayment(merchant, "drill-allow");
        scenario.blockedPayment(merchant, "drill-block");
        scenario.drainProjection();
        backdate();

        JsonNode body = decisionOutcomes();
        int drillable = 0;
        for (JsonNode segment : body.get("decisionDistribution")) {
            JsonNode drill = segment.get("drillThrough");
            if (drill == null || drill.isNull()) {
                // Only a placeholder the search grammar cannot express may lack a descriptor.
                assertThat(segment.get("code").asText()).isEqualTo("UNKNOWN");
                continue;
            }
            drillable++;
            // The descriptor is a filter, not a link: no tenant, operator, generation, or row id.
            assertThat(drill.toString())
                    .doesNotContain(merchant.id().toString())
                    .doesNotContain(manager.id().toString())
                    .doesNotContain("merchantId").doesNotContain("operatorId")
                    .doesNotContain("generationId").doesNotContain("paymentIntentId");
            assertThat(drill.get("fraudActions").get(0).asText())
                    .isEqualTo(segment.get("code").asText());

            // Replaying it against the search route reproduces exactly that segment's count.
            String search = mockMvc.perform(post("/risk/workspace/payments/search")
                            .header(RISK_OPERATOR_HEADER, manager.credential())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"filter\":{\"fraudActions\":[\""
                                    + segment.get("code").asText() + "\"]}}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(search).get("data").size())
                    .as("a drill-through that does not reproduce its own segment sends an "
                            + "analyst to a result set that disagrees with the card")
                    .isEqualTo((int) segment.get("count").asLong());
        }
        assertThat(drillable).as("the proof is vacuous with no drillable segment").isGreaterThan(0);
    }

    @Test
    void aDrillThroughDescriptorCopiedByAnotherTenantReachesOnlyItsOwnPayments() throws Exception {
        scenario.blockedPayment(merchant, "drill-tenant-own");
        scenario.drainProjection();
        backdate();

        JsonNode drill = null;
        for (JsonNode segment : decisionOutcomes().get("decisionDistribution")) {
            if (segment.get("drillThrough") != null && !segment.get("drillThrough").isNull()) {
                drill = segment.get("drillThrough");
            }
        }
        assertThat(drill).isNotNull();

        // A neighbour replays the identical descriptor with its own credential. Tenant comes
        // from the credential, never from the filter, so it sees nothing of ours.
        Merchant neighbour = scenario.merchant("drill-neighbour");
        Operator neighbourManager = scenario.operator(neighbour, "drill-neighbour-manager",
                "RISK_MANAGER");
        String search = mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, neighbourManager.credential())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"fraudActions\":[\"BLOCK\"]}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(search).get("data")).isEmpty();
    }

    private long population(String originPolicy) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .param("originPolicy", originPolicy)
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        long total = 0;
        for (JsonNode row : body.get("decisionDistribution")) {
            total += row.get("count").asLong();
        }
        return total;
    }

    private JsonNode decisionOutcomes() throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String domainFingerprint() {
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT COUNT(*) FROM payment_intents WHERE merchant_id = ?) || '|' ||
                       (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = ?) || '|' ||
                       (SELECT COUNT(*) FROM manual_review_cases WHERE merchant_id = ?) || '|' ||
                       (SELECT COUNT(*) FROM payment_risk_projections WHERE merchant_id = ?) || '|' ||
                       (SELECT COUNT(*) FROM domain_events WHERE merchant_id = ?)
                """, String.class, merchant.id(), merchant.id(), merchant.id(),
                merchant.id(), merchant.id());
    }

    /**
     * Shifts projected rows into a complete prior UTC day. The frozen range contract ends every
     * window at the start of today, so a payment created during a test run is otherwise invisible
     * to every valid range. The whole row moves because
     * {@code ck_payment_risk_projections_intent_time} requires the intent time to stay at or
     * before both event timestamps.
     */
    private void backdate() {
        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Timestamp shifted = Timestamp.from(startOfToday.minus(2, ChronoUnit.DAYS));
        jdbcTemplate.update("""
                UPDATE payment_risk_projections
                SET intent_occurred_at = ?, last_event_occurred_at = ?, last_event_recorded_at = ?
                WHERE merchant_id = ?
                """, shifted, shifted, shifted, merchant.id());
    }
}
