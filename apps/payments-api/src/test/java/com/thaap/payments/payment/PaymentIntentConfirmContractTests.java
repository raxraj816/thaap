package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.fraud.FeatureValue;
import com.thaap.payments.fraud.FeatureValueRepository;
import com.thaap.payments.support.DatabaseCleaner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentConfirmContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String SCENARIO_RUNNER_TOKEN = "X-Thaap-Scenario-Runner-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";
    private static final String TEST_SCENARIO_RUNNER_TOKEN = "test-scenario-runner-token";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private FeatureValueRepository featureValueRepository;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void confirmWithApprovedCardCreatesDurableAuthorizationAndMovesPaymentIntentToRequiresCapture()
            throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-a", "4999990000000001");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-a", 1200);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");

        var response = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-attempt-a");
        var paymentAttemptId = UUID.fromString(response.get("id").asText());

        assertThat(response.get("merchantId").asText()).isEqualTo(merchant.id().toString());
        assertThat(response.get("paymentIntentId").asText()).isEqualTo(paymentIntentId.toString());
        assertThat(response.get("paymentMethodId").asText()).isEqualTo(paymentMethodId.toString());
        assertThat(response.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(response.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(response.get("fraudDecisionId").isNull()).isFalse();
        assertThat(response.get("processorAuthorizationId").asText()).startsWith("auth_sim_");
        assertThat(response.get("threeDsStatus").isNull()).isTrue();
        var fraudDecisionId = UUID.fromString(response.get("fraudDecisionId").asText());

        var stored = jdbcTemplate.queryForMap("""
                SELECT merchant_id, payment_intent_id, payment_method_id, idempotency_key, status, attempt_number,
                       fraud_decision_id, processor_authorization_id, three_ds_status, created_at, updated_at
                FROM payment_attempts
                WHERE id = ?
                """, paymentAttemptId);
        assertThat(stored.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(stored.get("PAYMENT_INTENT_ID")).isEqualTo(paymentIntentId);
        assertThat(stored.get("PAYMENT_METHOD_ID")).isEqualTo(paymentMethodId);
        assertThat(stored.get("IDEMPOTENCY_KEY")).isEqualTo("confirm-attempt-a");
        assertThat(stored.get("STATUS")).isEqualTo("AUTHORIZED");
        assertThat(stored.get("ATTEMPT_NUMBER")).isEqualTo(1);
        assertThat(stored.get("FRAUD_DECISION_ID")).isEqualTo(fraudDecisionId);
        assertThat(stored.get("PROCESSOR_AUTHORIZATION_ID")).isEqualTo(response.get("processorAuthorizationId").asText());
        assertThat(stored.get("THREE_DS_STATUS")).isNull();
        assertThat(stored.get("CREATED_AT")).isNotNull();
        assertThat(stored.get("UPDATED_AT")).isNotNull();

        var fraudDecision = fraudDecisionById(fraudDecisionId);
        assertThat(fraudDecision.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(fraudDecision.get("PAYMENT_ATTEMPT_ID")).isEqualTo(paymentAttemptId);
        assertThat(fraudDecision.get("RISK_SCORE").toString()).isEqualTo("0.0000");
        assertThat(fraudDecision.get("RISK_LEVEL")).isEqualTo("LOW");
        assertThat(fraudDecision.get("ACTION")).isEqualTo("ALLOW");
        assertThat(fraudDecision.get("MODEL_VERSION")).isNull();
        assertThat(fraudDecision.get("RULESET_VERSION")).isEqualTo("rules_stub_v1");
        assertThat((Long) fraudDecision.get("LATENCY_MS")).isGreaterThanOrEqualTo(0L);
        assertThat(fraudDecision.get("DECISION_STARTED_AT")).isNotNull();
        assertThat(fraudDecision.get("DECISION_COMPLETED_AT")).isNotNull();
        assertThat(fraudDecision.get("FEATURE_FRESHNESS_STATUS")).isEqualTo("FRESH");
        assertThat((Long) fraudDecision.get("MAX_FEATURE_AGE_MS")).isGreaterThanOrEqualTo(0L);
        assertThat(fraudDecision.get("REASON_CODES")).isEqualTo("[\"RULES_NOT_ENABLED\"]");
        assertThat(fraudDecision.get("CREATED_AT")).isNotNull();

        var featureSnapshot = featureSnapshotForDecision(fraudDecisionId);
        var featuresJson = featureSnapshot.get("FEATURES_JSON").toString();
        assertThat(featureSnapshot.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(featureSnapshot.get("PAYMENT_ATTEMPT_ID")).isEqualTo(paymentAttemptId);
        assertThat(featureSnapshot.get("FEATURE_VERSION")).isEqualTo("features_v1");
        assertThat(featuresJson).contains("\"payment.amount_minor\":1200");
        assertThat(featuresJson).contains("\"payment.currency\":\"USD\"");
        assertThat(featuresJson).contains("\"card.country\":\"US\"");
        assertThat(featuresJson).doesNotContain("499999");
        assertThat(featuresJson).doesNotContain("4999990000000001");
        assertThat(featuresJson.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(PaymentIntentController.DEFAULT_MAX_FEATURE_SNAPSHOT_JSON_BYTES);
        assertThat(featureSnapshot.get("FRESHNESS_STATUS")).isEqualTo("FRESH");
        assertThat(featureSnapshot.get("STALE_FEATURE_NAMES")).isEqualTo("[]");
        assertThat(featureSnapshot.get("MISSING_FEATURE_NAMES")).isEqualTo("[]");

        List<FeatureValue> featureValues = featureValueRepository.findPointInTimeForDecision(
                merchant.id(), fraudDecisionId);
        assertThat(featureValues).extracting(FeatureValue::getFeatureCode).containsExactly(
                "card.country",
                "card.funding",
                "payment.amount_minor",
                "payment.attempt_number",
                "payment.capture_method",
                "payment.currency",
                "traffic.origin");
        assertThat(featureValues).allSatisfy(featureValue -> {
            assertThat(featureValue.getMerchantId()).isEqualTo(merchant.id());
            assertThat(featureValue.getPaymentAttemptId()).isEqualTo(paymentAttemptId);
            assertThat(featureValue.getFeatureVersion()).isEqualTo("v1");
            assertThat(featureValue.getTrafficOrigin().name()).isEqualTo("MERCHANT_API");
            assertThat(featureValue.getObservedAt()).isBeforeOrEqualTo(
                    ((java.time.OffsetDateTime) fraudDecision.get("DECISION_STARTED_AT")).toInstant());
            assertThat(featureValue.getObservedAt()).isBeforeOrEqualTo(featureValue.getComputedAt());
        });
        assertThat(featureValues).extracting(FeatureValue::typedValue).containsExactly(
                "US", "CREDIT", 1200L, 1L, "MANUAL", "USD", "MERCHANT_API");

        var authorization = authorizationForAttempt(paymentAttemptId);
        assertThat(authorization.get("MERCHANT_ID")).isEqualTo(merchant.id());
        assertThat(authorization.get("PAYMENT_ATTEMPT_ID")).isEqualTo(paymentAttemptId);
        assertThat(authorization.get("PROCESSOR")).isEqualTo("THAAP_SIMULATOR");
        assertThat(authorization.get("PROCESSOR_REFERENCE_ID")).isEqualTo(response.get("processorAuthorizationId").asText());
        assertThat(authorization.get("PROCESSOR_RESPONSE_CODE")).isEqualTo("APPROVED");
        assertThat(authorization.get("NETWORK_STATUS")).isEqualTo("APPROVED");
        assertThat(authorization.get("REQUESTED_AMOUNT")).isEqualTo(1200L);
        assertThat(authorization.get("AUTHORIZED_AMOUNT")).isEqualTo(1200L);
        assertThat(authorization.get("CURRENCY")).isEqualTo("USD");
        assertThat(authorization.get("STATUS")).isEqualTo("APPROVED");
        assertThat(authorization.get("CVC_CHECK")).isEqualTo("PASS");
        assertThat(authorization.get("AVS_LINE1_CHECK")).isEqualTo("PASS");
        assertThat(authorization.get("AVS_POSTAL_CODE_CHECK")).isEqualTo("PASS");
        assertThat(authorization.get("THREE_DS_RESULT")).isEqualTo("NOT_REQUIRED");
        assertThat(authorization.get("SIMULATOR_OUTCOME_CODE")).isEqualTo("APPROVED");
        assertThat(authorization.get("CREATED_AT")).isNotNull();

        var fetched = getPaymentAttempt(merchant, paymentAttemptId);
        assertThat(fetched).isEqualTo(response);
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("feature_values")).isEqualTo(7);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(4);
        assertThat(countAuditLogsByAction("payment_intent.confirmed")).isEqualTo(1);
        assertThat(countAuditLogsByAction("fraud_decision.completed")).isEqualTo(1);
        assertThat(countAuditLogsByAction("authorization.approved")).isEqualTo(1);
        List<AuditLogRow> auditRows = auditRowsForRequest("req-confirm-flow");
        assertThat(auditRows).hasSize(3);
        assertThat(auditRows).extracting(AuditLogRow::actorId).containsOnly(merchant.apiKeyPrefix());
        assertThat(auditRows).extracting(AuditLogRow::action)
                .containsExactly("payment_intent.confirmed", "fraud_decision.completed", "authorization.approved");
        AuditLogRow paymentIntentAudit = auditRow(auditRows, "payment_intent.confirmed");
        assertThat(paymentIntentAudit.aggregateType()).isEqualTo("payment_intent");
        assertThat(paymentIntentAudit.beforeJson().get("status").asText()).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(paymentIntentAudit.afterJson().get("paymentIntentId").asText()).isEqualTo(paymentIntentId.toString());
        assertThat(paymentIntentAudit.afterJson().get("paymentAttemptId").asText())
                .isEqualTo(paymentAttemptId.toString());
        assertThat(paymentIntentAudit.afterJson().get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        assertThat(paymentIntentAudit.afterJson().get("paymentAttemptStatus").asText()).isEqualTo("AUTHORIZED");

        AuditLogRow fraudDecisionAudit = auditRow(auditRows, "fraud_decision.completed");
        assertThat(fraudDecisionAudit.aggregateType()).isEqualTo("fraud_decision");
        assertThat(fraudDecisionAudit.beforeJson()).isNull();
        assertThat(fraudDecisionAudit.afterJson().get("fraudDecisionId").asText()).isEqualTo(fraudDecisionId.toString());
        assertThat(fraudDecisionAudit.afterJson().get("action").asText()).isEqualTo("ALLOW");
        assertThat(fraudDecisionAudit.afterJson().get("reasonCodes").get(0).asText()).isEqualTo("RULES_NOT_ENABLED");

        AuditLogRow authorizationAudit = auditRow(auditRows, "authorization.approved");
        assertThat(authorizationAudit.aggregateType()).isEqualTo("authorization");
        assertThat(authorizationAudit.beforeJson()).isNull();
        assertThat(authorizationAudit.afterJson().get("status").asText()).isEqualTo("APPROVED");
        assertThat(authorizationAudit.afterJson().get("processorResponseCode").asText()).isEqualTo("APPROVED");
        assertThat(auditRows.toString())
                .doesNotContain("4999990000000001")
                .doesNotContain("buyer-a@example.com")
                .doesNotContain(merchant.apiKey())
                .doesNotContain("confirm-attempt-a");
        assertThat(countWebhookDeliveries("payment_attempt.created")).isEqualTo(1);
    }

    @Test
    void trustedSyntheticOriginPropagatesThroughDecisionTimeAndEventLineage()
            throws Exception {
        MerchantAccess merchant = createMerchant("synthetic-lineage");
        UUID customerId = createCustomer(merchant, "synthetic-lineage@example.com");
        UUID paymentMethodId = createPaymentMethod(
                merchant, customerId, "synthetic-lineage-pm", "4999990000000001");
        createWebhookEndpoint(merchant, "https://synthetic-lineage.example/webhooks");
        String responseBody = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(SCENARIO_RUNNER_TOKEN, TEST_SCENARIO_RUNNER_TOKEN)
                        .header(IDEMPOTENCY_KEY, "synthetic-lineage-intent")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": 1900,
                                  "currency": "USD",
                                  "captureMethod": "MANUAL"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID paymentIntentId = UUID.fromString(
                objectMapper.readTree(responseBody).get("id").asText());

        JsonNode attempt = confirmPaymentIntent(
                merchant, paymentIntentId, paymentMethodId,
                "synthetic-lineage-confirm");
        UUID paymentAttemptId = UUID.fromString(attempt.get("id").asText());
        UUID fraudDecisionId = UUID.fromString(
                attempt.get("fraudDecisionId").asText());

        assertThat(attempt.get("trafficOrigin").asText())
                .isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT traffic_origin
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId))
                .isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT traffic_origin
                FROM payment_attempts
                WHERE id = ?
                """, String.class, paymentAttemptId))
                .isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT traffic_origin
                FROM fraud_decisions
                WHERE id = ?
                """, String.class, fraudDecisionId))
                .isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT traffic_origin
                FROM feature_snapshots
                WHERE fraud_decision_id = ?
                """, String.class, fraudDecisionId))
                .isEqualTo("SYNTHETIC_SCENARIO");
        assertThat(jdbcTemplate.queryForList("""
                SELECT traffic_origin
                FROM webhook_events
                WHERE resource_id IN (?, ?)
                ORDER BY event_type
                """, String.class, paymentIntentId, paymentAttemptId))
                .containsOnly("SYNTHETIC_SCENARIO");
    }

    @Test
    void confirmWithUnknownSimulatorCardDefaultsToApprovedOutcome() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-unknown-default",
                "4242424242424242");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-unknown-default", 4200);

        var response = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-unknown-default");
        var paymentAttemptId = UUID.fromString(response.get("id").asText());

        assertThat(response.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        var authorization = authorizationForAttempt(paymentAttemptId);
        assertThat(authorization.get("PROCESSOR_RESPONSE_CODE")).isEqualTo("APPROVED");
        assertThat(authorization.get("REQUESTED_AMOUNT")).isEqualTo(4200L);
        assertThat(authorization.get("AUTHORIZED_AMOUNT")).isEqualTo(4200L);
        assertThat(authorization.get("THREE_DS_RESULT")).isEqualTo("NOT_REQUIRED");
    }

    @ParameterizedTest
    @MethodSource("declineCards")
    void confirmWithRetryableProcessorDeclineCreatesAuthorizationAndReopensPaymentIntent(String cardNumber,
            String responseCode, String cvcCheck, String avsLine1Check, String avsPostalCheck) throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-" + responseCode, cardNumber);
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-" + responseCode, 2200);

        var response = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-" + responseCode);
        var paymentAttemptId = UUID.fromString(response.get("id").asText());

        assertThat(response.get("status").asText()).isEqualTo("DECLINED");
        assertThat(response.get("processorAuthorizationId").asText()).startsWith("auth_sim_");
        assertThat(response.get("threeDsStatus").isNull()).isTrue();
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText())
                .isEqualTo("REQUIRES_PAYMENT_METHOD");

        var authorization = authorizationForAttempt(paymentAttemptId);
        assertThat(authorization.get("PROCESSOR_RESPONSE_CODE")).isEqualTo(responseCode);
        assertThat(authorization.get("NETWORK_STATUS")).isEqualTo("DECLINED");
        assertThat(authorization.get("REQUESTED_AMOUNT")).isEqualTo(2200L);
        assertThat(authorization.get("AUTHORIZED_AMOUNT")).isEqualTo(0L);
        assertThat(authorization.get("STATUS")).isEqualTo("DECLINED");
        assertThat(authorization.get("CVC_CHECK")).isEqualTo(cvcCheck);
        assertThat(authorization.get("AVS_LINE1_CHECK")).isEqualTo(avsLine1Check);
        assertThat(authorization.get("AVS_POSTAL_CODE_CHECK")).isEqualTo(avsPostalCheck);
        assertThat(authorization.get("THREE_DS_RESULT")).isEqualTo("NOT_REQUIRED");
        assertThat(authorization.get("SIMULATOR_OUTCOME_CODE")).isEqualTo(responseCode);
        assertThat(response.get("fraudDecisionId").isNull()).isFalse();
        UUID fraudDecisionId = UUID.fromString(response.get("fraudDecisionId").asText());
        assertThat(fraudDecisionById(fraudDecisionId).get("ACTION")).isEqualTo("ALLOW");
        assertThat(featureSnapshotForDecision(fraudDecisionId).get("FEATURE_VERSION")).isEqualTo("features_v1");
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("feature_values")).isEqualTo(7);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(3);
        assertThat(countAuditLogsByAction("authorization.declined")).isEqualTo(1);
    }

    @Test
    void confirmWithThreeDsRequiredCardCreatesAuthorizationAndRequiresAction() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-3ds", "4999990000000008");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-3ds", 3200);

        var response = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-3ds");
        var paymentAttemptId = UUID.fromString(response.get("id").asText());

        assertThat(response.get("status").asText()).isEqualTo("REQUIRES_ACTION");
        assertThat(response.get("processorAuthorizationId").asText()).startsWith("auth_sim_");
        assertThat(response.get("threeDsStatus").asText()).isEqualTo("REQUESTED");
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_ACTION");

        var authorization = authorizationForAttempt(paymentAttemptId);
        assertThat(authorization.get("PROCESSOR_RESPONSE_CODE")).isEqualTo("THREE_DS_REQUIRED");
        assertThat(authorization.get("NETWORK_STATUS")).isEqualTo("REQUIRES_ACTION");
        assertThat(authorization.get("REQUESTED_AMOUNT")).isEqualTo(3200L);
        assertThat(authorization.get("AUTHORIZED_AMOUNT")).isEqualTo(0L);
        assertThat(authorization.get("STATUS")).isEqualTo("REQUIRES_ACTION");
        assertThat(authorization.get("THREE_DS_RESULT")).isEqualTo("REQUIRED");
        assertThat(authorization.get("SIMULATOR_OUTCOME_CODE")).isEqualTo("THREE_DS_REQUIRED");
        assertThat(response.get("fraudDecisionId").isNull()).isFalse();
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(3);
        assertThat(countAuditLogsByAction("authorization.requires_action")).isEqualTo(1);
    }

    @Test
    void idempotentConfirmReplayDoesNotCreateDuplicateAttemptAuthorizationOrWebhookDelivery() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-replay");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-replay", 2300);
        createWebhookEndpoint(merchant, "https://merchant-a.example/webhooks");

        var first = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay");
        var replay = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay");

        assertThat(replay).isEqualTo(first);
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("feature_values")).isEqualTo(7);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(4);
        assertThat(countWebhookDeliveries("payment_attempt.created")).isEqualTo(1);
    }

    @Test
    void sameConfirmKeyWithChangedBodyReturnsConflictWithoutDuplicateAttempt() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var firstPaymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-conflict-a");
        var secondPaymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-conflict-b");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-conflict", 3300);

        confirmPaymentIntent(merchant, paymentIntentId, firstPaymentMethodId, "confirm-changed-body");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-changed-body")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(secondPaymentMethodId)))
                .andExpect(status().isConflict());
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(3);
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
    }

    @Test
    void legacyStubDecisionRemainsReadableWithoutTypedBackfillOrResponseShapeBreak() throws Exception {
        MerchantAccess merchant = createMerchant("legacy-decision-merchant");
        UUID customerId = createCustomer(merchant, "legacy@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "legacy-decision-method");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "legacy-decision-intent", 1200);
        UUID attemptId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_attempts
                    (id, merchant_id, payment_intent_id, payment_method_id, idempotency_key, status,
                     attempt_number, fraud_decision_id, traffic_origin, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'legacy-confirm', 'CREATED', 1, ?, 'MERCHANT_API',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attemptId, merchant.id(), paymentIntentId, paymentMethodId, decisionId);
        jdbcTemplate.update("""
                INSERT INTO fraud_decisions
                    (id, merchant_id, payment_attempt_id, risk_score, risk_level, action, model_version,
                     ruleset_version, latency_ms, decision_started_at, decision_completed_at,
                     feature_freshness_status, max_feature_age_ms, reason_codes, traffic_origin, created_at)
                VALUES (?, ?, ?, 0.0000, 'LOW', 'ALLOW', NULL, 'rules_stub_v1', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'FRESH', 0,
                        '["RULES_NOT_ENABLED"]', 'MERCHANT_API', CURRENT_TIMESTAMP)
                """, decisionId, merchant.id(), attemptId);
        jdbcTemplate.update("""
                INSERT INTO feature_snapshots
                    (id, merchant_id, fraud_decision_id, payment_attempt_id, feature_version, features_json,
                     computed_at, oldest_feature_timestamp, max_feature_age_ms, freshness_status,
                     stale_feature_names, missing_feature_names, traffic_origin, created_at)
                VALUES (?, ?, ?, ?, 'features_v1',
                        '{"payment_amount":1200,"payment_currency":"USD","card_bin":"499999"}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 'FRESH', '[]', '[]',
                        'MERCHANT_API', CURRENT_TIMESTAMP)
                """, snapshotId, merchant.id(), decisionId, attemptId);

        String response = mockMvc.perform(get("/fraud/decisions/{id}", decisionId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode detail = objectMapper.readTree(response);
        assertThat(detail.get("decision").get("rulesetVersion").asText()).isEqualTo("rules_stub_v1");
        assertThat(detail.get("decision").get("reasonCodes").get(0).asText()).isEqualTo("RULES_NOT_ENABLED");
        assertThat(detail.get("featureSnapshot").get("features").get("payment_amount").asLong()).isEqualTo(1200L);
        assertThat(detail.get("featureSnapshot").get("features").get("card_bin").asText()).isEqualTo("499999");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM feature_values WHERE payment_attempt_id = ?
                """, Integer.class, attemptId)).isZero();
    }

    @Test
    void separateConfirmKeyAfterAuthorizationIsRejectedWithoutDuplicateAttemptOrAuthorization() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-second-key");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-second-key", 3400);

        var first = confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-number-1");

        assertThat(first.get("attemptNumber").asInt()).isEqualTo(1);
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-number-2")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isConflict());
        assertThat(countRows("payment_attempts")).isEqualTo(1);
        assertThat(countRows("fraud_decisions")).isEqualTo(1);
        assertThat(countRows("feature_snapshots")).isEqualTo(1);
        assertThat(countRows("authorizations")).isEqualTo(1);
        assertThat(countRows("audit_logs")).isEqualTo(3);
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
    }

    @Test
    void separateConfirmKeyAfterRetryableDeclineCreatesNextAttemptAndAuthorization() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var declinedPaymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-declined",
                "4999990000000002");
        var approvedPaymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-approved",
                "4999990000000001");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-retry", 3500);

        var declined = confirmPaymentIntent(merchant, paymentIntentId, declinedPaymentMethodId, "confirm-decline-1");
        var approved = confirmPaymentIntent(merchant, paymentIntentId, approvedPaymentMethodId, "confirm-retry-2");

        assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
        assertThat(declined.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(approved.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(approved.get("attemptNumber").asInt()).isEqualTo(2);
        assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        assertThat(countRows("payment_attempts")).isEqualTo(2);
        assertThat(countRows("fraud_decisions")).isEqualTo(2);
        assertThat(countRows("feature_snapshots")).isEqualTo(2);
        assertThat(countRows("authorizations")).isEqualTo(2);
        assertThat(countRows("audit_logs")).isEqualTo(6);
    }

    @Test
    void concurrentConfirmKeysCreateOneProcessorReadyAttemptForSamePaymentIntent() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-concurrent");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-concurrent", 3450);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> performConfirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                    "confirm-concurrent-1"));
            var second = executor.submit(() -> performConfirmPaymentIntent(merchant, paymentIntentId, paymentMethodId,
                    "confirm-concurrent-2"));

            var firstResponse = first.get(5, TimeUnit.SECONDS);
            var secondResponse = second.get(5, TimeUnit.SECONDS);

            assertThat(statuses(firstResponse, secondResponse)).containsExactlyInAnyOrder(201, 409);
            assertThat(countRows("payment_attempts")).isEqualTo(1);
            assertThat(countRows("fraud_decisions")).isEqualTo(1);
            assertThat(countRows("feature_snapshots")).isEqualTo(1);
            assertThat(countRows("authorizations")).isEqualTo(1);
            assertThat(countRows("audit_logs")).isEqualTo(3);
            assertThat(getPaymentIntent(merchant, paymentIntentId).get("status").asText()).isEqualTo("REQUIRES_CAPTURE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonConfirmablePaymentIntentRejectsAttemptCreation() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerId = createCustomer(merchant, "buyer-a@example.com");
        var paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-invalid-status");
        var paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-invalid-status", 4500);
        cancelPaymentIntent(merchant, paymentIntentId, "cancel-before-confirm");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-invalid-status")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isConflict());
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isEqualTo(1);
        assertThat(countAuditLogsByAction("payment_intent.canceled")).isEqualTo(1);
    }

    @Test
    void merchantCannotConfirmAnotherMerchantsPaymentIntent() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchantA, customerA, "confirm-pi-cross-intent", 5600);
        var paymentMethodB = createPaymentMethod(merchantB, customerB, "confirm-pm-cross-intent");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchantB.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-cross-intent")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isZero();
    }

    @Test
    void merchantCannotConfirmWithAnotherMerchantsPaymentMethod() throws Exception {
        var merchantA = createMerchant("merchant-a");
        var merchantB = createMerchant("merchant-b");
        var customerA = createCustomer(merchantA, "buyer-a@example.com");
        var customerB = createCustomer(merchantB, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchantA, customerA, "confirm-pi-cross-method", 6700);
        var paymentMethodB = createPaymentMethod(merchantB, customerB, "confirm-pm-cross-method");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchantA.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-cross-method")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isZero();
    }

    @Test
    void merchantCannotConfirmWithPaymentMethodForDifferentCustomer() throws Exception {
        var merchant = createMerchant("merchant-a");
        var customerA = createCustomer(merchant, "buyer-a@example.com");
        var customerB = createCustomer(merchant, "buyer-b@example.com");
        var paymentIntentA = createPaymentIntent(merchant, customerA, "confirm-pi-wrong-customer", 7800);
        var paymentMethodB = createPaymentMethod(merchant, customerB, "confirm-pm-wrong-customer");

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentA)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-wrong-customer")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodB)))
                .andExpect(status().isNotFound());
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isZero();
    }

    private static Stream<Arguments> declineCards() {
        return Stream.of(
                Arguments.of("4999990000000002", "ISSUER_DECLINE", "PASS", "PASS", "PASS"),
                Arguments.of("4999990000000003", "INSUFFICIENT_FUNDS", "PASS", "PASS", "PASS"),
                Arguments.of("4999990000000004", "SUSPECTED_FRAUD", "PASS", "PASS", "PASS"),
                Arguments.of("4999990000000005", "CVC_FAILURE", "FAIL", "PASS", "PASS"),
                Arguments.of("4999990000000006", "AVS_LINE1_FAILURE", "PASS", "FAIL", "PASS"),
                Arguments.of("4999990000000007", "AVS_POSTAL_FAILURE", "PASS", "PASS", "FAIL"));
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        return new MerchantAccess(UUID.fromString(json.get("id").asText()), json.get("apiKeyPrefix").asText(),
                json.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        var response = mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey) throws Exception {
        return createPaymentMethod(merchant, customerId, idempotencyKey, "4242424242424242");
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey, String cardNumber)
            throws Exception {
        var response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "%s"
                                }
                                """.formatted(customerId, cardNumber)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        var response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": %d,
                                  "currency": "USD",
                                  "captureMethod": "MANUAL"
                                }
                                """.formatted(customerId, amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private JsonNode confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        var response = mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-confirm-flow")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getPaymentAttempt(MerchantAccess merchant, UUID paymentAttemptId) throws Exception {
        var response = mockMvc.perform(get("/payment_attempts/{id}", paymentAttemptId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getPaymentIntent(MerchantAccess merchant, UUID paymentIntentId) throws Exception {
        var response = mockMvc.perform(get("/payment_intents/{id}", paymentIntentId)
                        .header(API_KEY, merchant.apiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private MvcResult performConfirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-confirm-flow")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andReturn();
    }

    private void createWebhookEndpoint(MerchantAccess merchant, String url) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey())
                        .header("Idempotency-Key", "webhook-" + merchant.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated());
    }

    private void cancelPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/cancel", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().isOk());
    }

    private String confirmBody(UUID paymentMethodId) {
        return """
                {
                  "paymentMethodId": "%s"
                }
                """.formatted(paymentMethodId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private java.util.Map<String, Object> authorizationForAttempt(UUID paymentAttemptId) {
        return jdbcTemplate.queryForMap("""
                SELECT merchant_id, payment_attempt_id, processor, processor_reference_id, processor_response_code,
                       network_status, requested_amount, authorized_amount, currency, status, cvc_check, avs_line1_check,
                       avs_postal_code_check, three_ds_result, simulator_outcome_code, created_at
                FROM authorizations
                WHERE payment_attempt_id = ?
                """, paymentAttemptId);
    }

    private java.util.Map<String, Object> fraudDecisionById(UUID fraudDecisionId) {
        return jdbcTemplate.queryForMap("""
                SELECT merchant_id, payment_attempt_id, risk_score, risk_level, action, model_version, ruleset_version,
                       latency_ms, decision_started_at, decision_completed_at, feature_freshness_status,
                       max_feature_age_ms, reason_codes, created_at
                FROM fraud_decisions
                WHERE id = ?
                """, fraudDecisionId);
    }

    private java.util.Map<String, Object> featureSnapshotForDecision(UUID fraudDecisionId) {
        return jdbcTemplate.queryForMap("""
                SELECT merchant_id, fraud_decision_id, payment_attempt_id, feature_version, features_json, computed_at,
                       oldest_feature_timestamp, max_feature_age_ms, freshness_status, stale_feature_names,
                       missing_feature_names, created_at
                FROM feature_snapshots
                WHERE fraud_decision_id = ?
                """, fraudDecisionId);
    }

    private int countWebhookDeliveries(String eventType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE event_type = ?
                """, Integer.class, eventType);
    }

    private int countAuditLogsByAction(String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = ?
                """, Integer.class, action);
    }

    private List<AuditLogRow> auditRowsForRequest(String requestId) {
        return jdbcTemplate.query("""
                SELECT actor_id, action, aggregate_type, before_json, after_json
                FROM audit_logs
                WHERE request_id = ?
                ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new AuditLogRow(
                resultSet.getString("actor_id"),
                resultSet.getString("action"),
                resultSet.getString("aggregate_type"),
                readNullableJson(resultSet.getString("before_json")),
                readNullableJson(resultSet.getString("after_json"))), requestId);
    }

    private AuditLogRow auditRow(List<AuditLogRow> auditRows, String action) {
        return auditRows.stream()
                .filter(row -> row.action().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing audit action: " + action));
    }

    private JsonNode readNullableJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit payload JSON was not readable", exception);
        }
    }

    private List<Integer> statuses(MvcResult first, MvcResult second) {
        return List.of(first.getResponse().getStatus(), second.getResponse().getStatus());
    }

    private record MerchantAccess(UUID id, String apiKeyPrefix, String apiKey) {
    }

    private record AuditLogRow(String actorId, String action, String aggregateType, JsonNode beforeJson,
            JsonNode afterJson) {
    }
}
