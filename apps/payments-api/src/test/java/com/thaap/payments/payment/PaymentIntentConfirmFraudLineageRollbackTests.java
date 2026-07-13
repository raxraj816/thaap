package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FeatureValueRepository;
import com.thaap.payments.fraud.RuleExecutionResult;
import com.thaap.payments.fraud.RuleExecutionResultRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PaymentIntentConfirmFraudLineageRollbackTests {

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
    private FeatureSnapshotRepository featureSnapshotRepository;

    @MockitoBean
    private ProcessorAdapter processorAdapter;

    @MockitoBean
    private FeatureValueRepository featureValueRepository;

    @MockitoBean
    private RuleExecutionResultRepository ruleExecutionResultRepository;

    @MockitoBean
    private ManualReviewCaseRepository manualReviewCaseRepository;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
        reset(featureSnapshotRepository, featureValueRepository, ruleExecutionResultRepository,
                manualReviewCaseRepository, processorAdapter);
    }

    @Test
    void featureSnapshotPersistenceFailureRollsBackAttemptDecisionSnapshotAuthorizationAndIntentState()
            throws Exception {
        when(featureSnapshotRepository.save(any(FeatureSnapshot.class)))
                .thenThrow(new IllegalStateException("feature snapshot unavailable"));
        MerchantAccess merchant = createMerchant("merchant-a");
        UUID customerId = createCustomer(merchant, "buyer-a@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-snapshot-failure");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-snapshot-failure", 1200);

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-snapshot-failure")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isInternalServerError());

        verify(featureSnapshotRepository, times(1)).save(any(FeatureSnapshot.class));
        verifyNoInteractions(processorAdapter);
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("feature_values")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isZero();
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void failureAtEveryFeatureValuePositionRollsBackAllLineageAndKeepsConfirmRetryable(int failingPosition)
            throws Exception {
        when(featureSnapshotRepository.save(any(FeatureSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger position = new AtomicInteger();
        when(featureValueRepository.save(any())).thenAnswer(invocation -> {
            if (position.getAndIncrement() == failingPosition) {
                throw new IllegalStateException("feature value unavailable");
            }
            return invocation.getArgument(0);
        });
        MerchantAccess merchant = createMerchant("merchant-feature-value-failure");
        UUID customerId = createCustomer(merchant, "buyer-values@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-value-failure");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-value-failure", 1200);

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-value-failure")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isInternalServerError());

        verifyNoInteractions(processorAdapter);
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("feature_values")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(countRows("audit_logs")).isZero();
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void failureAtEveryRuleResultPositionRollsBackCompleteDecisionBeforeProcessor(int failingPosition)
            throws Exception {
        when(featureSnapshotRepository.save(any(FeatureSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(featureValueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger position = new AtomicInteger();
        when(ruleExecutionResultRepository.save(any(RuleExecutionResult.class))).thenAnswer(invocation -> {
            if (position.getAndIncrement() == failingPosition) {
                throw new IllegalStateException("rule result unavailable");
            }
            return invocation.getArgument(0);
        });
        MerchantAccess merchant = createMerchant("merchant-rule-result-failure");
        UUID customerId = createCustomer(merchant, "buyer-rules@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-rule-failure");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-rule-failure", 1200);

        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "confirm-rule-failure")
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().isInternalServerError());

        verifyNoInteractions(processorAdapter);
        assertThat(countRows("payment_attempts")).isZero();
        assertThat(countRows("fraud_decisions")).isZero();
        assertThat(countRows("feature_snapshots")).isZero();
        assertThat(countRows("feature_values")).isZero();
        assertThat(countRows("rule_execution_results")).isZero();
        assertThat(countRows("authorizations")).isZero();
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_CONFIRMATION");
    }

    @Test
    void reviewCasePersistenceFailureRollsBackThirdAttemptAndKeepsPaymentRetryable() throws Exception {
        when(featureSnapshotRepository.save(any(FeatureSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(featureValueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ruleExecutionResultRepository.save(any(RuleExecutionResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(manualReviewCaseRepository.save(any(ManualReviewCase.class)))
                .thenThrow(new IllegalStateException("review store unavailable"));
        when(processorAdapter.authorize(any())).thenAnswer(invocation ->
                new com.thaap.payments.processor.ProcessorAuthorizationResult(
                "THAAP_SIMULATOR", "auth_review_failure_" + UUID.randomUUID(), "INSUFFICIENT_FUNDS",
                com.thaap.payments.authorization.NetworkStatus.DECLINED, 99_999, 0, "USD",
                com.thaap.payments.authorization.AuthorizationStatus.DECLINED,
                com.thaap.payments.authorization.VerificationResult.PASS,
                com.thaap.payments.authorization.VerificationResult.PASS,
                com.thaap.payments.authorization.VerificationResult.PASS,
                com.thaap.payments.authorization.ThreeDsResult.NOT_REQUIRED, "INSUFFICIENT_FUNDS"));
        MerchantAccess merchant = createMerchant("merchant-review-failure");
        UUID customerId = createCustomer(merchant, "buyer-review-failure@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "confirm-pm-review-failure",
                "4999990000000003");
        UUID paymentIntentId = createPaymentIntent(merchant, customerId, "confirm-pi-review-failure", 99_999);
        confirmExpecting(merchant, paymentIntentId, paymentMethodId, "review-failure-1", HttpStatus.CREATED.value());
        confirmExpecting(merchant, paymentIntentId, paymentMethodId, "review-failure-2", HttpStatus.CREATED.value());

        confirmExpecting(merchant, paymentIntentId, paymentMethodId, "review-failure-3",
                HttpStatus.INTERNAL_SERVER_ERROR.value());

        assertThat(countRows("payment_attempts")).isEqualTo(2);
        assertThat(countRows("fraud_decisions")).isEqualTo(2);
        assertThat(countRows("manual_review_cases")).isZero();
        assertThat(countRows("authorizations")).isEqualTo(2);
        assertThat(paymentIntentStatus(paymentIntentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
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
        return createPaymentMethod(merchant, customerId, idempotencyKey, "4242424242424242");
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String idempotencyKey,
            String cardNumber) throws Exception {
        String response = mockMvc.perform(post("/payment_methods")
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

    private String confirmBody(UUID paymentMethodId) {
        return """
                {
                  "paymentMethodId": "%s"
                }
                """.formatted(paymentMethodId);
    }

    private void confirmExpecting(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId,
            String idempotencyKey, int expectedStatus) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(confirmBody(paymentMethodId)))
                .andExpect(status().is(expectedStatus));
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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
