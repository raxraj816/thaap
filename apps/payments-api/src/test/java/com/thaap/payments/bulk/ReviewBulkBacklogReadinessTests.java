package com.thaap.payments.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.observability.DecisionIntelligenceHealthIndicator;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Closes the C55-OBS-001 residual gap: a stuck bulk run must be visible.
 *
 * <p>A request whose worker died stays `RUNNING` under an expired lease. Nothing throws, no item
 * fails, and no error is logged — the work simply stops. The only way an operator learns about it
 * is the backlog gauge and its oldest-age companion, so those two numbers are the entire
 * detection surface for this failure and are asserted here against a deliberately stranded run.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ReviewBulkBacklogReadinessTests {

    private static final String BACKLOG_GAUGE = "thaap.decision_intelligence.bulk.backlog";
    private static final String OLDEST_AGE_GAUGE =
            "thaap.decision_intelligence.bulk.oldest_age_seconds";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private ReviewBulkActionStore store;
    @Autowired private DecisionIntelligenceHealthIndicator healthIndicator;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private Clock clock;

    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator analyst;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("bulk-backlog");
        analyst = scenario.operator(merchant, "bulk-backlog-analyst", "ANALYST");
    }

    @Test
    void anEmptyQueueReportsZeroBacklogAndZeroAgeRatherThanALeftoverReading() {
        healthIndicator.health();

        assertThat(gauge(BACKLOG_GAUGE)).isZero();
        assertThat(gauge(OLDEST_AGE_GAUGE)).isZero();
    }

    @Test
    void aStrandedRunningRequestRaisesTheBacklogGaugeAndItsAge() {
        // A request whose worker died: still RUNNING, lease long expired, no terminal items.
        Instant createdAt = clock.instant().minus(3, ChronoUnit.HOURS);
        UUID requestId = strandedRequest(createdAt);

        healthIndicator.health();

        assertThat(gauge(BACKLOG_GAUGE))
                .as("a stranded run must be counted; nothing else reports this failure")
                .isEqualTo(1d);
        assertThat(gauge(OLDEST_AGE_GAUGE))
                .as("age is what distinguishes a run that is merely queued from one that is "
                        + "stuck, so an operator can alert on it")
                .isGreaterThanOrEqualTo(3 * 3600d - 60);

        // Readiness still reports UP: one stuck bulk run must not take payment mutation down.
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bulkBacklog", 1L);
        assertThat(requestId).isNotNull();
    }

    @Test
    void theBacklogFallsBackToZeroOnceTheStrandedRunReachesATerminalStatus() {
        UUID requestId = strandedRequest(clock.instant().minus(90, ChronoUnit.MINUTES));
        healthIndicator.health();
        assertThat(gauge(BACKLOG_GAUGE)).isEqualTo(1d);

        // Recovery finishes the run through the normal fenced path.
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "recovery-worker");
        assertThat(lease).as("an expired lease must be reclaimable").isNotNull();
        ReviewBulkActionStore.ClaimedItem item = store.claimNext(lease);
        store.completeFailure(item, "NOT_FOUND", "review_or_operator_not_found", null);
        store.finalizeRequest(lease);

        healthIndicator.health();
        assertThat(gauge(BACKLOG_GAUGE))
                .as("a terminal run must leave the backlog, or the gauge would alert forever")
                .isZero();
        assertThat(gauge(OLDEST_AGE_GAUGE)).isZero();
    }

    @Test
    void theBacklogGaugesCarryNoIdentifyingDimension() {
        strandedRequest(clock.instant().minus(10, ChronoUnit.MINUTES));
        healthIndicator.health();

        for (String name : new String[] {BACKLOG_GAUGE, OLDEST_AGE_GAUGE}) {
            Gauge meter = meterRegistry.find(name).gauge();
            assertThat(meter).as("%s must be registered", name).isNotNull();
            assertThat(meter.getId().getTags())
                    .as("%s must be a single bounded series, not one per tenant or request", name)
                    .isEmpty();
        }
    }

    private UUID strandedRequest(Instant createdAt) {
        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, merchant.id(), analyst.id(),
                ReviewBulkActionCode.ASSIGN_TO_SELF, null, sixtyFourChars("stranded-idem"),
                sixtyFourChars("stranded-digest"), 1, createdAt);
        store.insertItem(UUID.randomUUID(), merchant.id(), requestId, UUID.randomUUID(), 0, 1,
                createdAt);
        // Take the lease and let it expire, which is exactly the state a dead worker leaves.
        ReviewBulkActionStore.Lease lease = store.claim(requestId, "worker-that-died");
        store.claimNext(lease);
        jdbcTemplate.update("""
                UPDATE review_bulk_action_requests
                SET lease_expires_at = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(clock.instant().minusSeconds(600)),
                Timestamp.from(clock.instant()), requestId);
        return requestId;
    }

    private double gauge(String name) {
        Gauge meter = meterRegistry.find(name).gauge();
        return meter == null ? -1d : meter.value();
    }

    /** Deterministic exactly-64-character value for columns the schema pins at CHAR_LENGTH 64. */
    private String sixtyFourChars(String seed) {
        StringBuilder value = new StringBuilder(seed);
        while (value.length() < 64) value.append('0');
        return value.substring(0, 64);
    }
}
