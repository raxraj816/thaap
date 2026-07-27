package com.thaap.payments.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.TrafficOrigin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalystQueueService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAXIMUM_LIMIT = 100;
    private static final Duration REVIEW_SLA = Duration.ofHours(24);
    private static final String CURSOR_VERSION = "v1";
    private static final String CURSOR_DOMAIN = "analyst-review-queue-cursor:v1";
    private static final TypeReference<List<FraudDecisionReasonCode>> REASON_CODES = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ReviewQueueFilterCodec filterCodec;
    private final SensitiveValueHasher hasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnalystQueueService(NamedParameterJdbcTemplate jdbcTemplate,
            ReviewQueueFilterCodec filterCodec, SensitiveValueHasher hasher,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.filterCodec = filterCodec;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5,
            propagation = Propagation.REQUIRES_NEW)
    public ReviewQueuePage search(RiskOperatorContext context, ReviewQueueFilter rawFilter,
            ReviewQueueSort requestedSort, Integer requestedLimit, String cursorToken) {
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReviewQueueFilterCodec.NormalizedFilter normalized = filterCodec.normalize(rawFilter, asOf);
        ReviewQueueSort sort = requestedSort == null ? ReviewQueueSort.CREATED_AT_DESC : requestedSort;
        int limit = normalizeLimit(requestedLimit);
        String filterDigest = filterCodec.digest(normalized.canonicalJson(), sort, "[]");
        QueueCursor cursor = cursorToken == null || cursorToken.isBlank()
                ? null : decodeCursor(cursorToken, context.merchantId(), filterDigest, sort);

        StringBuilder sql = new StringBuilder("""
                SELECT review_case.id, review_case.payment_intent_id,
                       review_case.payment_attempt_id, review_case.fraud_decision_id,
                       review_case.review_stage, review_case.status,
                       review_case.decision_action, review_case.reason_codes,
                       review_case.traffic_origin, review_case.assigned_operator_id,
                       review_case.assignment_version, review_case.created_at,
                       review_case.updated_at, review_case.resolved_at, review_case.canceled_at
                  FROM manual_review_cases review_case
                 WHERE review_case.merchant_id = :merchantId
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("merchantId", context.merchantId())
                .addValue("asOf", Timestamp.from(asOf))
                .addValue("slaCutoff", Timestamp.from(asOf.minus(REVIEW_SLA)));
        appendFilter(sql, parameters, normalized.filter());
        if (cursor != null) {
            String comparison = sort == ReviewQueueSort.CREATED_AT_ASC ? ">" : "<";
            sql.append(" AND (review_case.created_at ").append(comparison)
                    .append(" :cursorCreatedAt OR (review_case.created_at = :cursorCreatedAt")
                    .append(" AND review_case.id ").append(comparison).append(" :cursorId))");
            parameters.addValue("cursorCreatedAt", Timestamp.from(cursor.createdAt()));
            parameters.addValue("cursorId", cursor.id());
        }
        String direction = sort == ReviewQueueSort.CREATED_AT_ASC ? " ASC" : " DESC";
        sql.append(" ORDER BY review_case.created_at").append(direction)
                .append(", review_case.id").append(direction).append(" LIMIT :limit");
        parameters.addValue("limit", limit + 1);
        List<ReviewQueueRow> fetched = jdbcTemplate.query(sql.toString(), parameters,
                (resultSet, rowNumber) -> row(resultSet, asOf));
        boolean hasMore = fetched.size() > limit;
        List<ReviewQueueRow> rows = hasMore
                ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? encodeCursor(context.merchantId(), filterDigest, sort, rows.getLast()) : null;
        return new ReviewQueuePage(ReviewQueueFilterCodec.SCHEMA_VERSION, asOf, sort,
                ReviewQueueFilterView.from(normalized.filter()), rows, nextCursor);
    }

    private void appendFilter(StringBuilder sql, MapSqlParameterSource parameters,
            ReviewQueueFilter filter) {
        addEnumSet(sql, parameters, "statuses", "review_case.status", filter.statuses());
        addEnumSet(sql, parameters, "reviewStages", "review_case.review_stage", filter.reviewStages());
        addEnumSet(sql, parameters, "decisionActions", "review_case.decision_action", filter.decisionActions());
        addEnumSet(sql, parameters, "trafficOrigins", "review_case.traffic_origin", filter.trafficOrigins());
        if (!filter.assignedOperatorIds().isEmpty()) {
            sql.append(" AND review_case.assigned_operator_id IN (:assignedOperatorIds)");
            parameters.addValue("assignedOperatorIds", filter.assignedOperatorIds());
        }
        if (!filter.reasonCodes().isEmpty()) {
            sql.append(" AND (");
            int index = 0;
            for (FraudDecisionReasonCode reasonCode : filter.reasonCodes()) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                String parameter = "reasonCode" + index;
                sql.append("review_case.reason_codes LIKE :").append(parameter);
                parameters.addValue(parameter, "%\"" + reasonCode.name() + "\"%");
                index++;
            }
            sql.append(')');
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND review_case.created_at >= :createdFrom");
            parameters.addValue("createdFrom", Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            sql.append(" AND review_case.created_at < :createdTo");
            parameters.addValue("createdTo", Timestamp.from(filter.createdTo()));
        }
        if (filter.slaStates().size() == 1) {
            boolean breached = filter.slaStates().contains(ReviewSlaState.BREACHED);
            sql.append(breached ? " AND " : " AND NOT ").append("(")
                    .append("review_case.status IN ('OPEN', 'ASSIGNED') ")
                    .append("AND review_case.created_at <= :slaCutoff)");
        }
    }

    private void addEnumSet(StringBuilder sql, MapSqlParameterSource parameters,
            String parameter, String column, Set<? extends Enum<?>> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (:").append(parameter).append(')');
        parameters.addValue(parameter, values.stream().map(Enum::name).toList());
    }

    private ReviewQueueRow row(ResultSet resultSet, Instant asOf) throws SQLException {
        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
        ManualReviewCaseStatus status = ManualReviewCaseStatus.valueOf(resultSet.getString("status"));
        UUID assignedOperatorId = resultSet.getObject("assigned_operator_id", UUID.class);
        return new ReviewQueueRow(resultSet.getObject("id", UUID.class),
                resultSet.getObject("payment_intent_id", UUID.class),
                resultSet.getObject("payment_attempt_id", UUID.class),
                resultSet.getObject("fraud_decision_id", UUID.class),
                ReviewStage.valueOf(resultSet.getString("review_stage")), status,
                FraudDecisionAction.valueOf(resultSet.getString("decision_action")),
                reasonCodes(resultSet.getString("reason_codes")),
                TrafficOrigin.valueOf(resultSet.getString("traffic_origin")),
                assignedOperatorId, resultSet.getLong("assignment_version"),
                slaState(status, createdAt, asOf), createdAt,
                resultSet.getTimestamp("updated_at").toInstant(),
                instant(resultSet, "resolved_at"), instant(resultSet, "canceled_at"));
    }

    private List<FraudDecisionReasonCode> reasonCodes(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, REASON_CODES));
        } catch (Exception exception) {
            throw new IllegalStateException("Review reason codes could not be read", exception);
        }
    }

    private ReviewSlaState slaState(ManualReviewCaseStatus status, Instant createdAt, Instant asOf) {
        boolean active = status == ManualReviewCaseStatus.OPEN || status == ManualReviewCaseStatus.ASSIGNED;
        return active && !createdAt.plus(REVIEW_SLA).isAfter(asOf)
                ? ReviewSlaState.BREACHED : ReviewSlaState.WITHIN_SLA;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private int normalizeLimit(Integer requested) {
        int limit = requested == null ? DEFAULT_LIMIT : requested;
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw badRequest("Review queue limit must be between 1 and 100");
        }
        return limit;
    }

    private String encodeCursor(UUID merchantId, String filterDigest,
            ReviewQueueSort sort, ReviewQueueRow row) {
        String payload = payload(merchantId, filterDigest, sort, row.createdAt(), row.id());
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hasher.hmacSha256(CURSOR_DOMAIN, payload);
    }

    private QueueCursor decodeCursor(String token, UUID merchantId,
            String filterDigest, ReviewQueueSort sort) {
        if (token.length() > 512 || !token.matches("[A-Za-z0-9_-]+\\.[0-9a-f]{64}")) {
            throw badRequest("Review queue cursor is malformed");
        }
        String[] tokenParts = token.split("\\.", -1);
        try {
            String payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]),
                    StandardCharsets.UTF_8);
            byte[] expected = HexFormat.of().parseHex(hasher.hmacSha256(CURSOR_DOMAIN, payload));
            byte[] supplied = HexFormat.of().parseHex(tokenParts[1]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw badRequest("Review queue cursor is invalid for this request");
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 6 || !CURSOR_VERSION.equals(fields[0])
                    || !merchantId.toString().equals(fields[1])
                    || !filterDigest.equals(fields[2]) || !sort.name().equals(fields[3])) {
                throw badRequest("Review queue cursor is invalid for this request");
            }
            return new QueueCursor(Instant.parse(fields[4]), UUID.fromString(fields[5]));
        } catch (IllegalArgumentException exception) {
            throw badRequest("Review queue cursor is malformed");
        }
    }

    private String payload(UUID merchantId, String filterDigest, ReviewQueueSort sort,
            Instant createdAt, UUID id) {
        return String.join("|", CURSOR_VERSION, merchantId.toString(), filterDigest,
                sort.name(), createdAt.truncatedTo(ChronoUnit.MICROS).toString(), id.toString());
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ReviewQueuePage(String filterSchemaVersion, Instant asOf,
            ReviewQueueSort sort, ReviewQueueFilterView filter,
            List<ReviewQueueRow> rows, String nextCursor) {
    }

    public record ReviewQueueRow(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, ReviewStage reviewStage, ManualReviewCaseStatus status,
            FraudDecisionAction decisionAction, List<FraudDecisionReasonCode> reasonCodes,
            TrafficOrigin trafficOrigin, UUID assignedOperatorId, long assignmentVersion,
            ReviewSlaState slaState, Instant createdAt, Instant updatedAt,
            Instant resolvedAt, Instant canceledAt) {
    }

    private record QueueCursor(Instant createdAt, UUID id) {
    }
}
