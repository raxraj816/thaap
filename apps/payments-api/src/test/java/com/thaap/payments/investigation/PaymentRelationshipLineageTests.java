package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Proves C54-LIN-001. Every edge in the relationship graph must be traceable to the exact source
 * record it was derived from. Without that lineage a fraud ring is an unfalsifiable assertion: an
 * analyst could not defend it in a chargeback dispute, and the platform could not re-key or
 * retract it when a source record is corrected.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentRelationshipLineageTests {

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
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Payment payment;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("relationship-lineage");
        payment = scenario.allowedPayment(merchant, "lineage-seed");
    }

    @Test
    void everyCustomerAnchorDigestMatchesTheCustomerRecordItNames() {
        List<Map<String, Object>> anchors = anchorsFor(payment.paymentIntentId(), "CUSTOMER");
        assertThat(anchors).as("the seed must carry customer anchors").isNotEmpty();

        for (Map<String, Object> anchor : anchors) {
            Map<String, Object> customer = jdbcTemplate.queryForMap(
                    "SELECT id, email_hash, phone_hash FROM customers WHERE merchant_id = ? AND id = ?",
                    merchant.id(), anchor.get("source_id"));
            String expected = "SAME_EMAIL".equals(anchor.get("relation_type"))
                    ? String.valueOf(customer.get("email_hash"))
                    : String.valueOf(customer.get("phone_hash"));
            assertThat(anchor.get("relation_identity_hmac")).isEqualTo(expected);
            assertThat(customer.get("id")).isEqualTo(payment.customerId());
        }
    }

    @Test
    void theCardAnchorDigestMatchesThePaymentMethodRecordItNames() {
        List<Map<String, Object>> anchors = anchorsFor(payment.paymentIntentId(), "PAYMENT_METHOD");
        assertThat(anchors).hasSize(1);

        Map<String, Object> anchor = anchors.getFirst();
        assertThat(anchor.get("relation_type")).isEqualTo("SAME_CARD_FINGERPRINT");
        String fingerprint = jdbcTemplate.queryForObject(
                "SELECT card_fingerprint FROM payment_methods WHERE merchant_id = ? AND id = ?",
                String.class, merchant.id(), anchor.get("source_id"));
        assertThat(anchor.get("relation_identity_hmac")).isEqualTo(fingerprint);
        assertThat(anchor.get("source_id")).isEqualTo(payment.paymentMethodId());
    }

    @Test
    void anchorsFollowTheAttemptThatObservedTheIdentityRatherThanTheIntent() throws Exception {
        RiskWorkspaceScenario.Payment unconfirmed =
                scenario.unconfirmedPayment(merchant, "lineage-unconfirmed");

        // An intent that was never confirmed observed no identity, so relating it to anything
        // would assert a fact the platform never witnessed.
        assertThat(anchorsFor(unconfirmed.paymentIntentId(), null)).isEmpty();
        assertThat(anchorsFor(payment.paymentIntentId(), null)).isNotEmpty();

        for (Map<String, Object> anchor : anchorsFor(payment.paymentIntentId(), null)) {
            Map<String, Object> attempt = jdbcTemplate.queryForMap("""
                    SELECT payment_intent_id, traffic_origin, created_at FROM payment_attempts
                    WHERE merchant_id = ? AND id = ?
                    """, merchant.id(), anchor.get("payment_attempt_id"));
            assertThat(attempt.get("payment_intent_id")).isEqualTo(payment.paymentIntentId());
            assertThat(anchor.get("traffic_origin")).isEqualTo(attempt.get("traffic_origin"));
        }
    }

    @Test
    void repeatedAttemptsOnOneIdentityDoNotMultiplyTheAnchorsThatDescribeIt() throws Exception {
        RiskWorkspaceScenario.Payment repeated = scenario.reviewedPayment(merchant,
                "lineage-repeat");

        List<Map<String, Object>> anchors = anchorsFor(repeated.paymentIntentId(), null);
        assertThat(anchors).isNotEmpty();

        // An anchor is an observation, so each attempt legitimately records its own row. What must
        // never happen is the same attempt claiming the same identity twice, which would let a
        // retry inflate the evidence behind a fraud ring.
        List<String> perAttempt = anchors.stream()
                .map(anchor -> anchor.get("payment_attempt_id") + "|" + anchor.get("relation_type")
                        + "|" + anchor.get("relation_identity_hmac") + "|"
                        + anchor.get("identity_key_version"))
                .toList();
        assertThat(perAttempt).doesNotHaveDuplicates();

        long distinctAttempts = anchors.stream()
                .map(anchor -> anchor.get("payment_attempt_id")).distinct().count();
        assertThat(distinctAttempts).as("the fixture must retry so duplication is possible")
                .isGreaterThan(1);
    }

    @Test
    void aSiblingSeenAcrossManyAttemptsIsStillReportedAsOneRelatedPayment() throws Exception {
        RiskWorkspaceScenario.Contact ring =
                new RiskWorkspaceScenario.Contact("repeat.ring@example.com", "+14155554141");
        RiskWorkspaceScenario.Payment seed = scenario.payment(merchant, "lineage-ring-seed",
                RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR, "USD", 1, ring,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        RiskWorkspaceScenario.Payment sibling = scenario.payment(merchant, "lineage-ring-sibling",
                RiskWorkspaceScenario.BELOW_ELEVATED_AMOUNT_MINOR, "USD", 3, ring,
                RiskWorkspaceScenario.DEFAULT_CARD_NUMBER);
        scenario.drainProjection();

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        RiskWorkspaceScenario.Operator reader = scenario.operator(merchant, "lineage-ring-key",
                "RISK_MANAGER");
        com.fasterxml.jackson.databind.JsonNode page = scenario.json(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/risk/workspace/payments/" + seed.paymentIntentId() + "/related")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, reader.credential()),
                200);

        List<String> returned = new java.util.ArrayList<>();
        page.get("data").forEach(row -> returned.add(row.get("paymentIntentId").asText()));
        assertThat(returned).contains(sibling.paymentIntentId().toString());
        // Three attempts produced three anchor rows per identity type. The analyst must still see
        // one payment with one reason list, not the same payment three times.
        assertThat(returned).doesNotHaveDuplicates();
        page.get("data").forEach(row -> {
            List<String> reasons = new java.util.ArrayList<>();
            row.get("reasons").forEach(reason -> reasons.add(reason.asText()));
            assertThat(reasons).doesNotHaveDuplicates();
            assertThat(row.get("matchedReasonCount").asInt()).isEqualTo(reasons.size());
        });
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void everyAnchorRecordsWhenItWasObservedBeforeWhenItWasWritten() {
        List<Map<String, Object>> anchors = anchorsFor(payment.paymentIntentId(), null);
        assertThat(anchors).isNotEmpty();

        for (Map<String, Object> anchor : anchors) {
            Instant observed = instant(anchor.get("observed_at"));
            Instant created = instant(anchor.get("created_at"));
            // Observation time is the fact; write time is the bookkeeping. Confusing them would
            // let a backfill claim the platform saw an identity earlier than it did.
            assertThat(observed).isBeforeOrEqualTo(created);
            assertThat(String.valueOf(anchor.get("identity_key_version"))).isNotBlank();
        }
    }

    @Test
    void noAnchorAnywhereNamesASourceThatDoesNotExist() {
        Long orphanedCustomers = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors anchors
                WHERE anchors.source_type = 'CUSTOMER' AND NOT EXISTS (
                    SELECT 1 FROM customers c
                    WHERE c.id = anchors.source_id AND c.merchant_id = anchors.merchant_id)
                """, Long.class);
        Long orphanedMethods = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors anchors
                WHERE anchors.source_type = 'PAYMENT_METHOD' AND NOT EXISTS (
                    SELECT 1 FROM payment_methods m
                    WHERE m.id = anchors.source_id AND m.merchant_id = anchors.merchant_id)
                """, Long.class);
        Long orphanedAttempts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors anchors
                WHERE NOT EXISTS (
                    SELECT 1 FROM payment_attempts a
                    WHERE a.id = anchors.payment_attempt_id
                      AND a.merchant_id = anchors.merchant_id
                      AND a.payment_intent_id = anchors.payment_intent_id)
                """, Long.class);

        assertThat(orphanedCustomers).isZero();
        assertThat(orphanedMethods).isZero();
        assertThat(orphanedAttempts).isZero();
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private List<Map<String, Object>> anchorsFor(UUID paymentIntentId, String sourceType) {
        String sql = """
                SELECT relation_type, relation_identity_hmac, identity_key_version, observed_at,
                       created_at, traffic_origin, source_type, source_id, payment_attempt_id
                FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                """;
        if (sourceType == null) {
            return jdbcTemplate.queryForList(sql, merchant.id(), paymentIntentId);
        }
        return jdbcTemplate.queryForList(sql + " AND source_type = ?", merchant.id(),
                paymentIntentId, sourceType);
    }
}
