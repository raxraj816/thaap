package com.thaap.payments.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C55-REC-001 against real PostgreSQL. Spring Batch owns restart mechanics, but durable
 * request/item rows own product truth: a worker that dies mid-run must resume from committed item
 * evidence, never reapply a completed item's source effect, never let one poison item strand the
 * eligible items behind it, and derive the request's terminal status only from item terminals.
 */
class ReviewBulkActionRestartPostgreSqlTests extends ReviewBulkPostgreSqlTestBase {

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
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator analyst;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("bulk-restart");
        analyst = scenario.operator(merchant, "bulk-restart-analyst", "ANALYST");
    }

    @Test
    void aWorkerThatDiesMidRunResumesFromItemTruthWithoutReapplyingACompletedAssignment()
            throws Exception {
        List<UUID> reviewCaseIds = reviewCases(3, "restart-resume");
        UUID requestId = seedRequest(reviewCaseIds, "resume");

        // First worker completes only the first item, then "crashes" before finalizing.
        ReviewBulkActionStore.Lease first = store.claim(requestId, "worker-one");
        itemExecutor.execute(store.claimNext(first));
        assertThat(itemStatuses(requestId)).containsExactly("SUCCEEDED", "PENDING", "PENDING");
        long assignmentEventsAfterCrash = assignmentEventCount(reviewCaseIds.getFirst());
        assertThat(assignmentEventsAfterCrash).isEqualTo(1);

        expireLease(requestId);

        // A second worker fences the first and resumes from durable item truth.
        ReviewBulkActionStore.Lease second = store.claim(requestId, "worker-two");
        assertThat(second).isNotNull();
        assertThat(second.epoch()).isEqualTo(first.epoch() + 1);
        ReviewBulkActionStore.ClaimedItem item;
        while ((item = store.claimNext(second)) != null) {
            itemExecutor.execute(item);
        }
        store.finalizeRequest(second);

        assertThat(itemStatuses(requestId))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertThat(assignmentEventCount(reviewCaseIds.getFirst()))
                .as("the already-completed item must not have its assignment applied twice")
                .isEqualTo(assignmentEventsAfterCrash);
        for (UUID reviewCaseId : reviewCaseIds) {
            assertThat(assignmentEventCount(reviewCaseId)).isEqualTo(1);
            assertThat(reviewCaseRepository.findByIdAndMerchantId(reviewCaseId, merchant.id())
                    .orElseThrow().getStatus()).isEqualTo(ManualReviewCaseStatus.ASSIGNED);
        }

        Map<String, Object> request = requestRow(requestId);
        assertThat(request.get("lifecycle_status")).isEqualTo("COMPLETED");
        assertThat(((Number) request.get("succeeded_count")).intValue()).isEqualTo(3);
        assertThat(((Number) request.get("failed_count")).intValue()).isZero();
    }

    @Test
    void onePoisonItemNeverStrandsTheEligibleItemsQueuedBehindIt() throws Exception {
        List<UUID> reviewCaseIds = reviewCases(3, "restart-poison");
        // Middle item carries an impossible expected version, so it must terminate STALE while
        // its neighbours still succeed.
        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("poison-idem"),
                sixtyFourChars("poison-digest"), 3, Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseIds.get(0), 0, 1,
                Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseIds.get(1), 99, 2,
                Instant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseIds.get(2), 0, 3,
                Instant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "poison-worker");
        ReviewBulkActionStore.ClaimedItem item;
        while ((item = store.claimNext(lease)) != null) {
            itemExecutor.execute(item);
        }
        store.finalizeRequest(lease);

        assertThat(itemStatuses(requestId)).containsExactly("SUCCEEDED", "STALE", "SUCCEEDED");
        Map<String, Object> request = requestRow(requestId);
        assertThat(request.get("lifecycle_status"))
                .as("a mixed run is PARTIAL, never a blanket success or failure")
                .isEqualTo("PARTIAL");
        assertThat(((Number) request.get("succeeded_count")).intValue()).isEqualTo(2);
        assertThat(((Number) request.get("failed_count")).intValue()).isEqualTo(1);

        // The stale item applied no source effect at all.
        assertThat(assignmentEventCount(reviewCaseIds.get(1))).isZero();
        assertThat(reviewCaseRepository.findByIdAndMerchantId(reviewCaseIds.get(1), merchant.id())
                .orElseThrow().getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
    }

    @Test
    void aRequestIsNotClaimableOnceTerminalAndCannotBeFinalizedTwice() throws Exception {
        List<UUID> reviewCaseIds = reviewCases(1, "restart-terminal");
        UUID requestId = seedRequest(reviewCaseIds, "terminal");

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "terminal-worker");
        itemExecutor.execute(store.claimNext(lease));
        store.finalizeRequest(lease);
        assertThat(requestRow(requestId).get("lifecycle_status")).isEqualTo("COMPLETED");

        // A completed request is closed to any further worker.
        assertThat(store.claim(requestId, "late-worker")).isNull();

        // It cannot even be nudged back into recoverability: the terminal guard refuses to let
        // an operator expire a completed request's lease to make it claimable again.
        assertThatThrownBy(() -> expireLease(requestId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("immutable history was changed");
        assertThat(store.claim(requestId, "later-worker")).isNull();

        // And the original lease can never finalize it a second time.
        assertThatThrownBy(() -> store.finalizeRequest(lease))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fence was lost");
        assertThat(requestRow(requestId).get("lifecycle_status")).isEqualTo("COMPLETED");
    }

    @Test
    void aRequestWithItemsStillPendingRefusesToFinalizeRatherThanReportAFalseTotal()
            throws Exception {
        List<UUID> reviewCaseIds = reviewCases(2, "restart-incomplete");
        UUID requestId = seedRequest(reviewCaseIds, "incomplete");

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "incomplete-worker");
        itemExecutor.execute(store.claimNext(lease));

        assertThatThrownBy(() -> store.finalizeRequest(lease))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("still has pending items");
        assertThat(requestRow(requestId).get("lifecycle_status")).isEqualTo("RUNNING");
    }

    @Test
    void anExpiredRunningRequestIsOfferedToRecoveryWhileATerminalOneIsNot() throws Exception {
        List<UUID> stuckCases = reviewCases(1, "restart-stuck");
        UUID stuckRequestId = seedRequest(stuckCases, "stuck");
        ReviewBulkActionStore.Lease stuckLease = store.claim(stuckRequestId, "abandoning-worker");
        store.claimNext(stuckLease);
        expireLease(stuckRequestId);

        List<UUID> doneCases = reviewCases(1, "restart-done");
        UUID doneRequestId = seedRequest(doneCases, "done");
        ReviewBulkActionStore.Lease doneLease = store.claim(doneRequestId, "finishing-worker");
        itemExecutor.execute(store.claimNext(doneLease));
        store.finalizeRequest(doneLease);

        List<UUID> recoverable = store.recoverableRequestIds(25);
        assertThat(recoverable).contains(stuckRequestId).doesNotContain(doneRequestId);
    }

    private List<UUID> reviewCases(int count, String prefix) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Payment payment = scenario.reviewedPayment(merchant, prefix + "-" + index);
            assertThat(payment.reviewCaseId()).isNotNull();
            ids.add(payment.reviewCaseId());
        }
        return ids;
    }

    private UUID seedRequest(List<UUID> reviewCaseIds, String seed) {
        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars(seed + "-idem"),
                sixtyFourChars(seed + "-digest"), reviewCaseIds.size(), Instant.now());
        int ordinal = 1;
        for (UUID reviewCaseId : reviewCaseIds) {
            store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0,
                    ordinal++, Instant.now());
        }
        return requestId;
    }

    private void expireLease(UUID requestId) {
        jdbcTemplate.update("""
                UPDATE review_bulk_action_requests
                SET lease_expires_at = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(clock.instant().minusSeconds(1)),
                Timestamp.from(clock.instant()), requestId);
    }

    private List<String> itemStatuses(UUID requestId) {
        return jdbcTemplate.queryForList("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ? ORDER BY ordinal
                """, String.class, requestId);
    }

    private Map<String, Object> requestRow(UUID requestId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM review_bulk_action_requests WHERE id = ?", requestId);
    }

    private long assignmentEventCount(UUID reviewCaseId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events WHERE manual_review_case_id = ?
                """, Long.class, reviewCaseId);
        return value == null ? 0 : value;
    }

    /** Deterministic exactly-64-character value for columns the schema pins at CHAR_LENGTH 64. */
    private String sixtyFourChars(String seed) {
        StringBuilder value = new StringBuilder(seed);
        while (value.length() < 64) value.append('0');
        return value.substring(0, 64);
    }
}
