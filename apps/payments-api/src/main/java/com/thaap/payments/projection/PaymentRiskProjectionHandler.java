package com.thaap.payments.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.event.CanonicalDomainEventRecord;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventSourceLineageStore;
import com.thaap.payments.event.DomainEventSourceLineageStore.FraudDecisionLineage;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentRiskProjectionHandler {

    private static final String COMPLETE = "COMPLETE";

    private final JdbcTemplate jdbcTemplate;
    private final DomainEventSourceLineageStore sourceLineageStore;

    public PaymentRiskProjectionHandler(JdbcTemplate jdbcTemplate,
            DomainEventSourceLineageStore sourceLineageStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.sourceLineageStore = sourceLineageStore;
    }

    public void apply(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration paymentGeneration,
            ActiveProjectionGeneration merchantGeneration, Instant processedAt) {
        applyPaymentState(event, paymentGeneration, processedAt);
        MerchantCohortState cohort = applyMerchantCohortState(event,
                merchantGeneration, processedAt);
        applyMerchantAggregate(event, merchantGeneration, cohort, processedAt);
    }

    /** Applies only the payment-shaped read model for an isolated shadow generation. */
    public void applyPaymentOnly(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration paymentGeneration, Instant processedAt) {
        applyPaymentState(event, paymentGeneration, processedAt);
    }

    /**
     * Applies the merchant aggregate and its capability-owned cohort state in an isolated shadow
     * generation. Label supersession never writes payment-projection rows under a merchant
     * generation.
     */
    public void applyMerchantOnly(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration merchantGeneration, Instant processedAt) {
        MerchantCohortState cohort = applyMerchantCohortState(event,
                merchantGeneration, processedAt);
        applyMerchantAggregate(event, merchantGeneration, cohort, processedAt);
    }

    private PaymentProjectionState applyPaymentState(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration generation, Instant processedAt) {
        PaymentProjectionState state = null;
        if (event.streamType() == DomainStreamType.PAYMENT) {
            state = loadPaymentState(event.merchantId(), generation.generationId(),
                    event.streamId());
            if (state == null) {
                if (event.streamVersion() != 1
                        || event.eventType() != DomainEventType.PAYMENT_INTENT_CREATED) {
                    throw new ProjectionInvariantException(
                            "Payment projection must begin with the intent-created fact");
                }
                state = PaymentProjectionState.initial(event, generation.generationId(),
                        generation.definitionId(), processedAt);
            }
            state.definitionId = generation.definitionId();
            applyPaymentFact(state, event);
            persistPaymentState(state, event, processedAt);
        }
        return state;
    }

    private MerchantCohortState applyMerchantCohortState(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration generation, Instant processedAt) {
        if (event.streamType() != DomainStreamType.PAYMENT) {
            return null;
        }
        List<MerchantCohortState> matches = jdbcTemplate.query("""
                SELECT id, currency, traffic_origin, intent_occurred_at,
                       label_value, label_available_at, created_at
                FROM merchant_payment_cohort_states
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND payment_intent_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> {
                    MerchantCohortState state = new MerchantCohortState();
                    state.id = resultSet.getObject(1, UUID.class);
                    state.merchantId = event.merchantId();
                    state.definitionId = generation.definitionId();
                    state.generationId = generation.generationId();
                    state.paymentIntentId = event.streamId();
                    state.currency = resultSet.getString(2);
                    state.trafficOrigin = resultSet.getString(3);
                    state.intentOccurredAt = JdbcTime.instant(resultSet, 4);
                    state.labelValue = resultSet.getString(5);
                    state.priorLabelValue = state.labelValue;
                    state.labelAvailableAt = JdbcTime.instant(resultSet, 6);
                    state.createdAt = JdbcTime.instant(resultSet, 7);
                    return state;
                }, event.merchantId(), generation.generationId(), event.streamId());
        MerchantCohortState state;
        if (matches.isEmpty()) {
            if (event.streamVersion() != 1
                    || event.eventType() != DomainEventType.PAYMENT_INTENT_CREATED) {
                throw new ProjectionInvariantException(
                        "Merchant payment cohort must begin with the intent-created fact");
            }
            state = MerchantCohortState.initial(event, generation, processedAt);
        } else if (matches.size() == 1) {
            state = matches.getFirst();
        } else {
            throw new ProjectionInvariantException("Merchant payment cohort is ambiguous");
        }
        applyMerchantCohortFact(state, event);
        persistMerchantCohortState(state, event, processedAt);
        return state;
    }

    private void applyMerchantCohortFact(MerchantCohortState state,
            CanonicalDomainEventRecord event) {
        if (event.eventType() == DomainEventType.PAYMENT_INTENT_CREATED) {
            state.currency = requiredText(event.payload(), "currency");
            state.trafficOrigin = event.trafficOrigin() == null
                    ? null : event.trafficOrigin().name();
            state.intentOccurredAt = event.occurredAt();
            return;
        }
        if (event.eventType() != DomainEventType.FRAUD_LABEL_RECORDED
                && event.eventType() != DomainEventType.FRAUD_LABEL_SUPERSEDED) {
            return;
        }
        Instant availableAt = parseInstant(event.payload(), "availableAt");
        if (state.labelAvailableAt != null && availableAt.isBefore(state.labelAvailableAt)) {
            throw new ProjectionInvariantException("Fraud label availability cannot regress");
        }
        if (event.eventType() == DomainEventType.FRAUD_LABEL_RECORDED
                && state.priorLabelValue != null) {
            throw new ProjectionInvariantException(
                    "A recorded fraud label cannot replace existing label truth");
        }
        if (event.eventType() == DomainEventType.FRAUD_LABEL_SUPERSEDED
                && state.priorLabelValue == null) {
            throw new ProjectionInvariantException(
                    "A superseding fraud label requires prior label truth");
        }
        state.labelValue = requiredText(event.payload(), "label");
        state.labelAvailableAt = availableAt;
    }

    private void persistMerchantCohortState(MerchantCohortState state,
            CanonicalDomainEventRecord event, Instant processedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE merchant_payment_cohort_states
                SET currency = ?, traffic_origin = ?, intent_occurred_at = ?,
                    label_value = ?, label_available_at = ?, last_stream_version = ?,
                    last_domain_event_id = ?, last_event_record_sha256 = ?,
                    last_event_recorded_at = ?, last_processed_at = ?, updated_at = ?
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND payment_intent_id = ?
                """, state.currency, state.trafficOrigin, timestamp(state.intentOccurredAt),
                state.labelValue, timestamp(state.labelAvailableAt), event.streamVersion(),
                event.eventId(), event.eventRecordSha256(), timestamp(event.recordedAt()),
                timestamp(processedAt), timestamp(processedAt), state.merchantId,
                state.generationId, state.paymentIntentId);
        if (updated != 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO merchant_payment_cohort_states (
                    id, merchant_id, projection_definition_id, projection_generation_id,
                    payment_intent_id, currency, traffic_origin, intent_occurred_at,
                    label_value, label_available_at, last_stream_version,
                    last_domain_event_id, last_event_record_sha256,
                    last_event_recorded_at, last_processed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, state.id, state.merchantId, state.definitionId, state.generationId,
                state.paymentIntentId, state.currency, state.trafficOrigin,
                timestamp(state.intentOccurredAt), state.labelValue,
                timestamp(state.labelAvailableAt),
                event.streamVersion(), event.eventId(), event.eventRecordSha256(),
                timestamp(event.recordedAt()), timestamp(processedAt),
                timestamp(state.createdAt), timestamp(processedAt));
    }

    private void applyMerchantAggregate(CanonicalDomainEventRecord event,
            ActiveProjectionGeneration merchantGeneration, MerchantCohortState state,
            Instant processedAt) {
        AggregateDelta delta = aggregateDelta(event, state);
        String currency = state == null || state.currency == null ? "XXX" : state.currency;
        applyAggregateIntervals(event, merchantGeneration.definitionId(),
                merchantGeneration.generationId(), currency, delta, processedAt);
        if (state != null && (event.eventType() == DomainEventType.FRAUD_LABEL_RECORDED
                || event.eventType() == DomainEventType.FRAUD_LABEL_SUPERSEDED)) {
            applyLabelCohortAdjustment(event, merchantGeneration.definitionId(),
                    merchantGeneration.generationId(), state, processedAt);
        }
    }

    private PaymentProjectionState loadPaymentState(UUID merchantId, UUID generationId,
            UUID paymentIntentId) {
        List<PaymentProjectionState> matches = jdbcTemplate.query("""
                SELECT id, intent_amount_minor, currency, capture_method, traffic_origin,
                       attempt_number, attempt_outcome, processor_code, fraud_action,
                       risk_level, risk_score, reason_codes_json, ruleset_version,
                       feature_catalogue_version, fraud_decision_id, feature_snapshot_id,
                       review_status, review_outcome,
                       captured_amount_minor, refunded_amount_minor, disputed_amount_minor,
                       label_value, fraud_type, label_source, label_available_at,
                       lifecycle_status, coverage_status, intent_occurred_at, created_at
                FROM payment_risk_projections
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND payment_intent_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> {
                    PaymentProjectionState state = new PaymentProjectionState();
                    state.id = resultSet.getObject("id", UUID.class);
                    state.merchantId = merchantId;
                    state.generationId = generationId;
                    state.paymentIntentId = paymentIntentId;
                    state.intentAmountMinor = nullableLong(resultSet, "intent_amount_minor");
                    state.currency = resultSet.getString("currency");
                    state.captureMethod = resultSet.getString("capture_method");
                    state.trafficOrigin = resultSet.getString("traffic_origin");
                    state.attemptNumber = nullableInteger(resultSet, "attempt_number");
                    state.attemptOutcome = resultSet.getString("attempt_outcome");
                    state.processorCode = resultSet.getString("processor_code");
                    state.fraudAction = resultSet.getString("fraud_action");
                    state.riskLevel = resultSet.getString("risk_level");
                    state.riskScore = nullableInteger(resultSet, "risk_score");
                    state.reasonCodesJson = resultSet.getString("reason_codes_json");
                    state.rulesetVersion = resultSet.getString("ruleset_version");
                    state.featureCatalogueVersion = resultSet.getString(
                            "feature_catalogue_version");
                    state.fraudDecisionId = resultSet.getObject("fraud_decision_id", UUID.class);
                    state.featureSnapshotId = resultSet.getObject("feature_snapshot_id", UUID.class);
                    state.reviewStatus = resultSet.getString("review_status");
                    state.reviewOutcome = resultSet.getString("review_outcome");
                    state.capturedAmountMinor = resultSet.getLong("captured_amount_minor");
                    state.refundedAmountMinor = resultSet.getLong("refunded_amount_minor");
                    state.disputedAmountMinor = resultSet.getLong("disputed_amount_minor");
                    state.labelValue = resultSet.getString("label_value");
                    state.priorLabelValue = state.labelValue;
                    state.fraudType = resultSet.getString("fraud_type");
                    state.labelSource = resultSet.getString("label_source");
                    state.labelAvailableAt = JdbcTime.instant(resultSet, "label_available_at");
                    state.lifecycleStatus = resultSet.getString("lifecycle_status");
                    state.coverageStatus = resultSet.getString("coverage_status");
                    state.intentOccurredAt = JdbcTime.instant(resultSet, "intent_occurred_at");
                    state.createdAt = JdbcTime.instant(resultSet, "created_at");
                    return state;
                }, merchantId, generationId, paymentIntentId);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new ProjectionInvariantException("Payment projection identity is ambiguous");
        }
        return matches.getFirst();
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private void applyPaymentFact(PaymentProjectionState state,
            CanonicalDomainEventRecord event) {
        ObjectNode payload = event.payload();
        switch (event.eventType()) {
            case PAYMENT_INTENT_CREATED -> {
                state.intentAmountMinor = requiredLong(payload, "amountMinor");
                state.currency = requiredText(payload, "currency");
                state.captureMethod = requiredText(payload, "captureMethod");
                state.trafficOrigin = event.trafficOrigin() == null
                        ? null : event.trafficOrigin().name();
                state.lifecycleStatus = "CREATED";
            }
            case PAYMENT_INTENT_CANCELED -> state.lifecycleStatus = "CANCELED";
            case PAYMENT_ATTEMPT_COMPLETED -> {
                state.attemptNumber = requiredPositiveInteger(payload, "attemptNumber");
                state.attemptOutcome = requiredText(payload, "outcome");
                state.processorCode = optionalText(payload, "processorCode");
                state.lifecycleStatus = state.attemptOutcome;
            }
            case FRAUD_DECISION_CREATED -> {
                state.fraudAction = requiredText(payload, "action");
                state.riskLevel = requiredText(payload, "riskLevel");
                state.riskScore = optionalRiskScoreBasisPoints(payload, "riskScore");
                state.reasonCodesJson = requiredArray(payload, "reasonCodes").toString();
                state.rulesetVersion = requiredText(payload, "rulesetVersion");
                state.featureCatalogueVersion = optionalText(payload,
                        "featureCatalogueVersion");
                FraudDecisionLineage lineage = sourceLineageStore.findFraudDecision(
                        event.merchantId(), event.eventId());
                if (lineage == null) {
                    throw new ProjectionInvariantException(
                            "Fraud decision event source lineage is unavailable");
                }
                state.fraudDecisionId = lineage.fraudDecisionId();
                state.featureSnapshotId = lineage.featureSnapshotId();
            }
            case AUTHENTICATION_CHALLENGE_CREATED -> state.lifecycleStatus = "REQUIRES_ACTION";
            case AUTHENTICATION_CHALLENGE_COMPLETED ->
                    state.lifecycleStatus = requiredText(payload, "result");
            case AUTHORIZATION_CREATED ->
                    state.lifecycleStatus = requiredText(payload, "outcome");
            case AUTHORIZATION_VOIDED -> state.lifecycleStatus = "AUTHORIZATION_VOIDED";
            case CAPTURE_CREATED -> state.capturedAmountMinor = addMoney(
                    state.capturedAmountMinor, requiredLong(payload, "amountMinor"));
            case REFUND_CREATED -> state.refundedAmountMinor = addMoney(
                    state.refundedAmountMinor, requiredLong(payload, "amountMinor"));
            case CHARGEBACK_CREATED -> state.disputedAmountMinor = addMoney(
                    state.disputedAmountMinor, requiredLong(payload, "amountMinor"));
            case MANUAL_REVIEW_OPENED -> state.reviewStatus = "OPEN";
            case MANUAL_REVIEW_ASSIGNED -> state.reviewStatus = "ASSIGNED";
            case MANUAL_REVIEW_RESOLVED -> {
                state.reviewStatus = "RESOLVED";
                state.reviewOutcome = requiredText(payload, "resolution");
                state.fraudType = optionalText(payload, "fraudType");
            }
            case FRAUD_LABEL_RECORDED, FRAUD_LABEL_SUPERSEDED -> {
                Instant availableAt = parseInstant(payload, "availableAt");
                if (state.labelAvailableAt != null && availableAt.isBefore(state.labelAvailableAt)) {
                    throw new ProjectionInvariantException("Fraud label availability cannot regress");
                }
                if (event.eventType() == DomainEventType.FRAUD_LABEL_RECORDED
                        && state.priorLabelValue != null) {
                    throw new ProjectionInvariantException(
                            "A recorded fraud label cannot replace existing label truth");
                }
                if (event.eventType() == DomainEventType.FRAUD_LABEL_SUPERSEDED
                        && state.priorLabelValue == null) {
                    throw new ProjectionInvariantException(
                            "A superseding fraud label requires prior label truth");
                }
                state.labelValue = requiredText(payload, "label");
                state.labelSource = requiredText(payload, "source");
                state.fraudType = optionalText(payload, "fraudType");
                state.labelAvailableAt = availableAt;
            }
            case POST_AUTHORIZATION_SIGNAL_RECORDED -> {
                // The safe signal remains visible through source lineage and aggregate coverage.
            }
            default -> throw new ProjectionInvariantException(
                    "Non-payment event was routed to the payment projection");
        }
    }

    private void persistPaymentState(PaymentProjectionState state,
            CanonicalDomainEventRecord event, Instant processedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE payment_risk_projections
                SET intent_amount_minor = ?, currency = ?, capture_method = ?, traffic_origin = ?,
                    attempt_number = ?, attempt_outcome = ?, processor_code = ?,
                    fraud_action = ?, risk_level = ?, risk_score = ?, reason_codes_json = ?,
                    ruleset_version = ?, feature_catalogue_version = ?, fraud_decision_id = ?,
                    feature_snapshot_id = ?, review_status = ?,
                    review_outcome = ?, captured_amount_minor = ?, refunded_amount_minor = ?,
                    disputed_amount_minor = ?, label_value = ?, fraud_type = ?,
                    label_source = ?, label_available_at = ?, lifecycle_status = ?,
                    coverage_status = ?, last_stream_version = ?, last_domain_event_id = ?,
                    last_event_record_sha256 = ?, last_event_occurred_at = ?,
                    last_event_recorded_at = ?, last_processed_at = ?, updated_at = ?
                WHERE merchant_id = ? AND projection_generation_id = ? AND payment_intent_id = ?
                """, state.intentAmountMinor, state.currency, state.captureMethod,
                state.trafficOrigin, state.attemptNumber, state.attemptOutcome,
                state.processorCode, state.fraudAction, state.riskLevel, state.riskScore,
                state.reasonCodesJson, state.rulesetVersion, state.featureCatalogueVersion,
                state.fraudDecisionId, state.featureSnapshotId, state.reviewStatus,
                state.reviewOutcome, state.capturedAmountMinor,
                state.refundedAmountMinor, state.disputedAmountMinor, state.labelValue,
                state.fraudType, state.labelSource, timestamp(state.labelAvailableAt),
                state.lifecycleStatus, state.coverageStatus, event.streamVersion(), event.eventId(),
                event.eventRecordSha256(), timestamp(event.occurredAt()),
                timestamp(event.recordedAt()), timestamp(processedAt), timestamp(processedAt),
                state.merchantId, state.generationId, state.paymentIntentId);
        if (updated == 0) {
            jdbcTemplate.update("""
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
                        last_stream_version,
                        last_domain_event_id, last_event_record_sha256,
                        last_event_occurred_at, last_event_recorded_at, last_processed_at,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, state.id, state.merchantId, state.definitionId,
                    state.generationId, state.paymentIntentId,
                    state.intentAmountMinor, state.currency, state.captureMethod,
                    state.trafficOrigin, state.attemptNumber, state.attemptOutcome,
                    state.processorCode, state.fraudAction, state.riskLevel, state.riskScore,
                    state.reasonCodesJson, state.rulesetVersion, state.featureCatalogueVersion,
                    state.fraudDecisionId, state.featureSnapshotId, state.reviewStatus,
                    state.reviewOutcome, state.capturedAmountMinor,
                    state.refundedAmountMinor, state.disputedAmountMinor, state.labelValue,
                    state.fraudType, state.labelSource, timestamp(state.labelAvailableAt),
                    state.lifecycleStatus, state.coverageStatus,
                    timestamp(state.intentOccurredAt),
                    event.streamVersion(), event.eventId(),
                    event.eventRecordSha256(), timestamp(event.occurredAt()),
                    timestamp(event.recordedAt()), timestamp(processedAt),
                    timestamp(state.createdAt), timestamp(processedAt));
        }
    }

    private AggregateDelta aggregateDelta(CanonicalDomainEventRecord event,
            MerchantCohortState state) {
        ObjectNode payload = event.payload();
        AggregateDelta delta = new AggregateDelta();
        delta.sourceEventCount = 1;
        switch (event.eventType()) {
            case PAYMENT_INTENT_CREATED -> {
                delta.paymentCount = 1;
                delta.unlabeledCount = 1;
                delta.intentAmountMinor = requiredLong(payload, "amountMinor");
            }
            case PAYMENT_ATTEMPT_COMPLETED -> {
                delta.attemptCount = 1;
                String outcome = requiredText(payload, "outcome");
                if (isDecline(outcome)) {
                    delta.declineCount = 1;
                } else if (!isKnownSuccess(outcome)) {
                    delta.unknownOutcomeCount = 1;
                }
            }
            case AUTHORIZATION_CREATED -> {
                delta.authorizationCount = 1;
                delta.authorizedAmountMinor = requiredLong(payload, "authorizedAmountMinor");
            }
            case CAPTURE_CREATED -> delta.capturedAmountMinor = requiredLong(payload, "amountMinor");
            case REFUND_CREATED -> delta.refundedAmountMinor = requiredLong(payload, "amountMinor");
            case CHARGEBACK_CREATED -> {
                delta.chargebackCount = 1;
                delta.disputedAmountMinor = requiredLong(payload, "amountMinor");
            }
            case MANUAL_REVIEW_OPENED -> delta.reviewCount = 1;
            case RISK_INCIDENT_OPENED -> delta.enumerationSignalCount = 1;
            default -> {
                // The event still contributes to source coverage and freshness.
            }
        }
        return delta;
    }

    private void applyAggregateIntervals(CanonicalDomainEventRecord event, UUID definitionId,
            UUID generationId, String currency, AggregateDelta delta, Instant processedAt) {
        Instant hour = event.occurredAt().truncatedTo(ChronoUnit.HOURS);
        Instant day = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        applyAggregate(event, definitionId, generationId, "HOUR", hour, "ALL", currency,
                delta, processedAt);
        applyAggregate(event, definitionId, generationId, "DAY", day, "ALL", currency,
                delta, processedAt);
        String specificPolicy = event.trafficOrigin() == TrafficOrigin.SYNTHETIC_SCENARIO
                ? "SYNTHETIC_ONLY"
                : event.trafficOrigin() == TrafficOrigin.MERCHANT_API ? "LIVE_ONLY" : null;
        if (specificPolicy != null) {
            applyAggregate(event, definitionId, generationId, "HOUR", hour,
                    specificPolicy, currency, delta, processedAt);
            applyAggregate(event, definitionId, generationId, "DAY", day,
                    specificPolicy, currency, delta, processedAt);
        }
    }

    private void applyLabelCohortAdjustment(CanonicalDomainEventRecord event,
            UUID definitionId, UUID generationId, MerchantCohortState state,
            Instant processedAt) {
        if (state.intentOccurredAt == null || state.currency == null) {
            throw new ProjectionInvariantException("Fraud label cohort lineage is incomplete");
        }
        AggregateDelta adjustment = new AggregateDelta();
        adjustment.unlabeledCount = state.priorLabelValue == null ? -1 : 0;
        adjustment.confirmedFraudCount = ("FRAUD".equals(state.labelValue) ? 1 : 0)
                - ("FRAUD".equals(state.priorLabelValue) ? 1 : 0);
        Instant hour = state.intentOccurredAt.truncatedTo(ChronoUnit.HOURS);
        Instant day = state.intentOccurredAt.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        applyExistingAggregate(event, generationId, "HOUR", hour, "ALL",
                state.currency, adjustment, processedAt);
        applyExistingAggregate(event, generationId, "DAY", day, "ALL",
                state.currency, adjustment, processedAt);
        String policy = "SYNTHETIC_SCENARIO".equals(state.trafficOrigin)
                ? "SYNTHETIC_ONLY"
                : "MERCHANT_API".equals(state.trafficOrigin) ? "LIVE_ONLY" : null;
        if (policy != null) {
            applyExistingAggregate(event, generationId, "HOUR", hour, policy,
                    state.currency, adjustment, processedAt);
            applyExistingAggregate(event, generationId, "DAY", day, policy,
                    state.currency, adjustment, processedAt);
        }
    }

    private void applyExistingAggregate(CanonicalDomainEventRecord event, UUID generationId,
            String intervalType, Instant intervalStart, String originPolicy, String currency,
            AggregateDelta delta, Instant processedAt) {
        List<UUID> existing = findAggregateForUpdate(event.merchantId(), generationId,
                intervalType, intervalStart, originPolicy, currency);
        if (existing.size() != 1) {
            throw new ProjectionInvariantException(
                    "Fraud label cohort aggregate is unavailable or ambiguous");
        }
        updateAggregate(existing.getFirst(), event, delta, processedAt);
    }

    private void applyAggregate(CanonicalDomainEventRecord event, UUID definitionId,
            UUID generationId, String intervalType, Instant intervalStart,
            String originPolicy, String currency, AggregateDelta delta, Instant processedAt) {
        List<UUID> existing = findAggregateForUpdate(event.merchantId(), generationId,
                intervalType, intervalStart, originPolicy, currency);
        if (existing.isEmpty()) {
            if (delta.paymentCount < 0 || delta.confirmedFraudCount < 0
                    || delta.unlabeledCount < 0 || delta.sourceEventCount <= 0) {
                throw new ProjectionInvariantException(
                        "Aggregate adjustment requires an existing cohort");
            }
            jdbcTemplate.update("""
                    INSERT INTO merchant_risk_aggregates (
                        id, merchant_id, projection_definition_id,
                        projection_generation_id, interval_type,
                        interval_start, traffic_origin_policy, payment_count, attempt_count,
                        authorization_count, decline_count, review_count,
                        confirmed_fraud_count, chargeback_count, enumeration_signal_count,
                        unlabeled_count, unknown_outcome_count, intent_amount_minor,
                        authorized_amount_minor, captured_amount_minor, refunded_amount_minor,
                        disputed_amount_minor, currency, source_event_count,
                        latest_domain_event_id, latest_source_occurred_at,
                        latest_source_recorded_at, last_processed_at, projection_version,
                        evaluator_version, coverage_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), event.merchantId(), definitionId, generationId,
                    intervalType,
                    timestamp(intervalStart), originPolicy, delta.paymentCount, delta.attemptCount,
                    delta.authorizationCount, delta.declineCount, delta.reviewCount,
                    delta.confirmedFraudCount, delta.chargebackCount,
                    delta.enumerationSignalCount, delta.unlabeledCount,
                    delta.unknownOutcomeCount, delta.intentAmountMinor,
                    delta.authorizedAmountMinor, delta.capturedAmountMinor,
                    delta.refundedAmountMinor, delta.disputedAmountMinor, currency,
                    delta.sourceEventCount, event.eventId(), timestamp(event.occurredAt()),
                    timestamp(event.recordedAt()), timestamp(processedAt),
                    ProjectionNames.HANDLER_VERSION, ProjectionNames.HANDLER_VERSION, COMPLETE,
                    timestamp(processedAt), timestamp(processedAt));
            return;
        }
        if (existing.size() != 1) {
            throw new ProjectionInvariantException("Merchant aggregate identity is ambiguous");
        }
        updateAggregate(existing.getFirst(), event, delta, processedAt);
    }

    private List<UUID> findAggregateForUpdate(UUID merchantId, UUID generationId,
            String intervalType, Instant intervalStart, String originPolicy, String currency) {
        return jdbcTemplate.query("""
                SELECT id FROM merchant_risk_aggregates
                WHERE merchant_id = ? AND projection_generation_id = ?
                  AND interval_type = ? AND interval_start = ?
                  AND traffic_origin_policy = ? AND currency = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                merchantId, generationId, intervalType, timestamp(intervalStart),
                originPolicy, currency);
    }

    private void updateAggregate(UUID aggregateId, CanonicalDomainEventRecord event,
            AggregateDelta delta, Instant processedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE merchant_risk_aggregates
                SET payment_count = payment_count + ?, attempt_count = attempt_count + ?,
                    authorization_count = authorization_count + ?,
                    decline_count = decline_count + ?, review_count = review_count + ?,
                    confirmed_fraud_count = confirmed_fraud_count + ?,
                    chargeback_count = chargeback_count + ?,
                    enumeration_signal_count = enumeration_signal_count + ?,
                    unlabeled_count = unlabeled_count + ?,
                    unknown_outcome_count = unknown_outcome_count + ?,
                    intent_amount_minor = intent_amount_minor + ?,
                    authorized_amount_minor = authorized_amount_minor + ?,
                    captured_amount_minor = captured_amount_minor + ?,
                    refunded_amount_minor = refunded_amount_minor + ?,
                    disputed_amount_minor = disputed_amount_minor + ?,
                    source_event_count = source_event_count + ?,
                    latest_domain_event_id = CASE
                        WHEN latest_source_recorded_at <= ? THEN ? ELSE latest_domain_event_id END,
                    latest_source_occurred_at = CASE
                        WHEN latest_source_recorded_at <= ? THEN ? ELSE latest_source_occurred_at END,
                    latest_source_recorded_at = CASE
                        WHEN latest_source_recorded_at <= ? THEN ? ELSE latest_source_recorded_at END,
                    last_processed_at = CASE
                        WHEN last_processed_at <= ? THEN ? ELSE last_processed_at END,
                    updated_at = CASE WHEN updated_at <= ? THEN ? ELSE updated_at END
                WHERE id = ?
                """, delta.paymentCount, delta.attemptCount, delta.authorizationCount,
                delta.declineCount, delta.reviewCount, delta.confirmedFraudCount,
                delta.chargebackCount, delta.enumerationSignalCount, delta.unlabeledCount,
                delta.unknownOutcomeCount, delta.intentAmountMinor,
                delta.authorizedAmountMinor, delta.capturedAmountMinor,
                delta.refundedAmountMinor, delta.disputedAmountMinor,
                delta.sourceEventCount, timestamp(event.recordedAt()), event.eventId(),
                timestamp(event.recordedAt()), timestamp(event.occurredAt()),
                timestamp(event.recordedAt()), timestamp(event.recordedAt()),
                timestamp(processedAt), timestamp(processedAt), timestamp(processedAt),
                timestamp(processedAt), aggregateId);
        if (updated != 1) {
            throw new ProjectionInvariantException("Merchant aggregate update was not singular");
        }
    }

    private long requiredLong(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProjectionInvariantException("Projection payload integer is invalid");
        }
        return value.longValue();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String requiredText(ObjectNode payload, String field) {
        String value = optionalText(payload, field);
        if (value == null) {
            throw new ProjectionInvariantException("Projection payload string is missing");
        }
        return value;
    }

    private Instant parseInstant(ObjectNode payload, String field) {
        try {
            return Instant.parse(requiredText(payload, field));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new ProjectionInvariantException("Projection payload timestamp is invalid");
        }
    }

    private String optionalText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.textValue();
    }

    private int requiredPositiveInteger(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() <= 0) {
            throw new ProjectionInvariantException("Projection payload positive integer is invalid");
        }
        return value.intValue();
    }

    private Integer optionalRiskScoreBasisPoints(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() > 10_000) {
            throw new ProjectionInvariantException(
                    "Projection risk score basis points are invalid");
        }
        return value.intValue();
    }

    private JsonNode requiredArray(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isArray()) {
            throw new ProjectionInvariantException("Projection payload array is missing");
        }
        return value;
    }

    private long addMoney(long current, long delta) {
        if (delta < 0) {
            throw new ProjectionInvariantException("Money summary cannot apply a negative fact");
        }
        try {
            return Math.addExact(current, delta);
        } catch (ArithmeticException exception) {
            throw new ProjectionInvariantException("Money summary overflowed");
        }
    }

    private boolean isDecline(String outcome) {
        return outcome.contains("DECLIN") || outcome.contains("FAIL")
                || outcome.contains("BLOCK");
    }

    private boolean isKnownSuccess(String outcome) {
        return outcome.contains("SUCCEED") || outcome.contains("APPROV")
                || outcome.contains("CAPTUR") || outcome.contains("REQUIRES_ACTION");
    }

    private static final class PaymentProjectionState {
        private UUID id;
        private UUID merchantId;
        private UUID definitionId;
        private UUID generationId;
        private UUID paymentIntentId;
        private Long intentAmountMinor;
        private String currency;
        private String captureMethod;
        private String trafficOrigin;
        private Integer attemptNumber;
        private String attemptOutcome;
        private String processorCode;
        private String fraudAction;
        private String riskLevel;
        private Integer riskScore;
        private String reasonCodesJson;
        private String rulesetVersion;
        private String featureCatalogueVersion;
        private UUID fraudDecisionId;
        private UUID featureSnapshotId;
        private String reviewStatus;
        private String reviewOutcome;
        private long capturedAmountMinor;
        private long refundedAmountMinor;
        private long disputedAmountMinor;
        private String priorLabelValue;
        private String labelValue;
        private String fraudType;
        private String labelSource;
        private Instant labelAvailableAt;
        private String lifecycleStatus;
        private String coverageStatus;
        private Instant intentOccurredAt;
        private Instant createdAt;

        private static PaymentProjectionState initial(CanonicalDomainEventRecord event,
                UUID generationId, UUID definitionId, Instant processedAt) {
            PaymentProjectionState state = new PaymentProjectionState();
            state.id = UUID.randomUUID();
            state.merchantId = event.merchantId();
            state.definitionId = definitionId;
            state.generationId = generationId;
            state.paymentIntentId = event.streamId();
            state.capturedAmountMinor = 0;
            state.refundedAmountMinor = 0;
            state.disputedAmountMinor = 0;
            state.coverageStatus = COMPLETE;
            state.intentOccurredAt = event.occurredAt();
            state.createdAt = processedAt;
            return state;
        }
    }

    private static final class MerchantCohortState {
        private UUID id;
        private UUID merchantId;
        private UUID definitionId;
        private UUID generationId;
        private UUID paymentIntentId;
        private String currency;
        private String trafficOrigin;
        private Instant intentOccurredAt;
        private String priorLabelValue;
        private String labelValue;
        private Instant labelAvailableAt;
        private Instant createdAt;

        private static MerchantCohortState initial(CanonicalDomainEventRecord event,
                ActiveProjectionGeneration generation, Instant processedAt) {
            MerchantCohortState state = new MerchantCohortState();
            state.id = UUID.randomUUID();
            state.merchantId = event.merchantId();
            state.definitionId = generation.definitionId();
            state.generationId = generation.generationId();
            state.paymentIntentId = event.streamId();
            state.intentOccurredAt = event.occurredAt();
            state.createdAt = processedAt;
            return state;
        }
    }

    private static final class AggregateDelta {
        private long paymentCount;
        private long attemptCount;
        private long authorizationCount;
        private long declineCount;
        private long reviewCount;
        private long confirmedFraudCount;
        private long chargebackCount;
        private long enumerationSignalCount;
        private long unlabeledCount;
        private long unknownOutcomeCount;
        private long intentAmountMinor;
        private long authorizedAmountMinor;
        private long capturedAmountMinor;
        private long refundedAmountMinor;
        private long disputedAmountMinor;
        private long sourceEventCount;
    }
}
