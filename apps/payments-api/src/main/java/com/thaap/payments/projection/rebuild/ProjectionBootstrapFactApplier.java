package com.thaap.payments.projection.rebuild;

import com.thaap.payments.projection.ProjectionNames;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies source-snapshot facts only to an isolated projection generation. */
@Component
public class ProjectionBootstrapFactApplier {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectionBootstrapSourceReader sourceReader;
    private final Clock clock;

    public ProjectionBootstrapFactApplier(JdbcTemplate jdbcTemplate,
            ProjectionBootstrapSourceReader sourceReader, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.sourceReader = sourceReader;
        this.clock = clock;
    }

    public ProjectionBootstrapPaymentFact apply(ProjectionRebuildInput input,
            ProjectionRebuildLease lease) {
        String actualDigest = ProjectionCutoffManifestService.sha256(
                input.canonicalRecordBytes());
        if (!constantTimeEquals(actualDigest, input.canonicalRecordSha256())) {
            throw new IllegalStateException("Projection bootstrap fact digest is invalid");
        }
        ProjectionBootstrapPaymentFact fact = sourceReader.decode(
                input.canonicalRecordBytes());
        if (!fact.merchantId().equals(input.merchantId())
                || !fact.paymentIntentId().equals(input.streamId())
                || fact.baseStreamVersion() != input.streamVersion()) {
            throw new IllegalStateException("Projection bootstrap fact identity is inconsistent");
        }
        EventLineage lineage = requireEventLineage(fact);
        Instant processedAt = processedAt(fact.sourceCutoffAt());
        if (ProjectionNames.PAYMENT_RISK.equals(lease.projectionName())) {
            insertPayment(input.inputId(), fact, lineage, lease, processedAt);
        } else if (ProjectionNames.MERCHANT_RISK.equals(lease.projectionName())) {
            insertMerchantCohort(input.inputId(), fact, lineage, lease, processedAt);
            applyMerchantAggregates(input.inputId(), fact, lease, processedAt);
        } else {
            throw new IllegalStateException("Projection bootstrap target is unsupported");
        }
        return fact;
    }

    private void insertPayment(UUID bootstrapFactId,
            ProjectionBootstrapPaymentFact fact, EventLineage lineage,
            ProjectionRebuildLease lease, Instant processedAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO payment_risk_projections (
                    id, merchant_id, projection_definition_id,
                    projection_generation_id, payment_intent_id,
                    intent_amount_minor, currency, capture_method, traffic_origin,
                    attempt_number, attempt_outcome, processor_code, fraud_action,
                    risk_level, risk_score, reason_codes_json, ruleset_version,
                    feature_catalogue_version, fraud_decision_id, feature_snapshot_id,
                    review_status, review_outcome,
                    captured_amount_minor, refunded_amount_minor, disputed_amount_minor,
                    label_value, fraud_type, label_source, label_available_at,
                    lifecycle_status, coverage_status, intent_occurred_at,
                    last_stream_version, last_domain_event_id, last_event_record_sha256,
                    last_event_occurred_at, last_event_recorded_at, last_processed_at,
                    created_at, updated_at, source_bootstrap_fact_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, 'PARTIAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fact.merchantId(), lease.projectionDefinitionId(),
                lease.projectionGenerationId(), fact.paymentIntentId(),
                fact.intentAmountMinor(), fact.currency(), fact.captureMethod(),
                fact.trafficOrigin(), fact.attemptNumber(), fact.attemptOutcome(),
                fact.processorCode(), fact.fraudAction(), fact.riskLevel(), fact.riskScore(),
                fact.reasonCodesJson(), fact.rulesetVersion(),
                fact.featureCatalogueVersion(), fact.fraudDecisionId(), fact.featureSnapshotId(),
                fact.reviewStatus(), fact.reviewOutcome(),
                fact.capturedAmountMinor(), fact.refundedAmountMinor(),
                fact.disputedAmountMinor(), fact.labelValue(), fact.fraudType(),
                fact.labelSource(), timestamp(fact.labelAvailableAt()),
                fact.lifecycleStatus(), Timestamp.from(fact.intentOccurredAt()),
                fact.baseStreamVersion(), fact.baseTerminalDomainEventId(),
                fact.baseTerminalEventRecordSha256(), timestamp(lineage.occurredAt()),
                timestamp(lineage.recordedAt()), Timestamp.from(processedAt),
                Timestamp.from(processedAt), Timestamp.from(processedAt), bootstrapFactId);
        if (inserted != 1) {
            throw new IllegalStateException("Projection bootstrap payment was not singular");
        }
    }

    private void insertMerchantCohort(UUID bootstrapFactId,
            ProjectionBootstrapPaymentFact fact, EventLineage lineage,
            ProjectionRebuildLease lease, Instant processedAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO merchant_payment_cohort_states (
                    id, merchant_id, projection_definition_id,
                    projection_generation_id, payment_intent_id, currency,
                    traffic_origin, intent_occurred_at, label_value, label_available_at,
                    last_stream_version, last_domain_event_id, last_event_record_sha256,
                    last_event_recorded_at, last_processed_at, created_at, updated_at,
                    source_bootstrap_fact_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fact.merchantId(), lease.projectionDefinitionId(),
                lease.projectionGenerationId(), fact.paymentIntentId(), fact.currency(),
                fact.trafficOrigin(), Timestamp.from(fact.intentOccurredAt()),
                fact.labelValue(), timestamp(fact.labelAvailableAt()),
                fact.baseStreamVersion(), fact.baseTerminalDomainEventId(),
                fact.baseTerminalEventRecordSha256(), timestamp(lineage.recordedAt()),
                Timestamp.from(processedAt), Timestamp.from(processedAt),
                Timestamp.from(processedAt), bootstrapFactId);
        if (inserted != 1) {
            throw new IllegalStateException("Projection bootstrap cohort was not singular");
        }
    }

    private void applyMerchantAggregates(UUID bootstrapFactId,
            ProjectionBootstrapPaymentFact fact, ProjectionRebuildLease lease,
            Instant processedAt) {
        Instant hour = fact.intentOccurredAt().truncatedTo(ChronoUnit.HOURS);
        Instant day = fact.intentOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        applyMerchantAggregate(bootstrapFactId, fact, lease, "HOUR", hour,
                "ALL", processedAt);
        applyMerchantAggregate(bootstrapFactId, fact, lease, "DAY", day,
                "ALL", processedAt);
        String policy = "SYNTHETIC_SCENARIO".equals(fact.trafficOrigin())
                ? "SYNTHETIC_ONLY" : "MERCHANT_API".equals(fact.trafficOrigin())
                        ? "LIVE_ONLY" : null;
        if (policy != null) {
            applyMerchantAggregate(bootstrapFactId, fact, lease, "HOUR", hour,
                    policy, processedAt);
            applyMerchantAggregate(bootstrapFactId, fact, lease, "DAY", day,
                    policy, processedAt);
        }
    }

    private void applyMerchantAggregate(UUID bootstrapFactId,
            ProjectionBootstrapPaymentFact fact, ProjectionRebuildLease lease,
            String intervalType, Instant intervalStart, String originPolicy,
            Instant processedAt) {
        long confirmedFraud = "FRAUD".equals(fact.labelValue()) ? 1 : 0;
        long unlabeled = fact.labelValue() == null ? 1 : 0;
        int updated = jdbcTemplate.update("""
                UPDATE merchant_risk_aggregates
                SET payment_count = payment_count + 1,
                    attempt_count = attempt_count + ?,
                    authorization_count = authorization_count + ?,
                    decline_count = decline_count + ?,
                    review_count = review_count + ?,
                    confirmed_fraud_count = confirmed_fraud_count + ?,
                    chargeback_count = chargeback_count + ?,
                    unlabeled_count = unlabeled_count + ?,
                    unknown_outcome_count = unknown_outcome_count + ?,
                    intent_amount_minor = intent_amount_minor + ?,
                    authorized_amount_minor = authorized_amount_minor + ?,
                    captured_amount_minor = captured_amount_minor + ?,
                    refunded_amount_minor = refunded_amount_minor + ?,
                    disputed_amount_minor = disputed_amount_minor + ?,
                    source_bootstrap_fact_count = source_bootstrap_fact_count + 1,
                    latest_bootstrap_fact_id = ?, coverage_status = 'PARTIAL',
                    last_processed_at = ?, updated_at = ?
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND interval_type = ? AND interval_start = ?
                  AND traffic_origin_policy = ? AND currency = ?
                """, fact.attemptCount(), fact.authorizationCount(), fact.declineCount(),
                fact.reviewCount(), confirmedFraud, fact.chargebackCount(), unlabeled,
                fact.unknownOutcomeCount(), fact.intentAmountMinor(),
                fact.authorizedAmountMinor(), fact.capturedAmountMinor(),
                fact.refundedAmountMinor(), fact.disputedAmountMinor(), bootstrapFactId,
                Timestamp.from(processedAt), Timestamp.from(processedAt),
                fact.merchantId(), lease.projectionGenerationId(), intervalType,
                Timestamp.from(intervalStart), originPolicy, fact.currency());
        if (updated == 1) {
            return;
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO merchant_risk_aggregates (
                    id, merchant_id, projection_definition_id,
                    projection_generation_id, interval_type, interval_start,
                    traffic_origin_policy, payment_count, attempt_count,
                    authorization_count, decline_count, review_count,
                    confirmed_fraud_count, chargeback_count, enumeration_signal_count,
                    unlabeled_count, unknown_outcome_count, intent_amount_minor,
                    authorized_amount_minor, captured_amount_minor, refunded_amount_minor,
                    disputed_amount_minor, currency, source_event_count,
                    latest_domain_event_id, latest_source_occurred_at,
                    latest_source_recorded_at, last_processed_at, projection_version,
                    evaluator_version, coverage_status, created_at, updated_at,
                    source_bootstrap_fact_count, latest_bootstrap_fact_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?,
                        0, NULL, NULL, NULL, ?, ?, ?, 'PARTIAL', ?, ?, 1, ?)
                """, UUID.randomUUID(), fact.merchantId(), lease.projectionDefinitionId(),
                lease.projectionGenerationId(), intervalType, Timestamp.from(intervalStart),
                originPolicy, fact.attemptCount(), fact.authorizationCount(),
                fact.declineCount(), fact.reviewCount(), confirmedFraud,
                fact.chargebackCount(), unlabeled, fact.unknownOutcomeCount(),
                fact.intentAmountMinor(), fact.authorizedAmountMinor(),
                fact.capturedAmountMinor(), fact.refundedAmountMinor(),
                fact.disputedAmountMinor(), fact.currency(), Timestamp.from(processedAt),
                ProjectionNames.HANDLER_VERSION, ProjectionNames.HANDLER_VERSION,
                Timestamp.from(processedAt), Timestamp.from(processedAt), bootstrapFactId);
        if (inserted != 1) {
            throw new IllegalStateException("Projection bootstrap aggregate was not singular");
        }
    }

    private EventLineage requireEventLineage(ProjectionBootstrapPaymentFact fact) {
        if (fact.baseStreamVersion() == 0) {
            return EventLineage.empty();
        }
        EventLineage lineage = jdbcTemplate.query("""
                SELECT occurred_at, recorded_at FROM domain_events
                WHERE merchant_id = ? AND id = ? AND stream_type = 'PAYMENT'
                  AND stream_id = ? AND stream_version = ?
                  AND event_record_sha256 = ?
                """, resultSet -> resultSet.next() ? new EventLineage(
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 1),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 2)) : null,
                fact.merchantId(), fact.baseTerminalDomainEventId(),
                fact.paymentIntentId(), fact.baseStreamVersion(),
                fact.baseTerminalEventRecordSha256());
        if (lineage == null) {
            throw new IllegalStateException("Projection bootstrap event base is unavailable");
        }
        return lineage;
    }

    private Instant processedAt(Instant sourceCutoffAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(sourceCutoffAt) ? sourceCutoffAt : now;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private record EventLineage(Instant occurredAt, Instant recordedAt) {
        private static EventLineage empty() {
            return new EventLineage(null, null);
        }
    }
}
