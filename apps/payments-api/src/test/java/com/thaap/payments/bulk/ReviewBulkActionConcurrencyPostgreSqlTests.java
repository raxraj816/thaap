package com.thaap.payments.bulk;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C55-CON-001 against real PostgreSQL: a bulk assignment item and a concurrent single-case
 * assignment on the same review case genuinely contend for the same row lock (forced, not hoped
 * for, via a raw holder transaction and a real lock-wait observation), converge without deadlock
 * or a hang, and never both succeed against the same expected version.
 */
class ReviewBulkActionConcurrencyPostgreSqlTests extends ReviewBulkPostgreSqlTestBase {


    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkActionStore store;
    @Autowired private ReviewBulkItemExecutor itemExecutor;
    @Autowired private ManualReviewCaseRepository reviewCaseRepository;
    @Autowired private PlatformTransactionManager transactionManager;


    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator analyst;
    private Operator manager;
    private Operator otherAnalyst;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("bulk-concurrency");
        analyst = scenario.operator(merchant, "bulk-concurrency-analyst", "ANALYST");
        manager = scenario.operator(merchant, "bulk-concurrency-manager", "RISK_MANAGER");
        otherAnalyst = scenario.operator(merchant, "bulk-concurrency-other", "ANALYST");
    }

    @Test
    void aBulkSelfAssignAndAConcurrentSingleCaseAssignToOperatorConvergeWithoutDeadlockOrDuplicateEffect()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-race");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("race-idem"),
                sixtyFourChars("race-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "race-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            // A raw holder forces genuine contention: both real domain paths must queue behind
            // it, so the interleaving below is observed, not hoped for.
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM manual_review_cases WHERE id = ? FOR UPDATE",
                                UUID.class, reviewCaseId);
                        rowLocked.countDown();
                        await(releaseHolder);
                    }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> bulkContender = executor.submit(() -> itemExecutor.execute(item));
            Future<Integer> singleCaseContender = executor.submit(() -> mockMvc.perform(
                    post("/risk/reviews/{id}/assign", reviewCaseId)
                            .header(RISK_OPERATOR_HEADER, manager.credential())
                            .header(IDEMPOTENCY_HEADER, "race-single-case-assign")
                            .contentType(APPLICATION_JSON)
                            .content("{\"operatorId\":\"" + otherAnalyst.id()
                                    + "\",\"expectedAssignmentVersion\":0}"))
                    .andReturn().getResponse().getStatus());

            awaitDatabaseLockWait("manual_review_cases");
            releaseHolder.countDown();

            holder.get(5, TimeUnit.SECONDS);
            bulkContender.get(5, TimeUnit.SECONDS);
            int singleCaseStatus = singleCaseContender.get(5, TimeUnit.SECONDS);
            assertThat(singleCaseStatus).isIn(200, 409);

            ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                    reviewCaseId, merchant.id()).orElseThrow();
            assertThat(after.getAssignmentVersion()).isEqualTo(1L);

            List<UUID> assignedTo = jdbcTemplate.queryForList("""
                    SELECT to_operator_id FROM review_assignment_events
                    WHERE manual_review_case_id = ? AND assignment_version = 1
                    """, UUID.class, reviewCaseId);
            assertThat(assignedTo).as("exactly one assignment event must exist for version 1, "
                    + "proving the two concurrent writers never both applied their effect")
                    .hasSize(1);
            UUID winner = assignedTo.getFirst();
            assertThat(winner).isIn(analyst.id(), otherAnalyst.id());
            assertThat(after.getAssignedOperatorId()).isEqualTo(winner);

            String bulkItemStatus = jdbcTemplate.queryForObject("""
                    SELECT lifecycle_status FROM review_bulk_action_items
                    WHERE bulk_action_request_id = ?
                    """, String.class, requestId);
            // Exactly one writer applied its effect and the other was refused. Which one wins is
            // a scheduling detail; that they cannot both win is the invariant. The loser's
            // refusal is asserted as a paired outcome so a silent double-apply cannot pass.
            if (winner.equals(analyst.id())) {
                assertThat(bulkItemStatus).isEqualTo("SUCCEEDED");
                assertThat(singleCaseStatus).isEqualTo(409);
            } else {
                assertThat(bulkItemStatus).isEqualTo("STALE");
                assertThat(singleCaseStatus).isEqualTo(200);
            }
        }
    }

    /**
     * The reverse ordering, forced rather than raced: a single-case assignment commits first and
     * moves the review to assignment version 1 while the bulk item still carries expected
     * version 0. The bulk request is initiated by a manager because
     * {@code ReviewOperationsService.assign} refuses a non-manager on an already-assigned case
     * before it ever compares versions; a manager therefore reaches the version comparison, which
     * is the partition this row owns. The losing item must not disturb the winner's assignment.
     */
    @Test
    void aManagerBulkItemWhoseExpectedVersionLostToAnEarlierAssignmentIsRefusedAsStale()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-lost");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("lost-idem"),
                sixtyFourChars("lost-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "lost-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "lost-single-case-assign")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operatorId\":\"" + otherAnalyst.id()
                                + "\",\"expectedAssignmentVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status || ':' || failure_code FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("STALE:assignment_version_stale");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.ASSIGNED);
        assertThat(after.getAssignedOperatorId())
                .as("the losing bulk item must not steal the winner's assignment")
                .isEqualTo(otherAnalyst.id());
        assertThat(after.getAssignmentVersion()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, reviewCaseId)).isEqualTo(1L);
    }

    /**
     * The same losing ordering for a non-manager. {@code forbidden()} deliberately raises the
     * non-disclosing {@code RISK_RESOURCE_NOT_FOUND}, so an analyst learns only that the case is
     * not actionable, never that it exists and is assigned to a named colleague. The bulk item
     * must therefore terminate NOT_FOUND rather than leaking assignment state through a
     * distinguishable STALE or DENIED code.
     */
    @Test
    void anAnalystBulkItemOnACaseAssignedElsewhereTerminatesNonDisclosingly() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-nondisclosure");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("nd-idem"),
                sixtyFourChars("nd-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "nd-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "nd-single-case-assign")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operatorId\":\"" + otherAnalyst.id()
                                + "\",\"expectedAssignmentVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status || ':' || failure_code FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("NOT_FOUND:review_or_operator_not_found");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getAssignedOperatorId()).isEqualTo(otherAnalyst.id());
        assertThat(after.getAssignmentVersion()).isEqualTo(1L);
    }

    /**
     * The initiating actor is disabled after the request was admitted but before the item runs.
     * Authority is rechecked live at the mutation boundary, so the already-queued request cannot
     * carry a revoked operator's authority forward into a real assignment.
     */
    @Test
    void anActorDisabledAfterRequestAdmissionCannotStillAssignThroughItsQueuedItem()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-actor-disable");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("actor-idem"),
                sixtyFourChars("actor-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "actor-disable-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        // Disabling goes through the real merchant-authenticated route: a database trigger
        // (enforce_risk_operator_lifecycle) correctly refuses a raw SQL lifecycle edit, so a
        // proof that reached around the API would be testing a state production cannot produce.
        disableOperator(analyst.id(), "disable-actor-key");

        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId))
                .as("a disabled initiating actor must terminate the item non-successfully")
                .isIn("NOT_FOUND", "DENIED");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
        assertThat(after.getAssignedOperatorId()).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, reviewCaseId)).isZero();
    }

    /**
     * The same recheck for the assignment target. A manager's ASSIGN_TO_OPERATOR request cannot
     * park work on an operator who was disabled between admission and execution.
     */
    @Test
    void aTargetDisabledAfterRequestAdmissionCannotStillReceiveTheAssignment() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-target-disable");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_OPERATOR, otherAnalyst.id(),
                sixtyFourChars("target-idem"), sixtyFourChars("target-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "target-disable-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        disableOperator(otherAnalyst.id(), "disable-target-key");

        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId))
                .as("a disabled target must terminate the item non-successfully")
                .isIn("NOT_FOUND", "DENIED");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
        assertThat(after.getAssignedOperatorId()).isNull();
    }

    private void disableOperator(UUID operatorId, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/risk/operators/{id}/disable", operatorId)
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(IDEMPOTENCY_HEADER, idempotencyKey))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    void aBulkAssignmentThatLosesToAConcurrentResolutionIsRefusedRatherThanAssigningAClosedCase()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-concurrency-resolve");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("resolve-idem"),
                sixtyFourChars("resolve-digest"), 1, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                Instant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "resolve-race-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        // The resolution commits first and closes the case. The bulk item was admitted against
        // version 0 and must now be refused: assigning a resolved case would hand an analyst work
        // that no longer exists, and would move a case out of its terminal state.
        mockMvc.perform(post("/risk/reviews/{id}/resolve", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, "resolve-before-bulk")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().minusSeconds(5))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        long assignmentEventsAfterResolution = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, reviewCaseId);

        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        String outcome = jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId);
        assertThat(outcome)
                .as("a bulk item that lost to a resolution must terminate as a refusal, never "
                        + "SUCCEEDED")
                .isIn("STALE", "NOT_ELIGIBLE", "NOT_FOUND", "DENIED", "FAILED");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus())
                .as("the resolved case must stay resolved")
                .isEqualTo(ManualReviewCaseStatus.RESOLVED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, reviewCaseId))
                .as("the refused item must not append an assignment event to a closed case")
                .isEqualTo(assignmentEventsAfterResolution);
    }

    @Test
    void anUnrelatedCaseInTheSameRequestStillProgressesWhileAnotherItemIsBlocked()
            throws Exception {
        Payment blocked = scenario.reviewedPayment(merchant, "bulk-concurrency-blocked");
        Payment free = scenario.reviewedPayment(merchant, "bulk-concurrency-free");

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("unrelated-idem"),
                sixtyFourChars("unrelated-digest"), 2, Instant.now());
        // Ordinal 1 is the case a competing transaction holds; ordinal 2 is untouched.
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, blocked.reviewCaseId(), 0, 1,
                Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, free.reviewCaseId(), 0, 2,
                Instant.now());

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM manual_review_cases WHERE id = ? FOR UPDATE",
                                UUID.class, blocked.reviewCaseId());
                        rowLocked.countDown();
                        await(releaseHolder);
                    }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            ReviewBulkActionStore.Lease lease = store.claim(requestId, "unrelated-worker");
            Future<?> worker = executor.submit(() -> {
                ReviewBulkActionStore.ClaimedItem next;
                while ((next = store.claimNext(lease)) != null) {
                    itemExecutor.execute(next);
                }
            });

            // The worker processes in ordinal order, so it must be waiting on the held row.
            awaitDatabaseLockWait("manual_review_cases");
            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
            worker.get(10, TimeUnit.SECONDS);
            store.finalizeRequest(lease);
        }

        // Blocking on ordinal 1 delayed ordinal 2 but never stranded it: both reached a terminal
        // state and the unrelated case really was assigned.
        assertThat(jdbcTemplate.queryForList("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ? ORDER BY ordinal
                """, String.class, requestId))
                .containsExactly("SUCCEEDED", "SUCCEEDED");
        assertThat(reviewCaseRepository.findByIdAndMerchantId(free.reviewCaseId(), merchant.id())
                .orElseThrow().getAssignedOperatorId()).isEqualTo(analyst.id());
    }

    private void awaitDatabaseLockWait(String relation) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND query ILIKE ?
                    """, Integer.class, "%" + relation + "%");
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Expected a PostgreSQL lock wait on " + relation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Deterministic exactly-64-character value for columns the schema pins at CHAR_LENGTH 64. */
    private String sixtyFourChars(String seed) {
        StringBuilder value = new StringBuilder(seed);
        while (value.length() < 64) value.append('0');
        return value.substring(0, 64);
    }
}
