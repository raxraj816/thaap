package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FeatureFreshnessStatus;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmAuditEventMapperTests {

    @Test
    void eventsForConfirmBuildsOrderedAuditEvidenceForApprovedAuthorization() {
        UUID merchantId = UUID.randomUUID();
        PaymentIntent paymentIntent = new PaymentIntent(merchantId, UUID.randomUUID(), 1200L, "usd",
                CaptureMethod.AUTOMATIC, "audit mapper test", Map.of());
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        PaymentAttempt paymentAttempt = new PaymentAttempt(merchantId, paymentIntent.getId(), UUID.randomUUID(),
                "confirm-attempt-a", 1);
        ConfirmDecision decision = new ConfirmDecision(FraudDecisionAction.ALLOW, new BigDecimal("0.1200"),
                FraudRiskLevel.LOW, "rules_stub_v1", null, List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED));
        Instant decisionStartedAt = Instant.parse("2026-06-21T00:00:00Z");
        FraudDecision fraudDecision = new FraudDecision(merchantId, paymentAttempt.getId(), decision, 7L,
                decisionStartedAt, decisionStartedAt.plusMillis(7), FeatureFreshnessStatus.FRESH, 0L);
        paymentAttempt.attachFraudDecision(fraudDecision.getId());
        paymentAttempt.markReadyForProcessor();
        Authorization authorization = authorization(merchantId, paymentAttempt, AuthorizationStatus.APPROVED,
                NetworkStatus.APPROVED, 1200L, "APPROVED");
        paymentAttempt.markAuthorized(authorization.getProcessorReferenceId());
        paymentIntent.markProcessingAfterConfirm();
        paymentIntent.markRequiresCaptureAfterAuthorization();

        List<ConfirmAuditEventMapper.ConfirmAuditEvent> auditEvents = ConfirmAuditEventMapper.eventsForConfirm(
                startingStatus, paymentIntent, paymentAttempt, fraudDecision, authorization);

        assertThat(auditEvents).extracting(ConfirmAuditEventMapper.ConfirmAuditEvent::action)
                .containsExactly("payment_intent.confirmed", "fraud_decision.completed", "authorization.approved");

        ConfirmAuditEventMapper.ConfirmAuditEvent paymentIntentEvent = auditEvents.getFirst();
        assertThat(paymentIntentEvent.aggregateType()).isEqualTo("payment_intent");
        assertThat(paymentIntentEvent.aggregateId()).isEqualTo(paymentIntent.getId());
        assertThat(paymentIntentEvent.reason()).isEqualTo("PaymentIntent confirm completed");
        assertThat(paymentIntentEvent.before()).containsEntry("status", "REQUIRES_CONFIRMATION");
        assertThat(paymentIntentEvent.after())
                .containsEntry("paymentIntentId", paymentIntent.getId().toString())
                .containsEntry("paymentAttemptId", paymentAttempt.getId().toString())
                .containsEntry("paymentAttemptStatus", "AUTHORIZED")
                .containsEntry("status", "REQUIRES_CAPTURE")
                .containsEntry("amount", 1200L)
                .containsEntry("currency", "USD")
                .containsEntry("captureMethod", "AUTOMATIC");

        ConfirmAuditEventMapper.ConfirmAuditEvent fraudDecisionEvent = auditEvents.get(1);
        assertThat(fraudDecisionEvent.aggregateType()).isEqualTo("fraud_decision");
        assertThat(fraudDecisionEvent.aggregateId()).isEqualTo(fraudDecision.getId());
        assertThat(fraudDecisionEvent.before()).isNull();
        assertThat(fraudDecisionEvent.after())
                .containsEntry("fraudDecisionId", fraudDecision.getId().toString())
                .containsEntry("paymentAttemptId", paymentAttempt.getId().toString())
                .containsEntry("action", "ALLOW")
                .containsEntry("riskScore", new BigDecimal("0.1200"))
                .containsEntry("riskLevel", "LOW")
                .containsEntry("rulesetVersion", "rules_stub_v1")
                .containsEntry("featureFreshnessStatus", "FRESH")
                .containsEntry("maxFeatureAgeMs", 0L)
                .containsEntry("latencyMs", 7L);
        assertThat(fraudDecisionEvent.after().get("reasonCodes")).isEqualTo(List.of("RULES_NOT_ENABLED"));

        ConfirmAuditEventMapper.ConfirmAuditEvent authorizationEvent = auditEvents.get(2);
        assertThat(authorizationEvent.aggregateType()).isEqualTo("authorization");
        assertThat(authorizationEvent.aggregateId()).isEqualTo(authorization.getId());
        assertThat(authorizationEvent.before()).isNull();
        assertThat(authorizationEvent.after())
                .containsEntry("authorizationId", authorization.getId().toString())
                .containsEntry("paymentAttemptId", paymentAttempt.getId().toString())
                .containsEntry("processor", "thaap_simulator")
                .containsEntry("processorReferenceId", "auth_approved")
                .containsEntry("processorResponseCode", "00")
                .containsEntry("networkStatus", "APPROVED")
                .containsEntry("status", "APPROVED")
                .containsEntry("requestedAmount", 1200L)
                .containsEntry("authorizedAmount", 1200L)
                .containsEntry("currency", "USD")
                .containsEntry("cvcCheck", "PASS")
                .containsEntry("avsLine1Check", "PASS")
                .containsEntry("avsPostalCodeCheck", "PASS")
                .containsEntry("threeDsResult", "NOT_REQUIRED")
                .containsEntry("simulatorOutcomeCode", "APPROVED");
    }

    @Test
    void eventsForConfirmMapsAuthorizationStatusToAuditAction() {
        UUID merchantId = UUID.randomUUID();
        PaymentIntent paymentIntent = new PaymentIntent(merchantId, UUID.randomUUID(), 1200L, "usd");
        PaymentAttempt paymentAttempt = new PaymentAttempt(merchantId, paymentIntent.getId(), UUID.randomUUID(),
                "confirm-attempt-a", 1);
        ConfirmDecision decision = new ConfirmDecision(FraudDecisionAction.ALLOW, BigDecimal.ZERO,
                FraudRiskLevel.LOW, "rules_stub_v1", null, List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED));
        Instant decisionStartedAt = Instant.parse("2026-06-21T00:00:00Z");
        FraudDecision fraudDecision = new FraudDecision(merchantId, paymentAttempt.getId(), decision, 0L,
                decisionStartedAt, decisionStartedAt, FeatureFreshnessStatus.FRESH, 0L);

        assertAuthorizationAction(paymentIntent, paymentAttempt, fraudDecision,
                authorization(merchantId, paymentAttempt, AuthorizationStatus.APPROVED, NetworkStatus.APPROVED,
                        1200L, "APPROVED"),
                "authorization.approved");
        assertAuthorizationAction(paymentIntent, paymentAttempt, fraudDecision,
                authorization(merchantId, paymentAttempt, AuthorizationStatus.DECLINED, NetworkStatus.DECLINED,
                        0L, "DECLINED"),
                "authorization.declined");
        assertAuthorizationAction(paymentIntent, paymentAttempt, fraudDecision,
                authorization(merchantId, paymentAttempt, AuthorizationStatus.REQUIRES_ACTION,
                        NetworkStatus.REQUIRES_ACTION, 0L, "REQUIRES_ACTION"),
                "authorization.requires_action");
    }

    private void assertAuthorizationAction(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            FraudDecision fraudDecision, Authorization authorization, String expectedAction) {
        List<ConfirmAuditEventMapper.ConfirmAuditEvent> auditEvents = ConfirmAuditEventMapper.eventsForConfirm(
                PaymentIntentStatus.REQUIRES_CONFIRMATION, paymentIntent, paymentAttempt, fraudDecision, authorization);

        assertThat(auditEvents.get(2).action()).isEqualTo(expectedAction);
    }

    private Authorization authorization(UUID merchantId, PaymentAttempt paymentAttempt, AuthorizationStatus status,
            NetworkStatus networkStatus, long authorizedAmount, String outcomeCode) {
        String processorReferenceId = "auth_" + status.name().toLowerCase();
        return new Authorization(merchantId, paymentAttempt.getId(), "thaap_simulator", processorReferenceId, "00",
                networkStatus, 1200L, authorizedAmount, "USD", status, VerificationResult.PASS,
                VerificationResult.PASS, VerificationResult.PASS, ThreeDsResult.NOT_REQUIRED, outcomeCode);
    }
}
