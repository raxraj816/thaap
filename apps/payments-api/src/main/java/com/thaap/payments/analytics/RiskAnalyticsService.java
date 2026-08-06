package com.thaap.payments.analytics;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.analytics.AnalyticsCursorCodec.Cursor;
import com.thaap.payments.analytics.AnalyticsCursorCodec.CurrencyCursor;
import com.thaap.payments.analytics.AnalyticsCursorCodec.GenerationCursor;
import com.thaap.payments.analytics.AnalyticsCursorCodec.TimeSeriesCursor;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.projection.ActiveProjectionGeneration;
import com.thaap.payments.projection.ProjectionDefinitionStore;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.review.FraudLabelValue;
import com.thaap.payments.review.ManualReviewCaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskAnalyticsService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectionDefinitionStore definitionStore;
    private final AnalyticsCursorCodec cursorCodec;
    private final AnalyticsFreshnessEvaluator freshnessEvaluator;
    private final Clock clock;

    public RiskAnalyticsService(JdbcTemplate jdbcTemplate,
            ProjectionDefinitionStore definitionStore, AnalyticsCursorCodec cursorCodec,
            AnalyticsFreshnessEvaluator freshnessEvaluator, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.definitionStore = definitionStore;
        this.cursorCodec = cursorCodec;
        this.freshnessEvaluator = freshnessEvaluator;
        this.clock = clock;
    }

    public PaymentSearchPage searchPayments(RiskOperatorContext context, int limit,
            String cursor, String fraudAction, String reviewStatus, String labelValue) {
        int boundedLimit = requireLimit(limit);
        String normalizedFraudAction = requireOptionalEnum(fraudAction,
                FraudDecisionAction.class, "fraud action");
        String normalizedReviewStatus = requireOptionalEnum(reviewStatus,
                ManualReviewCaseStatus.class, "review status");
        String normalizedLabelValue = requireOptionalEnum(labelValue,
                FraudLabelValue.class, "label value");
        ActiveProjectionGeneration generation = definitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        String queryShape = paymentSearchQueryShape(normalizedFraudAction,
                normalizedReviewStatus, normalizedLabelValue);
        Cursor decodedCursor = cursorCodec.decode(context.merchantId(),
                generation.generationId(), queryShape, cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT payment_intent_id, intent_amount_minor, currency, capture_method,
                       traffic_origin, attempt_number, attempt_outcome, processor_code,
                       fraud_action, risk_level, risk_score, reason_codes_json,
                       ruleset_version, feature_catalogue_version, fraud_decision_id,
                       feature_snapshot_id, review_status,
                       review_outcome, captured_amount_minor, refunded_amount_minor,
                       disputed_amount_minor, label_value, fraud_type, label_source,
                       label_available_at, lifecycle_status, coverage_status,
                       last_stream_version, last_domain_event_id,
                       last_event_record_sha256, last_event_occurred_at,
                       last_event_recorded_at, last_processed_at
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(context.merchantId());
        arguments.add(generation.generationId());
        appendFilter(sql, arguments, "fraud_action", normalizedFraudAction);
        appendFilter(sql, arguments, "review_status", normalizedReviewStatus);
        appendFilter(sql, arguments, "label_value", normalizedLabelValue);
        if (decodedCursor != null) {
            sql.append(" AND (last_event_recorded_at < ? OR "
                    + "(last_event_recorded_at = ? AND payment_intent_id > ?))");
            arguments.add(Timestamp.from(decodedCursor.recordedAt()));
            arguments.add(Timestamp.from(decodedCursor.recordedAt()));
            arguments.add(decodedCursor.paymentIntentId());
        }
        sql.append(" ORDER BY last_event_recorded_at DESC, payment_intent_id ASC LIMIT ?");
        arguments.add(boundedLimit + 1);
        List<PaymentRiskView> rows = jdbcTemplate.query(sql.toString(),
                (resultSet, rowNumber) -> paymentView(resultSet), arguments.toArray());
        String nextCursor = null;
        if (rows.size() > boundedLimit) {
            PaymentRiskView last = rows.get(boundedLimit - 1);
            nextCursor = cursorCodec.encode(context.merchantId(),
                    generation.generationId(), queryShape,
                    last.lastEventRecordedAt(), last.paymentIntentId());
            rows = List.copyOf(rows.subList(0, boundedLimit));
        }
        return new PaymentSearchPage(rows, nextCursor,
                freshness(context.merchantId(), ProjectionNames.PAYMENT_RISK,
                        generation, "ALL"));
    }

    public PaymentDetail paymentDetail(RiskOperatorContext context, UUID paymentIntentId) {
        ActiveProjectionGeneration generation = definitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        List<PaymentRiskView> rows = jdbcTemplate.query("""
                SELECT payment_intent_id, intent_amount_minor, currency, capture_method,
                       traffic_origin, attempt_number, attempt_outcome, processor_code,
                       fraud_action, risk_level, risk_score, reason_codes_json,
                       ruleset_version, feature_catalogue_version, fraud_decision_id,
                       feature_snapshot_id, review_status,
                       review_outcome, captured_amount_minor, refunded_amount_minor,
                       disputed_amount_minor, label_value, fraud_type, label_source,
                       label_available_at, lifecycle_status, coverage_status,
                       last_stream_version, last_domain_event_id,
                       last_event_record_sha256, last_event_occurred_at,
                       last_event_recorded_at, last_processed_at
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND payment_intent_id = ?
                """, (resultSet, rowNumber) -> paymentView(resultSet),
                context.merchantId(), generation.generationId(), paymentIntentId);
        if (rows.isEmpty()) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_ANALYTICS_RECORD_NOT_FOUND, "Payment analytics record was not found");
        }
        return new PaymentDetail(rows.getFirst(),
                freshness(context.merchantId(), ProjectionNames.PAYMENT_RISK,
                        generation, "ALL"));
    }

    public AnalyticsFreshness currentPaymentFreshness(RiskOperatorContext context,
            String originPolicy) {
        ActiveProjectionGeneration generation = definitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        return freshness(context.merchantId(), ProjectionNames.PAYMENT_RISK,
                generation, requireOriginPolicy(originPolicy));
    }

    public MerchantSummary merchantSummary(RiskOperatorContext context, Instant from,
            Instant to, String originPolicy) {
        return merchantSummary(context, from, to, originPolicy, 100, null);
    }

    public MerchantSummary merchantSummary(RiskOperatorContext context, Instant from,
            Instant to, String originPolicy, int limit, String cursor) {
        TimeRange range = requireRange(from, to, "HOUR");
        String policy = requireOriginPolicy(originPolicy);
        int boundedLimit = requireLimit(limit);
        ActiveProjectionGeneration generation = definitionStore.requireActive(
                ProjectionNames.MERCHANT_RISK);
        String queryShape = "merchant-summary|from=" + range.from() + "|to=" + range.to()
                + "|originPolicy=" + policy;
        CurrencyCursor decodedCursor = cursorCodec.decodeCurrency(context.merchantId(),
                generation.generationId(), queryShape, cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT currency, SUM(payment_count), SUM(attempt_count),
                       SUM(authorization_count), SUM(decline_count), SUM(review_count),
                       SUM(confirmed_fraud_count), SUM(chargeback_count),
                       SUM(enumeration_signal_count), SUM(unlabeled_count),
                       SUM(unknown_outcome_count), SUM(intent_amount_minor),
                       SUM(authorized_amount_minor), SUM(captured_amount_minor),
                       SUM(refunded_amount_minor), SUM(disputed_amount_minor),
                       SUM(source_event_count)
                FROM merchant_risk_aggregates
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND interval_type = 'HOUR' AND traffic_origin_policy = ?
                  AND interval_start >= ? AND interval_start < ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(context.merchantId());
        arguments.add(generation.generationId());
        arguments.add(policy);
        arguments.add(Timestamp.from(range.from()));
        arguments.add(Timestamp.from(range.to()));
        if (decodedCursor != null) {
            sql.append(" AND currency > ?");
            arguments.add(decodedCursor.currency());
        }
        sql.append(" GROUP BY currency ORDER BY currency LIMIT ?");
        arguments.add(boundedLimit + 1);
        List<CurrencySummary> currencies = jdbcTemplate.query(sql.toString(),
                (resultSet, rowNumber) -> currencySummary(resultSet), arguments.toArray());
        String nextCursor = null;
        if (currencies.size() > boundedLimit) {
            CurrencySummary last = currencies.get(boundedLimit - 1);
            nextCursor = cursorCodec.encodeCurrency(context.merchantId(),
                    generation.generationId(), queryShape, last.currency());
            currencies = List.copyOf(currencies.subList(0, boundedLimit));
        }
        return new MerchantSummary(range.from(), range.to(), currencies, nextCursor,
                freshness(context.merchantId(), ProjectionNames.MERCHANT_RISK,
                        generation, policy));
    }

    public MerchantTimeSeries merchantTimeSeries(RiskOperatorContext context,
            Instant from, Instant to, String intervalType, String originPolicy, int limit) {
        return merchantTimeSeries(context, from, to, intervalType, originPolicy, limit, null);
    }

    public MerchantTimeSeries merchantTimeSeries(RiskOperatorContext context,
            Instant from, Instant to, String intervalType, String originPolicy, int limit,
            String cursor) {
        String interval = requireInterval(intervalType);
        TimeRange range = requireRange(from, to, interval);
        String policy = requireOriginPolicy(originPolicy);
        int boundedLimit = requireLimit(limit);
        ActiveProjectionGeneration generation = definitionStore.requireActive(
                ProjectionNames.MERCHANT_RISK);
        String queryShape = "merchant-timeseries|from=" + range.from() + "|to=" + range.to()
                + "|interval=" + interval + "|originPolicy=" + policy;
        TimeSeriesCursor decodedCursor = cursorCodec.decodeTimeSeries(context.merchantId(),
                generation.generationId(), queryShape, cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT interval_start, currency, payment_count, attempt_count,
                       authorization_count, decline_count, review_count,
                       confirmed_fraud_count, chargeback_count,
                       enumeration_signal_count, unlabeled_count,
                       unknown_outcome_count, intent_amount_minor,
                       authorized_amount_minor, captured_amount_minor,
                       refunded_amount_minor, disputed_amount_minor,
                       source_event_count, latest_source_occurred_at,
                       latest_source_recorded_at, last_processed_at
                FROM merchant_risk_aggregates
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND interval_type = ? AND traffic_origin_policy = ?
                  AND interval_start >= ? AND interval_start < ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(context.merchantId());
        arguments.add(generation.generationId());
        arguments.add(interval);
        arguments.add(policy);
        arguments.add(Timestamp.from(range.from()));
        arguments.add(Timestamp.from(range.to()));
        if (decodedCursor != null) {
            sql.append(" AND (interval_start > ? OR (interval_start = ? AND currency > ?))");
            arguments.add(Timestamp.from(decodedCursor.intervalStart()));
            arguments.add(Timestamp.from(decodedCursor.intervalStart()));
            arguments.add(decodedCursor.currency());
        }
        sql.append(" ORDER BY interval_start, currency LIMIT ?");
        arguments.add(boundedLimit + 1);
        List<TimeSeriesPoint> points = jdbcTemplate.query(sql.toString(),
                (resultSet, rowNumber) -> timeSeriesPoint(resultSet), arguments.toArray());
        String nextCursor = null;
        if (points.size() > boundedLimit) {
            TimeSeriesPoint last = points.get(boundedLimit - 1);
            nextCursor = cursorCodec.encodeTimeSeries(context.merchantId(),
                    generation.generationId(), queryShape, last.intervalStart(), last.currency());
            points = List.copyOf(points.subList(0, boundedLimit));
        }
        return new MerchantTimeSeries(range.from(), range.to(), interval, points, nextCursor,
                freshness(context.merchantId(), ProjectionNames.MERCHANT_RISK,
                        generation, policy));
    }

    public ProjectionStatusView projectionStatus(RiskOperatorContext context) {
        return projectionStatus(context, 100, null);
    }

    public ProjectionStatusView projectionStatus(RiskOperatorContext context, int limit,
            String cursor) {
        int boundedLimit = requireLimit(limit);
        ActiveProjectionGeneration payment = definitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        ActiveProjectionGeneration merchant = definitionStore.requireActive(
                ProjectionNames.MERCHANT_RISK);
        String queryShape = "projection-status|payment=" + payment.generationId()
                + "|merchant=" + merchant.generationId();
        GenerationCursor decodedCursor = cursorCodec.decodeGeneration(context.merchantId(),
                payment.generationId(), queryShape, cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT definition.projection_name, generation.id,
                       generation.generation_number, generation.handler_version,
                       generation.build_source, generation.lifecycle_status,
                       generation.created_at, generation.reconciled_at,
                       generation.activated_at, generation.retired_at
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.projection_definition_id = definition.id
                WHERE definition.projection_name IN ('payment-risk-v1', 'merchant-risk-v1')
                """);
        List<Object> arguments = new ArrayList<>();
        if (decodedCursor != null) {
            sql.append(" AND (definition.projection_name > ? OR "
                    + "(definition.projection_name = ? AND generation.generation_number < ?))");
            arguments.add(decodedCursor.projectionName());
            arguments.add(decodedCursor.projectionName());
            arguments.add(decodedCursor.generationNumber());
        }
        sql.append(" ORDER BY definition.projection_name, generation.generation_number DESC "
                + "LIMIT ?");
        arguments.add(boundedLimit + 1);
        List<GenerationView> history = jdbcTemplate.query(sql.toString(),
                (resultSet, rowNumber) -> new GenerationView(
                        resultSet.getString(1), resultSet.getObject(2, UUID.class),
                        resultSet.getLong(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getString(6),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 7),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 8),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 9),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 10)),
                arguments.toArray());
        String nextCursor = null;
        if (history.size() > boundedLimit) {
            GenerationView last = history.get(boundedLimit - 1);
            nextCursor = cursorCodec.encodeGeneration(context.merchantId(),
                    payment.generationId(), queryShape, last.projectionName(),
                    last.generationNumber());
            history = List.copyOf(history.subList(0, boundedLimit));
        }
        return new ProjectionStatusView(history, nextCursor,
                freshness(context.merchantId(), ProjectionNames.PAYMENT_RISK,
                        payment, "ALL"),
                freshness(context.merchantId(), ProjectionNames.MERCHANT_RISK,
                        merchant, "ALL"));
    }

    private AnalyticsFreshness freshness(UUID merchantId, String projectionName,
            ActiveProjectionGeneration generation, String originPolicy) {
        String projectionScope = projectionEventScope("event", projectionName);
        String eventOriginClause = eventOriginClause("event", originPolicy);
        String sourceOriginClause = eventOriginClause("source", originPolicy);
        Long eligible = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events event
                WHERE event.merchant_id = ?
                """ + projectionScope + eventOriginClause, Long.class, merchantId);
        Long applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events event
                JOIN projection_stream_positions position
                  ON position.merchant_id = event.merchant_id
                 AND position.projection_generation_id = ?
                 AND position.stream_type = event.stream_type
                 AND position.stream_id = event.stream_id
                 AND position.last_stream_version >= event.stream_version
                WHERE event.merchant_id = ?
                """ + projectionScope + eventOriginClause, Long.class,
                generation.generationId(), merchantId);
        Long unavailableStreams = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT event.stream_type, event.stream_id
                    FROM domain_events event
                    LEFT JOIN projection_stream_positions position
                      ON position.merchant_id = event.merchant_id
                     AND position.projection_generation_id = ?
                     AND position.stream_type = event.stream_type
                     AND position.stream_id = event.stream_id
                    WHERE event.merchant_id = ?
                """ + projectionScope + eventOriginClause + """
                    GROUP BY event.stream_type, event.stream_id
                    HAVING MAX(position.last_stream_version) IS NULL
                        OR MAX(position.last_stream_version) < MAX(event.stream_version)
                ) coverage_gap
                """, Long.class, generation.generationId(), merchantId);
        Long bootstrapGaps = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_intents payment
                WHERE payment.merchant_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM domain_events source_event
                      WHERE source_event.merchant_id = payment.merchant_id
                        AND source_event.stream_type = 'PAYMENT'
                        AND source_event.stream_id = payment.id
                        AND source_event.stream_version = 1
                  )
                """ + paymentOriginClause("payment", originPolicy), Long.class, merchantId);
        Instant sourceCutoff = queryInstant("""
                SELECT MAX(source.recorded_at) FROM domain_events source
                WHERE source.merchant_id = ?
                """ + projectionEventScope("source", projectionName)
                + sourceOriginClause, merchantId);
        Instant lastApplied = queryInstant("""
                SELECT MAX(event.occurred_at)
                FROM domain_events event
                JOIN projection_stream_positions position
                  ON position.merchant_id = event.merchant_id
                 AND position.projection_generation_id = ?
                 AND position.stream_type = event.stream_type
                 AND position.stream_id = event.stream_id
                 AND position.last_stream_version >= event.stream_version
                WHERE event.merchant_id = ?
                """ + projectionScope + eventOriginClause,
                generation.generationId(), merchantId);
        Instant lastProcessed = queryInstant("""
                SELECT MAX(position.last_processed_at)
                FROM projection_stream_positions position
                JOIN domain_events event
                  ON event.merchant_id = position.merchant_id
                 AND event.stream_type = position.stream_type
                 AND event.stream_id = position.stream_id
                 AND event.stream_version <= position.last_stream_version
                WHERE position.merchant_id = ?
                  AND position.projection_generation_id = ?
                """ + projectionScope + eventOriginClause,
                merchantId, generation.generationId());
        long eligibleCount = eligible == null ? 0 : eligible;
        long appliedCount = applied == null ? 0 : applied;
        long unavailableCount = (unavailableStreams == null ? 0 : unavailableStreams)
                + (bootstrapGaps == null ? 0 : bootstrapGaps);
        AnalyticsFreshnessState state = freshnessEvaluator.evaluate(eligibleCount,
                appliedCount, unavailableCount, lastProcessed);
        return new AnalyticsFreshness(generation.generationId(),
                generation.generationNumber(), sourceCutoff, lastApplied, lastProcessed,
                state, eligibleCount, appliedCount, unavailableCount, originPolicy);
    }

    private Instant queryInstant(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql,
                (resultSet, rowNumber) -> JdbcTime.instant(resultSet, 1), arguments);
    }

    private String projectionEventScope(String alias, String projectionName) {
        return ProjectionNames.PAYMENT_RISK.equals(projectionName)
                ? " AND " + alias + ".stream_type = 'PAYMENT'" : "";
    }

    private String eventOriginClause(String alias, String originPolicy) {
        return switch (originPolicy) {
            case "LIVE_ONLY" -> " AND " + alias + ".traffic_origin = 'MERCHANT_API'";
            case "SYNTHETIC_ONLY" -> " AND " + alias
                    + ".traffic_origin = 'SYNTHETIC_SCENARIO'";
            default -> "";
        };
    }

    private String paymentOriginClause(String alias, String originPolicy) {
        return switch (originPolicy) {
            case "LIVE_ONLY" -> " AND " + alias + ".traffic_origin = 'MERCHANT_API'";
            case "SYNTHETIC_ONLY" -> " AND " + alias
                    + ".traffic_origin = 'SYNTHETIC_SCENARIO'";
            default -> "";
        };
    }

    private PaymentRiskView paymentView(ResultSet resultSet) throws SQLException {
        return new PaymentRiskView(resultSet.getObject(1, UUID.class), nullableLong(resultSet, 2),
                resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                nullableInteger(resultSet, 6), resultSet.getString(7), resultSet.getString(8),
                resultSet.getString(9), resultSet.getString(10), nullableInteger(resultSet, 11),
                resultSet.getString(12), resultSet.getString(13), resultSet.getString(14),
                resultSet.getObject(15, UUID.class), resultSet.getObject(16, UUID.class),
                resultSet.getString(17), resultSet.getString(18), resultSet.getLong(19),
                resultSet.getLong(20), resultSet.getLong(21), resultSet.getString(22),
                resultSet.getString(23), resultSet.getString(24),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 25),
                resultSet.getString(26), resultSet.getString(27), resultSet.getLong(28),
                resultSet.getObject(29, UUID.class), resultSet.getString(30),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 31),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 32),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 33));
    }

    private CurrencySummary currencySummary(ResultSet resultSet) throws SQLException {
        return new CurrencySummary(resultSet.getString(1), resultSet.getLong(2),
                resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5),
                resultSet.getLong(6), resultSet.getLong(7), resultSet.getLong(8),
                resultSet.getLong(9), resultSet.getLong(10), resultSet.getLong(11),
                resultSet.getLong(12), resultSet.getLong(13), resultSet.getLong(14),
                resultSet.getLong(15), resultSet.getLong(16), resultSet.getLong(17));
    }

    private TimeSeriesPoint timeSeriesPoint(ResultSet resultSet) throws SQLException {
        return new TimeSeriesPoint(
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 1),
                resultSet.getString(2),
                resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5),
                resultSet.getLong(6), resultSet.getLong(7), resultSet.getLong(8),
                resultSet.getLong(9), resultSet.getLong(10), resultSet.getLong(11),
                resultSet.getLong(12), resultSet.getLong(13), resultSet.getLong(14),
                resultSet.getLong(15), resultSet.getLong(16), resultSet.getLong(17),
                resultSet.getLong(18),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 19),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 20),
                com.thaap.payments.persistence.JdbcTime.instant(resultSet, 21));
    }

    private void appendFilter(StringBuilder sql, List<Object> arguments,
            String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value);
        }
    }

    private String paymentSearchQueryShape(String fraudAction, String reviewStatus,
            String labelValue) {
        return "payments|fraudAction=" + optionalQueryValue(fraudAction)
                + "|reviewStatus=" + optionalQueryValue(reviewStatus)
                + "|labelValue=" + optionalQueryValue(labelValue);
    }

    private String optionalQueryValue(String value) {
        return value == null ? "-" : value;
    }

    private <T extends Enum<T>> String requireOptionalEnum(String value,
            Class<T> type, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        try {
            return Enum.valueOf(type, normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new PublicApiException(PublicApiErrorDescriptor.ANALYTICS_FILTER_INVALID, "Analytics " + label + " filter is invalid");
        }
    }

    private int requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new PublicApiException(PublicApiErrorDescriptor.ANALYTICS_PAGE_SIZE_INVALID, "Analytics page size must be between 1 and 100");
        }
        return limit;
    }

    private TimeRange requireRange(Instant from, Instant to, String interval) {
        ChronoUnit unit = "DAY".equals(interval) ? ChronoUnit.DAYS : ChronoUnit.HOURS;
        Instant now = clock.instant();
        boolean defaultTo = to == null;
        Instant normalizedTo = defaultTo ? now.truncatedTo(unit).plus(1, unit) : to;
        Instant normalizedFrom = from == null ? normalizedTo.minus(24, ChronoUnit.HOURS) : from;
        if (!normalizedFrom.isBefore(normalizedTo)
                || Duration.between(normalizedFrom, normalizedTo).compareTo(Duration.ofDays(90)) > 0
                || (!defaultTo && normalizedTo.isAfter(now))
                || !normalizedFrom.equals(normalizedFrom.truncatedTo(unit))
                || !normalizedTo.equals(normalizedTo.truncatedTo(unit))) {
            throw new PublicApiException(PublicApiErrorDescriptor.ANALYTICS_TIME_RANGE_INVALID, "Analytics time range is invalid, unaligned, or exceeds 90 days");
        }
        return new TimeRange(normalizedFrom, normalizedTo);
    }

    private String requireOriginPolicy(String policy) {
        String normalized = policy == null ? "ALL" : policy.trim().toUpperCase();
        if (!List.of("ALL", "LIVE_ONLY", "SYNTHETIC_ONLY").contains(normalized)) {
            throw new PublicApiException(PublicApiErrorDescriptor.TRAFFIC_ORIGIN_POLICY_INVALID, "Traffic-origin policy is invalid");
        }
        return normalized;
    }

    private String requireInterval(String interval) {
        String normalized = interval == null ? "HOUR" : interval.trim().toUpperCase();
        if (!"HOUR".equals(normalized) && !"DAY".equals(normalized)) {
            throw new PublicApiException(PublicApiErrorDescriptor.ANALYTICS_INTERVAL_INVALID, "Analytics interval must be HOUR or DAY");
        }
        return normalized;
    }

    private Long nullableLong(ResultSet resultSet, int column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet resultSet, int column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public record PaymentSearchPage(List<PaymentRiskView> data, String nextCursor,
            AnalyticsFreshness freshness) {
    }

    public record PaymentRiskView(UUID paymentIntentId, Long intentAmountMinor,
            String currency, String captureMethod, String trafficOrigin,
            Integer attemptNumber, String attemptOutcome, String processorCode,
            String fraudAction, String riskLevel, Integer riskScoreBasisPoints,
            String reasonCodesJson, String rulesetVersion,
            String featureCatalogueVersion, UUID fraudDecisionId, UUID featureSnapshotId,
            String reviewStatus,
            String reviewOutcome, long capturedAmountMinor,
            long refundedAmountMinor, long disputedAmountMinor, String labelValue,
            String fraudType, String labelSource, Instant labelAvailableAt,
            String lifecycleStatus, String coverageStatus, long lastStreamVersion,
            UUID lastDomainEventId, String lastEventRecordSha256,
            Instant lastEventOccurredAt, Instant lastEventRecordedAt,
            Instant lastProcessedAt) {
    }

    public record PaymentDetail(PaymentRiskView data, AnalyticsFreshness freshness) {
    }

    public record MerchantSummary(Instant from, Instant to,
            List<CurrencySummary> currencies, String nextCursor,
            AnalyticsFreshness freshness) {
    }

    public record CurrencySummary(String currency, long paymentCount, long attemptCount,
            long authorizationCount, long declineCount, long reviewCount,
            long confirmedFraudCount, long chargebackCount, long enumerationSignalCount,
            long unlabeledCount, long unknownOutcomeCount, long intentAmountMinor,
            long authorizedAmountMinor, long capturedAmountMinor,
            long refundedAmountMinor, long disputedAmountMinor, long sourceEventCount) {
    }

    public record MerchantTimeSeries(Instant from, Instant to, String interval,
            List<TimeSeriesPoint> points, String nextCursor, AnalyticsFreshness freshness) {
    }

    public record TimeSeriesPoint(Instant intervalStart, String currency,
            long paymentCount, long attemptCount, long authorizationCount,
            long declineCount, long reviewCount, long confirmedFraudCount,
            long chargebackCount, long enumerationSignalCount, long unlabeledCount,
            long unknownOutcomeCount, long intentAmountMinor, long authorizedAmountMinor,
            long capturedAmountMinor, long refundedAmountMinor,
            long disputedAmountMinor, long sourceEventCount,
            Instant latestSourceOccurredAt, Instant latestSourceRecordedAt,
            Instant lastProcessedAt) {
    }

    public record ProjectionStatusView(List<GenerationView> generations, String nextCursor,
            AnalyticsFreshness paymentRisk, AnalyticsFreshness merchantRisk) {
    }

    public record GenerationView(String projectionName, UUID generationId,
            long generationNumber, String handlerVersion, String buildSource,
            String lifecycleStatus, Instant createdAt, Instant reconciledAt,
            Instant activatedAt, Instant retiredAt) {
    }

    private record TimeRange(Instant from, Instant to) {
    }
}
