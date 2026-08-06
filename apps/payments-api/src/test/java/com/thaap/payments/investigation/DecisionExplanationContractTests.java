package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-EXP-001: the explanation reports decision-time truth for every evaluated control,
 * not just the matched ones, converts the stored score without losing precision, and never
 * renders absent Phase 7 model state as a neutral model result.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class DecisionExplanationContractTests {

    /** The full rules_v1 catalogue. Every evaluated control must render, matched or not. */
    private static final List<String> EVERY_SYSTEM_CONTROL = List.of(
            "BLOCK_USD_EXTREME_AMOUNT_V1", "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1",
            "REVIEW_REPEATED_ATTEMPT_V1");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("decision-explanation-owner");
        manager = scenario.operator(merchant, "explanation-manager", "RISK_MANAGER");
    }

    @ParameterizedTest(name = "{0} renders score {1}bps at level {2} and winner {3}")
    @CsvSource({
            "allow,       500,  LOW,    DEFAULT_ALLOW",
            "challenged, 5500,  MEDIUM, REQUEST_3DS_USD_ELEVATED_AMOUNT_V1",
            "reviewed,   7500,  HIGH,   REVIEW_REPEATED_ATTEMPT_V1",
            "blocked,    9500,  HIGH,   BLOCK_USD_EXTREME_AMOUNT_V1"})
    void everyDecisionActionRendersItsStoredScoreLevelAndExactlyOneWinningControl(
            String partition, int expectedScoreBps, String expectedLevel, String expectedWinner)
            throws Exception {
        Payment payment = switch (partition) {
            case "allow" -> scenario.allowedPayment(merchant, "explain-allow");
            case "challenged" -> scenario.challengedPayment(merchant, "explain-3ds");
            case "reviewed" -> scenario.reviewedPayment(merchant, "explain-review");
            case "blocked" -> scenario.blockedPayment(merchant, "explain-block");
            default -> throw new IllegalArgumentException(partition);
        };

        JsonNode explanation = detail(payment.paymentIntentId()).get("decisionExplanation");
        assertThat(explanation.get("riskScoreBps").asInt()).isEqualTo(expectedScoreBps);
        assertThat(explanation.get("riskLevel").asText()).isEqualTo(expectedLevel);
        assertThat(explanation.at("/precedence/winningControl").asText()).isEqualTo(expectedWinner);

        // Basis points must be an exact conversion of the persisted decimal, not a rounded percent.
        BigDecimal storedScore = jdbcTemplate.queryForObject("""
                SELECT risk_score FROM fraud_decisions
                WHERE id = ?::uuid AND merchant_id = ?
                """, BigDecimal.class,
                detail(payment.paymentIntentId()).at("/identity/latestFraudDecisionId").asText(),
                merchant.id());
        assertThat(explanation.get("riskScoreBps").asInt())
                .isEqualTo(storedScore.multiply(BigDecimal.valueOf(10_000)).intValueExact());

        // Model output is absent in Phase 6. Rendering it as score zero would let an analyst
        // read "the model saw nothing risky" from a model that was never consulted.
        assertThat(explanation.get("modelState").asText()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void losingAndUnmatchedControlsStayVisibleSoTheAnalystSeesWhatDidNotFireToo() throws Exception {
        // 6_000_000 USD matches BOTH the extreme-amount BLOCK and the elevated-amount 3DS rule.
        // Only BLOCK wins, so this payment is the partition where a weaker matched control
        // could silently disappear from the evidence.
        Payment blocked = scenario.blockedPayment(merchant, "explain-losing");

        JsonNode explanation = detail(blocked.paymentIntentId()).get("decisionExplanation");
        JsonNode controls = explanation.get("controlEvaluations");
        List<String> renderedSystemCodes = controls.valueStream()
                .filter(control -> "SYSTEM_RULE".equals(control.get("family").asText()))
                .map(control -> control.get("code").asText()).sorted().toList();
        assertThat(renderedSystemCodes).containsExactlyElementsOf(
                EVERY_SYSTEM_CONTROL.stream().sorted().toList());

        JsonNode winner = controlNamed(controls, "BLOCK_USD_EXTREME_AMOUNT_V1");
        JsonNode loser = controlNamed(controls, "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1");
        JsonNode unmatched = controlNamed(controls, "REVIEW_REPEATED_ATTEMPT_V1");
        assertThat(winner.get("matched").asBoolean()).isTrue();
        assertThat(winner.get("contributedToWinningAction").asBoolean()).isTrue();
        assertThat(loser.get("matched").asBoolean())
                .as("the elevated-amount control matched but lost on precedence").isTrue();
        assertThat(loser.get("contributedToWinningAction").asBoolean()).isFalse();
        assertThat(unmatched.get("matched").asBoolean()).isFalse();
        assertThat(unmatched.get("outcome").asText()).isEqualTo("NO_MATCH");

        // Exactly one control may claim the final action; two would make the story unfalsifiable.
        assertThat(controls.valueStream()
                .filter(control -> control.get("contributedToWinningAction").asBoolean()).count())
                .isOne();
        assertThat(explanation.at("/precedence/explanation").asText())
                .contains("strongest action wins");
    }

    @Test
    void controlEvidenceMirrorsThePersistedDecisionLineageRatherThanTodaysRuleCatalogue()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "explain-lineage");
        JsonNode detail = detail(reviewed.paymentIntentId());
        String decisionId = detail.at("/identity/latestFraudDecisionId").asText();

        List<java.util.Map<String, Object>> persisted = jdbcTemplate.queryForList("""
                SELECT rule_code, rule_version, priority, matched, action, reason_code,
                       evaluation_outcome
                FROM rule_execution_results
                WHERE fraud_decision_id = ?::uuid AND merchant_id = ?
                ORDER BY rule_code
                """, decisionId, merchant.id());
        assertThat(persisted).hasSize(EVERY_SYSTEM_CONTROL.size());

        JsonNode controls = detail.at("/decisionExplanation/controlEvaluations");
        for (java.util.Map<String, Object> row : persisted) {
            JsonNode rendered = controlNamed(controls, row.get("rule_code").toString());
            assertThat(rendered.get("version").asText()).isEqualTo(row.get("rule_version").toString());
            assertThat(rendered.get("priority").asInt())
                    .isEqualTo(((Number) row.get("priority")).intValue());
            assertThat(rendered.get("matched").asBoolean())
                    .isEqualTo(Boolean.parseBoolean(row.get("matched").toString()));
            assertThat(rendered.get("proposedAction").asText()).isEqualTo(row.get("action").toString());
            assertThat(rendered.get("reason").asText()).isEqualTo(row.get("reason_code").toString());
            assertThat(rendered.get("outcome").asText())
                    .isEqualTo(row.get("evaluation_outcome").toString());
            // Availability must be an explicit closed value: an unavailable input rendered as
            // NO_MATCH would look like "the control was evaluated and cleared the payment".
            assertThat(rendered.get("availability").asText()).isEqualTo("AVAILABLE");
        }

        // Feature lineage is decision-time truth with a declared freshness verdict.
        JsonNode explanation = detail.get("decisionExplanation");
        assertThat(explanation.get("featureCatalogueVersion").asText()).isNotBlank();
        assertThat(explanation.get("featureFreshness").asText()).isNotBlank();
        assertThat(explanation.get("featureValues")).isNotEmpty();
        explanation.get("featureValues").forEach(feature -> {
            assertThat(feature.get("code").asText()).isNotBlank();
            assertThat(feature.get("version").asText()).isNotBlank();
            assertThat(feature.get("valueType").asText()).isNotBlank();
        });
        assertThat(explanation.get("decisionCompletedAt").asText()).isNotBlank();
        assertThat(explanation.get("latencyMs").asLong()).isNotNegative();
    }

    private JsonNode controlNamed(JsonNode controls, String code) {
        return controls.valueStream()
                .filter(control -> code.equals(control.get("code").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("control " + code + " is absent from the "
                        + "explanation; a losing or unmatched control must still be visible"));
    }

    private JsonNode detail(UUID paymentIntentId) throws Exception {
        return scenario.json(get("/risk/workspace/payments/{id}", paymentIntentId)
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
    }
}
