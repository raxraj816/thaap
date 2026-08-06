package com.thaap.payments.insights;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.analytics.AnalyticsFreshness;
import com.thaap.payments.analytics.AnalyticsFreshnessState;
import com.thaap.payments.analytics.RiskAnalyticsService;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.projection.ActiveProjectionGeneration;
import com.thaap.payments.projection.ProjectionDefinitionStore;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceInsightsService {

    private static final Duration REVIEW_SLA = Duration.ofHours(24);
    /**
     * Only a decision action the search grammar already accepts may become a drill-through.
     * `UNKNOWN` is a projection placeholder rather than a filterable value, so it yields none.
     */
    private static final List<String> KNOWN_FRAUD_ACTIONS = List.of(
            "ALLOW", "BLOCK", "REVIEW", "REQUEST_3DS");

    private final ProjectionDefinitionStore projectionDefinitionStore;
    private final RiskAnalyticsService analyticsService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public WorkspaceInsightsService(ProjectionDefinitionStore projectionDefinitionStore,
            RiskAnalyticsService analyticsService, JdbcTemplate jdbcTemplate, Clock clock) {
        this.projectionDefinitionStore = projectionDefinitionStore;
        this.analyticsService = analyticsService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public DecisionOutcomeInsights decisionOutcomes(RiskOperatorContext context,
            Instant requestedFrom, Instant requestedTo, String requestedOriginPolicy) {
        Range range = range(requestedFrom, requestedTo);
        String originPolicy = originPolicy(requestedOriginPolicy);
        ActiveProjectionGeneration generation = projectionDefinitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        AnalyticsFreshness freshness = analyticsService.currentPaymentFreshness(context,
                originPolicy);
        String originClause = originClause(originPolicy);
        List<CountSegment> decisions = jdbcTemplate.query("""
                SELECT COALESCE(fraud_action, 'UNKNOWN'), COUNT(*)
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND last_event_recorded_at >= ? AND last_event_recorded_at < ?
                """ + originClause + " GROUP BY fraud_action ORDER BY fraud_action",
                (rows, number) -> segment(rows.getString(1), rows.getLong(2), range, originPolicy),
                context.merchantId(), generation.generationId(), Timestamp.from(range.from()),
                Timestamp.from(range.to()));
        OutcomeCounts counts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*),
                       COUNT(*) FILTER (WHERE label_value IS NOT NULL),
                       COUNT(*) FILTER (WHERE label_value = 'FRAUD'),
                       COUNT(*) FILTER (WHERE label_value = 'LEGITIMATE'),
                       COUNT(*) FILTER (WHERE review_outcome IS NOT NULL),
                       COUNT(*) FILTER (WHERE review_outcome IS NOT NULL AND label_value IS NOT NULL),
                       COUNT(*) FILTER (WHERE review_outcome IS NOT NULL AND label_value = 'FRAUD'),
                       COUNT(*) FILTER (WHERE review_outcome IS NOT NULL AND label_value = 'LEGITIMATE'),
                       COUNT(*) FILTER (WHERE disputed_amount_minor > 0)
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND last_event_recorded_at >= ? AND last_event_recorded_at < ?
                """ + originClause, (rows, number) -> new OutcomeCounts(
                        rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4),
                        rows.getLong(5), rows.getLong(6), rows.getLong(7), rows.getLong(8),
                        rows.getLong(9)), context.merchantId(), generation.generationId(),
                Timestamp.from(range.from()), Timestamp.from(range.to()));
        if (counts == null) counts = OutcomeCounts.empty();
        List<CurrencyMoney> money = List.of();
        boolean withholdDerived = freshness.state() == AnalyticsFreshnessState.PARTIAL
                || freshness.state() == AnalyticsFreshnessState.UNAVAILABLE;
        if (!withholdDerived) {
            money = jdbcTemplate.query("""
                    SELECT currency, SUM(captured_amount_minor), SUM(refunded_amount_minor),
                           SUM(disputed_amount_minor)
                    FROM payment_risk_projections
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND last_event_recorded_at >= ? AND last_event_recorded_at < ?
                    """ + originClause + " GROUP BY currency ORDER BY currency",
                    (rows, number) -> new CurrencyMoney(rows.getString(1), rows.getLong(2),
                            rows.getLong(3), rows.getLong(4)), context.merchantId(),
                    generation.generationId(), Timestamp.from(range.from()),
                    Timestamp.from(range.to()));
        }
        long unlabeled = counts.labelEligible() - counts.labeledEligible();
        Map<String, Rate> rates = new LinkedHashMap<>();
        rates.put("labelCoverage", rate(counts.labeledEligible(), counts.labelEligible(),
                unlabeled, withholdDerived));
        rates.put("confirmedFraudRate", rate(counts.confirmedFraud(),
                counts.labeledEligible(), unlabeled, withholdDerived));
        rates.put("reviewYield", rate(counts.reviewConfirmedFraud(),
                counts.resolvedReviewsKnownLabel(), counts.resolvedReviews()
                        - counts.resolvedReviewsKnownLabel(), withholdDerived));
        rates.put("legitimateReviewRate", rate(counts.reviewLegitimate(),
                counts.resolvedReviewsKnownLabel(), counts.resolvedReviews()
                        - counts.resolvedReviewsKnownLabel(), withholdDerived));
        return new DecisionOutcomeInsights(range, originPolicy, freshness, decisions,
                rates, counts.chargebackCount(), money,
                withholdDerived ? "derived_rates_and_money_withheld_for_partial_coverage" : null,
                "DESCRIPTIVE_NOT_CAUSAL");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public ReviewOperationsInsights reviewOperations(RiskOperatorContext context,
            Instant requestedFrom, Instant requestedTo) {
        Range range = range(requestedFrom, requestedTo);
        Instant sourceAsOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Backlog backlog = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status IN ('OPEN', 'ASSIGNED')),
                       COUNT(*) FILTER (WHERE status = 'OPEN'),
                       COUNT(*) FILTER (WHERE status = 'ASSIGNED'),
                       COUNT(*) FILTER (WHERE status IN ('OPEN', 'ASSIGNED') AND created_at > ?),
                       COUNT(*) FILTER (WHERE status IN ('OPEN', 'ASSIGNED') AND created_at <= ?)
                FROM manual_review_cases WHERE merchant_id = ?
                """, (rows, number) -> new Backlog(rows.getLong(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4), rows.getLong(5)),
                Timestamp.from(sourceAsOf.minus(REVIEW_SLA)),
                Timestamp.from(sourceAsOf.minus(REVIEW_SLA)), context.merchantId());
        List<EventTime> assignmentEvents = jdbcTemplate.query("""
                SELECT event.event_type, event.created_at, review.created_at,
                       event.actor_operator_id
                FROM review_assignment_events event
                JOIN manual_review_cases review
                  ON review.merchant_id = event.merchant_id
                 AND review.id = event.manual_review_case_id
                WHERE event.merchant_id = ? AND event.created_at >= ? AND event.created_at < ?
                ORDER BY event.created_at, event.id
                """, (rows, number) -> new EventTime(rows.getString(1), JdbcTime.instant(rows, 2),
                        JdbcTime.instant(rows, 3), rows.getObject(4, UUID.class)),
                context.merchantId(), Timestamp.from(range.from()), Timestamp.from(range.to()));
        List<EventTime> resolutionEvents = jdbcTemplate.query("""
                SELECT 'RESOLVED', resolution.resolved_at, review.created_at,
                       resolution.actor_operator_id
                FROM review_resolutions resolution
                JOIN manual_review_cases review
                  ON review.merchant_id = resolution.merchant_id
                 AND review.id = resolution.manual_review_case_id
                WHERE resolution.merchant_id = ?
                  AND resolution.resolved_at >= ? AND resolution.resolved_at < ?
                ORDER BY resolution.resolved_at, resolution.id
                """, (rows, number) -> new EventTime(rows.getString(1), JdbcTime.instant(rows, 2),
                        JdbcTime.instant(rows, 3), rows.getObject(4, UUID.class)),
                context.merchantId(), Timestamp.from(range.from()), Timestamp.from(range.to()));
        long assignments = assignmentEvents.stream()
                .filter(event -> List.of("ASSIGNED", "REASSIGNED").contains(event.type())).count();
        long releases = assignmentEvents.stream()
                .filter(event -> "RELEASED".equals(event.type())).count();
        DurationStats assignmentTime = durationStats(assignmentEvents.stream()
                .filter(event -> List.of("ASSIGNED", "REASSIGNED").contains(event.type())).toList());
        DurationStats resolutionTime = durationStats(resolutionEvents);
        OperatorSummary own = context.role() == RiskOperatorRole.VIEWER ? null
                : operatorSummary(context.operatorId(), backlogForOperator(context, context.operatorId()),
                        assignmentEvents, resolutionEvents);
        return new ReviewOperationsInsights(range, sourceAsOf, backlog,
                assignments, releases, resolutionEvents.size(), null,
                assignmentTime, resolutionTime, own,
                "AUTHORITATIVE_REVIEW_SOURCE",
                "CONFLICT_ATTEMPTS_NOT_AVAILABLE_WITHOUT_DURABLE_CONFLICT_FACTS");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public OperatorInsights operators(RiskOperatorContext context,
            Instant requestedFrom, Instant requestedTo) {
        if (context.role() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.INSIGHT_MANAGER_REQUIRED);
        }
        ReviewOperationsInsights operations = reviewOperations(context, requestedFrom, requestedTo);
        List<OperatorIdentity> operators = jdbcTemplate.query("""
                SELECT id, operator_code FROM risk_operators
                WHERE merchant_id = ? AND status = 'ACTIVE'
                  AND role IN ('ANALYST', 'RISK_MANAGER')
                ORDER BY operator_code, id
                """, (rows, number) -> new OperatorIdentity(rows.getObject(1, UUID.class),
                        rows.getString(2)), context.merchantId());
        List<EventTime> assignmentEvents = events(context.merchantId(), operations.range(), false);
        List<EventTime> resolutionEvents = events(context.merchantId(), operations.range(), true);
        List<OperatorSummary> rows = operators.stream().map(operator -> {
            OperatorSummary summary = operatorSummary(operator.id(),
                    backlogForOperator(context, operator.id()), assignmentEvents, resolutionEvents);
            return new OperatorSummary(operator.id(), operator.code(), summary.currentWorkload(),
                    summary.assignments(), summary.resolutions());
        }).toList();
        return new OperatorInsights(operations.range(), operations.sourceAsOf(), rows,
                "MANAGER_ONLY_IDENTIFIED_BREAKDOWN");
    }

    private List<EventTime> events(UUID merchantId, Range range, boolean resolution) {
        if (resolution) {
            return jdbcTemplate.query("""
                    SELECT 'RESOLVED', resolution.resolved_at, review.created_at,
                           resolution.actor_operator_id
                    FROM review_resolutions resolution
                    JOIN manual_review_cases review ON review.merchant_id = resolution.merchant_id
                      AND review.id = resolution.manual_review_case_id
                    WHERE resolution.merchant_id = ? AND resolution.resolved_at >= ?
                      AND resolution.resolved_at < ?
                    """, (rows, number) -> new EventTime(rows.getString(1),
                            JdbcTime.instant(rows, 2), JdbcTime.instant(rows, 3),
                            rows.getObject(4, UUID.class)), merchantId,
                    Timestamp.from(range.from()), Timestamp.from(range.to()));
        }
        return jdbcTemplate.query("""
                SELECT event.event_type, event.created_at, review.created_at,
                       event.actor_operator_id
                FROM review_assignment_events event
                JOIN manual_review_cases review ON review.merchant_id = event.merchant_id
                  AND review.id = event.manual_review_case_id
                WHERE event.merchant_id = ? AND event.created_at >= ? AND event.created_at < ?
                """, (rows, number) -> new EventTime(rows.getString(1),
                        JdbcTime.instant(rows, 2), JdbcTime.instant(rows, 3),
                        rows.getObject(4, UUID.class)), merchantId,
                Timestamp.from(range.from()), Timestamp.from(range.to()));
    }

    private long backlogForOperator(RiskOperatorContext context, UUID operatorId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM manual_review_cases
                WHERE merchant_id = ? AND assigned_operator_id = ? AND status = 'ASSIGNED'
                """, Long.class, context.merchantId(), operatorId);
        return value == null ? 0 : value;
    }

    private OperatorSummary operatorSummary(UUID operatorId, long workload,
            List<EventTime> assignments, List<EventTime> resolutions) {
        long assignmentCount = assignments.stream()
                .filter(event -> operatorId.equals(event.operatorId())
                        && List.of("ASSIGNED", "REASSIGNED").contains(event.type())).count();
        long resolutionCount = resolutions.stream()
                .filter(event -> operatorId.equals(event.operatorId())).count();
        return new OperatorSummary(operatorId, null, workload, assignmentCount, resolutionCount);
    }

    private DurationStats durationStats(List<EventTime> events) {
        if (events.isEmpty()) return new DurationStats(0, 0, null, null, 0);
        List<Long> millis = events.stream()
                .map(event -> Duration.between(event.caseCreatedAt(), event.occurredAt()).toMillis())
                .filter(value -> value >= 0).toList();
        if (millis.isEmpty()) return new DurationStats(0, 0, null, null, events.size());
        return new DurationStats(millis.size(), millis.stream().mapToLong(Long::longValue).sum(),
                millis.stream().min(Long::compareTo).orElse(null),
                millis.stream().max(Long::compareTo).orElse(null), events.size() - millis.size());
    }

    /**
     * Builds the drill-through descriptor that reproduces one distribution segment.
     *
     * <p>It carries only closed filter values and the aligned range — never a merchant, an
     * operator, a generation, or a row identifier. The server re-derives the tenant from the
     * caller's own credential when the descriptor is replayed against the search route, so a
     * copied descriptor cannot reach another tenant's payments.
     */
    private CountSegment segment(String rawAction, long count, Range range, String originPolicy) {
        String action = rawAction == null ? "UNKNOWN" : rawAction;
        DrillThrough drillThrough = KNOWN_FRAUD_ACTIONS.contains(action)
                ? new DrillThrough(List.of(action), range.from(), range.to(), originPolicy)
                : null;
        return new CountSegment(action, count, drillThrough);
    }

    private Rate rate(long numerator, long denominator, long unknown, boolean withheld) {
        if (withheld) return new Rate(numerator, denominator, unknown, null,
                "WITHHELD_PARTIAL_COVERAGE");
        if (denominator == 0) return new Rate(numerator, denominator, unknown, null,
                "NOT_COMPUTABLE_EMPTY_DENOMINATOR");
        int basisPoints = BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).intValueExact();
        return new Rate(numerator, denominator, unknown, basisPoints, "COMPUTABLE");
    }

    private Range range(Instant requestedFrom, Instant requestedTo) {
        Instant now = clock.instant();
        Instant completeToday = now.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = requestedTo == null ? completeToday : requestedTo;
        Instant from = requestedFrom == null ? to.minus(7, ChronoUnit.DAYS) : requestedFrom;
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || to.isAfter(now) || !utcDayAligned(from) || !utcDayAligned(to)) {
            throw new PublicApiException(PublicApiErrorDescriptor.INSIGHT_RANGE_INVALID);
        }
        return new Range(from, to);
    }

    private boolean utcDayAligned(Instant value) {
        return value.equals(value.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private String originPolicy(String requested) {
        String value = requested == null ? "ALL" : requested.trim().toUpperCase();
        if (!List.of("ALL", "LIVE_ONLY", "SYNTHETIC_ONLY").contains(value)) {
            throw new PublicApiException(PublicApiErrorDescriptor.INSIGHT_ORIGIN_POLICY_INVALID);
        }
        return value;
    }

    private String originClause(String policy) {
        return switch (policy) {
            case "LIVE_ONLY" -> " AND traffic_origin = 'MERCHANT_API'";
            case "SYNTHETIC_ONLY" -> " AND traffic_origin = 'SYNTHETIC_SCENARIO'";
            default -> "";
        };
    }

    public record Range(Instant from, Instant to) {
    }

    public record DecisionOutcomeInsights(Range range, String trafficOriginPolicy,
            AnalyticsFreshness freshness, List<CountSegment> decisionDistribution,
            Map<String, Rate> rates, long chargebackPaymentCount,
            List<CurrencyMoney> moneyByCurrency, String warningCode, String claimBoundary) {
    }

    public record CountSegment(String code, long count, DrillThrough drillThrough) {
    }

    /** A replayable, tenant-free filter that reproduces exactly one distribution segment. */
    public record DrillThrough(List<String> fraudActions, Instant createdFrom, Instant createdTo,
            String trafficOriginPolicy) {
    }

    public record Rate(long numerator, long denominator, long unknownCount,
            Integer valueBasisPoints, String status) {
    }

    public record CurrencyMoney(String currency, long capturedAmountMinor,
            long refundedAmountMinor, long disputedAmountMinor) {
    }

    public record ReviewOperationsInsights(Range range, Instant sourceAsOf,
            Backlog backlog, long assignments, long releases, long resolutions,
            Long conflictedAttempts, DurationStats timeToAssignment,
            DurationStats timeToResolution, OperatorSummary ownWorkload,
            String authority, String conflictedAttemptsStatus) {
    }

    public record Backlog(long total, long unassigned, long assigned,
            long withinSla, long breached) {
    }

    public record DurationStats(long eligibleCount, long totalMilliseconds,
            Long minimumMilliseconds, Long maximumMilliseconds, long missingCount) {
    }

    public record OperatorInsights(Range range, Instant sourceAsOf,
            List<OperatorSummary> operators, String visibility) {
    }

    public record OperatorSummary(UUID operatorId, String operatorCode,
            long currentWorkload, long assignments, long resolutions) {
    }

    private record OutcomeCounts(long labelEligible, long labeledEligible,
            long confirmedFraud, long legitimate, long resolvedReviews,
            long resolvedReviewsKnownLabel, long reviewConfirmedFraud,
            long reviewLegitimate, long chargebackCount) {
        private static OutcomeCounts empty() {
            return new OutcomeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record EventTime(String type, Instant occurredAt, Instant caseCreatedAt,
            UUID operatorId) {
    }

    private record OperatorIdentity(UUID id, String code) {
    }
}
