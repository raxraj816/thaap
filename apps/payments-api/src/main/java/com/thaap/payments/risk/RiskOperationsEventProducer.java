package com.thaap.payments.risk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Typed producer boundary for the fraud and risk-operations facts in the V1
 * canonical domain-event catalogue. Payload construction is deliberately kept
 * here so mutation services cannot accidentally publish persistence entities,
 * analyst notes, credentials, or other unbounded data.
 */
@Component
public class RiskOperationsEventProducer {

    private static final String SYSTEM_ACTOR_ID = "thaap-risk-operations";

    private final DomainEventWriter eventWriter;

    public RiskOperationsEventProducer(DomainEventWriter eventWriter) {
        this.eventWriter = eventWriter;
    }

    public void manualReviewOpenedBySystem(UUID merchantId, UUID paymentIntentId,
            Instant occurredAt, String requestId, TrafficOrigin trafficOrigin,
            String priority, Collection<String> reasonCodes) {
        append(merchantId, DomainStreamType.PAYMENT, paymentIntentId,
                DomainEventType.MANUAL_REVIEW_OPENED, occurredAt, requestId,
                systemActor(), trafficOrigin, payload -> {
                    payload.put("priority", priority);
                    putStrings(payload, "reasonCodes", reasonCodes);
                });
    }

    public void manualReviewAssigned(RiskOperatorContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, long assignmentEpoch, UUID assigneeId,
            TrafficOrigin trafficOrigin) {
        append(context.merchantId(), DomainStreamType.PAYMENT, paymentIntentId,
                DomainEventType.MANUAL_REVIEW_ASSIGNED, occurredAt, requestId,
                riskActor(context), trafficOrigin, payload -> {
                    payload.put("assignmentEpoch", assignmentEpoch);
                    if (assigneeId != null) {
                        payload.put("assigneeReference", assigneeId.toString());
                    }
                });
    }

    public void manualReviewResolved(RiskOperatorContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String resolution, String fraudType,
            TrafficOrigin trafficOrigin) {
        append(context.merchantId(), DomainStreamType.PAYMENT, paymentIntentId,
                DomainEventType.MANUAL_REVIEW_RESOLVED, occurredAt, requestId,
                riskActor(context), trafficOrigin, payload -> {
                    payload.put("resolution", resolution);
                    putOptional(payload, "fraudType", fraudType);
                });
    }

    public void fraudLabelRecorded(RiskOperatorContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String label, String source,
            Instant availableAt, String fraudType, TrafficOrigin trafficOrigin) {
        fraudLabel(context.merchantId(), paymentIntentId, DomainEventType.FRAUD_LABEL_RECORDED,
                occurredAt, requestId, riskActor(context), trafficOrigin, label, source,
                availableAt, fraudType, null);
    }

    public void fraudLabelRecorded(MerchantContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String label, String source,
            Instant availableAt, String fraudType, TrafficOrigin trafficOrigin) {
        fraudLabel(context.merchantId(), paymentIntentId, DomainEventType.FRAUD_LABEL_RECORDED,
                occurredAt, requestId, merchantActor(context), trafficOrigin, label, source,
                availableAt, fraudType, null);
    }

    public void fraudLabelSuperseded(RiskOperatorContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String label, String source,
            Instant availableAt, String fraudType, UUID supersededLabelId,
            TrafficOrigin trafficOrigin) {
        fraudLabel(context.merchantId(), paymentIntentId, DomainEventType.FRAUD_LABEL_SUPERSEDED,
                occurredAt, requestId, riskActor(context), trafficOrigin, label, source,
                availableAt, fraudType, Objects.requireNonNull(supersededLabelId, "supersededLabelId"));
    }

    public void fraudLabelSuperseded(MerchantContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String label, String source,
            Instant availableAt, String fraudType, UUID supersededLabelId,
            TrafficOrigin trafficOrigin) {
        fraudLabel(context.merchantId(), paymentIntentId, DomainEventType.FRAUD_LABEL_SUPERSEDED,
                occurredAt, requestId, merchantActor(context), trafficOrigin, label, source,
                availableAt, fraudType, Objects.requireNonNull(supersededLabelId, "supersededLabelId"));
    }

    public void postAuthorizationSignalRecorded(MerchantContext context, UUID paymentIntentId,
            Instant occurredAt, String requestId, String signalType, String recommendation,
            Collection<String> reasonCodes, TrafficOrigin trafficOrigin) {
        append(context.merchantId(), DomainStreamType.PAYMENT, paymentIntentId,
                DomainEventType.POST_AUTHORIZATION_SIGNAL_RECORDED, occurredAt, requestId,
                merchantActor(context), trafficOrigin, payload -> {
                    payload.put("signalType", signalType);
                    payload.put("recommendation", recommendation);
                    if (reasonCodes != null && !reasonCodes.isEmpty()) {
                        putStrings(payload, "reasonCodes", reasonCodes);
                    }
                });
    }

    public void riskIncidentOpened(MerchantContext context, UUID incidentId,
            Instant occurredAt, String requestId, String severity, long evidenceCount) {
        append(context.merchantId(), DomainStreamType.RISK_INCIDENT, incidentId,
                DomainEventType.RISK_INCIDENT_OPENED, occurredAt, requestId,
                systemActor(), context.trafficOrigin(), payload -> {
                    payload.put("severity", severity);
                    payload.put("evidenceCount", evidenceCount);
                });
    }

    public void riskIncidentResolved(RiskOperatorContext context, UUID incidentId,
            Instant occurredAt, String requestId, String resolution) {
        append(context.merchantId(), DomainStreamType.RISK_INCIDENT, incidentId,
                DomainEventType.RISK_INCIDENT_RESOLVED, occurredAt, requestId,
                riskActor(context), null,
                payload -> payload.put("resolution", resolution));
    }

    public void riskControlChanged(RiskOperatorContext context, UUID controlId,
            DomainEventType eventType, Instant occurredAt, String requestId,
            long definitionVersion, String lifecycleStatus, String reasonCode) {
        requireRiskControlType(eventType);
        append(context.merchantId(), DomainStreamType.RISK_CONTROL, controlId,
                eventType, occurredAt, requestId, riskActor(context),
                null, payload -> {
                    payload.put("definitionVersion", definitionVersion);
                    payload.put("lifecycleStatus", lifecycleStatus);
                    putOptional(payload, "reasonCode", reasonCode);
                });
    }

    public void disputeWorkflowOpened(MerchantContext context, UUID workflowId,
            Instant occurredAt, String requestId, Instant deadlineAt, String reasonCode,
            TrafficOrigin trafficOrigin) {
        append(context.merchantId(), DomainStreamType.DISPUTE, workflowId,
                DomainEventType.DISPUTE_WORKFLOW_OPENED, occurredAt, requestId,
                merchantActor(context), trafficOrigin, payload -> {
                    payload.put("deadlineAt", deadlineAt.toString());
                    payload.put("reasonCode", reasonCode);
                });
    }

    public void disputeEvidenceSubmitted(RiskOperatorContext context, UUID workflowId,
            Instant occurredAt, String requestId, long evidenceVersion, String evidenceDigest) {
        append(context.merchantId(), DomainStreamType.DISPUTE, workflowId,
                DomainEventType.DISPUTE_EVIDENCE_SUBMITTED, occurredAt, requestId,
                riskActor(context), null, payload -> {
                    payload.put("evidenceVersion", evidenceVersion);
                    payload.put("evidenceDigest", evidenceDigest);
                });
    }

    public void disputeOutcomeRecorded(MerchantContext context, UUID workflowId,
            Instant occurredAt, String requestId, String outcome, Long amountMinor,
            String currency, TrafficOrigin trafficOrigin) {
        append(context.merchantId(), DomainStreamType.DISPUTE, workflowId,
                DomainEventType.DISPUTE_OUTCOME_RECORDED, occurredAt, requestId,
                merchantActor(context), trafficOrigin, payload -> {
                    payload.put("outcome", outcome);
                    if (amountMinor != null) {
                        payload.put("amountMinor", amountMinor);
                        payload.put("currency", Objects.requireNonNull(currency, "currency"));
                    }
                });
    }

    public void disputeWorkflowExpired(UUID merchantId, UUID workflowId,
            Instant occurredAt) {
        append(merchantId, DomainStreamType.DISPUTE, workflowId,
                DomainEventType.DISPUTE_WORKFLOW_EXPIRED, occurredAt, null,
                systemActor(), null, payload -> payload.put("expiredAt", occurredAt.toString()));
    }

    public void webhookEndpointVerified(MerchantContext context, UUID endpointId,
            Instant occurredAt, String requestId, UUID verificationGeneration) {
        append(context.merchantId(), DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_ENDPOINT_VERIFIED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(),
                payload -> payload.put("verificationGeneration", verificationGeneration.toString()));
    }

    public void webhookEndpointDisabled(MerchantContext context, UUID endpointId,
            Instant occurredAt, String requestId, String reasonCode) {
        append(context.merchantId(), DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_ENDPOINT_DISABLED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(),
                payload -> payload.put("reasonCode", reasonCode));
    }

    public void webhookEndpointSecretRotated(MerchantContext context, UUID endpointId,
            Instant occurredAt, String requestId, UUID secretGeneration) {
        append(context.merchantId(), DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_ENDPOINT_SECRET_ROTATED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(),
                payload -> payload.put("secretGeneration", secretGeneration.toString()));
    }

    public void webhookDeliveryExhausted(UUID merchantId, UUID endpointId,
            Instant occurredAt, int deliveryGeneration, int attemptCount,
            String outcomeCode) {
        append(merchantId, DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_DELIVERY_EXHAUSTED, occurredAt, null,
                systemActor(), null, payload -> {
                    payload.put("deliveryGeneration", deliveryGeneration);
                    payload.put("attemptCount", attemptCount);
                    putOptional(payload, "outcomeCode", outcomeCode);
                });
    }

    public void webhookRedeliveryRequested(MerchantContext context, UUID endpointId,
            Instant occurredAt, String requestId, int deliveryGeneration, String requestReason) {
        webhookRedeliveryRequested(context.merchantId(), endpointId, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(), deliveryGeneration, requestReason);
    }

    public void webhookRedeliveryRequested(RiskOperatorContext context, UUID endpointId,
            Instant occurredAt, String requestId, int deliveryGeneration, String requestReason) {
        webhookRedeliveryRequested(context.merchantId(), endpointId, occurredAt, requestId,
                riskActor(context), null, deliveryGeneration, requestReason);
    }

    public void webhookRedeliverySucceeded(UUID merchantId, UUID endpointId,
            Instant occurredAt, int deliveryGeneration) {
        append(merchantId, DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_REDELIVERY_SUCCEEDED, occurredAt, null,
                systemActor(), null,
                payload -> payload.put("deliveryGeneration", deliveryGeneration));
    }

    public void developerScenarioCreated(MerchantContext context, UUID scenarioId,
            Instant occurredAt, String requestId, String scenarioType) {
        append(context.merchantId(), DomainStreamType.DEVELOPER_SCENARIO, scenarioId,
                DomainEventType.DEVELOPER_SCENARIO_CREATED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(),
                payload -> payload.put("scenarioType", scenarioType));
    }

    public void developerScenarioCompleted(MerchantContext context, UUID scenarioId,
            Instant occurredAt, String requestId, String result) {
        append(context.merchantId(), DomainStreamType.DEVELOPER_SCENARIO, scenarioId,
                DomainEventType.DEVELOPER_SCENARIO_COMPLETED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(), payload -> payload.put("result", result));
    }

    public void developerScenarioFailed(MerchantContext context, UUID scenarioId,
            Instant occurredAt, String requestId, String failureCode) {
        append(context.merchantId(), DomainStreamType.DEVELOPER_SCENARIO, scenarioId,
                DomainEventType.DEVELOPER_SCENARIO_FAILED, occurredAt, requestId,
                merchantActor(context), context.trafficOrigin(), payload -> payload.put("failureCode", failureCode));
    }

    private void fraudLabel(UUID merchantId, UUID paymentIntentId, DomainEventType eventType,
            Instant occurredAt, String requestId, DomainEventActor actor, TrafficOrigin trafficOrigin,
            String label, String source, Instant availableAt, String fraudType, UUID supersededLabelId) {
        append(merchantId, DomainStreamType.PAYMENT, paymentIntentId, eventType,
                occurredAt, requestId, actor, trafficOrigin, payload -> {
                    payload.put("label", label);
                    payload.put("source", source);
                    payload.put("availableAt", availableAt.toString());
                    putOptional(payload, "fraudType", fraudType);
                    if (supersededLabelId != null) {
                        payload.put("supersedesLabelReference", supersededLabelId.toString());
                    }
                });
    }

    private void webhookRedeliveryRequested(UUID merchantId, UUID endpointId,
            Instant occurredAt, String requestId, DomainEventActor actor,
            TrafficOrigin trafficOrigin, int deliveryGeneration, String requestReason) {
        append(merchantId, DomainStreamType.WEBHOOK_ENDPOINT, endpointId,
                DomainEventType.WEBHOOK_REDELIVERY_REQUESTED, occurredAt, requestId,
                actor, trafficOrigin, payload -> {
                    payload.put("deliveryGeneration", deliveryGeneration);
                    payload.put("requestReason", requestReason);
                });
    }

    private void append(UUID merchantId, DomainStreamType streamType, UUID streamId,
            DomainEventType eventType, Instant occurredAt, String requestId,
            DomainEventActor actor, TrafficOrigin trafficOrigin, PayloadWriter payloadWriter) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payloadWriter.write(payload);
        eventWriter.append(new DomainEventCommand(merchantId, streamType, streamId,
                eventType, DomainEventCatalogue.INITIAL_EVENT_VERSION, occurredAt,
                streamId.toString(), null, normalizeRequestId(requestId), null,
                actor, trafficOrigin, payload));
    }

    private static void requireRiskControlType(DomainEventType eventType) {
        if (eventType == null || eventType.streamType() != DomainStreamType.RISK_CONTROL) {
            throw new IllegalArgumentException("Risk-control producer requires a RISK_CONTROL event type");
        }
    }

    private static DomainEventActor merchantActor(MerchantContext context) {
        return new DomainEventActor(DomainEventActorType.MERCHANT_API_KEY,
                context.apiKeyPrefix());
    }

    private static DomainEventActor riskActor(RiskOperatorContext context) {
        return new DomainEventActor(DomainEventActorType.RISK_OPERATOR,
                context.operatorId().toString());
    }

    private static DomainEventActor systemActor() {
        return new DomainEventActor(DomainEventActorType.SYSTEM, SYSTEM_ACTOR_ID);
    }

    private static void putOptional(ObjectNode payload, String field, String value) {
        if (value != null) {
            payload.put(field, value);
        }
    }

    private static void putStrings(ObjectNode payload, String field, Collection<String> values) {
        Objects.requireNonNull(values, field);
        ArrayNode array = payload.putArray(field);
        for (String value : values) {
            array.add(value);
        }
    }

    private static String normalizeRequestId(String requestId) {
        return requestId == null || requestId.isBlank() ? null : requestId;
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(ObjectNode payload);
    }
}
