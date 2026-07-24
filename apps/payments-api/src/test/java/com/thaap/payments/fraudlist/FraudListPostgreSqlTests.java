package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import org.springframework.dao.DataAccessException;
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
class FraudListPostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String CARD_NUMBER = "4242424242424242";

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
    static void postgresProperties(DynamicPropertyRegistry registry) {
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
    void publicMatchPersistsProductionEnforcedImmutableDecisionEvidence() throws Exception {
        MerchantAccess merchant = createMerchant("pg-fraud-list");
        OperatorAccess manager = createOperator(merchant, "pg-manager");
        UUID listId = createCardList(manager, "PG_BAD_CARD");
        UUID entryId = createCardEntry(manager, listId, "pg-card-entry");
        UUID customerId = createCustomer(merchant);
        UUID methodId = createPaymentMethod(merchant, customerId);
        UUID intentId = createIntent(merchant, customerId);
        JsonNode attempt = confirm(merchant, intentId, methodId);
        assertThat(attempt.get("status").asText()).isEqualTo("BLOCKED");
        UUID attemptId = UUID.fromString(attempt.get("id").asText());
        UUID evaluationId = jdbcTemplate.queryForObject("""
                SELECT id FROM fraud_list_evaluations
                 WHERE merchant_id = ? AND payment_attempt_id = ? AND matched_entry_id = ?
                """, UUID.class, merchant.id(), attemptId, entryId);
        assertThat(evaluationId).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fraud_list_evaluations SET reason_code = 'BOT_ACTIVITY' WHERE id = ?", evaluationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fraud-list history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM fraud_list_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fraud-list history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fraud_lists SET display_name = 'rewritten' WHERE id = ?", listId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fraud-list history is immutable");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal AND tgname LIKE 'trg_fraud_list%'
                """, Integer.class)).isEqualTo(6);
    }

    @Test
    void compositeKeysRejectCrossTenantEntryCreatorAndLifecycleSubstitution() throws Exception {
        MerchantAccess owner = createMerchant("pg-list-owner");
        MerchantAccess foreign = createMerchant("pg-list-foreign");
        OperatorAccess ownerManager = createOperator(owner, "owner-manager");
        OperatorAccess foreignManager = createOperator(foreign, "foreign-manager");
        UUID listId = createCardList(ownerManager, "OWNER_CARD_LIST");
        UUID versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM fraud_list_versions WHERE merchant_id = ? AND fraud_list_id = ?",
                UUID.class, owner.id(), listId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fraud_list_entries
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, identity_type,
                     canonical_value, value_hmac, hmac_key_version, action, reason_code,
                     effective_from, effective_until, created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, 'CARD_FINGERPRINT', NULL, ?, 'v1', 'BLOCK', 'KNOWN_FRAUD',
                        ?, NULL, ?, ?)
                """, UUID.randomUUID(), owner.id(), listId, versionId, "a".repeat(64),
                Instant.now(), foreignManager.id(), Instant.now()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fraud_list_lifecycle_events
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, fraud_list_entry_id,
                     lifecycle_target_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'VERSION_DEACTIVATED', ?, ?, ?)
                """, UUID.randomUUID(), foreign.id(), listId, versionId, versionId,
                foreignManager.id(), Instant.now(), Instant.now()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentDistinctKeyDeactivationSerializesAtMerchantLockAndCommitsOneLifecycleEvent()
            throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-concurrency");
        OperatorAccess manager = createOperator(merchant, "concurrency-manager");
        UUID listId = createCardList(manager, "CONCURRENT_CARD_LIST");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection();
                PreparedStatement lock = blocker.prepareStatement("SELECT id FROM merchants WHERE id = ? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setObject(1, merchant.id());
            lock.executeQuery();

            Future<MvcResult> first = executor.submit(() -> deactivateList(manager, listId, "deactivate-a"));
            Future<MvcResult> second = executor.submit(() -> deactivateList(manager, listId, "deactivate-b"));
            awaitPostgreSqlLockWaiters(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_list_lifecycle_events
                     WHERE merchant_id = ? AND fraud_list_id = ?
                    """, Integer.class, merchant.id(), listId)).isOne();

            blocker.commit();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).stream().sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_list_lifecycle_events
                     WHERE merchant_id = ? AND fraud_list_id = ?
                    """, Integer.class, merchant.id(), listId)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void committedOperatorDisableWinsWhenItHoldsTheAuthorizationRowBeforeMutation() throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-disable-first");
        OperatorAccess manager = createOperator(merchant, "disable-first-manager");
        UUID listId = createCardList(manager, "DISABLE_FIRST_LIST");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection disabling = dataSource.getConnection(); PreparedStatement update = disabling.prepareStatement("""
                UPDATE risk_operators
                   SET status = 'DISABLED', lifecycle_epoch = 2, disabled_at = ?
                 WHERE merchant_id = ? AND id = ?
                """)) {
            disabling.setAutoCommit(false);
            update.setTimestamp(1, Timestamp.from(Instant.now()));
            update.setObject(2, merchant.id());
            update.setObject(3, manager.id());
            assertThat(update.executeUpdate()).isOne();

            Future<MvcResult> mutation = executor.submit(
                    () -> deactivateList(manager, listId, "disable-first-mutation"));
            awaitPostgreSqlLockWaiters(1);
            assertThat(lifecycleCount(merchant.id(), listId)).isOne();

            disabling.commit();
            assertThat(mutation.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
            assertThat(lifecycleCount(merchant.id(), listId)).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM risk_operators WHERE merchant_id = ? AND id = ?",
                    String.class, merchant.id(), manager.id())).isEqualTo("DISABLED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mutationThatLockedAuthorizationFirstCommitsBeforeMerchantDisable() throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-mutation-first");
        OperatorAccess manager = createOperator(merchant, "mutation-first-manager");
        UUID listId = createCardList(manager, "MUTATION_FIRST_LIST");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection(); PreparedStatement lock = blocker.prepareStatement(
                "SELECT id FROM fraud_lists WHERE merchant_id = ? AND id = ? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setObject(1, merchant.id());
            lock.setObject(2, listId);
            lock.executeQuery();

            Future<MvcResult> mutation = executor.submit(
                    () -> deactivateList(manager, listId, "mutation-first-deactivate"));
            awaitPostgreSqlLockWaiters(1);
            Future<MvcResult> disable = executor.submit(
                    () -> disableOperator(merchant, manager.id(), "mutation-first-disable"));
            awaitPostgreSqlLockWaiters(2);
            assertThat(lifecycleCount(merchant.id(), listId)).isOne();

            blocker.commit();
            assertThat(mutation.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(disable.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(lifecycleCount(merchant.id(), listId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM risk_operators WHERE merchant_id = ? AND id = ?",
                    String.class, merchant.id(), manager.id())).isEqualTo("DISABLED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDirectVersionActivationsSerializeAtParentAndOnlyOneCanCommit() throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-direct-activation-race");
        OperatorAccess manager = createOperator(merchant, "activation-race-manager");
        UUID listId = createCardList(manager, "ACTIVATION_RACE_LIST");
        UUID firstVersionId = jdbcTemplate.queryForObject(
                "SELECT id FROM fraud_list_versions WHERE merchant_id = ? AND fraud_list_id = ?",
                UUID.class, merchant.id(), listId);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO fraud_list_lifecycle_events
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, fraud_list_entry_id,
                     lifecycle_target_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'VERSION_DEACTIVATED', ?, ?, ?)
                """, UUID.randomUUID(), merchant.id(), listId, firstVersionId, firstVersionId,
                manager.id(), Timestamp.from(now), Timestamp.from(now));
        UUID versionTwo = insertVersion(merchant.id(), manager.id(), listId, 2, now);
        UUID versionThree = insertVersion(merchant.id(), manager.id(), listId, 3, now);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            insertActivation(first, merchant.id(), manager.id(), listId, versionTwo, now);
            Future<Boolean> competing = executor.submit(() -> {
                try (Connection second = dataSource.getConnection()) {
                    insertActivation(second, merchant.id(), manager.id(), listId, versionThree, now);
                    return true;
                }
            });
            awaitPostgreSqlLockWaiters(1);
            assertThat(lifecycleCount(merchant.id(), listId)).isEqualTo(2);

            first.commit();
            assertThatThrownBy(() -> competing.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasStackTraceContaining("fraud list already has an active version");
            assertThat(lifecycleCount(merchant.id(), listId)).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_list_versions version
                     WHERE version.merchant_id = ? AND version.fraud_list_id = ?
                       AND EXISTS (
                           SELECT 1 FROM fraud_list_lifecycle_events activation
                            WHERE activation.merchant_id = version.merchant_id
                              AND activation.fraud_list_version_id = version.id
                              AND activation.event_type = 'VERSION_ACTIVATED')
                       AND NOT EXISTS (
                           SELECT 1 FROM fraud_list_lifecycle_events deactivation
                            WHERE deactivation.merchant_id = version.merchant_id
                              AND deactivation.fraud_list_version_id = version.id
                              AND deactivation.event_type = 'VERSION_DEACTIVATED')
                    """, Integer.class, merchant.id(), listId)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deactivationThatOwnsMerchantControlLockCommitsBeforeWaitingConfirmEvaluation()
            throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-snapshot");
        OperatorAccess manager = createOperator(merchant, "snapshot-manager");
        UUID listId = createCardList(manager, "SNAPSHOT_CARD_LIST");
        createCardEntry(manager, listId, "snapshot-entry");
        UUID versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM fraud_list_versions WHERE merchant_id = ? AND fraud_list_id = ?",
                UUID.class, merchant.id(), listId);
        UUID customerId = createCustomer(merchant);
        UUID methodId = createPaymentMethod(merchant, customerId);
        UUID intentId = createIntent(merchant, customerId);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lifecycle = dataSource.getConnection();
                PreparedStatement merchantLock = lifecycle.prepareStatement(
                        "SELECT id FROM merchants WHERE id = ? FOR UPDATE");
                PreparedStatement insert = lifecycle.prepareStatement("""
                INSERT INTO fraud_list_lifecycle_events
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, fraud_list_entry_id,
                     lifecycle_target_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'VERSION_DEACTIVATED', ?, ?, ?)
                """)) {
            lifecycle.setAutoCommit(false);
            merchantLock.setObject(1, merchant.id());
            merchantLock.executeQuery();
            Instant now = Instant.now();
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, merchant.id());
            insert.setObject(3, listId);
            insert.setObject(4, versionId);
            insert.setObject(5, versionId);
            insert.setObject(6, manager.id());
            insert.setTimestamp(7, Timestamp.from(now));
            insert.setTimestamp(8, Timestamp.from(now));
            insert.executeUpdate();

            Future<JsonNode> confirm = executor.submit(
                    () -> confirm(merchant, intentId, methodId, "snapshot-confirm-after-deactivation"));
            awaitPostgreSqlLockWaiters(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();
            lifecycle.commit();

            JsonNode afterCommit = confirm.get(10, TimeUnit.SECONDS);
            assertThat(afterCommit.get("status").asText()).isNotEqualTo("BLOCKED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_list_evaluations evaluation
                     WHERE evaluation.merchant_id = ? AND evaluation.fraud_list_id = ?
                    """, Integer.class, merchant.id(), listId)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmThatOwnsSharedControlLockCommitsItsSnapshotBeforeWaitingDeactivation() throws Exception {
        MerchantAccess merchant = createMerchant("pg-list-confirm-first");
        OperatorAccess manager = createOperator(merchant, "confirm-first-manager");
        UUID listId = createCardList(manager, "CONFIRM_FIRST_LIST");
        UUID customerId = createCustomer(merchant);
        UUID methodId = createPaymentMethod(merchant, customerId);
        UUID intentId = createIntent(merchant, customerId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection decisionBlocker = dataSource.getConnection(); PreparedStatement lock =
                decisionBlocker.prepareStatement("LOCK TABLE fraud_decisions IN ACCESS EXCLUSIVE MODE")) {
            decisionBlocker.setAutoCommit(false);
            lock.execute();

            Future<JsonNode> confirm = executor.submit(
                    () -> confirm(merchant, intentId, methodId, "confirm-first-confirm"));
            awaitPostgreSqlLockWaiters(1);
            Future<MvcResult> deactivate = executor.submit(
                    () -> deactivateList(manager, listId, "confirm-first-deactivate"));
            awaitPostgreSqlLockWaiters(2);
            assertThat(lifecycleCount(merchant.id(), listId)).isOne();

            decisionBlocker.commit();
            JsonNode attempt = confirm.get(10, TimeUnit.SECONDS);
            assertThat(attempt.get("status").asText()).isNotEqualTo("BLOCKED");
            assertThat(deactivate.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(lifecycleCount(merchant.id(), listId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_list_evaluations evaluation
                     WHERE evaluation.merchant_id = ? AND evaluation.fraud_list_id = ?
                       AND evaluation.matched = FALSE
                    """, Integer.class, merchant.id(), listId)).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT evaluation.evaluated_at <= event.occurred_at
                      FROM fraud_list_evaluations evaluation
                      JOIN fraud_list_lifecycle_events event
                        ON event.merchant_id = evaluation.merchant_id
                       AND event.fraud_list_id = evaluation.fraud_list_id
                       AND event.event_type = 'VERSION_DEACTIVATED'
                     WHERE evaluation.merchant_id = ? AND evaluation.fraud_list_id = ?
                    """, Boolean.class, merchant.id(), listId)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult deactivateList(OperatorAccess manager, UUID listId, String key) throws Exception {
        return mockMvc.perform(post("/risk/fraud-lists/{id}/deactivate", listId)
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andReturn();
    }

    private MvcResult disableOperator(MerchantAccess merchant, UUID operatorId, String key) throws Exception {
        return mockMvc.perform(post("/risk/operators/{id}/disable", operatorId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key))
                .andReturn();
    }

    private UUID insertVersion(UUID merchantId, UUID operatorId, UUID listId, int versionNumber,
            Instant effectiveFrom) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fraud_list_versions
                    (id, merchant_id, fraud_list_id, version_number, effective_from,
                     created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, versionId, merchantId, listId, versionNumber, Timestamp.from(effectiveFrom), operatorId,
                Timestamp.from(effectiveFrom));
        return versionId;
    }

    private void insertActivation(Connection connection, UUID merchantId, UUID operatorId,
            UUID listId, UUID versionId, Instant occurredAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fraud_list_lifecycle_events
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, fraud_list_entry_id,
                     lifecycle_target_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'VERSION_ACTIVATED', ?, ?, ?)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, merchantId);
            insert.setObject(3, listId);
            insert.setObject(4, versionId);
            insert.setObject(5, versionId);
            insert.setObject(6, operatorId);
            insert.setTimestamp(7, Timestamp.from(occurredAt));
            insert.setTimestamp(8, Timestamp.from(occurredAt));
            insert.executeUpdate();
        }
    }

    private int lifecycleCount(UUID merchantId, UUID listId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_list_lifecycle_events
                 WHERE merchant_id = ? AND fraud_list_id = ?
                """, Integer.class, merchantId, listId);
    }

    private void awaitPostgreSqlLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database()
                       AND pid <> pg_backend_pid()
                       AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiters != null && waiters >= expected) {
                return;
            }
            Thread.sleep(20L);
        }
        List<String> activity = jdbcTemplate.query("""
                SELECT state || '|' || COALESCE(wait_event_type, '-') || '|' ||
                       COALESCE(wait_event, '-') || '|' || LEFT(query, 160)
                  FROM pg_stat_activity
                 WHERE datname = current_database()
                   AND pid <> pg_backend_pid()
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

    private UUID createCardList(OperatorAccess manager, String code) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/fraud-lists")
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, code + "-create")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"%s","displayName":"PostgreSQL card list",
                                 "identityType":"CARD_FINGERPRINT"}
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createCardEntry(OperatorAccess manager, UUID listId, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/fraud-lists/{id}/entries", listId)
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"value":"%s","action":"BLOCK","reasonCode":"KNOWN_FRAUD"}
                                """.formatted(CARD_NUMBER)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createCustomer(MerchantAccess merchant) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "pg-customer")
                        .contentType(APPLICATION_JSON).content("{\"email\":\"pg@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "pg-method")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"%s"}
                                """.formatted(customerId, CARD_NUMBER)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createIntent(MerchantAccess merchant, UUID customerId) throws Exception {
        return createIntent(merchant, customerId, "pg-intent");
    }

    private UUID createIntent(MerchantAccess merchant, UUID customerId, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":1000,"currency":"USD"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId) throws Exception {
        return confirm(merchant, intentId, methodId, "pg-confirm");
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId, String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
}
