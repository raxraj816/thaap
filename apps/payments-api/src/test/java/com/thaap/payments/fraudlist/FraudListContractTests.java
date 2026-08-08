package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class FraudListContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String CARD_NUMBER = "4242424242424242";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @MockitoSpyBean private ProcessorAdapter processorAdapter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        Mockito.clearInvocations(processorAdapter);
    }

    @Test
    void managerCreatesPrivacySafeVersionedNegativeControlsAndReplayIsExact() throws Exception {
        MerchantAccess merchant = createMerchant("fraud-list-manager");
        OperatorAccess manager = createOperator(merchant, "manager", "RISK_MANAGER");
        OperatorAccess analyst = createOperator(merchant, "analyst", "ANALYST");

        String createBody = """
                {"code":"KNOWN_EMAILS","displayName":"Known bad emails","identityType":"EMAIL"}
                """;
        String first = mockMvc.perform(post("/risk/fraud-lists").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "create-email-list").contentType(APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/risk/fraud-lists").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "create-email-list").contentType(APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mockMvc.perform(post("/risk/fraud-lists").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "create-email-list").contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"CHANGED_EMAILS","displayName":"Changed body","identityType":"EMAIL"}
                                """))
                .andExpect(status().isConflict());
        UUID listId = UUID.fromString(objectMapper.readTree(first).get("id").asText());

        mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId).header(RISK_KEY, analyst.secret())
                        .header(IDEMPOTENCY_KEY, "analyst-entry").contentType(APPLICATION_JSON)
                        .content(entryBody("victim@example.com", "BLOCK")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId).header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "allow-entry").contentType(APPLICATION_JSON)
                        .content(entryBody("victim@example.com", "ALLOW")))
                .andExpect(status().isBadRequest());

        String entryResponse = mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId)
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "block-email")
                        .contentType(APPLICATION_JSON).content(entryBody("victim@example.com", "BLOCK")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(entryResponse).doesNotContain("victim@example.com");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_lists", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_list_versions", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_list_entries", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_list_lifecycle_events", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT value_hmac FROM fraud_list_entries", String.class))
                .matches("[0-9a-f]{64}");
        assertThat(databaseText()).doesNotContain("victim@example.com");

        JsonNode lists = objectMapper.readTree(mockMvc.perform(get("/risk/fraud-lists")
                        .header(RISK_KEY, analyst.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).get("active").asBoolean()).isTrue();
    }

    @Test
    void listAndEntryGrammarRejectsEveryInvalidIdentityNamespaceAndTimePartition() throws Exception {
        MerchantAccess merchant = createMerchant("fraud-list-invalid-grammar");
        OperatorAccess manager = createOperator(merchant, "grammar-manager", "RISK_MANAGER");

        assertCreateListRejected(manager, "GENERIC_MISSING", "GENERIC_STRING", null);
        assertCreateListRejected(manager, "CARD_WITH_NAMESPACE", "CARD_FINGERPRINT", "DEVICE_ID");

        List<ListSpec> invalidSpecs = List.of(
                new ListSpec("INVALID_CARD", "CARD_FINGERPRINT", null, "4242", "BLOCK"),
                new ListSpec("INVALID_BIN", "BIN", null, "42424x", "BLOCK"),
                new ListSpec("INVALID_COUNTRY", "COUNTRY", null, "USA", "BLOCK"),
                new ListSpec("INVALID_IP", "IP", null, "999.1.1.1", "BLOCK"),
                new ListSpec("INVALID_CUSTOMER", "CUSTOMER", null, "not-a-uuid", "BLOCK"),
                new ListSpec("INVALID_EMAIL", "EMAIL", null, "missing-at.example.com", "BLOCK"),
                new ListSpec("INVALID_DOMAIN", "EMAIL_DOMAIN", null, "localhost", "BLOCK"),
                new ListSpec("INVALID_GENERIC", "GENERIC_STRING", "SESSION_ID", "bad\\u0001value", "BLOCK"));
        int key = 0;
        for (ListSpec spec : invalidSpecs) {
            UUID listId = createList(manager, spec);
            mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId)
                            .header(RISK_KEY, manager.secret())
                            .header(IDEMPOTENCY_KEY, "invalid-entry-" + key++)
                            .contentType(APPLICATION_JSON).content(entryBody(spec.value(), spec.action())))
                    .andExpect(status().isBadRequest());
        }

        UUID timeListId = createList(manager,
                new ListSpec("INVALID_TIMES", "EMAIL", null, "unused@example.com", "BLOCK"));
        Instant now = DatabaseInstant.now();
        assertTimedEntryRejected(manager, timeListId, "backdated", now.minus(1, ChronoUnit.MINUTES), null);
        assertTimedEntryRejected(manager, timeListId, "too-far-future", now.plus(31, ChronoUnit.DAYS), null);
        assertTimedEntryRejected(manager, timeListId, "expiry-before-start", now.plus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.SECONDS));
        assertTimedEntryRejected(manager, timeListId, "expiry-too-distant", now.plus(1, ChronoUnit.MINUTES),
                now.plus(367, ChronoUnit.DAYS));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_list_entries", Integer.class)).isZero();
    }

    @Test
    void allIdentityFamiliesMatchOnePointInTimeDecisionAndStrongestActionWins(CapturedOutput output)
            throws Exception {
        MerchantAccess merchant = createMerchant("fraud-list-all-families");
        OperatorAccess manager = createOperator(merchant, "all-family-manager", "RISK_MANAGER");
        UUID customerId = createCustomer(merchant, "victim@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, CARD_NUMBER);

        List<ListSpec> specs = List.of(
                new ListSpec("BAD_CARD", "CARD_FINGERPRINT", null, CARD_NUMBER, "BLOCK"),
                new ListSpec("BAD_BIN", "BIN", null, "424242", "REVIEW"),
                new ListSpec("BAD_COUNTRY", "COUNTRY", null, "US", "REQUEST_3DS"),
                new ListSpec("BAD_IP", "IP", null, "203.0.113.7", "REVIEW"),
                new ListSpec("BAD_CUSTOMER", "CUSTOMER", null, customerId.toString(), "REVIEW"),
                new ListSpec("BAD_EMAIL", "EMAIL", null, "victim@example.com", "REVIEW"),
                new ListSpec("BAD_DOMAIN", "EMAIL_DOMAIN", null, "example.com", "REVIEW"),
                new ListSpec("BAD_DEVICE", "GENERIC_STRING", "DEVICE_ID", "device-canary", "REVIEW"),
                new ListSpec("BAD_SESSION", "GENERIC_STRING", "SESSION_ID", "session-canary", "REVIEW"));
        for (ListSpec spec : specs) {
            UUID listId = createList(manager, spec);
            createEntry(manager, listId, spec.value(), spec.action(), spec.code() + "-entry");
        }

        UUID intentId = createIntent(merchant, customerId, 1_000L, "all-family-intent");
        JsonNode attempt = confirm(merchant, intentId, paymentMethodId, "all-family-confirm", true);
        assertThat(attempt.get("status").asText()).isEqualTo("BLOCKED");
        UUID decisionId = jdbcTemplate.queryForObject(
                "SELECT fraud_decision_id FROM payment_attempts WHERE id = ?", UUID.class,
                UUID.fromString(attempt.get("id").asText()));
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, decisionId)).isEqualTo("BLOCK");
        assertThat(jdbcTemplate.queryForObject("SELECT reason_codes FROM fraud_decisions WHERE id = ?",
                String.class, decisionId)).contains("FRAUD_LIST_MATCH");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_list_evaluations WHERE fraud_decision_id = ?",
                Integer.class, decisionId)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_list_evaluations
                 WHERE fraud_decision_id = ? AND matched = TRUE AND matched_entry_id IS NOT NULL
                """, Integer.class, decisionId)).isEqualTo(9);
        Mockito.verify(processorAdapter, Mockito.never()).authorize(Mockito.any(ProcessorAuthorizationRequest.class));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authorizations", Integer.class)).isZero();
        assertThat(databaseText()).doesNotContain("victim@example.com", "203.0.113.7",
                CARD_NUMBER, "device-canary", "session-canary");
        assertThat(output.getAll()).doesNotContain("victim@example.com", "203.0.113.7",
                CARD_NUMBER, "device-canary", "session-canary");
    }

    @Test
    void entryDeactivationAndTenantBoundaryChangeOnlyFutureOwnMerchantDecisions() throws Exception {
        MerchantAccess owner = createMerchant("fraud-list-owner");
        MerchantAccess other = createMerchant("fraud-list-other");
        OperatorAccess manager = createOperator(owner, "owner-manager", "RISK_MANAGER");
        UUID ownerCustomer = createCustomer(owner, "owner@example.com");
        UUID otherCustomer = createCustomer(other, "other@example.com");
        UUID ownerMethod = createPaymentMethod(owner, ownerCustomer, CARD_NUMBER);
        UUID otherMethod = createPaymentMethod(other, otherCustomer, CARD_NUMBER);
        ListSpec card = new ListSpec("OWNER_BAD_CARD", "CARD_FINGERPRINT", null, CARD_NUMBER, "BLOCK");
        UUID listId = createList(manager, card);
        UUID entryId = createEntry(manager, listId, CARD_NUMBER, "BLOCK", "owner-card-entry");

        UUID otherIntent = createIntent(other, otherCustomer, 1_000L, "other-intent");
        JsonNode otherAttempt = confirm(other, otherIntent, otherMethod, "other-confirm", false);
        assertThat(otherAttempt.get("status").asText()).isNotEqualTo("BLOCKED");
        UUID ownerIntent = createIntent(owner, ownerCustomer, 1_000L, "owner-intent-1");
        JsonNode blocked = confirm(owner, ownerIntent, ownerMethod, "owner-confirm-1", false);
        assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");

        mockMvc.perform(post("/risk/fraud-lists/{listId}/entries/{entryId}/deactivate", listId, entryId)
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "deactivate-owner-card")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        UUID secondIntent = createIntent(owner, ownerCustomer, 1_000L, "owner-intent-2");
        JsonNode allowed = confirm(owner, secondIntent, ownerMethod, "owner-confirm-2", false);
        assertThat(allowed.get("status").asText()).isNotEqualTo("BLOCKED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_list_evaluations evaluation
                 WHERE evaluation.merchant_id = ? AND evaluation.matched = FALSE
                """, Integer.class, owner.id())).isOne();
    }

    private UUID createList(OperatorAccess operator, ListSpec spec) throws Exception {
        String namespace = spec.namespace() == null ? "" : ",\"genericNamespace\":\"" + spec.namespace() + "\"";
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/fraud-lists")
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, spec.code() + "-list")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"%s","displayName":"%s","identityType":"%s"%s}
                                """.formatted(spec.code(), spec.code(), spec.type(), namespace)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private void assertCreateListRejected(OperatorAccess operator, String code, String type, String namespace)
            throws Exception {
        String namespaceField = namespace == null ? "" : ",\"genericNamespace\":\"" + namespace + "\"";
        mockMvc.perform(post("/risk/fraud-lists").header(RISK_KEY, operator.secret())
                        .header(IDEMPOTENCY_KEY, "invalid-list-" + code).contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"Invalid namespace","identityType":"%s"%s}
                                """.formatted(code, type, namespaceField)))
                .andExpect(status().isBadRequest());
    }

    private void assertTimedEntryRejected(OperatorAccess operator, UUID listId, String key,
            Instant effectiveFrom, Instant effectiveUntil) throws Exception {
        String until = effectiveUntil == null ? "" : ",\"effectiveUntil\":\"" + effectiveUntil + "\"";
        String body = "{\"value\":\"bad@example.com\",\"action\":\"BLOCK\","
                + "\"reasonCode\":\"KNOWN_FRAUD\",\"effectiveFrom\":\"" + effectiveFrom + "\"" + until + "}";
        mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private UUID createEntry(OperatorAccess operator, UUID listId, String value, String action, String key)
            throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(entryBody(value, action)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private String entryBody(String value, String action) {
        return """
                {"value":"%s","action":"%s","reasonCode":"KNOWN_FRAUD"}
                """.formatted(value, action);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()), response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(UUID.fromString(response.get("id").asText()), response.get("credential").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "customer-" + UUID.randomUUID()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String cardNumber) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "method-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"cardNumber\":\"" + cardNumber + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createIntent(MerchantAccess merchant, UUID customerId, long amount, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":%d,"currency":"USD"}
                                """.formatted(customerId, amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId, String key,
            boolean withContext) throws Exception {
        String riskContext = withContext ? """
                ,"riskContext":{"ipAddress":"203.0.113.7","ipCountry":"US","shippingCountry":"US",
                  "deviceId":"device-canary","sessionId":"session-canary","checkoutChannel":"WEB"}
                """ : "";
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"" + riskContext + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String databaseText() {
        List<String> values = new ArrayList<>();
        values.addAll(jdbcTemplate.queryForList("""
                SELECT COALESCE(value_hmac, '') || COALESCE(canonical_value, '')
                  FROM fraud_list_entries
                """, String.class));
        values.addAll(jdbcTemplate.queryForList("""
                SELECT COALESCE(email_hash, '') || COALESCE(email_domain_hash, '') FROM customers
                """, String.class));
        values.addAll(jdbcTemplate.queryForList("""
                SELECT COALESCE(ip_identity_hmac, '') || COALESCE(device_identity_hmac, '')
                    || COALESCE(session_identity_hmac, '') FROM risk_contexts
                """, String.class));
        values.addAll(jdbcTemplate.queryForList("""
                SELECT request_hash || COALESCE(response_body, '') FROM idempotency_records
                """, String.class));
        values.addAll(jdbcTemplate.queryForList("""
                SELECT COALESCE(before_json, '') || COALESCE(after_json, '') FROM audit_logs
                """, String.class));
        values.addAll(jdbcTemplate.queryForList("SELECT payload FROM webhook_events", String.class));
        return String.join("\n", values);
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record ListSpec(String code, String type, String namespace, String value, String action) { }
}
