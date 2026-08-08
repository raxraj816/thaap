package com.thaap.payments.bulk;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C55-ATO-001 against real PostgreSQL: a bulk item's assignment write, assignment event,
 * audit entry, and terminal item outcome commit as one fenced transaction, and any fence loss
 * discovered only at the final terminal-item write rolls every earlier effect in that same
 * transaction back rather than leaving a committed assignment beside a lost item outcome.
 */
class ReviewBulkActionAtomicityPostgreSqlTests extends ReviewBulkPostgreSqlTestBase {


    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkActionStore store;
    @Autowired private ReviewBulkDomainMutation domainMutation;
    @Autowired private ReviewBulkItemExecutor itemExecutor;
    @Autowired private ManualReviewCaseRepository reviewCaseRepository;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;


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
        merchant = scenario.merchant("bulk-atomicity");
        analyst = scenario.operator(merchant, "bulk-atomicity-analyst", "ANALYST");
    }

    @Test
    void aFenceLossDiscoveredOnlyAtTheTerminalItemWriteRollsBackTheAssignmentEventAndAudit()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-fence");
        UUID reviewCaseId = reviewed.reviewCaseId();
        ManualReviewCase before = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
        long versionBefore = before.getAssignmentVersion();
        long assignmentEventsBefore = countRows("review_assignment_events", reviewCaseId);
        long auditEntriesBefore = countRows("audit_logs", reviewCaseId);

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("fence-idem"),
                sixtyFourChars("fence-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                DatabaseInstant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "atomicity-test-worker");
        assertThat(lease).isNotNull();
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);
        assertThat(item).isNotNull();

        // Simulate a takeover between the claim and the domain commit: another worker's recovery
        // path bumps the request's lease epoch, so this worker's terminal write is fenced out even
        // though it already holds a claimed item bound to the old epoch.
        jdbcTemplate.update("""
                UPDATE review_bulk_action_requests SET lease_epoch = lease_epoch + 1 WHERE id = ?
                """, requestId);

        // Spring wraps the IllegalStateException ReviewBulkActionStore throws because the store
        // is a @Repository, so persistence-exception translation intercepts it (the same
        // mechanism ER-0090 documented for ProjectionDefinitionStore). The concrete wrapper type
        // is an implementation detail; what C55-ATO-001 requires is that the failure still rolls
        // the transaction back, which the assertions below prove directly against the database.
        assertThatThrownBy(() -> domainMutation.apply(item))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fence was lost");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).as("the review case must not end up assigned when the "
                + "terminal item write that would have proven it was fenced out")
                .isEqualTo(ManualReviewCaseStatus.OPEN);
        assertThat(after.getAssignmentVersion()).isEqualTo(versionBefore);
        assertThat(after.getAssignedOperatorId()).isNull();
        assertThat(countRows("review_assignment_events", reviewCaseId))
                .as("the assignment event written inside the rolled-back transaction must not "
                        + "survive alongside a lost terminal item outcome")
                .isEqualTo(assignmentEventsBefore);
        assertThat(countRows("audit_logs", reviewCaseId)).isEqualTo(auditEntriesBefore);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("CLAIMED");
    }

    @Test
    void aStaleExpectedAssignmentVersionFailsTheItemAsStaleWithoutMutatingTheReviewCase()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-stale");
        UUID reviewCaseId = reviewed.reviewCaseId();
        long assignmentEventsBefore = countRows("review_assignment_events", reviewCaseId);

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("exec-idem"),
                sixtyFourChars("exec-digest"), 1, DatabaseInstant.now());
        // The review case's real assignment version is 0; requesting version 7 is the
        // "stale expected version" adversarial case the matrix names explicitly.
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 7, 1,
                DatabaseInstant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "executor-test-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        // ManualReviewCase.assign() refuses the version mismatch with a 409, which
        // ReviewBulkItemExecutor.classify() maps to the terminal STALE item outcome rather than
        // crashing the worker or applying any part of the assignment.
        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        String itemOutcome = jdbcTemplate.queryForObject("""
                SELECT lifecycle_status || ':' || failure_code FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId);
        assertThat(itemOutcome).isEqualTo("STALE:assignment_version_stale");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_requests WHERE id = ?
                """, String.class, requestId)).isEqualTo("FAILED");

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
        assertThat(after.getAssignedOperatorId()).isNull();
        assertThat(after.getAssignmentVersion()).isZero();
        assertThat(countRows("review_assignment_events", reviewCaseId))
                .as("a rejected stale-version item must not write an assignment event")
                .isEqualTo(assignmentEventsBefore);
    }

    @Test
    void aSuccessfulItemAtomicallyCommitsTheAssignmentEventAuditAndTerminalOutcomeTogether()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-success");
        UUID reviewCaseId = reviewed.reviewCaseId();
        long assignmentEventsBefore = countRows("review_assignment_events", reviewCaseId);
        long auditEntriesBefore = countRows("audit_logs", reviewCaseId);

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("ok-idem"),
                sixtyFourChars("ok-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                DatabaseInstant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "success-test-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);
        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.ASSIGNED);
        assertThat(after.getAssignedOperatorId()).isEqualTo(analyst.id());
        assertThat(countRows("review_assignment_events", reviewCaseId))
                .isEqualTo(assignmentEventsBefore + 1);
        assertThat(countRows("audit_logs", reviewCaseId)).isEqualTo(auditEntriesBefore + 1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_requests WHERE id = ?
                """, String.class, requestId)).isEqualTo("COMPLETED");
    }

    /**
     * The RELEASE arm of the closed action vocabulary, which the assignment tests never exercise.
     * A release must atomically clear the assignment, emit exactly one further assignment event,
     * and record its own terminal item outcome.
     */
    @Test
    void aReleaseItemAtomicallyClearsTheAssignmentAndRecordsItsOwnTerminalOutcome()
            throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-release");
        UUID reviewCaseId = reviewed.reviewCaseId();

        // Assign first through the ordinary single-case route so the release has something to undo.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/risk/reviews/{id}/assign", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, analyst.credential())
                        .header(com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER,
                                "release-setup-assign")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"" + analyst.id()
                                + "\",\"expectedAssignmentVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
        long assignmentEventsAfterAssign = countRows("review_assignment_events", reviewCaseId);

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.RELEASE, null, sixtyFourChars("rel-idem"),
                sixtyFourChars("rel-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 1, 1,
                DatabaseInstant.now());

        ReviewBulkActionStore.Lease lease = store.claim(requestId, "release-test-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);
        itemExecutor.execute(item);
        store.finalizeRequest(lease);

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.OPEN);
        assertThat(after.getAssignedOperatorId()).isNull();
        assertThat(after.getAssignmentVersion()).isEqualTo(2L);
        assertThat(countRows("review_assignment_events", reviewCaseId))
                .isEqualTo(assignmentEventsAfterAssign + 1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_requests WHERE id = ?
                """, String.class, requestId)).isEqualTo("COMPLETED");
    }

    @Test
    void aNoOpSelfAssignmentSucceedsAsAProvenNoOpWithoutEmittingASecondAssignmentEffect()
            throws Exception {
        Operator manager = scenario.operator(merchant, "bulk-atomicity-manager", "RISK_MANAGER");
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-noop");
        UUID reviewCaseId = reviewed.reviewCaseId();

        // First request assigns the case to the manager, taking it from version 0 to 1.
        UUID firstRequestId = UUID.randomUUID();
        store.insertRequest(firstRequestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("noop-first-idem"),
                sixtyFourChars("noop-first-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), firstRequestId, reviewCaseId, 0, 1,
                DatabaseInstant.now());
        ReviewBulkActionStore.Lease firstLease = store.claim(firstRequestId, "noop-worker-one");
        itemExecutor.execute(store.claimNext(firstLease));
        store.finalizeRequest(firstLease);

        long assignmentEventsAfterFirst = countRows("review_assignment_events", reviewCaseId);
        long auditEntriesAfterFirst = countRows("audit_logs", reviewCaseId);
        assertThat(assignmentEventsAfterFirst).isEqualTo(1);

        // A second request re-assigns the same case to the same operator at the current version.
        // The frozen item-state table admits this as SUCCEEDED via "proven no-op": the requested
        // end state already holds, so no second event, audit row, or version bump may appear.
        UUID secondRequestId = UUID.randomUUID();
        store.insertRequest(secondRequestId, merchant.id(), manager.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("noop-second-idem"),
                sixtyFourChars("noop-second-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), secondRequestId, reviewCaseId, 1, 1,
                DatabaseInstant.now());
        ReviewBulkActionStore.Lease secondLease = store.claim(secondRequestId, "noop-worker-two");
        itemExecutor.execute(store.claimNext(secondLease));
        store.finalizeRequest(secondLease);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, secondRequestId)).isEqualTo("SUCCEEDED");
        assertThat(countRows("review_assignment_events", reviewCaseId))
                .as("a no-op assignment must not emit a duplicate assignment event")
                .isEqualTo(assignmentEventsAfterFirst);
        assertThat(countRows("audit_logs", reviewCaseId))
                .as("a no-op assignment must not emit a duplicate audit entry")
                .isEqualTo(auditEntriesAfterFirst);

        ManualReviewCase after = reviewCaseRepository.findByIdAndMerchantId(
                reviewCaseId, merchant.id()).orElseThrow();
        assertThat(after.getAssignmentVersion())
                .as("a no-op must not advance the assignment version")
                .isEqualTo(1L);
        assertThat(after.getAssignedOperatorId()).isEqualTo(manager.id());
        assertThat(after.getStatus()).isEqualTo(ManualReviewCaseStatus.ASSIGNED);
    }

    @Test
    void aRolledBackItemIncrementsNoTerminalMeterSoCountersMatchDurableRows() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "bulk-atomicity-metrics");
        UUID reviewCaseId = reviewed.reviewCaseId();

        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("metric-idem"),
                sixtyFourChars("metric-digest"), 1, DatabaseInstant.now());
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, reviewCaseId, 0, 1,
                DatabaseInstant.now());
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "metric-worker");
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);

        double succeededBefore = itemCounter("SUCCEEDED");
        double failedBefore = itemCounter("FAILED");

        // Fence the worker out, then run the real executor path.
        jdbcTemplate.update("""
                UPDATE review_bulk_action_requests SET lease_epoch = lease_epoch + 1 WHERE id = ?
                """, requestId);
        assertThatThrownBy(() -> itemExecutor.execute(item))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fence was lost");

        // No terminal item row was written, so no terminal meter may have moved. A metric that
        // counted the pre-read intention would drift permanently from the durable truth.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_bulk_action_items
                WHERE bulk_action_request_id = ?
                """, String.class, requestId)).isEqualTo("CLAIMED");
        assertThat(itemCounter("SUCCEEDED")).isEqualTo(succeededBefore);
        assertThat(itemCounter("FAILED")).isEqualTo(failedBefore);

        long terminalRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_bulk_action_items
                WHERE bulk_action_request_id = ? AND lifecycle_status IN
                  ('SUCCEEDED', 'STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED')
                """, Long.class, requestId);
        assertThat(terminalRows).isZero();
    }

    private double itemCounter(String result) {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find("thaap.decision_intelligence.bulk.items").tag("result", result).counter();
        return counter == null ? 0d : counter.count();
    }

    private long countRows(String table, UUID reviewCaseId) {
        String column = table.equals("audit_logs") ? "aggregate_id" : "manual_review_case_id";
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, reviewCaseId);
        return value == null ? 0 : value;
    }

    /** Deterministic exactly-64-character value for columns the schema pins at CHAR_LENGTH 64. */
    private String sixtyFourChars(String seed) {
        StringBuilder value = new StringBuilder(seed);
        while (value.length() < 64) value.append('0');
        return value.substring(0, 64);
    }
}
