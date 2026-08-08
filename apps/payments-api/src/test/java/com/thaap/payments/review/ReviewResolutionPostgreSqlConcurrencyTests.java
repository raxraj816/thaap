package com.thaap.payments.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperatorService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import com.thaap.payments.workspace.ReviewNote;
import com.thaap.payments.workspace.ReviewNoteCipher;
import com.thaap.payments.workspace.ReviewNoteRepository;
import com.thaap.payments.workspace.ReviewNoteService;
import com.thaap.payments.workspace.AnalystQueueService;
import com.thaap.payments.workspace.ReviewQueueColumn;
import com.thaap.payments.workspace.ReviewQueueSort;
import com.thaap.payments.workspace.ReviewSavedSearchService;
import com.thaap.payments.workspace.SavedSearchVisibility;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class ReviewResolutionPostgreSqlConcurrencyTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private ReviewOperationsService reviewOperationsService;
    @Autowired private RiskOperatorService riskOperatorService;
    @Autowired private ReviewNoteService reviewNoteService;
    @Autowired private ReviewNoteRepository reviewNoteRepository;
    @Autowired private ReviewNoteCipher reviewNoteCipher;
    @Autowired private ReviewSavedSearchService reviewSavedSearchService;
    @Autowired private AnalystQueueService analystQueueService;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void forcedAnalystFirstResolutionMakesMerchantCancellationWaitAndLose() throws Exception {
        Fixture fixture = createFixture("analyst-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "analyst-first-assign");
        CountDownLatch resolutionOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseResolutionCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> resolution = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewOperationsService.resolve(fixture.analyst().context(), fixture.review().reviewCaseId(),
                            ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD,
                            ReviewClassification.CONFIRMED_FRAUD, FraudType.CARD_TESTING,
                            ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED,
                            DatabaseInstant.now().minusSeconds(5), "analyst-first-resolution");
                    resolutionOwnsPaymentLock.countDown();
                    await(releaseResolutionCommit);
                });
                return null;
            });

            assertThat(resolutionOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<MvcResult> merchantCancellation = executor.submit(() -> mockMvc.perform(
                            post("/payment_intents/{id}/cancel", fixture.review().paymentIntentId())
                                    .header(API_KEY, fixture.merchant().apiKey())
                                    .header(IDEMPOTENCY_KEY, "analyst-first-merchant-cancel"))
                    .andReturn());
            awaitDatabaseLockWait("payment_intents");

            releaseResolutionCommit.countDown();
            resolution.get(5, TimeUnit.SECONDS);
            assertThat(merchantCancellation.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }

        assertTerminalTruth(fixture.review(), "RESOLVED", 1, 1);
    }

    @Test
    void forcedMerchantFirstCancellationMakesAnalystResolutionWaitAndLose() throws Exception {
        Fixture fixture = createFixture("merchant-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "merchant-first-assign");
        CountDownLatch cancellationOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseCancellationCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    try {
                        mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.review().paymentIntentId())
                                        .header(API_KEY, fixture.merchant().apiKey())
                                        .header(IDEMPOTENCY_KEY, "merchant-first-cancel"))
                                .andExpect(status().isOk());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    cancellationOwnsPaymentLock.countDown();
                    await(releaseCancellationCommit);
                });
                return null;
            });

            assertThat(cancellationOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<?> resolution = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.resolve(
                            fixture.analyst().context(), fixture.review().reviewCaseId(),
                            ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD,
                            ReviewClassification.CONFIRMED_FRAUD, FraudType.CARD_TESTING,
                            ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED,
                            DatabaseInstant.now().minusSeconds(5), "merchant-first-resolution")));
            awaitDatabaseLockWait("payment_intents");

            releaseCancellationCommit.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            assertConflict(resolution);
        }

        assertTerminalTruth(fixture.review(), "CANCELED", 0, 0);
    }

    @Test
    void forcedReviewApprovalFirstMakesMerchantCancellationWaitAndPreservesProcessorTruth() throws Exception {
        Fixture fixture = createApprovedFixture("review_approval-approval-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "review_approval-approval-first-assign");
        CountDownLatch approvalOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseApprovalCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> approval = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewOperationsService.resolve(fixture.analyst().context(), fixture.review().reviewCaseId(),
                            ReviewResolutionOutcome.APPROVE, ReviewClassification.INCONCLUSIVE, null,
                            ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED,
                            DatabaseInstant.now().minusSeconds(5), "review_approval-approval-first");
                    approvalOwnsPaymentLock.countDown();
                    await(releaseApprovalCommit);
                });
                return null;
            });

            assertThat(approvalOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<MvcResult> cancellation = executor.submit(() -> mockMvc.perform(
                            post("/payment_intents/{id}/cancel", fixture.review().paymentIntentId())
                                    .header(API_KEY, fixture.merchant().apiKey())
                                    .header(IDEMPOTENCY_KEY, "review_approval-approval-first-cancel"))
                    .andReturn());
            awaitDatabaseLockWait("payment_intents");

            releaseApprovalCommit.countDown();
            approval.get(5, TimeUnit.SECONDS);
            assertThat(cancellation.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, fixture.review().paymentIntentId())).isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, fixture.review().paymentAttemptId())).isEqualTo("AUTHORIZED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, fixture.review().reviewCaseId())).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM review_resolutions",
                String.class)).isEqualTo("APPROVE");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, fixture.review().fraudDecisionId())).isEqualTo("REVIEW");
        assertThat(count("authorizations")).isEqualTo(3);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(count("fraud_labels")).isZero();
    }

    @Test
    void forcedMerchantCancellationFirstMakesReviewApprovalWaitAndNeverCallsProcessor() throws Exception {
        Fixture fixture = createApprovedFixture("review_approval-cancel-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "review_approval-cancel-first-assign");
        CountDownLatch cancellationOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseCancellationCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    try {
                        mockMvc.perform(post("/payment_intents/{id}/cancel", fixture.review().paymentIntentId())
                                        .header(API_KEY, fixture.merchant().apiKey())
                                        .header(IDEMPOTENCY_KEY, "review_approval-cancel-first"))
                                .andExpect(status().isOk());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    cancellationOwnsPaymentLock.countDown();
                    await(releaseCancellationCommit);
                });
                return null;
            });

            assertThat(cancellationOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<?> approval = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.resolve(
                            fixture.analyst().context(), fixture.review().reviewCaseId(),
                            ReviewResolutionOutcome.APPROVE, ReviewClassification.INCONCLUSIVE, null,
                            ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED,
                            DatabaseInstant.now().minusSeconds(5), "review_approval-cancel-first-approval")));
            awaitDatabaseLockWait("payment_intents");

            releaseCancellationCommit.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            assertConflict(approval);
        }

        assertTerminalTruth(fixture.review(), "CANCELED", 0, 0);
        assertThat(count("authorizations")).isEqualTo(2);
    }

    @Test
    void forcedCompetingClaimsConvergeToOneOwnerAndOneAssignmentEvent() throws Exception {
        Fixture fixture = createFixture("competing-claims");
        OperatorAccess competitor = createOperator(fixture.merchant(), "competing-second", "ANALYST");
        CountDownLatch firstClaimOwnsCaseLock = new CountDownLatch(1);
        CountDownLatch releaseFirstClaim = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstClaim = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewOperationsService.assign(fixture.analyst().context(), fixture.review().reviewCaseId(),
                            null, 0L, "first-claim");
                    firstClaimOwnsCaseLock.countDown();
                    await(releaseFirstClaim);
                });
                return null;
            });
            assertThat(firstClaimOwnsCaseLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("manual_review_cases", fixture.review().reviewCaseId());
            Future<?> secondClaim = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.assign(competitor.context(),
                            fixture.review().reviewCaseId(), null, 0L, "second-claim")));
            awaitDatabaseLockWait("manual_review_cases");

            releaseFirstClaim.countDown();
            firstClaim.get(5, TimeUnit.SECONDS);
            assertNotFound(secondClaim);
        }

        Map<String, Object> review = jdbcTemplate.queryForMap("""
                SELECT status, assigned_operator_id, assignment_version
                FROM manual_review_cases WHERE id = ?
                """, fixture.review().reviewCaseId());
        assertThat(review.get("status")).isEqualTo("ASSIGNED");
        assertThat(review.get("assigned_operator_id")).isEqualTo(fixture.analyst().id());
        assertThat(((Number) review.get("assignment_version")).longValue()).isEqualTo(1L);
        assertThat(count("review_assignment_events")).isOne();
    }

    @Test
    void forcedManagerReassignmentMakesStaleAnalystReleaseWaitAndFailWithoutClearingNewOwner()
            throws Exception {
        Fixture fixture = createFixture("reassign-release");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "reassign-release-initial");
        OperatorAccess manager = createOperator(fixture.merchant(), "reassign-release-manager", "RISK_MANAGER");
        CountDownLatch reassignmentOwnsCaseLock = new CountDownLatch(1);
        CountDownLatch releaseReassignment = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> reassignment = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewOperationsService.assign(manager.context(), fixture.review().reviewCaseId(),
                            manager.id(), 1L, "forced-manager-reassignment");
                    reassignmentOwnsCaseLock.countDown();
                    await(releaseReassignment);
                });
                return null;
            });
            assertThat(reassignmentOwnsCaseLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("manual_review_cases", fixture.review().reviewCaseId());
            Future<?> staleRelease = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.release(fixture.analyst().context(),
                            fixture.review().reviewCaseId(), 1L, "stale-analyst-release")));
            awaitDatabaseLockWait("manual_review_cases");

            releaseReassignment.countDown();
            reassignment.get(5, TimeUnit.SECONDS);
            assertNotFound(staleRelease);
        }

        Map<String, Object> review = jdbcTemplate.queryForMap("""
                SELECT status, assigned_operator_id, assignment_version
                FROM manual_review_cases WHERE id = ?
                """, fixture.review().reviewCaseId());
        assertThat(review.get("status")).isEqualTo("ASSIGNED");
        assertThat(review.get("assigned_operator_id")).isEqualTo(manager.id());
        assertThat(((Number) review.get("assignment_version")).longValue()).isEqualTo(2L);
        assertThat(count("review_assignment_events")).isEqualTo(2);
    }

    @Test
    void forcedRetentionFirstMakesManagerRedactionWaitAndConvergesToOneExpiredEvent()
            throws Exception {
        Fixture fixture = createFixture("workspace-retention-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "workspace-retention-first-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "workspace-retention-first-manager", "RISK_MANAGER");
        UUID noteId = createDueReviewNote(fixture);
        CountDownLatch retentionOwnsNoteLock = new CountDownLatch(1);
        CountDownLatch releaseRetention = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> retention = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(transactionStatus -> {
                        int expired = reviewNoteService.expireDueNotes();
                        retentionOwnsNoteLock.countDown();
                        await(releaseRetention);
                        return expired;
                    }));
            assertThat(retentionOwnsNoteLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("review_notes", noteId);
            Future<ReviewNoteService.ReviewNoteView> redaction = executor.submit(() ->
                    reviewNoteService.redact(manager.context(), fixture.review().reviewCaseId(),
                            noteId, "workspace-retention-first-redaction"));
            awaitDatabaseLockWait("review_notes");

            releaseRetention.countDown();
            assertThat(retention.get(5, TimeUnit.SECONDS)).isOne();
            assertThat(redaction.get(5, TimeUnit.SECONDS).lifecycleStatus().name())
                    .isEqualTo("EXPIRED");
        }

        assertTerminalNote(noteId, "EXPIRED", "RETENTION_EXPIRED");
    }

    @Test
    void forcedManagerRedactionFirstMakesRetentionSkipLockedAndConvergesToOneRedactedEvent()
            throws Exception {
        Fixture fixture = createFixture("workspace-redaction-first");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "workspace-redaction-first-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "workspace-redaction-first-manager", "RISK_MANAGER");
        UUID noteId = createDueReviewNote(fixture);
        CountDownLatch redactionOwnsNoteLock = new CountDownLatch(1);
        CountDownLatch releaseRedaction = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> redaction = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewNoteService.redact(manager.context(), fixture.review().reviewCaseId(),
                            noteId, "workspace-redaction-first");
                    redactionOwnsNoteLock.countDown();
                    await(releaseRedaction);
                });
                return null;
            });
            assertThat(redactionOwnsNoteLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("review_notes", noteId);
            Future<Integer> skippedRetention = executor.submit(reviewNoteService::expireDueNotes);
            assertThat(skippedRetention.get(5, TimeUnit.SECONDS)).isZero();

            releaseRedaction.countDown();
            redaction.get(5, TimeUnit.SECONDS);
        }

        assertThat(reviewNoteService.expireDueNotes()).isZero();
        assertTerminalNote(noteId, "REDACTED", "MANAGER_REDACTION");
    }

    @Test
    void forcedRedactionFirstBlocksReadUntilCiphertextIsDestroyed() throws Exception {
        Fixture fixture = createFixture("workspace-redact-read");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "workspace-redact-read-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "workspace-redact-read-manager", "RISK_MANAGER");
        ReviewNoteService.ReviewNoteView created = reviewNoteService.create(fixture.analyst().context(),
                fixture.review().reviewCaseId(), 1L, "Verified delivery-address context",
                null, "workspace-redact-read-create");
        CountDownLatch redactionOwnsCaseAndNoteLocks = new CountDownLatch(1);
        CountDownLatch releaseRedaction = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> redaction = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewNoteService.redact(manager.context(), fixture.review().reviewCaseId(),
                            created.id(), "workspace-redact-read-redaction");
                    redactionOwnsCaseAndNoteLocks.countDown();
                    await(releaseRedaction);
                });
                return null;
            });
            assertThat(redactionOwnsCaseAndNoteLocks.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("review_notes", created.id());
            Future<List<ReviewNoteService.ReviewNoteView>> read = executor.submit(() ->
                    reviewNoteService.list(fixture.analyst().context(),
                            fixture.review().reviewCaseId(), 1L,
                            "workspace-redact-read-list"));
            awaitDatabaseLockWait("manual_review_cases");

            releaseRedaction.countDown();
            redaction.get(5, TimeUnit.SECONDS);
            List<ReviewNoteService.ReviewNoteView> notes = read.get(5, TimeUnit.SECONDS);
            assertThat(notes).singleElement().satisfies(note -> {
                assertThat(note.lifecycleStatus().name()).isEqualTo("REDACTED");
                assertThat(note.content()).isNull();
            });
        }
    }

    @Test
    void forcedSavedSearchUpdateFirstMakesDisableWaitAndRejectsItsStaleVersion()
            throws Exception {
        Fixture fixture = createFixture("workspace-search-update-first");
        OperatorAccess owner = createOperator(
                fixture.merchant(), "workspace-search-update-owner", "RISK_MANAGER");
        OperatorAccess secondManager = createOperator(
                fixture.merchant(), "workspace-search-update-manager", "RISK_MANAGER");
        ReviewSavedSearchService.SavedSearchView search = createSharedSavedSearch(owner,
                "Update first queue");
        CountDownLatch updateOwnsSearchLock = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> update = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewSavedSearchService.update(owner.context(), search.id(), 1L,
                            "Updated priority queue", SavedSearchVisibility.MERCHANT_SHARED,
                            null, ReviewQueueSort.CREATED_AT_ASC,
                            Set.of(ReviewQueueColumn.CREATED_AT, ReviewQueueColumn.STATUS),
                            "workspace-search-update-first");
                    updateOwnsSearchLock.countDown();
                    await(releaseUpdate);
                });
                return null;
            });
            assertThat(updateOwnsSearchLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("review_saved_searches", search.id());
            Future<?> staleDisable = executor.submit(() -> reviewSavedSearchService.disable(
                    secondManager.context(), search.id(), 1L, "workspace-stale-disable"));
            awaitDatabaseLockWait("review_saved_searches");

            releaseUpdate.countDown();
            update.get(5, TimeUnit.SECONDS);
            assertConflict(staleDisable);
        }

        Map<String, Object> durable = jdbcTemplate.queryForMap("""
                SELECT name, lifecycle_status, lifecycle_version
                  FROM review_saved_searches WHERE id = ?
                """, search.id());
        assertThat(durable.get("name")).isEqualTo("Updated priority queue");
        assertThat(durable.get("lifecycle_status")).isEqualTo("ACTIVE");
        assertThat(((Number) durable.get("lifecycle_version")).longValue()).isEqualTo(2L);
    }

    @Test
    void forcedSavedSearchDisableFirstMakesUpdateWaitAndPreservesDisabledMeaning()
            throws Exception {
        Fixture fixture = createFixture("workspace-search-disable-first");
        OperatorAccess owner = createOperator(
                fixture.merchant(), "workspace-search-disable-owner", "RISK_MANAGER");
        OperatorAccess secondManager = createOperator(
                fixture.merchant(), "workspace-search-disable-manager", "RISK_MANAGER");
        ReviewSavedSearchService.SavedSearchView search = createSharedSavedSearch(owner,
                "Disable first queue");
        CountDownLatch disableOwnsSearchLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    reviewSavedSearchService.disable(secondManager.context(), search.id(), 1L,
                            "workspace-search-disable-first");
                    disableOwnsSearchLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsSearchLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("review_saved_searches", search.id());
            Future<?> staleUpdate = executor.submit(() -> reviewSavedSearchService.update(
                    owner.context(), search.id(), 1L, "Should not replace disabled meaning",
                    SavedSearchVisibility.MERCHANT_SHARED, null,
                    ReviewQueueSort.CREATED_AT_DESC,
                    Set.of(ReviewQueueColumn.CREATED_AT), "workspace-stale-update"));
            awaitDatabaseLockWait("review_saved_searches");

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertConflict(staleUpdate);
        }

        Map<String, Object> durable = jdbcTemplate.queryForMap("""
                SELECT name, lifecycle_status, lifecycle_version
                  FROM review_saved_searches WHERE id = ?
                """, search.id());
        assertThat(durable.get("name")).isEqualTo("Disable first queue");
        assertThat(durable.get("lifecycle_status")).isEqualTo("DISABLED");
        assertThat(((Number) durable.get("lifecycle_version")).longValue()).isEqualTo(2L);
    }

    @Test
    void equalTimestampQueuePagesHaveNoDuplicateOrOmissionOnPostgreSql() throws Exception {
        Fixture fixture = createFixture("workspace-equal-time-first");
        UUID secondCustomer = createCustomer(
                fixture.merchant(), "workspace-equal-time-second@example.com");
        UUID secondMethod = createPaymentMethod(
                fixture.merchant(), secondCustomer, "workspace-equal-time-second-card");
        ReviewFixture second = createReview(fixture.merchant(), secondCustomer, secondMethod,
                "workspace-equal-time-second");
        Instant equalCreatedAt = Instant.parse("2026-07-25T00:00:00.123456Z");
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            jdbcTemplate.execute("ALTER TABLE manual_review_cases DISABLE TRIGGER USER");
            try {
                jdbcTemplate.update("UPDATE manual_review_cases SET created_at = ? WHERE id IN (?, ?)",
                        java.sql.Timestamp.from(equalCreatedAt), fixture.review().reviewCaseId(),
                        second.reviewCaseId());
            } finally {
                jdbcTemplate.execute("ALTER TABLE manual_review_cases ENABLE TRIGGER USER");
            }
        });

        AnalystQueueService.ReviewQueuePage first = analystQueueService.search(
                fixture.analyst().context(), null, ReviewQueueSort.CREATED_AT_ASC, 1, null);
        AnalystQueueService.ReviewQueuePage next = analystQueueService.search(
                fixture.analyst().context(), null, ReviewQueueSort.CREATED_AT_ASC, 1,
                first.nextCursor());

        assertThat(first.rows()).hasSize(1);
        assertThat(next.rows()).hasSize(1);
        assertThat(List.of(first.rows().getFirst().id(), next.rows().getFirst().id()))
                .containsExactlyInAnyOrder(fixture.review().reviewCaseId(), second.reviewCaseId());
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void postgresRejectsNoteMutationMismatchedTerminalEventAndEventRewrite() throws Exception {
        Fixture fixture = createFixture("workspace-history-guard");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "workspace-history-guard-assign");
        UUID noteId = createDueReviewNote(fixture);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE review_notes SET ciphertext = 'rewritten' WHERE id = ?", noteId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "Review note history permits only terminal ciphertext destruction");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO review_note_redaction_events (
                    id, merchant_id, review_note_id, manual_review_case_id,
                    event_type, actor_operator_id, created_at)
                VALUES (?, ?, ?, ?, 'RETENTION_EXPIRED', NULL, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.merchant().id(), noteId,
                fixture.review().reviewCaseId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "Review note redaction event must match terminal note state");
        assertThat(reviewNoteService.expireDueNotes()).isOne();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE review_note_redaction_events SET created_at = CURRENT_TIMESTAMP
                 WHERE review_note_id = ?
                """, noteId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("Review note redaction events are append-only");
        assertTerminalNote(noteId, "EXPIRED", "RETENTION_EXPIRED");
    }

    @Test
    void forcedCompetingResolutionsReturnTheSingleCommittedTerminalTruth() throws Exception {
        Fixture fixture = createFixture("competing-resolutions");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "competing-resolution-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "competing-resolution-manager", "RISK_MANAGER");
        CountDownLatch firstResolutionOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseFirstResolution = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ReviewOperationsService.ResolutionResult> firstResolution = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                        ReviewOperationsService.ResolutionResult result = reviewOperationsService.resolve(
                                fixture.analyst().context(), fixture.review().reviewCaseId(),
                                ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD,
                                ReviewClassification.CONFIRMED_FRAUD, FraudType.CARD_TESTING,
                                ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED,
                                DatabaseInstant.now().minusSeconds(5), "first-competing-resolution");
                        firstResolutionOwnsPaymentLock.countDown();
                        await(releaseFirstResolution);
                        return result;
                    }));
            assertThat(firstResolutionOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<ReviewOperationsService.ResolutionResult> secondResolution = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            reviewOperationsService.resolve(manager.context(), fixture.review().reviewCaseId(),
                                    ReviewResolutionOutcome.CANCEL,
                                    ReviewClassification.INCONCLUSIVE, null,
                                    ReviewResolutionReasonCode.RISK_TOO_HIGH,
                                    DatabaseInstant.now().minusSeconds(5), "second-competing-resolution")));
            awaitDatabaseLockWait("payment_intents");

            releaseFirstResolution.countDown();
            ReviewOperationsService.ResolutionResult winner = firstResolution.get(5, TimeUnit.SECONDS);
            ReviewOperationsService.ResolutionResult follower = secondResolution.get(5, TimeUnit.SECONDS);
            assertThat(follower.resolution().getId()).isEqualTo(winner.resolution().getId());
            assertThat(follower.resolution().getOutcome())
                    .isEqualTo(ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD);
            assertThat(follower.label().getId()).isEqualTo(winner.label().getId());
        }

        assertTerminalTruth(fixture.review(), "RESOLVED", 1, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM review_resolutions",
                String.class)).isEqualTo("CANCEL_AND_REPORT_FRAUD");
    }

    @Test
    void forcedCompetingApprovalsConvergeToOneProcessorIdentityAndOneCapture() throws Exception {
        Fixture fixture = createApprovedFixture("review_approval-competing-approvals");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "review_approval-competing-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "review_approval-competing-manager", "RISK_MANAGER");
        CountDownLatch firstApprovalOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseFirstApproval = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ReviewOperationsService.ResolutionResult> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                        ReviewOperationsService.ResolutionResult result = reviewOperationsService.resolve(
                                fixture.analyst().context(), fixture.review().reviewCaseId(),
                                ReviewResolutionOutcome.APPROVE, ReviewClassification.INCONCLUSIVE, null,
                                ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED,
                                DatabaseInstant.now().minusSeconds(5), "review_approval-first-approval");
                        firstApprovalOwnsPaymentLock.countDown();
                        await(releaseFirstApproval);
                        return result;
                    }));
            assertThat(firstApprovalOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<ReviewOperationsService.ResolutionResult> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            reviewOperationsService.resolve(
                                    manager.context(), fixture.review().reviewCaseId(),
                                    ReviewResolutionOutcome.APPROVE,
                                    ReviewClassification.CONFIRMED_LEGITIMATE, null,
                                    ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED,
                                    DatabaseInstant.now().minusSeconds(4), "review_approval-second-approval")));
            awaitDatabaseLockWait("payment_intents");

            releaseFirstApproval.countDown();
            ReviewOperationsService.ResolutionResult winner = first.get(5, TimeUnit.SECONDS);
            ReviewOperationsService.ResolutionResult follower = second.get(5, TimeUnit.SECONDS);
            assertThat(follower.resolution().getId()).isEqualTo(winner.resolution().getId());
            assertThat(follower.authorization().getId()).isEqualTo(winner.authorization().getId());
            assertThat(follower.label()).isNull();
        }

        assertThat(count("review_resolutions")).isOne();
        assertThat(count("fraud_labels")).isZero();
        assertThat(count("authorizations")).isEqualTo(3);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM review_resolutions", String.class))
                .isEqualTo("APPROVE");
        assertThat(jdbcTemplate.queryForObject("SELECT processor_reference_id FROM authorizations "
                        + "WHERE payment_attempt_id = ?", String.class, fixture.review().paymentAttemptId()))
                .isEqualTo("auth_sim_" + fixture.review().paymentAttemptId().toString().replace("-", ""));
    }

    @Test
    void forcedDisableBeforeActionRevalidatesLockedActorAndCreatesNoReviewSideEffect() throws Exception {
        Fixture fixture = createFixture("disable-before-action");
        CountDownLatch disableOwnsOperatorLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    riskOperatorService.disable(fixture.merchant().context(), fixture.analyst().id(),
                            "disable-before-action");
                    disableOwnsOperatorLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsOperatorLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("risk_operators", fixture.analyst().id());
            Future<?> action = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.assign(fixture.analyst().context(),
                            fixture.review().reviewCaseId(), null, 0L, "disabled-action")));
            awaitDatabaseLockWait("risk_operators");

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertNotFound(action);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM risk_operators WHERE id = ?",
                String.class, fixture.analyst().id())).isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, fixture.review().reviewCaseId())).isEqualTo("OPEN");
        assertThat(count("review_assignment_events")).isZero();
    }

    @Test
    void forcedDisableBeforeApprovalCreatesNoOverrideProcessorOrMoneySideEffect() throws Exception {
        Fixture fixture = createApprovedFixture("review_approval-disable-before-approval");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "review_approval-disable-assign");
        CountDownLatch disableOwnsOperatorLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    riskOperatorService.disable(fixture.merchant().context(), fixture.analyst().id(),
                            "review_approval-disable-before-approval");
                    disableOwnsOperatorLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsOperatorLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("risk_operators", fixture.analyst().id());
            Future<?> approval = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> reviewOperationsService.resolve(
                            fixture.analyst().context(), fixture.review().reviewCaseId(),
                            ReviewResolutionOutcome.APPROVE, ReviewClassification.INCONCLUSIVE, null,
                            ReviewResolutionReasonCode.MANUAL_REVIEW_APPROVED,
                            DatabaseInstant.now().minusSeconds(5), "review_approval-disabled-approval")));
            awaitDatabaseLockWait("risk_operators");

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertNotFound(approval);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, fixture.review().paymentIntentId())).isEqualTo("REQUIRES_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, fixture.review().paymentAttemptId())).isEqualTo("IN_REVIEW");
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("authorizations")).isEqualTo(2);
        assertThat(count("ledger_entries")).isZero();
    }

    @ParameterizedTest(name = "rollback at {0}")
    @ValueSource(strings = {
            "resolution", "label", "payment_state", "audit", "webhook", "idempotency_completion"
    })
    void productionConstraintFailureRollsBackResolutionAndSameKeyCanRetryCleanly(
            String failurePosition, CapturedOutput output)
            throws Exception {
        Fixture fixture = createFixture("rollback-" + failurePosition);
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "rollback-assign-" + failurePosition);
        createRiskWebhookEndpoint(fixture.merchant(), "rollback-webhook-" + failurePosition);
        int auditCountBefore = count("audit_logs");
        int webhookEventCountBefore = count("webhook_events");
        double rolledBackMetricBefore = riskOperationCount(
                "resolve", "CANCEL_AND_REPORT_FRAUD", "rolled_back");
        double committedMetricBefore = riskOperationCount(
                "resolve", "CANCEL_AND_REPORT_FRAUD", "committed");
        String body = resolutionBody();
        ConstraintSpec constraint = constraintFor(failurePosition);

        jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                + " ADD CONSTRAINT ck_test_review_resolution_forced_failure CHECK ("
                + constraint.expression() + ") NOT VALID");
        try {
            mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.review().reviewCaseId())
                            .header(RISK_KEY, fixture.analyst().secret())
                            .header(IDEMPOTENCY_KEY, "rollback-resolution-" + failurePosition)
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().is5xxServerError());
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                    + " DROP CONSTRAINT IF EXISTS ck_test_review_resolution_forced_failure");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, fixture.review().paymentIntentId())).isEqualTo("REQUIRES_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, fixture.review().paymentAttemptId())).isEqualTo("IN_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, fixture.review().reviewCaseId())).isEqualTo("ASSIGNED");
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(count("audit_logs")).isEqualTo(auditCountBefore);
        assertThat(count("webhook_events")).isEqualTo(webhookEventCountBefore);
        assertThat(riskOperationCount("resolve", "CANCEL_AND_REPORT_FRAUD", "rolled_back"))
                .isEqualTo(rolledBackMetricBefore + 1.0d);
        assertThat(riskOperationCount("resolve", "CANCEL_AND_REPORT_FRAUD", "committed"))
                .isEqualTo(committedMetricBefore);
        assertThat(output.getAll()).doesNotContain("Failing row contains");
        assertThat(output.getAll()).doesNotContain("violates check constraint");

        mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.review().reviewCaseId())
                        .header(RISK_KEY, fixture.analyst().secret())
                        .header(IDEMPOTENCY_KEY, "rollback-resolution-" + failurePosition)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertTerminalTruth(fixture.review(), "RESOLVED", 1, 1);
        assertThat(riskOperationCount("resolve", "CANCEL_AND_REPORT_FRAUD", "committed"))
                .isEqualTo(committedMetricBefore + 1.0d);
    }

    @ParameterizedTest(name = "review approval rollback at {0}")
    @ValueSource(strings = {
            "override_flush", "authorization", "ledger", "audit", "webhook", "idempotency_completion"
    })
    void reviewApprovalRollbackLeavesNoPartialProcessorOrMoneyTruthAndSameKeyRetries(
            String failurePosition, CapturedOutput output) throws Exception {
        Fixture fixture = createApprovedFixture("review_approval-rollback-" + failurePosition);
        assign(fixture.analyst(), fixture.review().reviewCaseId(),
                "review_approval-rollback-assign-" + failurePosition);
        createRiskWebhookEndpoint(fixture.merchant(), "review_approval-rollback-webhook-" + failurePosition);
        int auditCountBefore = count("audit_logs");
        int webhookEventCountBefore = count("webhook_events");
        ConstraintSpec constraint = approvalConstraintFor(failurePosition);
        String body = approvalBody();

        jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                + " ADD CONSTRAINT ck_test_review_approval_forced_failure CHECK ("
                + constraint.expression() + ") NOT VALID");
        try {
            mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.review().reviewCaseId())
                            .header(RISK_KEY, fixture.analyst().secret())
                            .header(IDEMPOTENCY_KEY, "review_approval-rollback-" + failurePosition)
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().is5xxServerError());
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + constraint.table()
                    + " DROP CONSTRAINT IF EXISTS ck_test_review_approval_forced_failure");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, fixture.review().paymentIntentId())).isEqualTo("REQUIRES_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, fixture.review().paymentAttemptId())).isEqualTo("IN_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, fixture.review().reviewCaseId())).isEqualTo("ASSIGNED");
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(count("authorizations")).isEqualTo(2);
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("audit_logs")).isEqualTo(auditCountBefore);
        assertThat(count("webhook_events")).isEqualTo(webhookEventCountBefore);
        assertThat(output.getAll()).doesNotContain("Failing row contains");

        mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.review().reviewCaseId())
                        .header(RISK_KEY, fixture.analyst().secret())
                        .header(IDEMPOTENCY_KEY, "review_approval-rollback-" + failurePosition)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, fixture.review().paymentIntentId())).isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, fixture.review().paymentAttemptId())).isEqualTo("AUTHORIZED");
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("authorizations")).isEqualTo(3);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT processor_reference_id FROM authorizations "
                        + "WHERE payment_attempt_id = ?", String.class, fixture.review().paymentAttemptId()))
                .isEqualTo("auth_sim_" + fixture.review().paymentAttemptId().toString().replace("-", ""));
    }

    @Test
    void postgresRejectsHistoryMutationInvalidCodesAndCrossTenantActorSubstitution() throws Exception {
        Fixture fixture = createFixture("database-guards");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "database-guard-assign");
        resolve(fixture, "database-guard-resolve");
        MerchantAccess foreignMerchant = createMerchant("review-label-postgres-foreign-guard");
        OperatorAccess foreignOperator = createOperator(foreignMerchant, "foreign-guard-operator", "ANALYST");
        UUID labelId = jdbcTemplate.queryForObject("SELECT id FROM fraud_labels", UUID.class);
        UUID resolutionId = jdbcTemplate.queryForObject("SELECT id FROM review_resolutions", UUID.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fraud_labels SET fraud_type = 'OTHER_CONFIRMED_FRAUD' WHERE id = ?", labelId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM review_resolutions WHERE id = ?", resolutionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE manual_review_cases SET payment_intent_id = ? WHERE id = ?",
                UUID.randomUUID(), fixture.review().reviewCaseId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO review_annotations (
                    id, merchant_id, manual_review_case_id, actor_operator_id, code, created_at
                ) VALUES (?, ?, ?, ?, 'UNBOUNDED_FREE_TEXT', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.merchant().id(), fixture.review().reviewCaseId(),
                fixture.analyst().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO review_annotations (
                    id, merchant_id, manual_review_case_id, actor_operator_id, code, created_at
                ) VALUES (?, ?, ?, ?, 'IDENTITY_MISMATCH', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.merchant().id(), fixture.review().reviewCaseId(),
                foreignOperator.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fraud_decisions SET action = 'ALLOW' WHERE id = ?", fixture.review().fraudDecisionId()))
                .isInstanceOf(DataAccessException.class);

        assertTerminalTruth(fixture.review(), "RESOLVED", 1, 1);
        assertThat(count("review_annotations")).isZero();
    }

    @Test
    void postgresRejectsMismatchedFraudTypeAndReasonBeforeReviewCanResolve() throws Exception {
        Fixture fixture = createFixture("database-taxonomy");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO review_resolutions (
                    id, merchant_id, manual_review_case_id, payment_intent_id,
                    payment_attempt_id, fraud_decision_id, actor_operator_id, actor_role,
                    review_stage, outcome, classification, fraud_type, reason_code, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ANALYST', 'PRE_AUTHORIZATION',
                          'CANCEL_AND_REPORT_FRAUD', 'CONFIRMED_FRAUD', 'CARD_TESTING',
                          'ACCOUNT_TAKEOVER_CONFIRMED', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.merchant().id(), fixture.review().reviewCaseId(),
                fixture.review().paymentIntentId(), fixture.review().paymentAttemptId(),
                fixture.review().fraudDecisionId(), fixture.analyst().id()))
                .isInstanceOf(DataAccessException.class);

        assertThat(count("review_resolutions")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, fixture.review().reviewCaseId())).isEqualTo("OPEN");
    }

    @Test
    void postgresRejectsSelfAndCrossPaymentLabelSupersessionLineage() throws Exception {
        Fixture first = createFixture("label-supersession-first");
        assign(first.analyst(), first.review().reviewCaseId(), "label-supersession-first-assign");
        resolve(first, "label-supersession-first-resolve");
        Fixture second = createFixture("label-supersession-second");
        assign(second.analyst(), second.review().reviewCaseId(), "label-supersession-second-assign");
        resolve(second, "label-supersession-second-resolve");
        UUID firstLabelId = jdbcTemplate.queryForObject("""
                SELECT id FROM fraud_labels WHERE payment_intent_id = ?
                """, UUID.class, first.review().paymentIntentId());
        UUID secondResolutionId = jdbcTemplate.queryForObject("""
                SELECT id FROM review_resolutions WHERE payment_intent_id = ?
                """, UUID.class, second.review().paymentIntentId());
        UUID selfLabelId = UUID.randomUUID();

        assertThatThrownBy(() -> insertSupersedingLabel(
                second, secondResolutionId, UUID.randomUUID(), firstLabelId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fraud label supersession lineage mismatch");
        assertThatThrownBy(() -> insertSupersedingLabel(
                second, secondResolutionId, selfLabelId, selfLabelId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fraud label supersession lineage mismatch");

        assertThat(count("fraud_labels")).isEqualTo(2);
    }

    private void insertSupersedingLabel(Fixture fixture, UUID resolutionId,
            UUID newLabelId, UUID supersedesLabelId) {
        jdbcTemplate.update("""
                INSERT INTO fraud_labels (
                    id, merchant_id, payment_intent_id, payment_attempt_id,
                    fraud_decision_id, traffic_origin, source, label_value, fraud_type,
                    evidence_occurred_at, available_at, source_review_resolution_id,
                    actor_operator_id, supersedes_label_id, reason_code, created_at
                ) VALUES (?, ?, ?, ?, ?, 'MERCHANT_API', 'MANUAL_REVIEW', 'FRAUD',
                          'CARD_TESTING', CURRENT_TIMESTAMP - INTERVAL '5 seconds',
                          CURRENT_TIMESTAMP, ?, ?, ?, 'CARD_TESTING_CONFIRMED', CURRENT_TIMESTAMP)
                """, newLabelId, fixture.merchant().id(), fixture.review().paymentIntentId(),
                fixture.review().paymentAttemptId(), fixture.review().fraudDecisionId(),
                resolutionId, fixture.analyst().id(), supersedesLabelId);
    }

    private UUID createDueReviewNote(Fixture fixture) {
        Instant createdAt = DatabaseInstant.now().minus(java.time.Duration.ofDays(91));
        Instant retentionExpiresAt = DatabaseInstant.now().minus(java.time.Duration.ofDays(1));
        UUID noteId = UUID.randomUUID();
        ReviewNoteCipher.EncryptedNote encrypted = reviewNoteCipher.encrypt(
                "Historical delivery verification context", fixture.merchant().id(),
                fixture.review().reviewCaseId(), noteId, fixture.analyst().id(), createdAt);
        ReviewNote note = new ReviewNote(noteId, fixture.merchant().id(),
                fixture.review().reviewCaseId(), fixture.analyst().id(), 1L, null,
                encrypted, 40, createdAt, retentionExpiresAt);
        return new TransactionTemplate(transactionManager).execute(transactionStatus ->
                reviewNoteRepository.saveAndFlush(note).getId());
    }

    private ReviewSavedSearchService.SavedSearchView createSharedSavedSearch(
            OperatorAccess owner, String name) {
        return reviewSavedSearchService.create(owner.context(), name,
                SavedSearchVisibility.MERCHANT_SHARED, null,
                ReviewQueueSort.CREATED_AT_DESC,
                Set.of(ReviewQueueColumn.CREATED_AT, ReviewQueueColumn.STATUS),
                "workspace-search-create");
    }

    private void assertTerminalNote(UUID noteId, String lifecycle, String reason) {
        Map<String, Object> note = jdbcTemplate.queryForMap("""
                SELECT lifecycle_status, ciphertext, nonce, key_version, redaction_reason
                  FROM review_notes WHERE id = ?
                """, noteId);
        assertThat(note.get("lifecycle_status")).isEqualTo(lifecycle);
        assertThat(note.get("ciphertext")).isNull();
        assertThat(note.get("nonce")).isNull();
        assertThat(note.get("key_version")).isNull();
        assertThat(note.get("redaction_reason")).isEqualTo(reason);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_note_redaction_events WHERE review_note_id = ?
                """, Integer.class, noteId)).isOne();
    }

    private Fixture createFixture(String name) throws Exception {
        MerchantAccess merchant = createMerchant("review-label-postgres-" + name);
        UUID customerId = createCustomer(merchant, name + "@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, name + "-card");
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId, name);
        OperatorAccess analyst = createOperator(merchant, name + "-analyst", "ANALYST");
        return new Fixture(merchant, analyst, review);
    }

    private Fixture createApprovedFixture(String name) throws Exception {
        MerchantAccess merchant = createMerchant("review-approval-postgres-" + name);
        UUID customerId = createCustomer(merchant, name + "@example.com");
        UUID declineMethodId = createPaymentMethod(
                merchant, customerId, name + "-decline-card", "4999990000000003");
        UUID approvedMethodId = createPaymentMethod(
                merchant, customerId, name + "-approved-card", "4999990000000001");
        ReviewFixture review = createReview(
                merchant, customerId, declineMethodId, approvedMethodId, name, "AUTOMATIC");
        OperatorAccess analyst = createOperator(merchant, name + "-analyst", "ANALYST");
        return new Fixture(merchant, analyst, review);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(response.get("id").asText());
        String apiKey = response.get("apiKey").asText();
        String prefix = response.get("apiKeyPrefix").asText();
        return new MerchantAccess(id, apiKey, prefix);
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(merchant.id(), UUID.fromString(response.get("id").asText()),
                response.get("operatorCode").asText(), RiskOperatorRole.valueOf(role),
                response.get("credentialPrefix").asText(), response.get("credential").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key) throws Exception {
        return createPaymentMethod(merchant, customerId, key, "4999990000000003");
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key,
            String cardNumber) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"" + cardNumber + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private ReviewFixture createReview(MerchantAccess merchant, UUID customerId, UUID paymentMethodId,
            String keyPrefix) throws Exception {
        return createReview(merchant, customerId, paymentMethodId, paymentMethodId,
                keyPrefix, "MANUAL");
    }

    private ReviewFixture createReview(MerchantAccess merchant, UUID customerId,
            UUID declinePaymentMethodId, UUID reviewedPaymentMethodId,
            String keyPrefix, String captureMethod) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, keyPrefix + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":99999,"currency":"USD","captureMethod":"%s"}
                                """.formatted(customerId, captureMethod)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID paymentIntentId = UUID.fromString(intent.get("id").asText());
        confirm(merchant, paymentIntentId, declinePaymentMethodId, keyPrefix + "-confirm-1");
        confirm(merchant, paymentIntentId, declinePaymentMethodId, keyPrefix + "-confirm-2");
        JsonNode attempt = confirm(merchant, paymentIntentId, reviewedPaymentMethodId,
                keyPrefix + "-confirm-3");
        UUID reviewCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?", UUID.class, paymentIntentId);
        return new ReviewFixture(paymentIntentId, UUID.fromString(attempt.get("id").asText()),
                UUID.fromString(attempt.get("fraudDecisionId").asText()), reviewCaseId);
    }

    private JsonNode confirm(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId, String key)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void assign(OperatorAccess operator, UUID reviewCaseId, String key) throws Exception {
        mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":0}"))
                .andExpect(status().isOk());
    }

    private void resolve(Fixture fixture, String key) throws Exception {
        mockMvc.perform(post("/risk/reviews/{id}/resolve", fixture.review().reviewCaseId())
                        .header(RISK_KEY, fixture.analyst().secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(resolutionBody()))
                .andExpect(status().isOk());
    }

    private void createRiskWebhookEndpoint(MerchantAccess merchant, String key) throws Exception {
        mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "url":"http://127.0.0.1:9191/review-label",
                                  "enabledEvents":[
                                    "payment_intent.canceled",
                                    "review.resolved",
                                    "fraud_label.created"
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private ConstraintSpec constraintFor(String failurePosition) {
        return switch (failurePosition) {
            case "resolution" -> new ConstraintSpec("review_resolutions", "FALSE");
            case "label" -> new ConstraintSpec("fraud_labels", "FALSE");
            case "payment_state" -> new ConstraintSpec("payment_intents", "status <> 'CANCELED'");
            case "audit" -> new ConstraintSpec("audit_logs", "action <> 'review.resolved'");
            case "webhook" -> new ConstraintSpec("webhook_events",
                    "event_type NOT IN ('payment_intent.canceled', 'review.resolved', 'fraud_label.created')");
            case "idempotency_completion" -> new ConstraintSpec("idempotency_records",
                    "status <> 'COMPLETED'");
            default -> throw new IllegalArgumentException("Unknown failure position " + failurePosition);
        };
    }

    private ConstraintSpec approvalConstraintFor(String failurePosition) {
        return switch (failurePosition) {
            case "override_flush" -> new ConstraintSpec("review_resolutions", "outcome <> 'APPROVE'");
            case "authorization" -> new ConstraintSpec("authorizations", "status <> 'APPROVED'");
            case "ledger" -> new ConstraintSpec("ledger_entries", "FALSE");
            case "audit" -> new ConstraintSpec("audit_logs", "action <> 'review.resolved'");
            case "webhook" -> new ConstraintSpec("webhook_events", "event_type <> 'review.resolved'");
            case "idempotency_completion" -> new ConstraintSpec("idempotency_records",
                    "status <> 'COMPLETED'");
            default -> throw new IllegalArgumentException("Unknown approval failure position " + failurePosition);
        };
    }

    private String resolutionBody() {
        return """
                {
                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                  "classification":"CONFIRMED_FRAUD",
                  "fraudType":"CARD_TESTING",
                  "reasonCode":"CARD_TESTING_CONFIRMED",
                  "evidenceOccurredAt":"%s"
                }
                """.formatted(DatabaseInstant.now().minusSeconds(5));
    }

    private String approvalBody() {
        return """
                {
                  "outcome":"APPROVE",
                  "classification":"INCONCLUSIVE",
                  "reasonCode":"MANUAL_REVIEW_APPROVED",
                  "evidenceOccurredAt":"%s"
                }
                """.formatted(DatabaseInstant.now().minusSeconds(5));
    }

    /**
     * Proof for the {@code C46-CON-001} remainder: the forced review-resolution ordering above
     * proves one source truth, and this proves the event boundary underneath it tells the same
     * story. The losing transition must leave no event, and the winning one must leave exactly
     * the facts it actually performed, on a gap-free stream.
     */
    @Test
    void forcedCompetingResolutionsEmitOneEventSequenceAndNothingFromTheLoser() throws Exception {
        Fixture fixture = createFixture("competing-resolution-events");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "competing-events-assign");
        OperatorAccess manager = createOperator(
                fixture.merchant(), "competing-events-manager", "RISK_MANAGER");
        CountDownLatch firstResolutionOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseFirstResolution = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ReviewOperationsService.ResolutionResult> firstResolution = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                        ReviewOperationsService.ResolutionResult result =
                                reviewOperationsService.resolve(fixture.analyst().context(),
                                        fixture.review().reviewCaseId(),
                                        ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD,
                                        ReviewClassification.CONFIRMED_FRAUD, FraudType.CARD_TESTING,
                                        ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED,
                                        DatabaseInstant.now().minusSeconds(5), "competing-events-first");
                        firstResolutionOwnsPaymentLock.countDown();
                        await(releaseFirstResolution);
                        return result;
                    }));
            assertThat(firstResolutionOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict("payment_intents", fixture.review().paymentIntentId());
            Future<ReviewOperationsService.ResolutionResult> secondResolution = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus ->
                            reviewOperationsService.resolve(manager.context(),
                                    fixture.review().reviewCaseId(),
                                    ReviewResolutionOutcome.CANCEL,
                                    ReviewClassification.INCONCLUSIVE, null,
                                    ReviewResolutionReasonCode.RISK_TOO_HIGH,
                                    DatabaseInstant.now().minusSeconds(5), "competing-events-second")));
            awaitDatabaseLockWait("payment_intents");

            releaseFirstResolution.countDown();
            firstResolution.get(5, TimeUnit.SECONDS);
            secondResolution.get(5, TimeUnit.SECONDS);
        }

        // One resolution committed, so exactly one resolved fact and one label fact exist — not
        // two of either, and neither attributed to the operator whose transition lost. The
        // payment lifecycle facts that precede them are the stream this review belongs to.
        List<String> emitted = eventTypesFor(fixture.review().paymentIntentId());
        assertThat(emitted).containsSubsequence("manual_review.assigned",
                "manual_review.resolved", "fraud_label.recorded");
        assertThat(emitted.stream().filter("manual_review.resolved"::equals).count()).isEqualTo(1);
        assertThat(emitted.stream().filter("fraud_label.recorded"::equals).count()).isEqualTo(1);
        assertThat(actorIdsFor(fixture.review().paymentIntentId(), "manual_review.resolved"))
                .containsExactly(fixture.analyst().id().toString())
                .doesNotContain(manager.id().toString());

        // The losing transition took a stream version and rolled it back, so the surviving
        // sequence must still be contiguous from one.
        List<Long> versions = streamVersionsFor(fixture.review().paymentIntentId());
        assertThat(versions).isEqualTo(java.util.stream.LongStream
                .rangeClosed(1, versions.size()).boxed().toList());
        assertThat(publicationCountFor(fixture.review().paymentIntentId()))
                .isEqualTo(versions.size());
    }

    /**
     * Proof for the {@code C46-AUTH-001} remainder. A role that may not resolve must be refused
     * by the existing guard, and the refusal must leave no event behind — event insertion sits
     * after authorization, so a refused mutation has nothing to emit.
     */
    @Test
    void aViewerCannotResolveAndTheRefusalEmitsNoEvent() throws Exception {
        Fixture fixture = createFixture("viewer-cannot-resolve");
        OperatorAccess viewer = createOperator(fixture.merchant(), "viewer-resolve", "VIEWER");
        long eventsBefore = count("domain_events");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                reviewOperationsService.resolve(viewer.context(),
                        fixture.review().reviewCaseId(), ReviewResolutionOutcome.CANCEL,
                        ReviewClassification.INCONCLUSIVE, null,
                        ReviewResolutionReasonCode.RISK_TOO_HIGH,
                        DatabaseInstant.now().minusSeconds(5), "viewer-resolve-request")))
                .isInstanceOf(PublicApiException.class);

        assertThat(count("domain_events")).isEqualTo(eventsBefore);
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("fraud_labels")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM manual_review_cases WHERE id = ?", String.class,
                fixture.review().reviewCaseId())).isNotEqualTo("RESOLVED");
    }

    /**
     * Proof for the {@code C46-AUTH-001} remainder. An operator disabled after their credential
     * was issued must not be able to resolve, and the refusal must emit nothing — authority is
     * revalidated against live operator state rather than trusted from the caller's context.
     */
    @Test
    void anOperatorDisabledAfterIssueCannotResolveAndTheRefusalEmitsNoEvent() throws Exception {
        Fixture fixture = createFixture("disabled-cannot-resolve");
        assign(fixture.analyst(), fixture.review().reviewCaseId(), "disabled-resolve-assign");
        long eventsAfterAssign = count("domain_events");
        assertThat(eventsAfterAssign).isPositive();

        jdbcTemplate.update("""
                UPDATE risk_operators SET status = 'DISABLED', disabled_at = CURRENT_TIMESTAMP,
                    lifecycle_epoch = lifecycle_epoch + 1
                WHERE id = ?
                """, fixture.analyst().id());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                reviewOperationsService.resolve(fixture.analyst().context(),
                        fixture.review().reviewCaseId(),
                        ReviewResolutionOutcome.CANCEL_AND_REPORT_FRAUD,
                        ReviewClassification.CONFIRMED_FRAUD, FraudType.CARD_TESTING,
                        ReviewResolutionReasonCode.CARD_TESTING_CONFIRMED,
                        DatabaseInstant.now().minusSeconds(5), "disabled-resolve-request")))
                .isInstanceOf(PublicApiException.class);

        assertThat(count("domain_events")).isEqualTo(eventsAfterAssign);
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("fraud_labels")).isZero();
    }

    private List<String> eventTypesFor(UUID streamId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type FROM domain_events WHERE stream_id = ? ORDER BY stream_version
                """, String.class, streamId);
    }

    private List<Long> streamVersionsFor(UUID streamId) {
        return jdbcTemplate.queryForList("""
                SELECT stream_version FROM domain_events WHERE stream_id = ?
                ORDER BY stream_version
                """, Long.class, streamId);
    }

    private List<String> actorIdsFor(UUID streamId, String eventType) {
        return jdbcTemplate.queryForList("""
                SELECT actor_id FROM domain_events WHERE stream_id = ? AND event_type = ?
                """, String.class, streamId, eventType);
    }

    private int publicationCountFor(UUID streamId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM event_publications publication
                JOIN domain_events event ON event.id = publication.domain_event_id
                WHERE event.stream_id = ?
                """, Integer.class, streamId);
    }

    private void assertTerminalTruth(ReviewFixture review, String reviewStatus,
            int resolutionCount, int labelCount) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, review.paymentIntentId())).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, review.paymentAttemptId())).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, review.reviewCaseId())).isEqualTo(reviewStatus);
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, review.fraudDecisionId())).isEqualTo("REVIEW");
        assertThat(count("review_resolutions")).isEqualTo(resolutionCount);
        assertThat(count("fraud_labels")).isEqualTo(labelCount);
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("refunds")).isZero();
    }

    private void awaitRowLockConflict(String tableName, UUID rowId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT id FROM " + tableName + " WHERE id = ? FOR UPDATE NOWAIT")) {
                connection.setAutoCommit(false);
                statement.setObject(1, rowId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected row lock was not observed for " + tableName);
    }

    private void awaitDatabaseLockWait(String tableName) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND wait_event_type = 'Lock'
                          AND query LIKE ?
                    )
                    """, Boolean.class, "%" + tableName + "%");
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing operation did not wait on " + tableName);
    }

    private void assertConflict(Future<?> future) throws Exception {
        Throwable cause = futureFailure(future);
        assertThat(cause).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) cause).getStatusCode().value()).isEqualTo(409);
    }

    private void assertNotFound(Future<?> future) throws Exception {
        Throwable cause = futureFailure(future);
        assertThat(cause).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) cause).getStatusCode().value()).isEqualTo(404);
    }

    private Throwable futureFailure(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected competing operation to fail");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause.getCause() != null
                    && !(cause instanceof ResponseStatusException)) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for Review and label forced ordering latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Review and label forced ordering was interrupted", exception);
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private double riskOperationCount(String operation, String outcome, String transaction) {
        Counter counter = meterRegistry.find("thaap.risk.operations")
                .tags("operation", operation, "outcome", outcome, "transaction", transaction)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private record MerchantAccess(UUID id, String apiKey, String apiKeyPrefix) {
        MerchantContext context() {
            return new MerchantContext(id, "test-merchant", apiKeyPrefix, TrafficOrigin.MERCHANT_API);
        }
    }

    private record OperatorAccess(UUID merchantId, UUID id, String operatorCode, RiskOperatorRole role,
            String credentialPrefix, String secret) {
        RiskOperatorContext context() {
            return new RiskOperatorContext(merchantId, id, operatorCode, role, credentialPrefix);
        }
    }

    private record ReviewFixture(UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, UUID reviewCaseId) {
    }

    private record Fixture(MerchantAccess merchant, OperatorAccess analyst, ReviewFixture review) {
    }

    private record ConstraintSpec(String table, String expression) {
    }
}
