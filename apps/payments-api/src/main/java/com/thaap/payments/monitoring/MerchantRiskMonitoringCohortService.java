package com.thaap.payments.monitoring;

import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringRequest;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringWindow;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
public class MerchantRiskMonitoringCohortService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAXIMUM_LIMIT = 100;
    private static final Duration REVIEW_SLA = Duration.ofHours(24);
    private static final String CURSOR_VERSION = "v1";
    private static final String CURSOR_HMAC_DOMAIN = "merchant-risk-monitoring-cursor:v1";

    private final JdbcTemplate jdbcTemplate;
    private final MerchantRiskMonitoringQueryService queryService;
    private final SensitiveValueHasher hasher;

    public MerchantRiskMonitoringCohortService(JdbcTemplate jdbcTemplate,
            MerchantRiskMonitoringQueryService queryService, SensitiveValueHasher hasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.hasher = hasher;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5,
            propagation = Propagation.REQUIRES_NEW)
    public CohortPage cohort(RiskOperatorContext context, MerchantRiskMetricCode metricCode,
            MonitoringRequest request, Integer requestedLimit, String cursorToken) {
        MonitoringWindow window = queryService.normalize(request);
        int limit = normalizeLimit(requestedLimit);
        if (metricCode == MerchantRiskMetricCode.VOLUME_SPIKE_RATIO) {
            if (cursorToken != null && !cursorToken.isBlank()) {
                throw badRequest("Volume bucket drill-down does not accept a cursor");
            }
            return volumeBuckets(context.merchantId(), metricCode, window);
        }
        CohortCursor cursor = cursorToken == null || cursorToken.isBlank()
                ? null : decodeCursor(cursorToken, context.merchantId(), metricCode, window);
        CohortSql cohortSql = sql(context.merchantId(), metricCode, window);
        StringBuilder sql = new StringBuilder("SELECT * FROM (")
                .append(cohortSql.sql()).append(") cohort");
        List<Object> arguments = new ArrayList<>(cohortSql.arguments());
        if (cursor != null) {
            sql.append(" WHERE (occurred_at > ? OR (occurred_at = ? AND resource_id > ?))");
            arguments.add(Timestamp.from(cursor.occurredAt()));
            arguments.add(Timestamp.from(cursor.occurredAt()));
            arguments.add(cursor.resourceId());
        }
        sql.append(" ORDER BY occurred_at, resource_id LIMIT ?");
        arguments.add(limit + 1);
        List<CohortRow> fetched = jdbcTemplate.query(sql.toString(), this::row,
                arguments.toArray());
        boolean hasMore = fetched.size() > limit;
        List<CohortRow> rows = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? encodeCursor(context.merchantId(), metricCode, window, rows.getLast()) : null;
        return new CohortPage(metricCode, MerchantRiskMonitoringQueryService.DEFINITION_VERSION,
                window.windowStart(), window.windowEnd(), window.asOf(),
                window.trafficOriginPolicy(), rows, nextCursor);
    }

    private CohortSql sql(UUID merchantId, MerchantRiskMetricCode metricCode,
            MonitoringWindow window) {
        return switch (metricCode) {
            case AUTHORIZATION_APPROVAL_RATE -> authorizationSql(merchantId, window, "APPROVED");
            case PROCESSOR_DECLINE_RATE -> authorizationSql(merchantId, window, "DECLINED");
            case CHARGEBACK_COUNT_RATE -> chargebackRateSql(merchantId, window);
            case CONFIRMED_FRAUD_RATE -> confirmedFraudSql(merchantId, window);
            case FRAUD_DISPUTE_OVERLAP_RATE -> fraudDisputeSql(merchantId, window);
            case ENUMERATION_RATIO -> enumerationSql(merchantId, window);
            case REVIEW_BACKLOG -> reviewBacklogSql(merchantId, window);
            case REVIEW_SLA_BREACH_RATE -> reviewSlaSql(merchantId, window);
            case VOLUME_SPIKE_RATIO -> throw new IllegalStateException("Volume uses bounded buckets");
        };
    }

    private CohortSql authorizationSql(UUID merchantId, MonitoringWindow window, String numeratorStatus) {
        return new CohortSql("""
                SELECT 'AUTHORIZATION' AS resource_type,
                       authorization_record.id AS resource_id,
                       authorization_record.created_at AS occurred_at,
                       CASE WHEN authorization_record.status = ? THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       authorization_record.status AS evidence_code,
                       1 AS row_count
                  FROM authorizations authorization_record
                  JOIN payment_attempts attempt
                    ON attempt.merchant_id = authorization_record.merchant_id
                   AND attempt.id = authorization_record.payment_attempt_id
                 WHERE authorization_record.merchant_id = ?
                   AND authorization_record.created_at >= ?
                   AND authorization_record.created_at < ?
                   AND authorization_record.status IN ('APPROVED', 'DECLINED')
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR attempt.traffic_origin = 'MERCHANT_API')
                """, List.of(numeratorStatus, merchantId, Timestamp.from(window.windowStart()),
                Timestamp.from(window.windowEnd()), window.trafficOriginPolicy().name()));
    }

    private CohortSql chargebackRateSql(UUID merchantId, MonitoringWindow window) {
        return new CohortSql("""
                SELECT 'PAYMENT_INTENT' AS resource_type,
                       ledger.payment_intent_id AS resource_id,
                       ledger.created_at AS occurred_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM chargebacks chargeback
                            WHERE chargeback.merchant_id = ledger.merchant_id
                              AND chargeback.payment_intent_id = ledger.payment_intent_id
                              AND chargeback.available_at <= ?
                              AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                                   OR chargeback.traffic_origin = 'MERCHANT_API'))
                           THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       'CAPTURED' AS evidence_code,
                       1 AS row_count
                  FROM ledger_entries ledger
                  JOIN payment_intents intent
                    ON intent.merchant_id = ledger.merchant_id
                   AND intent.id = ledger.payment_intent_id
                 WHERE ledger.merchant_id = ?
                   AND ledger.source_type = 'PAYMENT_INTENT_CAPTURE'
                   AND ledger.account = 'CUSTOMER_RECEIVABLE'
                   AND ledger.created_at >= ? AND ledger.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR intent.traffic_origin = 'MERCHANT_API')
                """, List.of(Timestamp.from(window.asOf()), window.trafficOriginPolicy().name(),
                merchantId, Timestamp.from(window.windowStart()), Timestamp.from(window.windowEnd()),
                window.trafficOriginPolicy().name()));
    }

    private CohortSql confirmedFraudSql(UUID merchantId, MonitoringWindow window) {
        String currentFraud = """
                EXISTS (
                    SELECT 1 FROM fraud_labels label
                     WHERE label.merchant_id = attempt.merchant_id
                       AND label.payment_attempt_id = attempt.id
                       AND label.label_value = 'FRAUD'
                       AND label.available_at <= ?
                       AND NOT EXISTS (
                           SELECT 1 FROM fraud_labels newer
                            WHERE newer.merchant_id = label.merchant_id
                              AND newer.supersedes_label_id = label.id
                              AND newer.available_at <= ?))
                """;
        String query = """
                SELECT 'PAYMENT_ATTEMPT' AS resource_type,
                       attempt.id AS resource_id,
                       attempt.created_at AS occurred_at,
                       CASE WHEN %s THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       CASE WHEN %s THEN 'FRAUD' ELSE 'UNLABELED_OR_NON_FRAUD' END AS evidence_code,
                       1 AS row_count
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                 WHERE attempt.merchant_id = ?
                   AND attempt.created_at >= ? AND attempt.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR attempt.traffic_origin = 'MERCHANT_API')
                """.formatted(currentFraud, currentFraud);
        return new CohortSql(query,
                List.of(window.asOf(), window.asOf(), window.asOf(), window.asOf(), merchantId,
                window.windowStart(), window.windowEnd(), window.trafficOriginPolicy().name()).stream()
                .map(this::sqlArgument).toList());
    }

    private CohortSql fraudDisputeSql(UUID merchantId, MonitoringWindow window) {
        return new CohortSql("""
                SELECT 'CHARGEBACK' AS resource_type,
                       chargeback.id AS resource_id,
                       chargeback.available_at AS occurred_at,
                       CASE WHEN EXISTS (
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
                           THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       chargeback.reason_code AS evidence_code,
                       1 AS row_count
                  FROM chargebacks chargeback
                 WHERE chargeback.merchant_id = ?
                   AND chargeback.available_at >= ? AND chargeback.available_at < ?
                   AND chargeback.available_at <= ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR chargeback.traffic_origin = 'MERCHANT_API')
                """, List.of(Timestamp.from(window.asOf()), Timestamp.from(window.asOf()), merchantId,
                Timestamp.from(window.windowStart()), Timestamp.from(window.windowEnd()),
                Timestamp.from(window.asOf()), window.trafficOriginPolicy().name()));
    }

    private CohortSql enumerationSql(UUID merchantId, MonitoringWindow window) {
        return new CohortSql("""
                SELECT 'PAYMENT_ATTEMPT' AS resource_type,
                       attempt.id AS resource_id,
                       attempt.created_at AS occurred_at,
                       CASE WHEN (
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
                           THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       'CVC_AVS_OR_CARD_TESTING' AS evidence_code,
                       1 AS row_count
                  FROM payment_attempts attempt
                 WHERE attempt.merchant_id = ?
                   AND attempt.created_at >= ? AND attempt.created_at < ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR attempt.traffic_origin = 'MERCHANT_API')
                   AND (
                       EXISTS (SELECT 1 FROM authorizations covered_authorization
                                WHERE covered_authorization.merchant_id = attempt.merchant_id
                                  AND covered_authorization.payment_attempt_id = attempt.id
                                  AND covered_authorization.created_at <= ?)
                       OR EXISTS (SELECT 1 FROM incident_evidence_links covered_evidence
                                   WHERE covered_evidence.merchant_id = attempt.merchant_id
                                     AND covered_evidence.payment_attempt_id = attempt.id
                                     AND covered_evidence.linked_at <= ?))
                """, List.of(Timestamp.from(window.asOf()), Timestamp.from(window.asOf()),
                merchantId, Timestamp.from(window.windowStart()), Timestamp.from(window.windowEnd()),
                window.trafficOriginPolicy().name(), Timestamp.from(window.asOf()),
                Timestamp.from(window.asOf())));
    }

    private CohortSql reviewBacklogSql(UUID merchantId, MonitoringWindow window) {
        return new CohortSql("""
                SELECT 'MANUAL_REVIEW_CASE' AS resource_type,
                       review_case.id AS resource_id,
                       review_case.created_at AS occurred_at,
                       TRUE AS numerator_member,
                       FALSE AS denominator_member,
                       'OPEN_AT_AS_OF' AS evidence_code,
                       1 AS row_count
                  FROM manual_review_cases review_case
                 WHERE review_case.merchant_id = ?
                   AND review_case.created_at <= ?
                   AND (review_case.resolved_at IS NULL OR review_case.resolved_at > ?)
                   AND (review_case.canceled_at IS NULL OR review_case.canceled_at > ?)
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR review_case.traffic_origin = 'MERCHANT_API')
                """, List.of(merchantId, Timestamp.from(window.asOf()), Timestamp.from(window.asOf()),
                Timestamp.from(window.asOf()), window.trafficOriginPolicy().name()));
    }

    private CohortSql reviewSlaSql(UUID merchantId, MonitoringWindow window) {
        return new CohortSql("""
                SELECT 'MANUAL_REVIEW_CASE' AS resource_type,
                       review_case.id AS resource_id,
                       review_case.created_at AS occurred_at,
                       CASE WHEN COALESCE(review_case.resolved_at, review_case.canceled_at) IS NULL
                                 OR COALESCE(review_case.resolved_at, review_case.canceled_at)
                                    > review_case.created_at + INTERVAL '24 hours'
                            THEN TRUE ELSE FALSE END AS numerator_member,
                       TRUE AS denominator_member,
                       'DUE_BY_AS_OF' AS evidence_code,
                       1 AS row_count
                  FROM manual_review_cases review_case
                 WHERE review_case.merchant_id = ?
                   AND review_case.created_at <= ?
                   AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                        OR review_case.traffic_origin = 'MERCHANT_API')
                """, List.of(merchantId, Timestamp.from(window.asOf().minus(REVIEW_SLA)),
                window.trafficOriginPolicy().name()));
    }

    private CohortPage volumeBuckets(UUID merchantId, MerchantRiskMetricCode metricCode,
            MonitoringWindow window) {
        Duration bucket = Duration.between(window.windowStart(), window.windowEnd());
        List<CohortRow> rows = new ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            Instant bucketStart = offset == 0 ? window.windowStart()
                    : window.windowStart().minus(bucket.multipliedBy(offset));
            Instant bucketEnd = bucketStart.plus(bucket);
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM payment_attempts attempt
                     WHERE attempt.merchant_id = ?
                       AND attempt.created_at >= ? AND attempt.created_at < ?
                       AND (CAST(? AS VARCHAR) = 'INCLUDE_SYNTHETIC'
                            OR attempt.traffic_origin = 'MERCHANT_API')
                    """, Long.class, merchantId, Timestamp.from(bucketStart), Timestamp.from(bucketEnd),
                    window.trafficOriginPolicy().name());
            UUID bucketId = UUID.nameUUIDFromBytes((merchantId + "|" + bucketStart)
                    .getBytes(StandardCharsets.UTF_8));
            rows.add(new CohortRow("TIME_BUCKET", bucketId, bucketStart, offset == 0,
                    offset > 0, offset == 0 ? "CURRENT_BUCKET" : "PRIOR_BUCKET", count == null ? 0 : count));
        }
        return new CohortPage(metricCode, MerchantRiskMonitoringQueryService.DEFINITION_VERSION,
                window.windowStart(), window.windowEnd(), window.asOf(),
                window.trafficOriginPolicy(), List.copyOf(rows), null);
    }

    private int normalizeLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw badRequest("limit must be between 1 and 100");
        }
        return limit;
    }

    private CohortRow row(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CohortRow(resultSet.getString("resource_type"),
                resultSet.getObject("resource_id", UUID.class),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getBoolean("numerator_member"),
                resultSet.getBoolean("denominator_member"),
                resultSet.getString("evidence_code"), resultSet.getLong("row_count"));
    }

    private String encodeCursor(UUID merchantId, MerchantRiskMetricCode metricCode,
            MonitoringWindow window, CohortRow row) {
        String payload = cursorPayload(merchantId, metricCode, window, row.occurredAt(), row.resourceId());
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hasher.hmacSha256(CURSOR_HMAC_DOMAIN, payload);
    }

    private CohortCursor decodeCursor(String token, UUID merchantId,
            MerchantRiskMetricCode metricCode, MonitoringWindow window) {
        if (token.length() > 512 || !token.matches("[A-Za-z0-9_-]+\\.[0-9a-f]{64}")) {
            throw badRequest("Monitoring cursor is malformed");
        }
        String[] tokenParts = token.split("\\.", -1);
        try {
            String payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]),
                    StandardCharsets.UTF_8);
            byte[] expected = HexFormat.of().parseHex(hasher.hmacSha256(CURSOR_HMAC_DOMAIN, payload));
            byte[] supplied = HexFormat.of().parseHex(tokenParts[1]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw badRequest("Monitoring cursor is invalid for this request");
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 9
                    || !CURSOR_VERSION.equals(fields[0])
                    || !merchantId.toString().equals(fields[1])
                    || !metricCode.name().equals(fields[2])
                    || !window.windowStart().toString().equals(fields[3])
                    || !window.windowEnd().toString().equals(fields[4])
                    || !window.asOf().toString().equals(fields[5])
                    || !window.trafficOriginPolicy().name().equals(fields[6])) {
                throw badRequest("Monitoring cursor is invalid for this request");
            }
            return new CohortCursor(Instant.parse(fields[7]), UUID.fromString(fields[8]));
        } catch (IllegalArgumentException exception) {
            throw badRequest("Monitoring cursor is malformed");
        }
    }

    private String cursorPayload(UUID merchantId, MerchantRiskMetricCode metricCode,
            MonitoringWindow window, Instant occurredAt, UUID resourceId) {
        return String.join("|", CURSOR_VERSION, merchantId.toString(), metricCode.name(),
                window.windowStart().toString(), window.windowEnd().toString(),
                window.asOf().toString(), window.trafficOriginPolicy().name(),
                occurredAt.toString(), resourceId.toString());
    }

    private Object sqlArgument(Object argument) {
        return argument instanceof Instant instant ? Timestamp.from(instant) : argument;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CohortPage(MerchantRiskMetricCode metricCode, String definitionVersion,
            Instant windowStart, Instant windowEnd, Instant asOf,
            MonitoringTrafficOriginPolicy trafficOriginPolicy,
            List<CohortRow> rows, String nextCursor) { }

    public record CohortRow(String resourceType, UUID resourceId, Instant occurredAt,
            boolean numeratorMember, boolean denominatorMember, String evidenceCode,
            long count) { }

    private record CohortSql(String sql, List<Object> arguments) { }

    private record CohortCursor(Instant occurredAt, UUID resourceId) { }
}
