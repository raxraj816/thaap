package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-DAT-001: every section names the authority it speaks for, and derived data never
 * gets promoted to source truth. An analyst who cannot tell "the payment is authorized" from
 * "a projection that last ran an hour ago believes the payment is authorized" will eventually
 * release a fraudulent payment and be certain they were right.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationLineageTests {

    private static final Map<String, String> EXPECTED_AUTHORITIES = Map.of(
            "paymentLifecycle", "SOURCE_DATABASE",
            "decisionExplanation", "DECISION_TIME_SOURCE",
            "reviewAndLabels", "REVIEW_SOURCE",
            "moneyAndDisputes", "LEDGER_AND_DISPUTE_SOURCE",
            "webhookSummary", "WEBHOOK_TRANSPORT_SOURCE",
            "analyticsContext", "ACTIVE_PAYMENT_RISK_PROJECTION");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

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
        merchant = scenario.merchant("investigation-lineage-owner");
        manager = scenario.operator(merchant, "lineage-manager", "RISK_MANAGER");
    }

    @Test
    void everySectionNamesItsAuthoritySoDerivedDataIsNeverMistakenForSourceTruth() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "lineage-authority");
        scenario.drainProjection();

        JsonNode statuses = detail(reviewed).get("sectionStatuses");
        EXPECTED_AUTHORITIES.forEach((section, authority) ->
                assertThat(statuses.at("/" + section + "/authority").asText())
                        .as("section %s must declare authority %s", section, authority)
                        .isEqualTo(authority));
        assertThat(statuses.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_AUTHORITIES.keySet());
    }

    @Test
    void anUnbuiltProjectionDegradesOnlyItsOwnSectionAndNeverTheAuthoritativeLifecycle()
            throws Exception {
        // Deliberately skip drainProjection: the source is committed, the projection has not
        // caught up. This is the ordinary steady state of any asynchronous read model.
        Payment reviewed = scenario.reviewedPayment(merchant, "lineage-unbuilt");

        JsonNode detail = detail(reviewed);
        String sourceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE merchant_id = ? AND id = ?",
                String.class, merchant.id(), reviewed.paymentIntentId());

        assertThat(detail.at("/paymentLifecycle/status").asText()).isEqualTo(sourceStatus);
        assertThat(detail.at("/sectionStatuses/paymentLifecycle/state").asText())
                .isEqualTo("AVAILABLE");
        assertThat(detail.at("/sectionStatuses/analyticsContext/state").asText())
                .as("an uncaught-up projection must not be advertised as AVAILABLE")
                .isIn("EMPTY", "PARTIAL", "STALE", "UNAVAILABLE");
        assertThat(detail.at("/sectionStatuses/analyticsContext/reasonCode").asText())
                .isNotBlank();
    }

    @Test
    void retiringTheProjectionGenerationEmptiesSearchWithoutAlteringSourceDetail()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "lineage-generation");
        scenario.drainProjection();
        JsonNode before = detail(reviewed);
        assertThat(searchIds()).contains(reviewed.paymentIntentId().toString());

        // Simulate a generation swap: the existing rows now belong to a generation that is
        // not the active one. Reading them anyway would serve an analyst rows built by a
        // handler version the platform no longer claims to run. The generation must be a real
        // row -- fk_payment_risk_projections_generation refuses to let a projection float free.
        UUID otherGeneration = insertNonActiveGeneration();
        jdbcTemplate.update("""
                UPDATE payment_risk_projections SET projection_generation_id = ?
                WHERE merchant_id = ?
                """, otherGeneration, merchant.id());

        assertThat(searchIds()).as("a non-active generation must not be served").isEmpty();
        JsonNode after = detail(reviewed);
        assertThat(after.get("paymentLifecycle")).isEqualTo(before.get("paymentLifecycle"));
        assertThat(after.get("decisionExplanation")).isEqualTo(before.get("decisionExplanation"));
        assertThat(after.at("/sectionStatuses/paymentLifecycle/state").asText())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void controlEvidenceStaysDecisionTimeTruthEvenAfterTheLiveCatalogueChanges() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "lineage-catalogue");
        scenario.drainProjection();
        JsonNode before = detail(reviewed).get("decisionExplanation");

        // The runtime cannot reprice, add, or remove a rule at all: the catalogue only moves
        // through a reviewed migration. This is the strongest form of the invariant -- past
        // decisions cannot be re-justified even by someone holding a database connection.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fraud_rules SET priority = priority + 500, long_threshold = 99999
                WHERE rule_code = 'REVIEW_REPEATED_ATTEMPT_V1'
                """)).rootCause().hasMessageContaining("rule lineage is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM fraud_rules WHERE rule_code = 'REVIEW_REPEATED_ATTEMPT_V1'
                """)).rootCause().hasMessageContaining("rule lineage is immutable");
        assertThatThrownBy(this::insertRepeatedAttemptRuleVersionTwo)
                .rootCause().hasMessageContaining("rule lineage is immutable");

        // The evidence the decision actually recorded is equally frozen.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rule_execution_results WHERE merchant_id = ?
                """, Integer.class, merchant.id())).isPositive();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE rule_execution_results SET priority = 1 WHERE merchant_id = ?
                """, merchant.id())).rootCause()
                .hasMessageContaining("rule lineage is immutable");

        JsonNode after = detail(reviewed).get("decisionExplanation");
        assertThat(after).isEqualTo(before);
        JsonNode control = controlNamed(after.get("controlEvaluations"),
                "REVIEW_REPEATED_ATTEMPT_V1");
        // Three attempts produced three decisions, so the evidence must be read for the exact
        // decision the response rendered rather than for whichever row the query happens to hit.
        UUID renderedDecisionId = UUID.fromString(
                detail(reviewed).at("/identity/latestFraudDecisionId").asText());
        Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                SELECT priority, rule_version FROM rule_execution_results
                WHERE merchant_id = ? AND fraud_decision_id = ?
                  AND rule_code = 'REVIEW_REPEATED_ATTEMPT_V1'
                """, merchant.id(), renderedDecisionId);
        assertThat(control.get("priority").asInt())
                .isEqualTo(((Number) persisted.get("priority")).intValue());
        assertThat(control.get("version").asText()).isEqualTo(persisted.get("rule_version"));
    }

    @Test
    void unavailableModelOutputIsDeclaredRatherThanFabricatedFromDeterministicScores()
            throws Exception {
        Payment blocked = scenario.blockedPayment(merchant, "lineage-model");
        scenario.drainProjection();

        JsonNode explanation = detail(blocked).get("decisionExplanation");
        assertThat(explanation.get("modelState").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(explanation.has("modelScore")).isFalse();
        assertThat(explanation.has("modelVersion")).isFalse();
        // The score that does exist is deterministic rule output and must say so through the
        // control that produced it, not through an implied model.
        assertThat(explanation.at("/precedence/winningControl").asText())
                .isEqualTo("BLOCK_USD_EXTREME_AMOUNT_V1");
        assertThat(explanation.get("featureCatalogueVersion").asText()).isNotBlank();
    }

    @Test
    void trafficOriginIsCarriedFromTheSourceRowAndCannotBeRewrittenUnderItsReview()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "lineage-origin");
        scenario.drainProjection();

        String sourceOrigin = jdbcTemplate.queryForObject(
                "SELECT traffic_origin FROM payment_intents WHERE merchant_id = ? AND id = ?",
                String.class, merchant.id(), reviewed.paymentIntentId());
        assertThat(detail(reviewed).at("/identity/trafficOrigin").asText()).isEqualTo(sourceOrigin);
        assertThat(sourceOrigin).isEqualTo("MERCHANT_API");

        // Origin is part of the composite key every risk child row references. Relabelling
        // live traffic as simulated after the fact would let real fraud be excluded from
        // every report that filters synthetic traffic out, so the database forbids it.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payment_intents SET traffic_origin = 'SIMULATED'
                WHERE merchant_id = ? AND id = ?
                """, merchant.id(), reviewed.paymentIntentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(detail(reviewed).at("/identity/trafficOrigin").asText()).isEqualTo(sourceOrigin);
    }

    /** A non-active generation still has to be a real, well-formed generation row. */
    private UUID insertNonActiveGeneration() {
        Map<String, Object> active = jdbcTemplate.queryForMap("""
                SELECT generation.projection_definition_id AS definition_id,
                       generation.generation_number AS generation_number,
                       generation.handler_version AS handler_version
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                WHERE definition.projection_name = 'payment-risk-v1'
                """);
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_generations (id, projection_definition_id,
                        generation_number, handler_version, build_source, lifecycle_status,
                        created_at)
                VALUES (?, ?, ?, ?, 'REBUILD', 'BUILDING', ?)
                """, generationId, active.get("definition_id"),
                ((Number) active.get("generation_number")).longValue() + 1,
                active.get("handler_version"), Timestamp.from(DatabaseInstant.now()));
        return generationId;
    }

    /** Attempts to reprice the control through a new catalogue version. The runtime must refuse. */
    private void insertRepeatedAttemptRuleVersionTwo() {
        Map<String, Object> current = jdbcTemplate.queryForMap("""
                SELECT action, reason_code, condition_type, feature_code,
                       currency_feature_code, currency_value
                FROM fraud_rules WHERE rule_code = 'REVIEW_REPEATED_ATTEMPT_V1'
                """);
        jdbcTemplate.update("""
                INSERT INTO fraud_rules (id, rule_code, ruleset_version, priority, action,
                        reason_code, condition_type, feature_code, currency_feature_code,
                        currency_value, long_threshold, status, created_at)
                VALUES (?, 'REVIEW_REPEATED_ATTEMPT_V1', 'v2', 700, ?, ?, ?, ?, ?, ?, 99999,
                        'ACTIVE', ?)
                """, UUID.randomUUID(), current.get("action"), current.get("reason_code"),
                current.get("condition_type"), current.get("feature_code"),
                current.get("currency_feature_code"), current.get("currency_value"),
                Timestamp.from(DatabaseInstant.now()));
    }

    private JsonNode detail(Payment payment) throws Exception {
        return scenario.json(get("/risk/workspace/payments/{id}", payment.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
    }

    private List<String> searchIds() throws Exception {
        JsonNode page = scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential())
                .contentType(APPLICATION_JSON).content("{}"), 200);
        return page.get("data").valueStream().map(row -> row.get("paymentIntentId").asText())
                .toList();
    }

    private JsonNode controlNamed(JsonNode controls, String code) {
        return controls.valueStream()
                .filter(control -> code.equals(control.get("code").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no control evaluation named " + code));
    }
}
