package com.thaap.payments.payment;

import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ConfirmAuditEventMapper {

    private ConfirmAuditEventMapper() {
    }

    static List<ConfirmAuditEvent> eventsForConfirm(PaymentIntentStatus startingStatus, PaymentIntent paymentIntent,
            PaymentAttempt paymentAttempt, FraudDecision fraudDecision, Authorization authorization) {
        return List.of(
                paymentIntentConfirmed(startingStatus, paymentIntent, paymentAttempt),
                fraudDecisionCompleted(paymentAttempt, fraudDecision),
                authorizationCompleted(paymentAttempt, authorization));
    }

    private static ConfirmAuditEvent paymentIntentConfirmed(PaymentIntentStatus startingStatus,
            PaymentIntent paymentIntent, PaymentAttempt paymentAttempt) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", startingStatus.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("paymentIntentId", paymentIntent.getId().toString());
        after.put("paymentAttemptId", paymentAttempt.getId().toString());
        after.put("paymentAttemptStatus", paymentAttempt.getStatus().name());
        after.put("status", paymentIntent.getStatus().name());
        after.put("amount", paymentIntent.getAmount());
        after.put("currency", paymentIntent.getCurrency());
        after.put("captureMethod", paymentIntent.getCaptureMethod().name());
        return new ConfirmAuditEvent("payment_intent.confirmed", "payment_intent", paymentIntent.getId(),
                "PaymentIntent confirm completed", before, after);
    }

    private static ConfirmAuditEvent fraudDecisionCompleted(PaymentAttempt paymentAttempt,
            FraudDecision fraudDecision) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fraudDecisionId", fraudDecision.getId().toString());
        after.put("paymentAttemptId", paymentAttempt.getId().toString());
        after.put("action", fraudDecision.getAction().name());
        after.put("riskScore", fraudDecision.getRiskScore());
        after.put("riskLevel", fraudDecision.getRiskLevel().name());
        after.put("rulesetVersion", fraudDecision.getRulesetVersion());
        after.put("reasonCodes", fraudDecision.getReasonCodes().stream().map(Enum::name).toList());
        after.put("featureFreshnessStatus", fraudDecision.getFeatureFreshnessStatus().name());
        after.put("maxFeatureAgeMs", fraudDecision.getMaxFeatureAgeMs());
        after.put("latencyMs", fraudDecision.getLatencyMs());
        return new ConfirmAuditEvent("fraud_decision.completed", "fraud_decision", fraudDecision.getId(),
                "Fraud decision completed", null, after);
    }

    private static ConfirmAuditEvent authorizationCompleted(PaymentAttempt paymentAttempt,
            Authorization authorization) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("authorizationId", authorization.getId().toString());
        after.put("paymentAttemptId", paymentAttempt.getId().toString());
        after.put("processor", authorization.getProcessor());
        after.put("processorReferenceId", authorization.getProcessorReferenceId());
        after.put("processorResponseCode", authorization.getProcessorResponseCode());
        after.put("networkStatus", authorization.getNetworkStatus().name());
        after.put("status", authorization.getStatus().name());
        after.put("requestedAmount", authorization.getRequestedAmount());
        after.put("authorizedAmount", authorization.getAuthorizedAmount());
        after.put("currency", authorization.getCurrency());
        after.put("cvcCheck", authorization.getCvcCheck().name());
        after.put("avsLine1Check", authorization.getAvsLine1Check().name());
        after.put("avsPostalCodeCheck", authorization.getAvsPostalCodeCheck().name());
        after.put("threeDsResult", authorization.getThreeDsResult().name());
        after.put("simulatorOutcomeCode", authorization.getSimulatorOutcomeCode());
        return new ConfirmAuditEvent(authorizationAuditAction(authorization), "authorization", authorization.getId(),
                "Processor authorization completed", null, after);
    }

    private static String authorizationAuditAction(Authorization authorization) {
        return switch (authorization.getStatus()) {
            case APPROVED -> "authorization.approved";
            case DECLINED -> "authorization.declined";
            case REQUIRES_ACTION -> "authorization.requires_action";
        };
    }

    record ConfirmAuditEvent(String action, String aggregateType, UUID aggregateId, String reason,
            Map<String, Object> before, Map<String, Object> after) {
    }
}
