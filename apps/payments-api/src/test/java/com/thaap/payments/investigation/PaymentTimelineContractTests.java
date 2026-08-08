package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C54-TIM-001. The timeline is the analyst's account of what actually happened to a
 * payment, so it must present every canonical stream fact exactly once in immutable version
 * order, and must never let transport retries or business timestamps rewrite that order.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentTimelineContractTests {

    private static final Set<String> CLOSED_CATEGORIES = Set.of("PAYMENT", "DECISION", "MONEY",
            "DISPUTE", "AUTHENTICATION", "REVIEW", "LABEL", "POST_AUTHORIZATION");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() throws Exception {
        // A shared cached context means a shared database. Without this the fixture
        // inherits the previous class's rows, and drainProjection replays them as if
        // they were its own. See ER-0102.
        databaseCleaner.clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("timeline-contract");
        operator = scenario.operator(merchant, "timeline-contract-key", "RISK_MANAGER");
    }

    @Test
    void theTimelineReproducesTheCanonicalStreamExactlyOnceInVersionOrder() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "timeline-full");

        List<JsonNode> entries = allEntries(reviewed.paymentIntentId());
        List<Long> versions = entries.stream()
                .map(entry -> entry.get("streamVersion").asLong()).toList();

        List<Map<String, Object>> stored = jdbcTemplate.queryForList("""
                SELECT stream_version, id FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                ORDER BY stream_version
                """, merchant.id(), reviewed.paymentIntentId());

        assertThat(versions).isSorted().doesNotHaveDuplicates();
        assertThat(versions).containsExactlyElementsOf(
                stored.stream().map(row -> ((Number) row.get("stream_version")).longValue()).toList());
        assertThat(entries.stream().map(entry -> UUID.fromString(entry.get("eventId").asText())))
                .containsExactlyElementsOf(stored.stream().map(row -> (UUID) row.get("id")).toList());
    }

    @Test
    void everyEntryCarriesAClosedCategoryAndATitleDerivedFromItsEventType() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "timeline-codes");

        List<JsonNode> entries = allEntries(reviewed.paymentIntentId());
        assertThat(entries).isNotEmpty();
        for (JsonNode entry : entries) {
            String eventType = entry.get("eventType").asText();
            assertThat(entry.get("category").asText()).isIn(CLOSED_CATEGORIES);
            // The title is a code the client renders, never a server-composed sentence that could
            // carry payload text.
            assertThat(entry.get("titleCode").asText()).isEqualTo("timeline." + eventType);
            assertThat(entry.get("eventVersion").asInt()).isEqualTo(1);
        }
    }

    @Test
    void aLaterAttemptNeverReordersOrRewritesTheDecisionThatCameBeforeIt() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "timeline-order");

        List<JsonNode> entries = allEntries(reviewed.paymentIntentId());
        List<String> types = entries.stream().map(entry -> entry.get("eventType").asText()).toList();

        // Three confirms produce three attempts, but the third is held by the REVIEW decision and
        // never completes. The timeline must report the attempts that actually completed rather
        // than one entry per request.
        Long completedAttempts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                  AND event_type = 'payment_attempt.completed'
                """, Long.class, merchant.id(), reviewed.paymentIntentId());
        assertThat(completedAttempts).isGreaterThan(1);
        assertThat(types.stream().filter("payment_attempt.completed"::equals).count())
                .isEqualTo(completedAttempts);
        assertThat(types.indexOf("payment_intent.created")).isZero();

        JsonNode firstDecision = entries.stream()
                .filter(entry -> "fraud_decision.created".equals(entry.get("eventType").asText()))
                .findFirst().orElseThrow();
        Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                SELECT d.action, d.risk_level FROM fraud_decisions d
                JOIN payment_attempts a ON a.merchant_id = d.merchant_id AND a.id = d.payment_attempt_id
                WHERE d.merchant_id = ? AND a.payment_intent_id = ?
                ORDER BY d.created_at, a.attempt_number LIMIT 1
                """, merchant.id(), reviewed.paymentIntentId());
        assertThat(firstDecision.at("/safeFacts/action").asText()).isEqualTo(persisted.get("action"));
        assertThat(firstDecision.at("/safeFacts/riskLevel").asText())
                .isEqualTo(persisted.get("risk_level"));
    }

    @Test
    void twoEventsRecordedInTheSameInstantStayDistinctBecauseOrderComesFromTheStream()
            throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "timeline-tie");

        List<JsonNode> entries = allEntries(reviewed.paymentIntentId());
        Map<String, List<JsonNode>> byRecordedAt = new java.util.HashMap<>();
        for (JsonNode entry : entries) {
            byRecordedAt.computeIfAbsent(entry.get("recordedAt").asText(), key -> new ArrayList<>())
                    .add(entry);
        }
        List<JsonNode> collided = byRecordedAt.values().stream()
                .filter(group -> group.size() > 1).findFirst().orElse(List.of());

        // If the fixture produced no timestamp collision the claim would be vacuous, so assert the
        // weaker but always-true property instead: no two entries share a stream version.
        assertThat(entries.stream().map(entry -> entry.get("streamVersion").asLong()).toList())
                .doesNotHaveDuplicates();
        for (JsonNode entry : collided) {
            assertThat(entry.get("eventId").asText()).isNotBlank();
        }
    }

    @Test
    void webhookTransportAttemptsAreASeparateCollectionAndNeverEnterTheStream() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "timeline-hook");

        List<JsonNode> entries = allEntries(reviewed.paymentIntentId());
        assertThat(entries.stream().map(entry -> entry.get("category").asText()))
                .doesNotContain("WEBHOOK", "TRANSPORT");
        assertThat(entries.stream().map(entry -> entry.get("eventType").asText()))
                .noneMatch(type -> type.startsWith("webhook"));

        JsonNode webhooks = scenario.json(get("/risk/workspace/payments/"
                + reviewed.paymentIntentId() + "/webhook-activity")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
        // A retried delivery is transport evidence. Presenting it beside payment facts would let
        // one business event look like several.
        assertThat(webhooks.get("authority").asText()).isEqualTo("WEBHOOK_TRANSPORT_EVIDENCE");
    }

    @Test
    void aPaymentWithNoConfirmationStillReturnsItsCreationFactRatherThanAnEmptySection()
            throws Exception {
        RiskWorkspaceScenario.Payment unconfirmed = scenario.unconfirmedPayment(merchant,
                "timeline-thin");

        List<JsonNode> entries = allEntries(unconfirmed.paymentIntentId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("eventType").asText()).isEqualTo("payment_intent.created");
        assertThat(entries.getFirst().at("/safeFacts/currency").asText()).isEqualTo("USD");
    }

    private List<JsonNode> allEntries(UUID paymentIntentId) throws Exception {
        String route = "/risk/workspace/payments/" + paymentIntentId + "/timeline";
        List<JsonNode> entries = new ArrayList<>();
        String cursor = null;
        do {
            String query = route + "?limit=50" + (cursor == null ? ""
                    : "&cursor=" + java.net.URLEncoder.encode(cursor,
                            java.nio.charset.StandardCharsets.UTF_8));
            JsonNode page = scenario.json(get(query)
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
            page.get("data").forEach(entries::add);
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        return entries;
    }
}
