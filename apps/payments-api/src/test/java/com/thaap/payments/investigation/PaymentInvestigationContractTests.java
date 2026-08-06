package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Proves C53-PAY-001: one authorized investigation detail reconciles current payment state,
 * every attempt, decision lineage, review, money, and webhook truth against the source rows,
 * and declares an explicit state for every section instead of silently omitting one.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationContractTests {

    private static final List<String> REQUIRED_SECTIONS = List.of("paymentLifecycle",
            "decisionExplanation", "reviewAndLabels", "moneyAndDisputes", "webhookSummary",
            "analyticsContext");

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
        merchant = scenario.merchant("investigation-detail-owner");
        manager = scenario.operator(merchant, "investigation-manager", "RISK_MANAGER");
    }

    @Test
    void everyAttemptCountPartitionReconcilesIdentityAndAttemptsAgainstSourceRows() throws Exception {
        Map<String, Payment> partitions = Map.of(
                "zero-attempt", scenario.unconfirmedPayment(merchant, "detail-zero"),
                "one-attempt", scenario.allowedPayment(merchant, "detail-one"),
                "three-attempt", scenario.reviewedPayment(merchant, "detail-many"));

        for (Map.Entry<String, Payment> partition : partitions.entrySet()) {
            UUID paymentIntentId = partition.getValue().paymentIntentId();
            JsonNode detail = detail(paymentIntentId);

            List<Map<String, Object>> sourceAttempts = jdbcTemplate.queryForList("""
                    SELECT id, attempt_number, status, fraud_decision_id
                    FROM payment_attempts
                    WHERE merchant_id = ? AND payment_intent_id = ?
                    ORDER BY attempt_number ASC
                    """, merchant.id(), paymentIntentId);
            JsonNode attempts = detail.at("/paymentLifecycle/attempts");
            assertThat(attempts).as("%s attempt count", partition.getKey())
                    .hasSize(sourceAttempts.size());
            for (int index = 0; index < sourceAttempts.size(); index++) {
                Map<String, Object> source = sourceAttempts.get(index);
                JsonNode view = attempts.get(index);
                assertThat(view.get("id").asText()).isEqualTo(source.get("id").toString());
                assertThat(view.get("attemptNumber").asInt())
                        .isEqualTo(((Number) source.get("attempt_number")).intValue());
                assertThat(view.get("status").asText()).isEqualTo(source.get("status").toString());
            }

            // Identity must name the latest attempt, not the first one loaded or a stale link.
            JsonNode identity = detail.get("identity");
            if (sourceAttempts.isEmpty()) {
                assertThat(identity.get("latestPaymentAttemptId").isNull()).isTrue();
                assertThat(identity.get("latestFraudDecisionId").isNull()).isTrue();
                assertThat(detail.get("decisionExplanation").isNull()).isTrue();
                assertThat(detail.at("/sectionStatuses/decisionExplanation/state").asText())
                        .isEqualTo("NOT_APPLICABLE");
            } else {
                Map<String, Object> latest = sourceAttempts.getLast();
                assertThat(identity.get("latestPaymentAttemptId").asText())
                        .isEqualTo(latest.get("id").toString());
                assertThat(identity.get("latestFraudDecisionId").asText())
                        .isEqualTo(latest.get("fraud_decision_id").toString());
            }

            // Current payment state comes from the source row, never from the projection.
            Map<String, Object> sourcePayment = jdbcTemplate.queryForMap("""
                    SELECT status, amount, currency FROM payment_intents WHERE id = ?
                    """, paymentIntentId);
            assertThat(detail.at("/paymentLifecycle/status").asText())
                    .isEqualTo(sourcePayment.get("status").toString());
            assertThat(detail.at("/paymentLifecycle/amountMinor").asLong())
                    .isEqualTo(((Number) sourcePayment.get("amount")).longValue());
            assertThat(detail.at("/sourceSnapshot/authority").asText()).isEqualTo("SOURCE_DATABASE");
        }
    }

    @Test
    void everySectionDeclaresAnExplicitStateSoAMissingSectionCannotLookLikeAbsentTruth()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "detail-sections");
        scenario.drainProjection();

        JsonNode detail = detail(reviewed.paymentIntentId());
        JsonNode statuses = detail.get("sectionStatuses");
        assertThat(statuses.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(REQUIRED_SECTIONS);
        statuses.properties().forEach(section -> {
            assertThat(section.getValue().get("authority").asText())
                    .as("%s authority", section.getKey()).isNotBlank();
            assertThat(InvestigationSectionState.valueOf(
                    section.getValue().get("state").asText())).isNotNull();
        });

        // An uncaptured payment with no webhook delivery must read EMPTY, not AVAILABLE-with-zeroes
        // and not a silently dropped section: EMPTY is the reviewable claim "we looked, there is none".
        assertThat(statuses.at("/moneyAndDisputes/state").asText()).isEqualTo("EMPTY");
        assertThat(detail.at("/moneyAndDisputes/capturedAmountMinor").asLong()).isZero();
        assertThat(detail.at("/moneyAndDisputes/ledgerBalanced").asBoolean()).isTrue();
        assertThat(statuses.at("/reviewAndLabels/state").asText()).isEqualTo("AVAILABLE");
        assertThat(detail.at("/reviewAndLabels/reviews")).hasSize(1);
        assertThat(detail.at("/reviewAndLabels/labels")).isEmpty();

        // Related payments stay a link with a declared scope; the pivot route owns traversal.
        assertThat(detail.at("/relatedLinksSummary/scope").asText())
                .isEqualTo("SERVER_DERIVED_SAME_MERCHANT_ONLY");
        assertThat(detail.at("/relatedLinksSummary/route").asText())
                .isEqualTo("/risk/workspace/payments/" + reviewed.paymentIntentId() + "/related");
    }

    @Test
    void repeatedReadsReturnTheSameEvidenceSoDetailCannotRecomputeOrMutateSourceTruth()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "detail-repeat");
        scenario.drainProjection();

        ObjectNode first = (ObjectNode) detail(reviewed.paymentIntentId());
        ObjectNode second = (ObjectNode) detail(reviewed.paymentIntentId());

        long firstVersion = first.at("/sourceSnapshot/maximumPaymentStreamVersion").asLong();
        assertThat(firstVersion).isPositive();
        assertThat(second.at("/sourceSnapshot/maximumPaymentStreamVersion").asLong())
                .isEqualTo(firstVersion);

        // sourceAsOf is the only field allowed to advance: it reports when the snapshot was taken.
        ((ObjectNode) first.get("sourceSnapshot")).remove("sourceAsOf");
        ((ObjectNode) second.get("sourceSnapshot")).remove("sourceAsOf");
        assertThat(second).isEqualTo(first);
    }

    private JsonNode detail(UUID paymentIntentId) throws Exception {
        return scenario.json(get("/risk/workspace/payments/{id}", paymentIntentId)
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
    }
}
