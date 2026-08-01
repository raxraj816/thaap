package com.thaap.payments.event;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class DomainEventCatalogue {

    public static final int INITIAL_EVENT_VERSION = 1;

    private final Map<DomainEventType, DomainEventSchema> schemas;

    public DomainEventCatalogue() {
        EnumMap<DomainEventType, DomainEventSchema> definitions = new EnumMap<>(DomainEventType.class);
        definitions.put(DomainEventType.PAYMENT_INTENT_CREATED, schema(DomainEventType.PAYMENT_INTENT_CREATED,
                fields("amountMinor", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER,
                        "currency", DomainEventFieldType.STRING,
                        "captureMethod", DomainEventFieldType.STRING),
                fields("customerReference", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.PAYMENT_INTENT_CANCELED, schema(DomainEventType.PAYMENT_INTENT_CANCELED,
                fields("cancellationReason", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.PAYMENT_ATTEMPT_COMPLETED, schema(DomainEventType.PAYMENT_ATTEMPT_COMPLETED,
                fields("attemptNumber", DomainEventFieldType.POSITIVE_INT32,
                        "outcome", DomainEventFieldType.STRING),
                fields("processorCode", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.FRAUD_DECISION_CREATED, schema(DomainEventType.FRAUD_DECISION_CREATED,
                fields("action", DomainEventFieldType.STRING,
                        "riskLevel", DomainEventFieldType.STRING,
                        "reasonCodes", DomainEventFieldType.STRING_ARRAY,
                        "rulesetVersion", DomainEventFieldType.STRING),
                fields("riskScore", DomainEventFieldType.RISK_SCORE_BASIS_POINTS,
                        "featureCatalogueVersion", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.AUTHENTICATION_CHALLENGE_CREATED,
                schema(DomainEventType.AUTHENTICATION_CHALLENGE_CREATED,
                        fields("challengeType", DomainEventFieldType.STRING,
                                "expiresAt", DomainEventFieldType.INSTANT_MICROSECONDS), Map.of()));
        definitions.put(DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED,
                schema(DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED,
                        fields("result", DomainEventFieldType.STRING),
                        fields("challengeStatus", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.AUTHORIZATION_CREATED, schema(DomainEventType.AUTHORIZATION_CREATED,
                fields("requestedAmountMinor", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER,
                        "authorizedAmountMinor", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER,
                        "currency", DomainEventFieldType.STRING,
                        "outcome", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.AUTHORIZATION_VOIDED, schema(DomainEventType.AUTHORIZATION_VOIDED,
                fields("reasonCode", DomainEventFieldType.STRING,
                        "voidedAmountMinor", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER,
                        "currency", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.CAPTURE_CREATED, moneySchema(DomainEventType.CAPTURE_CREATED));
        definitions.put(DomainEventType.REFUND_CREATED, schema(DomainEventType.REFUND_CREATED,
                moneyFields(), fields("reasonCode", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.CHARGEBACK_CREATED, schema(DomainEventType.CHARGEBACK_CREATED,
                moneyFields(), fields("reasonCode", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.MANUAL_REVIEW_OPENED, schema(DomainEventType.MANUAL_REVIEW_OPENED,
                fields("priority", DomainEventFieldType.STRING,
                        "reasonCodes", DomainEventFieldType.STRING_ARRAY), Map.of()));
        definitions.put(DomainEventType.MANUAL_REVIEW_ASSIGNED, schema(DomainEventType.MANUAL_REVIEW_ASSIGNED,
                fields("assignmentEpoch", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER),
                fields("assigneeReference", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.MANUAL_REVIEW_RESOLVED, schema(DomainEventType.MANUAL_REVIEW_RESOLVED,
                fields("resolution", DomainEventFieldType.STRING),
                fields("fraudType", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.FRAUD_LABEL_RECORDED, schema(DomainEventType.FRAUD_LABEL_RECORDED,
                fields("label", DomainEventFieldType.STRING,
                        "source", DomainEventFieldType.STRING,
                        "availableAt", DomainEventFieldType.INSTANT_MICROSECONDS),
                fields("fraudType", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.FRAUD_LABEL_SUPERSEDED, schema(DomainEventType.FRAUD_LABEL_SUPERSEDED,
                fields("label", DomainEventFieldType.STRING,
                        "source", DomainEventFieldType.STRING,
                        "availableAt", DomainEventFieldType.INSTANT_MICROSECONDS,
                        "supersedesLabelReference", DomainEventFieldType.UUID),
                fields("fraudType", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.POST_AUTHORIZATION_SIGNAL_RECORDED,
                schema(DomainEventType.POST_AUTHORIZATION_SIGNAL_RECORDED,
                        fields("signalType", DomainEventFieldType.STRING,
                                "recommendation", DomainEventFieldType.STRING),
                        fields("reasonCodes", DomainEventFieldType.STRING_ARRAY)));
        definitions.put(DomainEventType.RISK_INCIDENT_OPENED,
                schema(DomainEventType.RISK_INCIDENT_OPENED,
                        fields("severity", DomainEventFieldType.STRING,
                                "evidenceCount", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER), Map.of()));
        definitions.put(DomainEventType.RISK_INCIDENT_RESOLVED,
                schema(DomainEventType.RISK_INCIDENT_RESOLVED,
                        fields("resolution", DomainEventFieldType.STRING), Map.of()));
        addVersionSchemas(definitions);
        addDisputeSchemas(definitions);
        addWebhookSchemas(definitions);
        addScenarioSchemas(definitions);
        if (definitions.size() != DomainEventType.values().length) {
            throw new IllegalStateException("Every domain event type must have exactly one V1 schema");
        }
        this.schemas = Collections.unmodifiableMap(definitions);
    }

    public DomainEventSchema require(DomainEventType eventType, int eventVersion) {
        DomainEventSchema schema = schemas.get(eventType);
        if (schema == null || schema.eventVersion() != eventVersion) {
            throw new IllegalArgumentException("Unsupported domain event type/version");
        }
        return schema;
    }

    public Map<DomainEventType, DomainEventSchema> schemas() {
        return schemas;
    }

    private static void addVersionSchemas(EnumMap<DomainEventType, DomainEventSchema> definitions) {
        DomainEventType[] types = {
                DomainEventType.MERCHANT_RISK_PROFILE_ACTIVATED,
                DomainEventType.MERCHANT_RISK_PROFILE_ROLLED_BACK,
                DomainEventType.RISK_POLICY_ACTIVATED,
                DomainEventType.RISK_POLICY_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_ACTIVATED,
                DomainEventType.AUTHORED_RULE_ROLLED_BACK,
                DomainEventType.AUTHORED_RULE_DISABLED,
                DomainEventType.SAFETY_EXCEPTION_ACTIVATED,
                DomainEventType.SAFETY_EXCEPTION_EXPIRED,
                DomainEventType.SAFETY_EXCEPTION_DISABLED
        };
        for (DomainEventType type : types) {
            definitions.put(type, schema(type,
                    fields("definitionVersion", DomainEventFieldType.POSITIVE_SAFE_INTEGER,
                            "lifecycleStatus", DomainEventFieldType.STRING),
                    fields("reasonCode", DomainEventFieldType.STRING)));
        }
    }

    private static void addDisputeSchemas(EnumMap<DomainEventType, DomainEventSchema> definitions) {
        definitions.put(DomainEventType.DISPUTE_WORKFLOW_OPENED,
                schema(DomainEventType.DISPUTE_WORKFLOW_OPENED,
                        fields("deadlineAt", DomainEventFieldType.INSTANT_MICROSECONDS,
                                "reasonCode", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.DISPUTE_EVIDENCE_SUBMITTED,
                schema(DomainEventType.DISPUTE_EVIDENCE_SUBMITTED,
                        fields("evidenceVersion", DomainEventFieldType.POSITIVE_SAFE_INTEGER,
                                "evidenceDigest", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.DISPUTE_OUTCOME_RECORDED,
                schema(DomainEventType.DISPUTE_OUTCOME_RECORDED,
                        fields("outcome", DomainEventFieldType.STRING), moneyFields()));
        definitions.put(DomainEventType.DISPUTE_WORKFLOW_EXPIRED,
                schema(DomainEventType.DISPUTE_WORKFLOW_EXPIRED,
                        fields("expiredAt", DomainEventFieldType.INSTANT_MICROSECONDS), Map.of()));
    }

    private static void addWebhookSchemas(EnumMap<DomainEventType, DomainEventSchema> definitions) {
        definitions.put(DomainEventType.WEBHOOK_ENDPOINT_VERIFIED,
                schema(DomainEventType.WEBHOOK_ENDPOINT_VERIFIED,
                        fields("verificationGeneration", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.WEBHOOK_ENDPOINT_DISABLED,
                schema(DomainEventType.WEBHOOK_ENDPOINT_DISABLED,
                        fields("reasonCode", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.WEBHOOK_ENDPOINT_SECRET_ROTATED,
                schema(DomainEventType.WEBHOOK_ENDPOINT_SECRET_ROTATED,
                        fields("secretGeneration", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.WEBHOOK_DELIVERY_EXHAUSTED,
                schema(DomainEventType.WEBHOOK_DELIVERY_EXHAUSTED,
                        fields("deliveryGeneration", DomainEventFieldType.POSITIVE_SAFE_INTEGER,
                                "attemptCount", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER),
                        fields("outcomeCode", DomainEventFieldType.STRING)));
        definitions.put(DomainEventType.WEBHOOK_REDELIVERY_REQUESTED,
                schema(DomainEventType.WEBHOOK_REDELIVERY_REQUESTED,
                        fields("deliveryGeneration", DomainEventFieldType.POSITIVE_SAFE_INTEGER,
                                "requestReason", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.WEBHOOK_REDELIVERY_SUCCEEDED,
                schema(DomainEventType.WEBHOOK_REDELIVERY_SUCCEEDED,
                        fields("deliveryGeneration", DomainEventFieldType.POSITIVE_SAFE_INTEGER), Map.of()));
    }

    private static void addScenarioSchemas(EnumMap<DomainEventType, DomainEventSchema> definitions) {
        definitions.put(DomainEventType.DEVELOPER_SCENARIO_CREATED,
                schema(DomainEventType.DEVELOPER_SCENARIO_CREATED,
                        fields("scenarioType", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.DEVELOPER_SCENARIO_COMPLETED,
                schema(DomainEventType.DEVELOPER_SCENARIO_COMPLETED,
                        fields("result", DomainEventFieldType.STRING), Map.of()));
        definitions.put(DomainEventType.DEVELOPER_SCENARIO_FAILED,
                schema(DomainEventType.DEVELOPER_SCENARIO_FAILED,
                        fields("failureCode", DomainEventFieldType.STRING), Map.of()));
    }

    private static DomainEventSchema moneySchema(DomainEventType eventType) {
        return schema(eventType, moneyFields(), Map.of());
    }

    private static Map<String, DomainEventFieldType> moneyFields() {
        return fields("amountMinor", DomainEventFieldType.NON_NEGATIVE_SAFE_INTEGER,
                "currency", DomainEventFieldType.STRING);
    }

    private static DomainEventSchema schema(DomainEventType type,
            Map<String, DomainEventFieldType> required,
            Map<String, DomainEventFieldType> optional) {
        return new DomainEventSchema(type, INITIAL_EVENT_VERSION, required, optional);
    }

    private static Map<String, DomainEventFieldType> fields(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Field declarations must be name/type pairs");
        }
        Map<String, DomainEventFieldType> fields = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String name = (String) values[index];
            DomainEventFieldType type = (DomainEventFieldType) values[index + 1];
            fields.put(name, type);
        }
        return fields;
    }
}
