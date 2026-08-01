package com.thaap.payments.event;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists and reads immutable, tenant-qualified links from canonical events to source facts. */
@Component
public class DomainEventSourceLineageStore {

    private final JdbcTemplate jdbcTemplate;

    public DomainEventSourceLineageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendFraudDecision(DomainEventWriteResult event, UUID fraudDecisionId,
            UUID featureSnapshotId) {
        if (event == null || event.eventType() != DomainEventType.FRAUD_DECISION_CREATED
                || fraudDecisionId == null || featureSnapshotId == null) {
            throw new IllegalArgumentException("Fraud event source lineage is invalid");
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO domain_event_source_lineage (
                    id, merchant_id, domain_event_id, source_type,
                    fraud_decision_id, feature_snapshot_id, created_at)
                SELECT ?, domain_event.merchant_id, domain_event.id, 'FRAUD_DECISION',
                       decision.id, snapshot.id, ?
                  FROM domain_events domain_event
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = domain_event.merchant_id
                   AND decision.id = ?
                  JOIN payment_attempts attempt
                    ON attempt.merchant_id = decision.merchant_id
                   AND attempt.id = decision.payment_attempt_id
                   AND attempt.payment_intent_id = domain_event.stream_id
                  JOIN feature_snapshots snapshot
                    ON snapshot.merchant_id = decision.merchant_id
                   AND snapshot.id = ?
                   AND snapshot.fraud_decision_id = decision.id
                   AND snapshot.payment_attempt_id = attempt.id
                 WHERE domain_event.merchant_id = ?
                   AND domain_event.id = ?
                   AND domain_event.stream_type = 'PAYMENT'
                   AND domain_event.event_type = 'fraud_decision.created'
                """, UUID.randomUUID(), Timestamp.from(event.recordedAt()), fraudDecisionId,
                featureSnapshotId, event.merchantId(), event.eventId());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Fraud event source lineage was not an exact singular payment chain");
        }
    }

    public FraudDecisionLineage findFraudDecision(UUID merchantId, UUID domainEventId) {
        List<FraudDecisionLineage> matches = jdbcTemplate.query("""
                SELECT fraud_decision_id, feature_snapshot_id
                FROM domain_event_source_lineage
                WHERE merchant_id = ? AND domain_event_id = ?
                  AND source_type = 'FRAUD_DECISION'
                """, (resultSet, rowNumber) -> new FraudDecisionLineage(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class)), merchantId, domainEventId);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Fraud event source lineage is ambiguous");
        }
        return matches.getFirst();
    }

    public record FraudDecisionLineage(UUID fraudDecisionId, UUID featureSnapshotId) {
        public FraudDecisionLineage {
            if (fraudDecisionId == null || featureSnapshotId == null) {
                throw new IllegalArgumentException("Fraud decision lineage identities are required");
            }
        }
    }
}
