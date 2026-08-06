package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C56-REV-001. Review-operation insights are the one insight surface backed by
 * authoritative source rows rather than a projection, so its numbers must reconcile against those
 * rows exactly — and the history it does not have must be named unavailable rather than inferred
 * from current state, because a zero that means "we did not record this" reads identically to a
 * zero that means "it never happened".
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ReviewOperationInsightContractTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;
    private Operator analyst;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("review-insight");
        manager = scenario.operator(merchant, "review-insight-manager", "RISK_MANAGER");
        analyst = scenario.operator(merchant, "review-insight-analyst", "ANALYST");
    }

    @Test
    void backlogReconcilesExactlyAgainstAuthoritativeReviewRows() throws Exception {
        scenario.reviewedPayment(merchant, "rev-backlog-a");
        scenario.reviewedPayment(merchant, "rev-backlog-b");
        scenario.reviewedPayment(merchant, "rev-backlog-c");

        JsonNode body = reviewOperations(manager);
        long openRows = countReviews("OPEN");
        long assignedRows = countReviews("ASSIGNED");

        assertThat(body.at("/backlog/unassigned").asLong())
                .as("backlog must equal the authoritative row count, not an approximation")
                .isEqualTo(openRows);
        assertThat(body.at("/backlog/assigned").asLong()).isEqualTo(assignedRows);
        assertThat(body.at("/backlog/total").asLong()).isEqualTo(openRows + assignedRows);
        assertThat(body.get("authority").asText()).isEqualTo("AUTHORITATIVE_REVIEW_SOURCE");
    }

    @Test
    void anAssignmentMovesACaseBetweenBacklogBucketsAndIsCountedOnce() throws Exception {
        Payment payment = scenario.reviewedPayment(merchant, "rev-assign");
        UUID reviewCaseId = payment.reviewCaseId();

        JsonNode before = reviewOperations(manager);
        long unassignedBefore = before.at("/backlog/unassigned").asLong();
        long assignedBefore = before.at("/backlog/assigned").asLong();
        long assignmentsBefore = before.get("assignments").asLong();

        assign(reviewCaseId, analyst.id(), 0, "rev-assign-1");
        backdateActivityIntoTheWindow();

        JsonNode after = reviewOperations(manager);
        assertThat(after.at("/backlog/unassigned").asLong()).isEqualTo(unassignedBefore - 1);
        assertThat(after.at("/backlog/assigned").asLong()).isEqualTo(assignedBefore + 1);
        assertThat(after.get("assignments").asLong())
                .as("one assignment must count once; a reassignment counting twice would "
                        + "overstate throughput")
                .isEqualTo(assignmentsBefore + 1);
        assertThat(after.get("releases").asLong()).isZero();
    }

    @Test
    void aReassignmentAndAReleaseAreCountedUnderTheirOwnActivityRatherThanMerged()
            throws Exception {
        Payment payment = scenario.reviewedPayment(merchant, "rev-reassign");
        UUID reviewCaseId = payment.reviewCaseId();

        assign(reviewCaseId, analyst.id(), 0, "rev-reassign-1");
        assign(reviewCaseId, manager.id(), 1, "rev-reassign-2");
        release(reviewCaseId, 2, "rev-release-1");
        backdateActivityIntoTheWindow();

        JsonNode body = reviewOperations(manager);
        assertThat(body.get("assignments").asLong())
                .as("an initial assignment and a reassignment are both assignment activity")
                .isEqualTo(2);
        assertThat(body.get("releases").asLong())
                .as("a release is its own activity, never folded into assignments")
                .isEqualTo(1);

        // The case is back in the unassigned bucket, reconciled against the source row.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM manual_review_cases WHERE id = ?
                """, String.class, reviewCaseId)).isEqualTo("OPEN");
        assertThat(body.at("/backlog/unassigned").asLong()).isEqualTo(countReviews("OPEN"));

        // Every counted assignment corresponds to a durable event row, not a derived guess.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM review_assignment_events
                WHERE merchant_id = ? AND event_type IN ('ASSIGNED', 'REASSIGNED')
                """, Long.class, merchant.id())).isEqualTo(2L);
    }

    @Test
    void timeToAssignmentReportsItsEligibleAndMissingCountsRatherThanAnAverageAlone()
            throws Exception {
        Payment assigned = scenario.reviewedPayment(merchant, "rev-timing-a");
        scenario.reviewedPayment(merchant, "rev-timing-b");
        assign(assigned.reviewCaseId(), analyst.id(), 0, "rev-timing-assign");
        backdateActivityIntoTheWindow();

        JsonNode timing = reviewOperations(manager).get("timeToAssignment");
        assertThat(timing.get("eligibleCount").asLong())
                .as("only the assigned case can contribute a duration")
                .isEqualTo(1);
        assertThat(timing.get("totalMilliseconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(timing.hasNonNull("minimumMilliseconds")).isTrue();
        assertThat(timing.hasNonNull("maximumMilliseconds")).isTrue();
        assertThat(timing.has("missingCount"))
                .as("a duration statistic that hides how many cases it could not measure is "
                        + "an average over an unstated population")
                .isTrue();
    }

    @Test
    void anEmptyReviewPopulationReportsZeroesAndNoDurationRatherThanAFabricatedAverage()
            throws Exception {
        JsonNode body = reviewOperations(manager);

        assertThat(body.at("/backlog/total").asLong()).isZero();
        assertThat(body.get("assignments").asLong()).isZero();
        assertThat(body.get("resolutions").asLong()).isZero();
        JsonNode timing = body.get("timeToAssignment");
        assertThat(timing.get("eligibleCount").asLong()).isZero();
        assertThat(timing.get("minimumMilliseconds").isNull())
                .as("no eligible case means no minimum; a 0 here would read as instant assignment")
                .isTrue();
        assertThat(timing.get("maximumMilliseconds").isNull()).isTrue();
    }

    @Test
    void absentConflictHistoryIsNamedUnavailableRatherThanReportedAsZero() throws Exception {
        scenario.reviewedPayment(merchant, "rev-conflict");

        JsonNode body = reviewOperations(manager);
        assertThat(body.get("conflictedAttempts").isNull())
                .as("the platform stores no durable conflict facts, and a 0 would assert that "
                        + "no conflict ever happened")
                .isTrue();
        assertThat(body.get("conflictedAttemptsStatus").asText())
                .isEqualTo("CONFLICT_ATTEMPTS_NOT_AVAILABLE_WITHOUT_DURABLE_CONFLICT_FACTS");
    }

    @Test
    void theSourceAsOfIsIndependentOfAnyProjectionFreshness() throws Exception {
        scenario.reviewedPayment(merchant, "rev-asof");
        scenario.drainProjection();

        JsonNode reviews = reviewOperations(manager);
        assertThat(reviews.hasNonNull("sourceAsOf")).isTrue();
        assertThat(reviews.has("freshness"))
                .as("review operations read source rows, so publishing a projection freshness "
                        + "here would invite reading a stale number as current source truth")
                .isFalse();
        assertThat(reviews.has("generationId")).isFalse();
    }

    /**
     * Moves durable assignment and resolution evidence into a complete prior UTC day.
     *
     * <p>The frozen range contract ends every window at the start of the current UTC day, so
     * activity produced during a test run is always in the excluded partial day and no valid
     * range can count it. Only the event timestamps move; the events themselves were produced by
     * the real assignment and release routes. This works because the append-only trigger on
     * `review_assignment_events` is a PostgreSQL-only migration, and that immutability is proven
     * separately on real PostgreSQL rather than assumed here.
     */
    private void backdateActivityIntoTheWindow() {
        java.time.Instant shifted = java.time.Instant.now()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().minus(2, java.time.temporal.ChronoUnit.DAYS);
        jdbcTemplate.update("UPDATE review_assignment_events SET created_at = ? WHERE merchant_id = ?",
                java.sql.Timestamp.from(shifted), merchant.id());
        jdbcTemplate.update("UPDATE manual_review_cases SET created_at = ? WHERE merchant_id = ?",
                java.sql.Timestamp.from(shifted.minusSeconds(3600)), merchant.id());
    }

    private JsonNode reviewOperations(Operator operator) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/review-operations")
                        .header(RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void assign(UUID reviewCaseId, UUID operatorId, long expectedVersion, String key)
            throws Exception {
        mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"operatorId\":\"" + operatorId
                                + "\",\"expectedAssignmentVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private void release(UUID reviewCaseId, long expectedVersion, String key) throws Exception {
        mockMvc.perform(post("/risk/reviews/{id}/release", reviewCaseId)
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .header(IDEMPOTENCY_HEADER, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedAssignmentVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private long countReviews(String status) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM manual_review_cases WHERE merchant_id = ? AND status = ?
                """, Long.class, merchant.id(), status);
        return value == null ? 0 : value;
    }
}
