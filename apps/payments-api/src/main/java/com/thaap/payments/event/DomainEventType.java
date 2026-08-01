package com.thaap.payments.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public enum DomainEventType {
    PAYMENT_INTENT_CREATED("payment_intent.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    PAYMENT_INTENT_CANCELED("payment_intent.canceled", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    PAYMENT_ATTEMPT_COMPLETED("payment_attempt.completed", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    FRAUD_DECISION_CREATED("fraud_decision.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    AUTHORIZATION_CREATED("authorization.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    AUTHORIZATION_VOIDED("authorization.voided", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    CAPTURE_CREATED("capture.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    REFUND_CREATED("refund.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    CHARGEBACK_CREATED("chargeback.created", DomainStreamType.PAYMENT, DomainEventProducer.PAYMENTS_CORE),
    AUTHENTICATION_CHALLENGE_CREATED("authentication_challenge.created", DomainStreamType.PAYMENT,
            DomainEventProducer.PAYMENTS_CORE),
    AUTHENTICATION_CHALLENGE_COMPLETED("authentication_challenge.completed", DomainStreamType.PAYMENT,
            DomainEventProducer.PAYMENTS_CORE),
    MANUAL_REVIEW_OPENED("manual_review.opened", DomainStreamType.PAYMENT, DomainEventProducer.RISK_PLATFORM),
    MANUAL_REVIEW_ASSIGNED("manual_review.assigned", DomainStreamType.PAYMENT, DomainEventProducer.RISK_PLATFORM),
    MANUAL_REVIEW_RESOLVED("manual_review.resolved", DomainStreamType.PAYMENT, DomainEventProducer.RISK_PLATFORM),
    FRAUD_LABEL_RECORDED("fraud_label.recorded", DomainStreamType.PAYMENT, DomainEventProducer.RISK_PLATFORM),
    FRAUD_LABEL_SUPERSEDED("fraud_label.superseded", DomainStreamType.PAYMENT, DomainEventProducer.RISK_PLATFORM),
    RISK_INCIDENT_OPENED("risk_incident.opened", DomainStreamType.RISK_INCIDENT,
            DomainEventProducer.RISK_PLATFORM),
    RISK_INCIDENT_RESOLVED("risk_incident.resolved", DomainStreamType.RISK_INCIDENT,
            DomainEventProducer.RISK_PLATFORM),
    MERCHANT_RISK_PROFILE_ACTIVATED("merchant_risk_profile.activated", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    MERCHANT_RISK_PROFILE_ROLLED_BACK("merchant_risk_profile.rolled_back", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    RISK_POLICY_ACTIVATED("risk_policy.activated", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    RISK_POLICY_ROLLED_BACK("risk_policy.rolled_back", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    AUTHORED_RULE_ACTIVATED("authored_rule.activated", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    AUTHORED_RULE_ROLLED_BACK("authored_rule.rolled_back", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    AUTHORED_RULE_DISABLED("authored_rule.disabled", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    SAFETY_EXCEPTION_ACTIVATED("safety_exception.activated", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    SAFETY_EXCEPTION_EXPIRED("safety_exception.expired", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    SAFETY_EXCEPTION_DISABLED("safety_exception.disabled", DomainStreamType.RISK_CONTROL,
            DomainEventProducer.RISK_PLATFORM),
    DISPUTE_WORKFLOW_OPENED("dispute_workflow.opened", DomainStreamType.DISPUTE,
            DomainEventProducer.RISK_PLATFORM),
    DISPUTE_EVIDENCE_SUBMITTED("dispute_evidence.submitted", DomainStreamType.DISPUTE,
            DomainEventProducer.RISK_PLATFORM),
    DISPUTE_OUTCOME_RECORDED("dispute_outcome.recorded", DomainStreamType.DISPUTE,
            DomainEventProducer.RISK_PLATFORM),
    DISPUTE_WORKFLOW_EXPIRED("dispute_workflow.expired", DomainStreamType.DISPUTE,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_ENDPOINT_VERIFIED("webhook_endpoint.verified", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_ENDPOINT_DISABLED("webhook_endpoint.disabled", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_ENDPOINT_SECRET_ROTATED("webhook_endpoint.secret_rotated", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_DELIVERY_EXHAUSTED("webhook_delivery.exhausted", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_REDELIVERY_REQUESTED("webhook_redelivery.requested", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    WEBHOOK_REDELIVERY_SUCCEEDED("webhook_redelivery.succeeded", DomainStreamType.WEBHOOK_ENDPOINT,
            DomainEventProducer.RISK_PLATFORM),
    POST_AUTHORIZATION_SIGNAL_RECORDED("post_authorization_signal.recorded", DomainStreamType.PAYMENT,
            DomainEventProducer.RISK_PLATFORM),
    DEVELOPER_SCENARIO_CREATED("developer_scenario.created", DomainStreamType.DEVELOPER_SCENARIO,
            DomainEventProducer.RISK_PLATFORM),
    DEVELOPER_SCENARIO_COMPLETED("developer_scenario.completed", DomainStreamType.DEVELOPER_SCENARIO,
            DomainEventProducer.RISK_PLATFORM),
    DEVELOPER_SCENARIO_FAILED("developer_scenario.failed", DomainStreamType.DEVELOPER_SCENARIO,
            DomainEventProducer.RISK_PLATFORM);

    private static final Map<String, DomainEventType> BY_WIRE_VALUE = buildWireValueIndex();

    private final String wireValue;
    private final DomainStreamType streamType;
    private final DomainEventProducer producer;

    DomainEventType(String wireValue, DomainStreamType streamType, DomainEventProducer producer) {
        this.wireValue = wireValue;
        this.streamType = streamType;
        this.producer = producer;
    }

    public String wireValue() {
        return wireValue;
    }

    public DomainStreamType streamType() {
        return streamType;
    }

    public DomainEventProducer producer() {
        return producer;
    }

    public static DomainEventType fromWireValue(String wireValue) {
        DomainEventType eventType = BY_WIRE_VALUE.get(wireValue);
        if (eventType == null) {
            throw new IllegalArgumentException("Unknown domain event wire value");
        }
        return eventType;
    }

    private static Map<String, DomainEventType> buildWireValueIndex() {
        Map<String, DomainEventType> index = new LinkedHashMap<>();
        for (DomainEventType eventType : values()) {
            DomainEventType previous = index.put(eventType.wireValue, eventType);
            if (previous != null) {
                throw new IllegalStateException("Duplicate domain event wire value");
            }
        }
        return Collections.unmodifiableMap(index);
    }
}
