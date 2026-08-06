package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;

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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * Proves the non-impact half of C53-PAY-001: opening an investigation composes existing truth
 * and never writes it. A read that appends an event, burns an idempotency key, touches
 * updated_at, or writes an audit row would silently make the workspace a mutation surface.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationSourceReconciliationTests {

    /**
     * Every store a detail read touches or could plausibly touch. Digesting only the payment
     * tables would miss the real risk: a read that appends an event or an audit row.
     */
    private static final List<String> AUTHORITATIVE_TABLES = List.of(
            "payment_intents", "payment_attempts", "fraud_decisions", "feature_snapshots",
            "feature_values", "rule_execution_results", "risk_control_evaluations",
            "manual_review_cases", "review_assignment_events", "review_resolutions",
            "fraud_labels", "authorizations", "refunds", "chargebacks", "ledger_entries",
            "webhook_deliveries", "webhook_delivery_attempts", "domain_events",
            "event_stream_heads", "idempotency_records", "audit_logs",
            "payment_investigation_relation_anchors");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private RiskWorkspaceScenario scenario;

    @BeforeEach
    void setUp() {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
    }

    @Test
    void readingDetailSearchTimelineAndRelatedLeavesEveryAuthoritativeRowByteIdentical()
            throws Exception {
        Merchant merchant = scenario.merchant("investigation-nonimpact-owner");
        Operator manager = scenario.operator(merchant, "nonimpact-manager", "RISK_MANAGER");
        Payment reviewed = scenario.reviewedPayment(merchant, "nonimpact-reviewed");
        scenario.allowedPayment(merchant, "nonimpact-allowed");
        scenario.blockedPayment(merchant, "nonimpact-blocked");
        scenario.drainProjection();

        Map<String, List<Map<String, Object>>> before = fingerprint();

        scenario.json(get("/risk/workspace/payments/{id}", reviewed.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        scenario.json(get("/risk/workspace/payments/{id}/timeline", reviewed.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        scenario.json(get("/risk/workspace/payments/{id}/related", reviewed.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        scenario.json(get("/risk/workspace/payments/{id}/webhook-activity",
                reviewed.paymentIntentId()).header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential())
                .contentType(APPLICATION_JSON)
                .content("{\"sort\":\"CREATED_AT_DESC\"}"), 200);
        // Read the same payment twice: an accidental write is often idempotent-looking on the
        // first pass and only visible when the second read finds state the first one created.
        scenario.json(get("/risk/workspace/payments/{id}", reviewed.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        Map<String, List<Map<String, Object>>> after = fingerprint();
        AUTHORITATIVE_TABLES.forEach(table -> assertThat(after.get(table))
                .as("investigation reads mutated %s", table)
                .isEqualTo(before.get(table)));
    }

    @Test
    void aDeniedReadLeavesNoTraceInPaymentFraudOrEventTruth() throws Exception {
        Merchant owner = scenario.merchant("investigation-denied-owner");
        Merchant intruder = scenario.merchant("investigation-denied-intruder");
        Operator intruderManager = scenario.operator(intruder, "denied-manager", "RISK_MANAGER");
        Payment owned = scenario.reviewedPayment(owner, "denied-target");
        scenario.drainProjection();

        Map<String, List<Map<String, Object>>> before = fingerprint();

        scenario.json(get("/risk/workspace/payments/{id}", owned.paymentIntentId())
                .header(RISK_OPERATOR_HEADER, intruderManager.credential()), 404);

        Map<String, List<Map<String, Object>>> after = fingerprint();
        AUTHORITATIVE_TABLES.forEach(table -> assertThat(after.get(table))
                .as("denied investigation read mutated %s", table)
                .isEqualTo(before.get(table)));
    }

    @Test
    void detailIdentityLinksResolveToRowsThatActuallyBelongToTheInvestigatedPayment()
            throws Exception {
        Merchant merchant = scenario.merchant("investigation-linkage-owner");
        Operator manager = scenario.operator(merchant, "linkage-manager", "RISK_MANAGER");
        Payment reviewed = scenario.reviewedPayment(merchant, "linkage-reviewed");
        // A second payment on the same merchant makes an unqualified join observable: a
        // "latest decision" query without the payment predicate would drift to this row.
        scenario.reviewedPayment(merchant, "linkage-decoy");
        scenario.drainProjection();

        JsonNode detail = scenario.json(get("/risk/workspace/payments/{id}",
                reviewed.paymentIntentId()).header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        String attemptId = detail.at("/identity/latestPaymentAttemptId").asText();
        String decisionId = detail.at("/identity/latestFraudDecisionId").asText();
        String reviewCaseId = detail.at("/identity/latestReviewCaseId").asText();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts
                WHERE id = ?::uuid AND payment_intent_id = ? AND merchant_id = ?
                """, Integer.class, attemptId, reviewed.paymentIntentId(), merchant.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_decisions
                WHERE id = ?::uuid AND payment_attempt_id = ?::uuid AND merchant_id = ?
                """, Integer.class, decisionId, attemptId, merchant.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM manual_review_cases
                WHERE id = ?::uuid AND payment_intent_id = ? AND merchant_id = ?
                """, Integer.class, reviewCaseId, reviewed.paymentIntentId(), merchant.id())).isOne();

        // Every rendered control must be lineage of this decision, not today's rule catalogue.
        List<String> renderedSystemControls = detail.at("/decisionExplanation/controlEvaluations")
                .findValues("family").isEmpty() ? List.of()
                : detail.at("/decisionExplanation/controlEvaluations").valueStream()
                        .filter(control -> "SYSTEM_RULE".equals(control.get("family").asText()))
                        .map(control -> control.get("code").asText()).sorted().toList();
        assertThat(jdbcTemplate.queryForList("""
                SELECT rule_code FROM rule_execution_results
                WHERE fraud_decision_id = ?::uuid AND merchant_id = ?
                ORDER BY rule_code
                """, String.class, decisionId, merchant.id()))
                .isEqualTo(renderedSystemControls);
    }

    private Map<String, List<Map<String, Object>>> fingerprint() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : AUTHORITATIVE_TABLES) {
            rows.put(table, jdbcTemplate.queryForList("SELECT * FROM " + table).stream()
                    .map(this::comparableRow)
                    .sorted(Comparator.comparing(Object::toString)).toList());
        }
        return rows;
    }

    /** Column order and byte-array identity vary per read, so compare values, not references. */
    private Map<String, Object> comparableRow(Map<String, Object> row) {
        Map<String, Object> normalized = new TreeMap<>();
        row.forEach((column, value) -> normalized.put(column, switch (value) {
            case null -> null;
            case byte[] bytes -> HexFormat.of().formatHex(bytes);
            default -> value.toString();
        }));
        return normalized;
    }
}
