package com.thaap.payments.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.ConfirmDecisionEngine;
import com.thaap.payments.fraud.ConfirmDecisionRequest;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.processor.ProcessorAuthorizationResult;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentConfirmProcessorAdapterCallTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

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

    @MockitoBean
    private ProcessorAdapter processorAdapter;

    @MockitoBean
    private ConfirmDecisionEngine confirmDecisionEngine;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
        reset(processorAdapter, confirmDecisionEngine);
        when(confirmDecisionEngine.decide(any(ConfirmDecisionRequest.class)))
                .thenReturn(new ConfirmDecision(FraudDecisionAction.ALLOW,
                        java.util.List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED)));
        when(processorAdapter.authorize(any(ProcessorAuthorizationRequest.class)))
                .thenReturn(new ProcessorAuthorizationResult("THAAP_SIMULATOR", "auth_sim_replay_proof",
                        "APPROVED", NetworkStatus.APPROVED, 1200, 1200, "USD", AuthorizationStatus.APPROVED,
                        VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                        ThreeDsResult.NOT_REQUIRED, "APPROVED"));
    }

    @Test
    void idempotentConfirmReplayDoesNotCallProcessorAdapterTwice() throws Exception {
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-replay-proof");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-replay-proof", 1200);

        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay-proof");
        confirmPaymentIntent(merchant, paymentIntentId, paymentMethodId, "confirm-replay-proof");

        verify(processorAdapter, times(1)).authorize(any(ProcessorAuthorizationRequest.class));
        org.assertj.core.api.Assertions.assertThat(countRows("payment_attempts")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countRows("authorizations")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("nonAllowActions")
    void nonAllowFraudDecisionDoesNotCallProcessorAdapterOrPersistAuthorization(FraudDecisionAction action)
            throws Exception {
        when(confirmDecisionEngine.decide(any(ConfirmDecisionRequest.class)))
                .thenReturn(new ConfirmDecision(action,
                        java.util.List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED)));
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-" + action);
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-" + action, 1200);

        confirmPaymentIntentExpectingConflict(merchant, paymentIntentId, paymentMethodId, "confirm-" + action);

        verifyNoInteractions(processorAdapter);
        org.assertj.core.api.Assertions.assertThat(countRows("payment_attempts")).isZero();
        org.assertj.core.api.Assertions.assertThat(countRows("authorizations")).isZero();
        org.assertj.core.api.Assertions.assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @Test
    void processorAdapterFailureRollsBackProcessingStateAttemptAndAuthorization() throws Exception {
        when(processorAdapter.authorize(any(ProcessorAuthorizationRequest.class)))
                .thenThrow(new IllegalStateException("processor unavailable"));
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-failure-proof");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-failure-proof", 1200);

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-failure-proof")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isInternalServerError());

        verify(processorAdapter, times(1)).authorize(any(ProcessorAuthorizationRequest.class));
        org.assertj.core.api.Assertions.assertThat(countRows("payment_attempts")).isZero();
        org.assertj.core.api.Assertions.assertThat(countRows("authorizations")).isZero();
        org.assertj.core.api.Assertions.assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        String response = mockMvc.perform(post("/admin/merchants")
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
        return new MerchantAccess(UUID.fromString(json.get("id").asText()), json.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        String response = mockMvc.perform(post("/customers")
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

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey)
            throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "cardNumber": "4242424242424242"
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId, String idempotencyKey, long amount)
            throws Exception {
        String response = mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "amount": %d,
                                  "currency": "USD"
                                }
                                """.formatted(customerId, amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void confirmPaymentIntent(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMethodId": "%s"
                                }
                                """.formatted(paymentMethodId)))
                .andExpect(status().isCreated());
    }

    private void confirmPaymentIntentExpectingConflict(MerchantAccess merchant, UUID paymentIntentId,
            UUID paymentMethodId, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isConflict());
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

    private static Stream<Arguments> nonAllowActions() {
        return Stream.of(Arguments.of(FraudDecisionAction.BLOCK), Arguments.of(FraudDecisionAction.REVIEW));
    }

    private String paymentIntentStatus(UUID paymentIntentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
}
