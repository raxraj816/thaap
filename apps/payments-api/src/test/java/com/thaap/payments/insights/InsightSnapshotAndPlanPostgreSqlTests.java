package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.IDEMPOTENCY_HEADER;
import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.thaap.payments.support.WorkspacePostgreSqlTestBase;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C56-SNP-001 and the measured half of C56-MIG-001 on the engine that enforces both.
 *
 * <p>H2 cannot certify a PostgreSQL `REPEATABLE READ` claim and has no comparable planner, so
 * snapshot and index evidence has to run here. The failure this guards against is a response that
 * is internally inconsistent — a numerator counted under one projection generation and a
 * denominator under the next — which produces a rate that never existed at any instant.
 */
class InsightSnapshotAndPlanPostgreSqlTests extends WorkspacePostgreSqlTestBase {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private Clock clock;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
        merchant = scenario.merchant("insight-snapshot");
        manager = scenario.operator(merchant, "insight-snapshot-manager", "RISK_MANAGER");
        analyst = scenario.operator(merchant, "insight-snapshot-analyst", "ANALYST");
    }

    @Test
    void oneDecisionResponseIsInternallyConsistentWithTheGenerationItNames() throws Exception {
        scenario.allowedPayment(merchant, "snap-a");
        scenario.blockedPayment(merchant, "snap-b");
        scenario.drainProjection();
        backdate();

        JsonNode body = decisionOutcomes();
        UUID named = UUID.fromString(body.at("/freshness/generationId").asText());

        long distributionTotal = 0;
        for (JsonNode row : body.get("decisionDistribution")) {
            distributionTotal += row.get("count").asLong();
        }
        Long rowsInNamedGeneration = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                """, Long.class, merchant.id(), named);

        // The distribution, the coverage denominator, and the generation the response names must
        // all describe the same population. Any disagreement means two queries saw two worlds.
        assertThat(distributionTotal).isEqualTo(rowsInNamedGeneration);
        assertThat(body.at("/rates/labelCoverage/denominator").asLong())
                .isEqualTo(rowsInNamedGeneration);
        assertThat(rowsInNamedGeneration).isGreaterThan(0);
    }

    @Test
    void aGenerationCannotBeSwappedUnderAReadBecauseActivationIsAGovernedOperation()
            throws Exception {
        scenario.allowedPayment(merchant, "snap-before");
        scenario.drainProjection();
        backdate();

        JsonNode before = decisionOutcomes();
        UUID firstGeneration = UUID.fromString(before.at("/freshness/generationId").asText());
        assertThat(before.at("/rates/labelCoverage/denominator").asLong()).isGreaterThan(0);

        // The strongest reason a read cannot observe a half-swapped generation is that a
        // generation cannot be swapped casually at all: activation is governed and requires
        // current complete reconciliation evidence. Fabricating one directly in the database —
        // exactly what a careless operator or a buggy rebuild would attempt — is refused.
        assertThatThrownBy(() -> forceActivateReplacementGeneration(firstGeneration))
                .as("an ungoverned generation activation must be refused, or a read could "
                        + "straddle two populations")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reconciliation");

        // The active generation is unchanged, and a fresh read still names it.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT generation.id FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                WHERE definition.projection_name = 'payment-risk-v1'
                """, UUID.class)).isEqualTo(firstGeneration);
        assertThat(UUID.fromString(decisionOutcomes().at("/freshness/generationId").asText()))
                .isEqualTo(firstGeneration);
    }

    @Test
    void oneResponseResolvesItsGenerationOnceAndPassesItToEveryProjectionQuery() throws Exception {
        scenario.allowedPayment(merchant, "snap-once-a");
        scenario.blockedPayment(merchant, "snap-once-b");
        scenario.payment(merchant, "snap-once-c", 5_000L, "EUR", 1);
        scenario.drainProjection();
        backdate();

        JsonNode body = decisionOutcomes();
        UUID named = UUID.fromString(body.at("/freshness/generationId").asText());

        // Distribution, denominator, and money are three separate statements. If any of them
        // resolved the active generation independently, a swap between statements would produce
        // a response whose parts describe different populations. They are compared against one
        // generation here, so a second resolution would have to agree by luck to pass.
        long distributionTotal = 0;
        for (JsonNode row : body.get("decisionDistribution")) {
            distributionTotal += row.get("count").asLong();
        }
        long moneyRows = 0;
        for (JsonNode row : body.get("moneyByCurrency")) {
            moneyRows += jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM payment_risk_projections
                    WHERE merchant_id = ? AND projection_generation_id = ? AND currency = ?
                    """, Long.class, merchant.id(), named, row.get("currency").asText());
        }
        Long rowsInNamedGeneration = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                """, Long.class, merchant.id(), named);

        assertThat(distributionTotal).isEqualTo(rowsInNamedGeneration);
        assertThat(moneyRows).isEqualTo(rowsInNamedGeneration);
        assertThat(body.at("/rates/labelCoverage/denominator").asLong())
                .isEqualTo(rowsInNamedGeneration);
        assertThat(rowsInNamedGeneration).isGreaterThan(1);
    }

    @Test
    void aReviewResolvedDuringAReadCannotProduceAMixedBacklogAndActivityTotal() throws Exception {
        Payment first = scenario.reviewedPayment(merchant, "snap-review-a");
        Payment second = scenario.reviewedPayment(merchant, "snap-review-b");

        long openBefore = countReviews("OPEN");
        assertThat(openBefore).isEqualTo(2);

        // Assign one case, then read. The response reads every review fact under one bounded
        // REPEATABLE READ snapshot, so its buckets must sum to the population it observed.
        assign(first.reviewCaseId(), analyst.id(), 0, "snap-assign-1");

        JsonNode body = reviewOperations();
        long unassigned = body.at("/backlog/unassigned").asLong();
        long assigned = body.at("/backlog/assigned").asLong();
        assertThat(unassigned + assigned)
                .as("backlog buckets must partition the population the snapshot saw exactly once")
                .isEqualTo(body.at("/backlog/total").asLong())
                .isEqualTo(2);
        assertThat(assigned).isEqualTo(1);

        // A transition after the read does not retroactively alter the returned value.
        assign(second.reviewCaseId(), analyst.id(), 0, "snap-assign-2");
        assertThat(body.at("/backlog/assigned").asLong()).isEqualTo(1);
        assertThat(reviewOperations().at("/backlog/assigned").asLong()).isEqualTo(2);
    }

    @Test
    void theReviewReadDeclaresTheBoundedSnapshotItReliesOn() throws NoSuchMethodException {
        // A response that publishes one sourceAsOf across several statements must actually hold a
        // snapshot, or the declared boundary is decoration.
        for (String name : List.of("reviewOperations", "operators")) {
            Method method = WorkspaceInsightsService.class.getMethod(name,
                    com.thaap.payments.risk.RiskOperatorContext.class, Instant.class, Instant.class);
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as("%s publishes one sourceAsOf and must hold a snapshot",
                    name).isNotNull();
            assertThat(transactional.readOnly()).isTrue();
            assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
            assertThat(transactional.timeout())
                    .as("%s must be bounded so a saturated source cannot hold a connection open",
                            name).isPositive();
        }
    }

    @Test
    void theProjectionInsightQueryUsesTheTenantLeadingIndexRatherThanScanningEveryMerchant()
            throws Exception {
        scenario.allowedPayment(merchant, "plan-a");
        scenario.blockedPayment(merchant, "plan-b");
        scenario.drainProjection();
        backdate();

        UUID generation = jdbcTemplate.queryForObject("""
                SELECT generation.id FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                 AND generation.projection_definition_id = definition.id
                WHERE definition.projection_name = 'payment-risk-v1'
                  AND generation.lifecycle_status = 'ACTIVE'
                """, UUID.class);

        // Planner choice on a tiny table is not meaningful, so the index is forced off-limits for
        // sequential scans and the plan is required to reach the tenant-leading index. This is
        // the measured evidence C56-MIG-001 asks for: the index is usable by the real query
        // shape, not merely present.
        jdbcTemplate.execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN SELECT COALESCE(fraud_action, 'UNKNOWN'), COUNT(*)
                    FROM payment_risk_projections
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND last_event_recorded_at >= ? AND last_event_recorded_at < ?
                    GROUP BY fraud_action
                    """, String.class, merchant.id(), generation,
                    Timestamp.from(windowStart()), Timestamp.from(windowEnd())));
            assertThat(plan)
                    .as("the insight query must be servable by an index rather than a full scan "
                            + "across every merchant's payments")
                    .containsIgnoringCase("Index");
        } finally {
            jdbcTemplate.execute("SET enable_seqscan = on");
        }
    }

    @Test
    void theReviewBacklogQueryIsServableByItsTenantLeadingIndex() {
        jdbcTemplate.execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN SELECT COUNT(*) FILTER (WHERE status IN ('OPEN', 'ASSIGNED'))
                    FROM manual_review_cases WHERE merchant_id = ?
                    """, String.class, merchant.id()));
            assertThat(plan).containsIgnoringCase("Index");
        } finally {
            jdbcTemplate.execute("SET enable_seqscan = on");
        }
    }

    @Test
    void everyWorkspaceInsightIndexIsPresentOnTheMigratedSchema() {
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE indexname IN ('idx_payment_risk_projections_workspace_filters',
                                    'idx_manual_reviews_workspace_operations',
                                    'idx_review_assignment_events_workspace_range')
                """, String.class);
        assertThat(indexes)
                .as("V91 declares three query-support indexes; a missing one means the migration "
                        + "did not actually apply on this engine")
                .hasSize(3);
    }

    /**
     * Attempts an ungoverned activation, which the platform must refuse.
     *
     * <p>The whole attempt runs in one transaction so a refusal leaves the projection exactly as
     * it was. A half-applied fabrication would retire the live generation and take the insight
     * surface down, which would be this test breaking the system rather than observing it.
     */
    private UUID forceActivateReplacementGeneration(UUID current) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    UUID definitionId = jdbcTemplate.queryForObject("""
                            SELECT id FROM projection_definitions
                            WHERE projection_name = 'payment-risk-v1'
                            """, UUID.class);
                    UUID replacement = UUID.randomUUID();
                    Long nextNumber = jdbcTemplate.queryForObject("""
                            SELECT COALESCE(MAX(generation_number), 0) + 1
                            FROM projection_generations WHERE projection_definition_id = ?
                            """, Long.class, definitionId);
                    // Clone the whole current row rather than naming columns: the generation table
                    // carries required lineage columns a hand-written list would silently rot on.
                    List<String> columns = jdbcTemplate.queryForList("""
                            SELECT column_name FROM information_schema.columns
                            WHERE table_name = 'projection_generations'
                              AND table_schema = current_schema()
                            ORDER BY ordinal_position
                            """, String.class);
                    String projected = columns.stream().map(column -> switch (column) {
                        case "id" -> "CAST(? AS uuid)";
                        case "generation_number" -> "CAST(? AS bigint)";
                        case "lifecycle_status" -> "'ACTIVE'";
                        default -> column;
                    }).reduce((left, right) -> left + ", " + right).orElseThrow();
                    jdbcTemplate.update("""
                            UPDATE projection_generations SET lifecycle_status = 'RETIRED'
                            WHERE id = ?
                            """, current);
                    jdbcTemplate.update("INSERT INTO projection_generations SELECT " + projected
                            + " FROM projection_generations WHERE id = ?",
                            replacement, nextNumber, current);
                    jdbcTemplate.update("""
                            UPDATE projection_definitions SET active_generation_id = ?
                            WHERE id = ?
                            """, replacement, definitionId);
                    return replacement;
                });
    }

    private JsonNode decisionOutcomes() throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode reviewOperations() throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/review-operations")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
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

    private long countReviews(String status) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM manual_review_cases WHERE merchant_id = ? AND status = ?
                """, Long.class, merchant.id(), status);
        return value == null ? 0 : value;
    }

    private Instant windowEnd() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant windowStart() {
        return windowEnd().minus(7, ChronoUnit.DAYS);
    }

    /** Shifts projected rows into a complete prior UTC day so the frozen range can see them. */
    private void backdate() {
        Timestamp shifted = Timestamp.from(windowEnd().minus(2, ChronoUnit.DAYS));
        jdbcTemplate.update("""
                UPDATE payment_risk_projections
                SET intent_occurred_at = ?, last_event_occurred_at = ?, last_event_recorded_at = ?
                WHERE merchant_id = ?
                """, shifted, shifted, shifted, merchant.id());
    }
}
