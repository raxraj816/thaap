package com.thaap.payments.simulation;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;

public class HistoricalSimulationItemReader implements ItemStreamReader<HistoricalSimulationCohortItem> {

    private static final String CHECKPOINT_TIME = "simulation.checkpoint.time";
    private static final String CHECKPOINT_ID = "simulation.checkpoint.id";

    private final JdbcTemplate jdbcTemplate;
    private final UUID runId;
    private final int pageSize;
    private final Deque<HistoricalSimulationCohortItem> buffer = new ArrayDeque<>();
    private RunBoundary boundary;
    private Instant lastCreatedAt;
    private UUID lastAttemptId;
    private boolean exhausted;

    public HistoricalSimulationItemReader(JdbcTemplate jdbcTemplate, UUID runId, int pageSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.runId = runId;
        this.pageSize = pageSize;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        boundary = jdbcTemplate.query("""
                SELECT merchant_id, cohort_start, cohort_end, as_of, traffic_origin_policy
                  FROM risk_simulation_runs WHERE id = ?
                """, resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Simulation run is unavailable to its reader");
                    }
                    return new RunBoundary(resultSet.getObject(1, UUID.class),
                            resultSet.getTimestamp(2).toInstant(), resultSet.getTimestamp(3).toInstant(),
                            resultSet.getTimestamp(4).toInstant(),
                            RiskSimulationTrafficOriginPolicy.valueOf(resultSet.getString(5)));
                }, runId);
        String checkpointTime = executionContext.getString(CHECKPOINT_TIME, null);
        String checkpointId = executionContext.getString(CHECKPOINT_ID, null);
        if (checkpointTime != null && checkpointId != null) {
            lastCreatedAt = Instant.parse(checkpointTime);
            lastAttemptId = UUID.fromString(checkpointId);
        }
    }

    @Override
    public HistoricalSimulationCohortItem read() {
        if (buffer.isEmpty() && !exhausted) {
            loadPage();
        }
        HistoricalSimulationCohortItem item = buffer.pollFirst();
        if (item != null) {
            lastCreatedAt = item.decisionCreatedAt();
            lastAttemptId = item.paymentAttemptId();
        }
        return item;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        if (lastCreatedAt != null && lastAttemptId != null) {
            executionContext.putString(CHECKPOINT_TIME, lastCreatedAt.toString());
            executionContext.putString(CHECKPOINT_ID, lastAttemptId.toString());
        }
    }

    private void loadPage() {
        String keyset = lastCreatedAt == null ? """
                """ : " AND (decision.created_at > ? OR (decision.created_at = ? AND attempt.id > ?))";
        String origin = boundary.originPolicy() == RiskSimulationTrafficOriginPolicy.LIVE_ONLY
                ? " AND attempt.traffic_origin = 'MERCHANT_API'" : "";
        String sql = """
                SELECT attempt.id, decision.id, attempt.payment_intent_id,
                       payment_method.card_fingerprint, decision.created_at,
                       attempt.traffic_origin, decision.action,
                       COALESCE(payment_authorization.authorized_amount, 0),
                       COALESCE((SELECT SUM(entry.amount) FROM ledger_entries entry
                                  WHERE entry.merchant_id = attempt.merchant_id
                                    AND entry.source_type = 'PAYMENT_INTENT_CAPTURE'
                                    AND entry.source_id = attempt.payment_intent_id
                                    AND entry.account = 'MERCHANT_PAYABLE'
                                    AND entry.direction = 'CREDIT'
                                    AND entry.created_at <= ?), 0),
                       COALESCE((SELECT SUM(refund.amount) FROM refunds refund
                                  WHERE refund.merchant_id = attempt.merchant_id
                                    AND refund.payment_attempt_id = attempt.id
                                    AND refund.created_at <= ?), 0)
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                  JOIN payment_methods payment_method
                    ON payment_method.merchant_id = attempt.merchant_id
                   AND payment_method.id = attempt.payment_method_id
             LEFT JOIN authorizations payment_authorization
                    ON payment_authorization.merchant_id = attempt.merchant_id
                   AND payment_authorization.payment_attempt_id = attempt.id
                   AND payment_authorization.created_at <= ?
                 WHERE attempt.merchant_id = ?
                   AND decision.created_at >= ? AND decision.created_at < ?
                   AND decision.created_at <= ?
                """ + origin + keyset + " ORDER BY decision.created_at, attempt.id LIMIT ?";
        Object[] parameters;
        if (lastCreatedAt == null) {
            parameters = new Object[] {Timestamp.from(boundary.asOf()), Timestamp.from(boundary.asOf()),
                    Timestamp.from(boundary.asOf()), boundary.merchantId(),
                    Timestamp.from(boundary.cohortStart()), Timestamp.from(boundary.cohortEnd()),
                    Timestamp.from(boundary.asOf()), pageSize};
        } else {
            parameters = new Object[] {Timestamp.from(boundary.asOf()), Timestamp.from(boundary.asOf()),
                    Timestamp.from(boundary.asOf()), boundary.merchantId(),
                    Timestamp.from(boundary.cohortStart()), Timestamp.from(boundary.cohortEnd()),
                    Timestamp.from(boundary.asOf()), Timestamp.from(lastCreatedAt),
                    Timestamp.from(lastCreatedAt), lastAttemptId, pageSize};
        }
        List<HistoricalSimulationCohortItem> page = jdbcTemplate.query(sql,
                (resultSet, rowNumber) -> new HistoricalSimulationCohortItem(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getString(4),
                        resultSet.getTimestamp(5).toInstant(), TrafficOrigin.valueOf(resultSet.getString(6)),
                        FraudDecisionAction.valueOf(resultSet.getString(7)),
                        resultSet.getLong(8), resultSet.getLong(9), resultSet.getLong(10)), parameters);
        buffer.addAll(page);
        exhausted = page.isEmpty();
    }

    private record RunBoundary(UUID merchantId, Instant cohortStart, Instant cohortEnd,
            Instant asOf, RiskSimulationTrafficOriginPolicy originPolicy) { }
}
