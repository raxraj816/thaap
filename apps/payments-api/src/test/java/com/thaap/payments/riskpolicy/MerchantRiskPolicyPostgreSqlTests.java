package com.thaap.payments.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MerchantRiskPolicyPostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DataSource dataSource;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void directHistoryMutationSelfApprovalAndPostSubmitThresholdAreRejectedByPostgreSql() throws Exception {
        Fixture fixture = approvedPolicy("pg-policy-history", "PG_HISTORY_POLICY");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE merchant_risk_policy_versions SET display_name = 'rewritten' WHERE id = ?
                """, fixture.policyId())).hasMessageContaining("merchant risk configuration history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM merchant_risk_policy_thresholds WHERE policy_version_id = ?
                """, fixture.policyId())).hasMessageContaining("merchant risk configuration history is immutable");

        UUID selfPolicy = createPolicy(fixture.creator(), "PG_SELF_POLICY", "pg-self-policy");
        createThreshold(fixture.creator(), selfPolicy, "SELF_AMOUNT", "pg-self-threshold");
        transitionPolicy(fixture.creator(), selfPolicy, "submit", "pg-self-submit", 200);
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO merchant_risk_policy_events
                    (id, merchant_id, policy_version_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, 'APPROVED', ?, ?, ?)
                """, UUID.randomUUID(), fixture.merchant().id(), selfPolicy, fixture.creator().id(),
                Timestamp.from(now), Timestamp.from(now)))
                .hasMessageContaining("creator cannot approve");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO merchant_risk_policy_thresholds
                    (id, merchant_id, policy_version_id, threshold_code, feature_definition_id,
                     feature_code, feature_version, value_type, operator, long_operand,
                     boolean_operand, string_operands_json, candidate_action, priority,
                     reason_code, currency_scope, created_by_operator_id, created_at)
                SELECT ?, ?, ?, 'LATE_THRESHOLD', id, feature_code, feature_version, value_type,
                       'GTE', 100, NULL, NULL, 'BLOCK', 100,
                       'MERCHANT_AMOUNT_THRESHOLD', NULL, ?, ?
                  FROM feature_definitions WHERE feature_code = 'payment.amount_minor'
                """, UUID.randomUUID(), fixture.merchant().id(), selfPolicy, fixture.creator().id(),
                Timestamp.from(now))).hasMessageContaining("immutable after submission");
    }

    @Test
    void concurrentDirectActivationsSerializeOnGuardAndExactlyOneCommits() throws Exception {
        Fixture first = approvedPolicy("pg-policy-race", "PG_RACE_ONE");
        UUID secondPolicy = createPolicy(first.creator(), "PG_RACE_TWO", "pg-race-two");
        createThreshold(first.creator(), secondPolicy, "RACE_TWO_AMOUNT", "pg-race-two-threshold");
        transitionPolicy(first.creator(), secondPolicy, "submit", "pg-race-two-submit", 200);
        transitionPolicy(first.approver(), secondPolicy, "approve", "pg-race-two-approve", 200);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Instant now = Instant.now();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            insertPolicyEvent(winner, first.merchant().id(), first.policyId(), first.approver().id(),
                    "ACTIVATED", now);
            Future<Boolean> competing = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    insertPolicyEvent(connection, first.merchant().id(), secondPolicy, first.approver().id(),
                            "ACTIVATED", now);
                    return true;
                }
            });
            awaitPostgreSqlLockWaiters(1);
            assertThat(activePolicyCount(first.merchant().id())).isZero();
            winner.commit();
            assertThatThrownBy(() -> competing.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasStackTraceContaining("already has an active risk policy");
            assertThat(activePolicyCount(first.merchant().id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedReplacementActivationRollsBackAutomaticRetirementAndKeepsPriorPolicyActive() throws Exception {
        Fixture first = approvedPolicy("pg-policy-rollback", "PG_ROLLBACK_ONE");
        transitionPolicy(first.approver(), first.policyId(), "activate", "pg-rollback-one-activate", 200);
        UUID replacement = createPolicy(first.creator(), "PG_ROLLBACK_TWO", "pg-rollback-two");
        createThreshold(first.creator(), replacement, "ROLLBACK_TWO_AMOUNT", "pg-rollback-two-threshold");
        transitionPolicy(first.creator(), replacement, "submit", "pg-rollback-two-submit", 200);
        transitionPolicy(first.approver(), replacement, "approve", "pg-rollback-two-approve", 200);
        jdbcTemplate.execute("ALTER TABLE merchant_risk_policy_events ADD CONSTRAINT ck_ch31_reject_target "
                + "CHECK (event_type <> 'ACTIVATED' OR policy_version_id <> '" + replacement + "'::uuid) NOT VALID");
        try {
            transitionPolicy(first.approver(), replacement, "activate", "pg-rollback-two-activate", 409);
            assertThat(activePolicyCount(first.merchant().id())).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM merchant_risk_policy_events
                     WHERE policy_version_id = ? AND event_type = 'RETIRED'
                    """, Integer.class, first.policyId())).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM merchant_risk_policy_events
                     WHERE policy_version_id = ? AND event_type = 'ACTIVATED'
                    """, Integer.class, replacement)).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE merchant_risk_policy_events DROP CONSTRAINT ck_ch31_reject_target");
        }
    }

    @Test
    void activationThatOwnsExclusiveMerchantLockCommitsBeforeWaitingConfirm() throws Exception {
        Fixture fixture = approvedPolicy("pg-policy-activation-first", "PG_ACTIVATION_FIRST");
        PaymentFixture payment = paymentFixture(fixture.merchant(), "activation-first");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection activation = dataSource.getConnection(); PreparedStatement merchantLock =
                activation.prepareStatement("SELECT id FROM merchants WHERE id = ? FOR UPDATE")) {
            activation.setAutoCommit(false);
            merchantLock.setObject(1, fixture.merchant().id());
            merchantLock.executeQuery();
            insertPolicyEvent(activation, fixture.merchant().id(), fixture.policyId(), fixture.approver().id(),
                    "ACTIVATED", Instant.now());
            Future<JsonNode> confirm = executor.submit(() -> confirm(fixture.merchant(), payment,
                    "activation-first-confirm"));
            awaitPostgreSqlLockWaiters(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();
            activation.commit();
            JsonNode result = confirm.get(10, TimeUnit.SECONDS);
            assertThat(result.get("status").asText()).isEqualTo("BLOCKED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM risk_control_evaluations
                     WHERE fraud_decision_id = ? AND policy_version_id = ? AND matched = TRUE
                    """, Integer.class, UUID.fromString(result.get("fraudDecisionId").asText()),
                    fixture.policyId())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmThatOwnsSharedMerchantLockCommitsDefaultSnapshotBeforeWaitingActivation() throws Exception {
        Fixture fixture = approvedPolicy("pg-policy-confirm-first", "PG_CONFIRM_FIRST");
        PaymentFixture payment = paymentFixture(fixture.merchant(), "confirm-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection decisionBlocker = dataSource.getConnection(); PreparedStatement lock =
                decisionBlocker.prepareStatement("LOCK TABLE fraud_decisions IN ACCESS EXCLUSIVE MODE")) {
            decisionBlocker.setAutoCommit(false);
            lock.execute();
            Future<JsonNode> confirm = executor.submit(() -> confirm(fixture.merchant(), payment,
                    "confirm-first-confirm"));
            awaitPostgreSqlLockWaiters(1);
            Future<MvcResult> activation = executor.submit(() -> transitionPolicyResult(
                    fixture.approver(), fixture.policyId(), "activate", "confirm-first-activate"));
            awaitPostgreSqlLockWaiters(2);
            decisionBlocker.commit();
            JsonNode result = confirm.get(10, TimeUnit.SECONDS);
            assertThat(result.get("status").asText()).isNotEqualTo("BLOCKED");
            assertThat(activation.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            UUID decisionId = UUID.fromString(result.get("fraudDecisionId").asText());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM risk_control_evaluations
                     WHERE fraud_decision_id = ? AND control_type = 'POLICY_THRESHOLD'
                    """, Integer.class, decisionId)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT configuration_status FROM risk_control_evaluations
                     WHERE fraud_decision_id = ? AND control_type = 'PROFILE'
                    """, String.class, decisionId)).isEqualTo("APPLICATION_DEFAULT");
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture approvedPolicy(String prefix, String code) throws Exception {
        MerchantAccess merchant = createMerchant(prefix);
        OperatorAccess creator = createOperator(merchant, prefix + "-creator");
        OperatorAccess approver = createOperator(merchant, prefix + "-approver");
        UUID policy = createPolicy(creator, code, prefix + "-policy");
        createThreshold(creator, policy, code + "_AMOUNT", prefix + "-threshold");
        transitionPolicy(creator, policy, "submit", prefix + "-submit", 200);
        transitionPolicy(approver, policy, "approve", prefix + "-approve", 200);
        return new Fixture(merchant, creator, approver, policy);
    }

    private int activePolicyCount(UUID merchantId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_risk_policy_versions policy
                 WHERE policy.merchant_id = ?
                   AND EXISTS (SELECT 1 FROM merchant_risk_policy_events activation
                        WHERE activation.merchant_id = policy.merchant_id
                          AND activation.policy_version_id = policy.id
                          AND activation.event_type = 'ACTIVATED')
                   AND NOT EXISTS (SELECT 1 FROM merchant_risk_policy_events retirement
                        WHERE retirement.merchant_id = policy.merchant_id
                          AND retirement.policy_version_id = policy.id
                          AND retirement.event_type = 'RETIRED')
                """, Integer.class, merchantId);
    }

    private void insertPolicyEvent(Connection connection, UUID merchantId, UUID policyId, UUID actorId,
            String eventType, Instant occurredAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO merchant_risk_policy_events
                    (id, merchant_id, policy_version_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, merchantId);
            insert.setObject(3, policyId);
            insert.setString(4, eventType);
            insert.setObject(5, actorId);
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.setTimestamp(7, Timestamp.from(occurredAt));
            insert.executeUpdate();
        }
    }

    private void awaitPostgreSqlLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND pid <> pg_backend_pid()
                       AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiters != null && waiters >= expected) {
                return;
            }
            Thread.sleep(20L);
        }
        List<String> activity = jdbcTemplate.query("""
                SELECT state || '|' || COALESCE(wait_event_type, '-') || '|' || LEFT(query, 180)
                  FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()
                 ORDER BY pid
                """, (resultSet, rowNumber) -> resultSet.getString(1));
        throw new AssertionError("Expected " + expected + " PostgreSQL lock waiters; activity=" + activity);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()), response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"RISK_MANAGER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(UUID.fromString(response.get("id").asText()), response.get("credential").asText());
    }

    private UUID createPolicy(OperatorAccess operator, String code, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/policies")
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"" + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private void createThreshold(OperatorAccess operator, UUID policyId, String code, String key) throws Exception {
        mockMvc.perform(post("/risk/policies/{id}/thresholds", policyId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"code\":\"" + code
                                + "\",\"featureCode\":\"payment.amount_minor\",\"operator\":\"GTE\","
                                + "\"longValue\":100,\"candidateAction\":\"BLOCK\",\"priority\":100,"
                                + "\"reasonCode\":\"MERCHANT_AMOUNT_THRESHOLD\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode transitionPolicy(OperatorAccess operator, UUID policyId, String operation, String key,
            int expectedStatus) throws Exception {
        MvcResult result = transitionPolicyResult(operator, policyId, operation, key);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult transitionPolicyResult(OperatorAccess operator, UUID policyId, String operation, String key)
            throws Exception {
        return mockMvc.perform(post("/risk/policies/{id}/{operation}", policyId, operation)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andReturn();
    }

    private PaymentFixture paymentFixture(MerchantAccess merchant, String prefix) throws Exception {
        JsonNode customer = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + prefix + "@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID customerId = UUID.fromString(customer.get("id").asText());
        JsonNode method = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, prefix + "-method")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, prefix + "-intent")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new PaymentFixture(UUID.fromString(intent.get("id").asText()),
                UUID.fromString(method.get("id").asText()));
    }

    private JsonNode confirm(MerchantAccess merchant, PaymentFixture payment, String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", payment.intentId())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + payment.methodId() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record Fixture(MerchantAccess merchant, OperatorAccess creator, OperatorAccess approver,
            UUID policyId) { }
    private record PaymentFixture(UUID intentId, UUID methodId) { }
}
