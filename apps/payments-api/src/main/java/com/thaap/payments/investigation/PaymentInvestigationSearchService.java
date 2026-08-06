package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.analytics.AnalyticsCursorCodec;
import com.thaap.payments.analytics.AnalyticsFreshness;
import com.thaap.payments.analytics.RiskAnalyticsService;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.projection.ActiveProjectionGeneration;
import com.thaap.payments.projection.ProjectionDefinitionStore;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.risk.RiskOperatorContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentInvestigationSearchService {

    private final ProjectionDefinitionStore projectionDefinitionStore;
    private final PaymentInvestigationFilterCodec filterCodec;
    private final AnalyticsCursorCodec cursorCodec;
    private final RiskAnalyticsService analyticsService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PaymentInvestigationSearchService(ProjectionDefinitionStore projectionDefinitionStore,
            PaymentInvestigationFilterCodec filterCodec, AnalyticsCursorCodec cursorCodec,
            RiskAnalyticsService analyticsService, JdbcTemplate jdbcTemplate, Clock clock) {
        this.projectionDefinitionStore = projectionDefinitionStore;
        this.filterCodec = filterCodec;
        this.cursorCodec = cursorCodec;
        this.analyticsService = analyticsService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public SearchPage search(RiskOperatorContext context, PaymentInvestigationFilter filter,
            PaymentInvestigationSort requestedSort, Integer requestedLimit, String cursor) {
        PaymentInvestigationFilterCodec.NormalizedFilter normalized = filterCodec
                .normalize(filter, clock.instant());
        PaymentInvestigationSort sort = requestedSort == null
                ? PaymentInvestigationSort.CREATED_AT_DESC : requestedSort;
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new PublicApiException(PublicApiErrorDescriptor.WORKSPACE_PAGE_SIZE_INVALID);
        }
        ActiveProjectionGeneration generation = projectionDefinitionStore.requireActive(
                ProjectionNames.PAYMENT_RISK);
        String queryShape = filterCodec.digest(normalized.canonicalJson(), sort, "search");
        AnalyticsCursorCodec.Cursor decoded = cursorCodec.decode(context.merchantId(),
                generation.generationId(), queryShape, cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT payment_intent_id, lifecycle_status, intent_amount_minor, currency,
                       fraud_action, risk_level, risk_score, reason_codes_json,
                       review_status, review_outcome, label_value, fraud_type,
                       traffic_origin, coverage_status, last_stream_version,
                       last_event_recorded_at, last_processed_at
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(context.merchantId());
        arguments.add(generation.generationId());
        PaymentInvestigationFilter value = normalized.filter();
        appendSet(sql, arguments, "lifecycle_status", names(value.lifecycleStatuses()));
        appendSet(sql, arguments, "fraud_action", names(value.fraudActions()));
        appendSet(sql, arguments, "risk_level", names(value.riskLevels()));
        appendSet(sql, arguments, "review_status", names(value.reviewStatuses()));
        appendSet(sql, arguments, "review_outcome", names(value.reviewOutcomes()));
        appendSet(sql, arguments, "label_value", names(value.labelValues()));
        appendSet(sql, arguments, "fraud_type", names(value.fraudTypes()));
        appendSet(sql, arguments, "traffic_origin", names(value.trafficOrigins()));
        appendSet(sql, arguments, "currency", value.currencies());
        appendReasons(sql, arguments, names(value.reasonCodes()));
        append(sql, arguments, "intent_amount_minor >= ?", value.minimumAmountMinor());
        append(sql, arguments, "intent_amount_minor <= ?", value.maximumAmountMinor());
        append(sql, arguments, "last_event_recorded_at >= ?", timestamp(value.createdFrom()));
        append(sql, arguments, "last_event_recorded_at < ?", timestamp(value.createdTo()));
        if (decoded != null) {
            if (sort == PaymentInvestigationSort.CREATED_AT_DESC) {
                sql.append(" AND (last_event_recorded_at < ? OR (last_event_recorded_at = ? AND payment_intent_id < ?))");
            } else {
                sql.append(" AND (last_event_recorded_at > ? OR (last_event_recorded_at = ? AND payment_intent_id > ?))");
            }
            arguments.add(Timestamp.from(decoded.recordedAt()));
            arguments.add(Timestamp.from(decoded.recordedAt()));
            arguments.add(decoded.paymentIntentId());
        }
        sql.append(sort == PaymentInvestigationSort.CREATED_AT_DESC
                ? " ORDER BY last_event_recorded_at DESC, payment_intent_id DESC LIMIT ?"
                : " ORDER BY last_event_recorded_at ASC, payment_intent_id ASC LIMIT ?");
        arguments.add(limit + 1);
        List<SearchRow> rows = jdbcTemplate.query(sql.toString(), (result, number) ->
                new SearchRow(result.getObject(1, UUID.class), result.getString(2),
                        nullableLong(result.getObject(3)), result.getString(4), result.getString(5),
                        result.getString(6), nullableInteger(result.getObject(7)), result.getString(8),
                        result.getString(9), result.getString(10), result.getString(11),
                        result.getString(12), result.getString(13), result.getString(14),
                        result.getLong(15), JdbcTime.instant(result, 16), JdbcTime.instant(result, 17)),
                arguments.toArray());
        String nextCursor = null;
        if (rows.size() > limit) {
            SearchRow last = rows.get(limit - 1);
            nextCursor = cursorCodec.encode(context.merchantId(), generation.generationId(),
                    queryShape, last.lastEventRecordedAt(), last.paymentIntentId());
            rows = List.copyOf(rows.subList(0, limit));
        }
        AnalyticsFreshness freshness = analyticsService.currentPaymentFreshness(context, "ALL");
        return new SearchPage(rows, nextCursor, freshness, normalized.canonicalJson(), sort);
    }

    private void appendSet(StringBuilder sql, List<Object> arguments, String column,
            Collection<String> values) {
        if (values.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(values.size(), "?")))
                .append(')');
        arguments.addAll(values);
    }

    private void appendReasons(StringBuilder sql, List<Object> arguments,
            Collection<String> reasons) {
        if (reasons.isEmpty()) return;
        sql.append(" AND (");
        int index = 0;
        for (String reason : reasons) {
            if (index++ > 0) sql.append(" OR ");
            sql.append("reason_codes_json LIKE ?");
            arguments.add("%\"" + reason + "\"%");
        }
        sql.append(')');
    }

    private void append(StringBuilder sql, List<Object> arguments, String expression,
            Object value) {
        if (value != null) {
            sql.append(" AND ").append(expression);
            arguments.add(value);
        }
    }

    private Set<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public record SearchPage(List<SearchRow> data, String nextCursor,
            AnalyticsFreshness freshness, String canonicalFilterJson,
            PaymentInvestigationSort sort) {
    }

    public record SearchRow(UUID paymentIntentId, String lifecycleStatus,
            Long amountMinor, String currency, String fraudAction, String riskLevel,
            Integer riskScoreBasisPoints, String reasonCodesJson, String reviewStatus,
            String reviewOutcome, String labelValue, String fraudType, String trafficOrigin,
            String coverageStatus, long lastStreamVersion, Instant lastEventRecordedAt,
            Instant lastProcessedAt) {
    }
}
