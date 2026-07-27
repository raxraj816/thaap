package com.thaap.payments.monitoring;

import com.thaap.payments.risk.RiskOperatorContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantRiskMonitoringQueryService {

    public static final String DEFINITION_VERSION = "merchant_risk_metrics_v1";
    public static final String TAXONOMY_VERSION = FraudTaxonomyCatalogue.VERSION;
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(90);
    private static final Duration REVIEW_SLA = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final MerchantRiskMonitoringReadProbe readProbe;

    public MerchantRiskMonitoringQueryService(JdbcTemplate jdbcTemplate, Clock clock,
            MerchantRiskMonitoringReadProbe readProbe) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.readProbe = readProbe;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5,
            propagation = Propagation.REQUIRES_NEW)
    public SummaryView summary(RiskOperatorContext context, MonitoringRequest request) {
        MonitoringWindow window = normalize(request);
        List<MetricResult> metrics = new ArrayList<>();
        for (MerchantRiskMetricCode code : MerchantRiskMetricCode.values()) {
            metrics.add(metric(context.merchantId(), code, window));
        }
        ReviewBacklogBreakdown backlog = backlogBreakdown(context.merchantId(), window);
        return new SummaryView(DEFINITION_VERSION, TAXONOMY_VERSION, window.windowStart(),
                window.windowEnd(), window.asOf(), window.trafficOriginPolicy(),
                List.copyOf(metrics), backlog);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5,
            propagation = Propagation.REQUIRES_NEW)
    public MetricResult metric(RiskOperatorContext context, MerchantRiskMetricCode code,
            MonitoringRequest request) {
        return metric(context.merchantId(), code, normalize(request));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5,
            propagation = Propagation.REQUIRES_NEW)
    public List<TaxonomyEntry> taxonomy(FraudTaxonomySourceType sourceType) {
        String sql = """
                SELECT taxonomy_version, source_type, source_code, fraud_family,
                       fraud_type, display_label, active_from
                  FROM fraud_taxonomy_definitions
                 WHERE taxonomy_version = ?
                """ + (sourceType == null ? "" : " AND source_type = ?")
                + " ORDER BY source_type, source_code";
        Object[] arguments = sourceType == null
                ? new Object[] {TAXONOMY_VERSION}
                : new Object[] {TAXONOMY_VERSION, sourceType.name()};
        return jdbcTemplate.query(sql, this::taxonomyEntry, arguments);
    }

    public MonitoringWindow normalize(MonitoringRequest request) {
        MonitoringRequest effectiveRequest = request == null
                ? new MonitoringRequest(null, null, null, null) : request;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant asOf = (effectiveRequest.asOf() == null ? now : effectiveRequest.asOf())
                .truncatedTo(ChronoUnit.MICROS);
        Instant windowEnd = (effectiveRequest.windowEnd() == null
                ? asOf : effectiveRequest.windowEnd()).truncatedTo(ChronoUnit.MICROS);
        Instant windowStart = (effectiveRequest.windowStart() == null
                ? windowEnd.minus(DEFAULT_WINDOW) : effectiveRequest.windowStart())
                .truncatedTo(ChronoUnit.MICROS);
        MonitoringTrafficOriginPolicy originPolicy = effectiveRequest.trafficOriginPolicy() == null
                ? MonitoringTrafficOriginPolicy.MERCHANT_API_ONLY
                : effectiveRequest.trafficOriginPolicy();
        if (asOf.isAfter(now)) {
            throw badRequest("asOf cannot be in the future");
        }
        if (!windowStart.isBefore(windowEnd)) {
            throw badRequest("windowStart must be before windowEnd");
        }
        if (windowEnd.isAfter(asOf)) {
            throw badRequest("windowEnd must be at or before asOf");
        }
        Duration duration = Duration.between(windowStart, windowEnd);
        if (duration.compareTo(MAXIMUM_WINDOW) > 0) {
            throw badRequest("Monitoring window must not exceed 90 days");
        }
        return new MonitoringWindow(windowStart, windowEnd, asOf, originPolicy);
    }

    private MetricResult metric(UUID merchantId, MerchantRiskMetricCode code,
            MonitoringWindow window) {
        return switch (code) {
            case AUTHORIZATION_APPROVAL_RATE -> authorizationRate(merchantId, window, true);
            case PROCESSOR_DECLINE_RATE -> authorizationRate(merchantId, window, false);
            case CHARGEBACK_COUNT_RATE -> chargebackRate(merchantId, window);
            case CONFIRMED_FRAUD_RATE -> confirmedFraudRate(merchantId, window);
            case FRAUD_DISPUTE_OVERLAP_RATE -> fraudDisputeOverlap(merchantId, window);
            case ENUMERATION_RATIO -> enumerationRatio(merchantId, window);
            case REVIEW_BACKLOG -> reviewBacklog(merchantId, window);
            case REVIEW_SLA_BREACH_RATE -> reviewSlaBreachRate(merchantId, window);
            case VOLUME_SPIKE_RATIO -> volumeSpikeRatio(merchantId, window);
        };
    }

    private MetricResult authorizationRate(UUID merchantId, MonitoringWindow window, boolean approvals) {
        long denominator = count("""
                SELECT COUNT(*)
                  FROM authorizations authorization_record
                  JOIN payment_attempts attempt
                    ON attempt.merchant_id = authorization_record.merchant_id
                   AND attempt.id = authorization_record.payment_attempt_id
                 WHERE authorization_record.merchant_id = ?
                   AND authorization_record.created_at >= ? AND authorization_record.created_at < ?
                   AND authorization_record.status IN ('APPROVED', 'DECLINED')
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                """, merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name());
        readProbe.afterAuthorizationDenominator(merchantId,
                approvals ? MerchantRiskMetricCode.AUTHORIZATION_APPROVAL_RATE
                        : MerchantRiskMetricCode.PROCESSOR_DECLINE_RATE);
        String status = approvals ? "APPROVED" : "DECLINED";
        long numerator = count("""
                SELECT COUNT(*)
                  FROM authorizations authorization_record
                  JOIN payment_attempts attempt
                    ON attempt.merchant_id = authorization_record.merchant_id
                   AND attempt.id = authorization_record.payment_attempt_id
                 WHERE authorization_record.merchant_id = ?
                   AND authorization_record.created_at >= ? AND authorization_record.created_at < ?
                   AND authorization_record.status = ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                """, merchantId, window.windowStart(), window.windowEnd(), status,
                window.trafficOriginPolicy().name());
        MerchantRiskMetricCode code = approvals
                ? MerchantRiskMetricCode.AUTHORIZATION_APPROVAL_RATE
                : MerchantRiskMetricCode.PROCESSOR_DECLINE_RATE;
        return ratio(code, numerator, denominator, denominator, denominator,
                denominator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, approvals
                        ? "Approved terminal processor outcomes divided by approved plus declined outcomes"
                        : "Declined terminal processor outcomes divided by approved plus declined outcomes");
    }

    private MetricResult chargebackRate(UUID merchantId, MonitoringWindow window) {
        String captureCohort = """
                FROM ledger_entries ledger
                JOIN payment_intents intent
                  ON intent.merchant_id = ledger.merchant_id
                 AND intent.id = ledger.payment_intent_id
               WHERE ledger.merchant_id = ?
                 AND ledger.source_type = 'PAYMENT_INTENT_CAPTURE'
                 AND ledger.account = 'CUSTOMER_RECEIVABLE'
                 AND ledger.created_at >= ? AND ledger.created_at < ?
                 AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR intent.traffic_origin = 'MERCHANT_API')
                """;
        long denominator = count("SELECT COUNT(DISTINCT ledger.payment_intent_id) " + captureCohort,
                merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name());
        long numerator = count("SELECT COUNT(DISTINCT ledger.payment_intent_id) " + captureCohort + """
                 AND EXISTS (
                       SELECT 1 FROM chargebacks chargeback
                        WHERE chargeback.merchant_id = ledger.merchant_id
                          AND chargeback.payment_intent_id = ledger.payment_intent_id
                          AND chargeback.available_at <= ?
                          AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                               OR chargeback.traffic_origin = 'MERCHANT_API'))
                """, merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(),
                window.trafficOriginPolicy().name());
        return ratio(MerchantRiskMetricCode.CHARGEBACK_COUNT_RATE, numerator, denominator,
                denominator, denominator,
                denominator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, "Captured PaymentIntents with an available chargeback divided by captured PaymentIntents");
    }

    private MetricResult confirmedFraudRate(UUID merchantId, MonitoringWindow window) {
        String decidedAttemptCohort = """
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                 WHERE attempt.merchant_id = ?
                   AND attempt.created_at >= ? AND attempt.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                """;
        long eligible = count("SELECT COUNT(DISTINCT attempt.id) " + decidedAttemptCohort,
                merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name());
        String currentLabel = """
                 label.available_at <= ?
                AND NOT EXISTS (
                    SELECT 1 FROM fraud_labels newer
                     WHERE newer.merchant_id = label.merchant_id
                       AND newer.supersedes_label_id = label.id
                       AND newer.available_at <= ?)
                """;
        long observed = count("""
                SELECT COUNT(DISTINCT attempt.id)
                """ + decidedAttemptCohort + """
                   AND EXISTS (
                       SELECT 1 FROM fraud_labels label
                        WHERE label.merchant_id = attempt.merchant_id
                          AND label.payment_attempt_id = attempt.id
                          AND """ + currentLabel + ")",
                merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf());
        long numerator = count("""
                SELECT COUNT(DISTINCT attempt.id)
                """ + decidedAttemptCohort + """
                   AND EXISTS (
                       SELECT 1 FROM fraud_labels label
                        WHERE label.merchant_id = attempt.merchant_id
                          AND label.payment_attempt_id = attempt.id
                          AND label.label_value = 'FRAUD'
                          AND """ + currentLabel + ")",
                merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf());
        long contradictions = count("""
                SELECT COUNT(*) FROM (
                    SELECT label.payment_attempt_id
                      FROM fraud_labels label
                      JOIN payment_attempts attempt
                        ON attempt.merchant_id = label.merchant_id
                       AND attempt.id = label.payment_attempt_id
                      JOIN fraud_decisions decision
                        ON decision.merchant_id = attempt.merchant_id
                       AND decision.payment_attempt_id = attempt.id
                     WHERE label.merchant_id = ?
                       AND attempt.created_at >= ? AND attempt.created_at < ?
                       AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                       AND label.available_at <= ?
                       AND NOT EXISTS (
                           SELECT 1 FROM fraud_labels newer
                            WHERE newer.merchant_id = label.merchant_id
                              AND newer.supersedes_label_id = label.id
                              AND newer.available_at <= ?)
                     GROUP BY label.payment_attempt_id
                    HAVING COUNT(DISTINCT label.label_value) > 1
                ) contradictory
                """, merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf());
        MonitoringCoverageStatus coverage = eligible == 0
                ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                : contradictions > 0 ? MonitoringCoverageStatus.CONTRADICTORY_LABELS
                : observed < eligible ? MonitoringCoverageStatus.PARTIAL_LABEL_COVERAGE
                : MonitoringCoverageStatus.COMPLETE;
        return ratio(MerchantRiskMetricCode.CONFIRMED_FRAUD_RATE, numerator, eligible,
                observed, eligible, coverage, window,
                "Attempts with a current FRAUD label available by asOf divided by eligible attempts; unlabeled is coverage");
    }

    private MetricResult fraudDisputeOverlap(UUID merchantId, MonitoringWindow window) {
        long denominator = count("""
                SELECT COUNT(DISTINCT chargeback.payment_attempt_id)
                  FROM chargebacks chargeback
                 WHERE chargeback.merchant_id = ?
                   AND chargeback.available_at >= ? AND chargeback.available_at < ?
                   AND chargeback.available_at <= ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR chargeback.traffic_origin = 'MERCHANT_API')
                """, merchantId, window.windowStart(), window.windowEnd(), window.asOf(),
                window.trafficOriginPolicy().name());
        long numerator = count("""
                SELECT COUNT(DISTINCT chargeback.payment_attempt_id)
                  FROM chargebacks chargeback
                 WHERE chargeback.merchant_id = ?
                   AND chargeback.available_at >= ? AND chargeback.available_at < ?
                   AND chargeback.available_at <= ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR chargeback.traffic_origin = 'MERCHANT_API')
                   AND EXISTS (
                       SELECT 1 FROM fraud_labels label
                        WHERE label.merchant_id = chargeback.merchant_id
                          AND label.payment_attempt_id = chargeback.payment_attempt_id
                          AND label.label_value = 'FRAUD'
                          AND label.available_at <= ?
                          AND NOT EXISTS (
                              SELECT 1 FROM fraud_labels newer
                               WHERE newer.merchant_id = label.merchant_id
                                 AND newer.supersedes_label_id = label.id
                                 AND newer.available_at <= ?))
                """, merchantId, window.windowStart(), window.windowEnd(), window.asOf(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf());
        return ratio(MerchantRiskMetricCode.FRAUD_DISPUTE_OVERLAP_RATE, numerator, denominator,
                denominator, denominator,
                denominator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, "Chargeback-affected attempts with available FRAUD labels divided by chargeback-affected attempts");
    }

    private MetricResult enumerationRatio(UUID merchantId, MonitoringWindow window) {
        String coveredAttemptCohort = """
                  FROM payment_attempts attempt
                 WHERE attempt.merchant_id = ?
                   AND attempt.created_at >= ? AND attempt.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                   AND (
                       EXISTS (SELECT 1 FROM authorizations authorization_record
                                WHERE authorization_record.merchant_id = attempt.merchant_id
                                  AND authorization_record.payment_attempt_id = attempt.id
                                  AND authorization_record.created_at <= ?)
                       OR EXISTS (SELECT 1 FROM incident_evidence_links evidence
                                   WHERE evidence.merchant_id = attempt.merchant_id
                                     AND evidence.payment_attempt_id = attempt.id
                                     AND evidence.linked_at <= ?))
                """;
        long denominator = count("SELECT COUNT(DISTINCT attempt.id) " + coveredAttemptCohort,
                merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf());
        long numerator = count("""
                SELECT COUNT(DISTINCT attempt.id)
                """ + coveredAttemptCohort + """
                   AND (
                       EXISTS (SELECT 1 FROM authorizations authorization_record
                                WHERE authorization_record.merchant_id = attempt.merchant_id
                                  AND authorization_record.payment_attempt_id = attempt.id
                                  AND authorization_record.created_at <= ?
                                  AND (authorization_record.cvc_check = 'FAIL'
                                       OR authorization_record.avs_line1_check = 'FAIL'
                                       OR authorization_record.avs_postal_code_check = 'FAIL'))
                       OR EXISTS (SELECT 1 FROM incident_evidence_links evidence
                                   WHERE evidence.merchant_id = attempt.merchant_id
                                     AND evidence.payment_attempt_id = attempt.id
                                     AND evidence.linked_at <= ?))
                """, merchantId, window.windowStart(), window.windowEnd(),
                window.trafficOriginPolicy().name(), window.asOf(), window.asOf(),
                window.asOf(), window.asOf());
        return ratio(MerchantRiskMetricCode.ENUMERATION_RATIO, numerator, denominator,
                denominator, denominator,
                denominator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, "Attempts with CVC, AVS, or persisted card-testing evidence divided by confirm attempts");
    }

    private MetricResult reviewBacklog(UUID merchantId, MonitoringWindow window) {
        long numerator = count("""
                SELECT COUNT(*)
                  FROM manual_review_cases review_case
                 WHERE review_case.merchant_id = ?
                   AND review_case.created_at <= ?
                   AND (review_case.resolved_at IS NULL OR review_case.resolved_at > ?)
                   AND (review_case.canceled_at IS NULL OR review_case.canceled_at > ?)
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR review_case.traffic_origin = 'MERCHANT_API')
                """, merchantId, window.asOf(), window.asOf(), window.asOf(),
                window.trafficOriginPolicy().name());
        return countResult(MerchantRiskMetricCode.REVIEW_BACKLOG, numerator,
                numerator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, "Point-in-time reviews not resolved or canceled by asOf");
    }

    private MetricResult reviewSlaBreachRate(UUID merchantId, MonitoringWindow window) {
        List<ReviewTiming> dueReviews = jdbcTemplate.query("""
                SELECT created_at, resolved_at, canceled_at
                  FROM manual_review_cases
                 WHERE merchant_id = ?
                   AND created_at <= ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR traffic_origin = 'MERCHANT_API')
                """, (resultSet, rowNumber) -> reviewTiming(resultSet),
                sqlArguments(merchantId, window.asOf().minus(REVIEW_SLA),
                        window.trafficOriginPolicy().name()));
        long breaches = dueReviews.stream().filter(ReviewTiming::breached).count();
        long denominator = dueReviews.size();
        return ratio(MerchantRiskMetricCode.REVIEW_SLA_BREACH_RATE, breaches, denominator,
                denominator, denominator,
                denominator == 0 ? MonitoringCoverageStatus.NO_ELIGIBLE_DATA
                        : MonitoringCoverageStatus.COMPLETE,
                window, "Reviews completed after the 24-hour SLA or still open after the deadline divided by due reviews");
    }

    private MetricResult volumeSpikeRatio(UUID merchantId, MonitoringWindow window) {
        Duration bucket = Duration.between(window.windowStart(), window.windowEnd());
        Instant requiredHistoryStart = window.windowStart().minus(bucket.multipliedBy(6));
        Timestamp merchantCreatedTimestamp = jdbcTemplate.queryForObject(
                "SELECT created_at FROM merchants WHERE id = ?", Timestamp.class, merchantId);
        Instant merchantCreatedAt = merchantCreatedTimestamp == null
                ? null : merchantCreatedTimestamp.toInstant();
        if (merchantCreatedAt == null || merchantCreatedAt.isAfter(requiredHistoryStart)) {
            return ratio(MerchantRiskMetricCode.VOLUME_SPIKE_RATIO,
                    attemptCount(merchantId, window.windowStart(), window.windowEnd(), window),
                    null, 0, 0, MonitoringCoverageStatus.INSUFFICIENT_HISTORY, window,
                    "Current complete bucket divided by the median of six prior complete equal buckets");
        }
        List<Long> priorCounts = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            Instant priorEnd = window.windowStart().minus(bucket.multipliedBy(index - 1L));
            Instant priorStart = priorEnd.minus(bucket);
            priorCounts.add(attemptCount(merchantId, priorStart, priorEnd, window));
        }
        Collections.sort(priorCounts);
        long lowerMiddle = priorCounts.get(2);
        long upperMiddle = priorCounts.get(3);
        BigDecimal median = BigDecimal.valueOf(lowerMiddle)
                .add(BigDecimal.valueOf(upperMiddle))
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.UNNECESSARY);
        long current = attemptCount(merchantId, window.windowStart(), window.windowEnd(), window);
        MonitoringCoverageStatus coverage = median.signum() == 0
                ? MonitoringCoverageStatus.ZERO_BASELINE : MonitoringCoverageStatus.COMPLETE;
        return decimalRatio(MerchantRiskMetricCode.VOLUME_SPIKE_RATIO, current,
                median.signum() == 0 ? null : median, 6, 6, coverage, window,
                "Current complete bucket divided by the median of six prior complete equal buckets");
    }

    private ReviewBacklogBreakdown backlogBreakdown(UUID merchantId, MonitoringWindow window) {
        List<Instant> createdTimes = jdbcTemplate.query("""
                SELECT created_at
                  FROM manual_review_cases
                 WHERE merchant_id = ?
                   AND created_at <= ?
                   AND (resolved_at IS NULL OR resolved_at > ?)
                   AND (canceled_at IS NULL OR canceled_at > ?)
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR traffic_origin = 'MERCHANT_API')
                """, (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                sqlArguments(merchantId, window.asOf(), window.asOf(), window.asOf(),
                        window.trafficOriginPolicy().name()));
        long underOneHour = 0;
        long oneToFourHours = 0;
        long fourToTwentyFourHours = 0;
        long overTwentyFourHours = 0;
        for (Instant createdAt : createdTimes) {
            Duration age = Duration.between(createdAt, window.asOf());
            if (age.compareTo(Duration.ofHours(1)) < 0) {
                underOneHour++;
            } else if (age.compareTo(Duration.ofHours(4)) < 0) {
                oneToFourHours++;
            } else if (age.compareTo(Duration.ofHours(24)) < 0) {
                fourToTwentyFourHours++;
            } else {
                overTwentyFourHours++;
            }
        }
        return new ReviewBacklogBreakdown(createdTimes.size(), underOneHour,
                oneToFourHours, fourToTwentyFourHours, overTwentyFourHours);
    }

    private long attemptCount(UUID merchantId, Instant start, Instant end, MonitoringWindow window) {
        return count("""
                SELECT COUNT(*) FROM payment_attempts attempt
                 WHERE attempt.merchant_id = ?
                   AND attempt.created_at >= ? AND attempt.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC' OR attempt.traffic_origin = 'MERCHANT_API')
                """, merchantId, start, end, window.trafficOriginPolicy().name());
    }

    private MetricResult ratio(MerchantRiskMetricCode code, long numerator, Long denominator,
            long observed, long eligible, MonitoringCoverageStatus coverage,
            MonitoringWindow window, String explanation) {
        return decimalRatio(code, numerator,
                denominator == null ? null : BigDecimal.valueOf(denominator),
                observed, eligible, coverage, window, explanation);
    }

    private MetricResult decimalRatio(MerchantRiskMetricCode code, long numerator,
            BigDecimal denominator, long observed, long eligible,
            MonitoringCoverageStatus coverage, MonitoringWindow window, String explanation) {
        BigDecimal value = denominator == null || denominator.signum() == 0 ? null
                : BigDecimal.valueOf(numerator).divide(denominator, 6, RoundingMode.HALF_UP);
        return new MetricResult(code, DEFINITION_VERSION, window.windowStart(),
                window.windowEnd(), window.asOf(), numerator, denominator, value,
                MonitoringMetricUnit.RATIO, coverage, observed, eligible,
                window.trafficOriginPolicy(), explanation);
    }

    private MetricResult countResult(MerchantRiskMetricCode code, long numerator,
            MonitoringCoverageStatus coverage, MonitoringWindow window, String explanation) {
        return new MetricResult(code, DEFINITION_VERSION, window.windowStart(),
                window.windowEnd(), window.asOf(), numerator, null, BigDecimal.valueOf(numerator),
                MonitoringMetricUnit.COUNT, coverage, numerator, numerator,
                window.trafficOriginPolicy(), explanation);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, sqlArguments(arguments));
        return result == null ? 0 : result;
    }

    private Object[] sqlArguments(Object... arguments) {
        Object[] converted = new Object[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            converted[index] = argument instanceof Instant instant
                    ? Timestamp.from(instant) : argument;
        }
        return converted;
    }

    private ReviewTiming reviewTiming(ResultSet resultSet) throws SQLException {
        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
        Timestamp resolved = resultSet.getTimestamp("resolved_at");
        Timestamp canceled = resultSet.getTimestamp("canceled_at");
        Instant terminalAt = resolved != null ? resolved.toInstant()
                : canceled == null ? null : canceled.toInstant();
        return new ReviewTiming(createdAt, terminalAt);
    }

    private TaxonomyEntry taxonomyEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaxonomyEntry(resultSet.getString("taxonomy_version"),
                FraudTaxonomySourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getString("source_code"), resultSet.getString("fraud_family"),
                resultSet.getString("fraud_type"), resultSet.getString("display_label"),
                resultSet.getTimestamp("active_from").toInstant());
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record MonitoringRequest(Instant windowStart, Instant windowEnd, Instant asOf,
            MonitoringTrafficOriginPolicy trafficOriginPolicy) { }

    public record MonitoringWindow(Instant windowStart, Instant windowEnd, Instant asOf,
            MonitoringTrafficOriginPolicy trafficOriginPolicy) { }

    public record MetricResult(MerchantRiskMetricCode metricCode, String definitionVersion,
            Instant windowStart, Instant windowEnd, Instant asOf, long numerator,
            BigDecimal denominator,
            BigDecimal value, MonitoringMetricUnit unit, MonitoringCoverageStatus coverageStatus,
            long observedCount, long eligibleCount,
            MonitoringTrafficOriginPolicy trafficOriginPolicy, String explanation) { }

    public record ReviewBacklogBreakdown(long total, long underOneHour, long oneToFourHours,
            long fourToTwentyFourHours, long overTwentyFourHours) { }

    public record SummaryView(String definitionVersion, String taxonomyVersion,
            Instant windowStart, Instant windowEnd, Instant asOf,
            MonitoringTrafficOriginPolicy trafficOriginPolicy, List<MetricResult> metrics,
            ReviewBacklogBreakdown reviewBacklog) { }

    public record TaxonomyEntry(String taxonomyVersion, FraudTaxonomySourceType sourceType,
            String sourceCode, String fraudFamily, String fraudType, String displayLabel,
            Instant activeFrom) { }

    private record ReviewTiming(Instant createdAt, Instant terminalAt) {
        boolean breached() {
            Instant deadline = createdAt.plus(REVIEW_SLA);
            return terminalAt == null || terminalAt.isAfter(deadline);
        }
    }
}
