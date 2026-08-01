package com.thaap.payments.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.authentication.AuthenticationChallenge;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.AuthorizationVoid;
import com.thaap.payments.dispute.Chargeback;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainEventSourceLineageStore;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.event.EventTimePrecision;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds the closed, privacy-safe payment event schemas from transaction-owned domain facts.
 * The writer requires the caller's transaction, so this service never creates an independent
 * commit boundary and never contacts the broker.
 */
@Service
public class PaymentDomainEventService {

    private static final int EVENT_VERSION = 1;
    private static final BigDecimal RISK_SCORE_BASIS_POINTS = new BigDecimal("10000");

    private final DomainEventWriter eventWriter;
    private final ObjectMapper objectMapper;
    private final PaymentDomainEventMetrics metrics;
    private final DomainEventSourceLineageStore sourceLineageStore;

    public PaymentDomainEventService(DomainEventWriter eventWriter, ObjectMapper objectMapper,
            PaymentDomainEventMetrics metrics, DomainEventSourceLineageStore sourceLineageStore) {
        this.eventWriter = eventWriter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.sourceLineageStore = sourceLineageStore;
    }

    public DomainEventActor merchantActor(MerchantContext context) {
        return new DomainEventActor(DomainEventActorType.MERCHANT_API_KEY, context.apiKeyPrefix());
    }

    public DomainEventActor riskOperatorActor(RiskOperatorContext context) {
        return new DomainEventActor(DomainEventActorType.RISK_OPERATOR, context.operatorId().toString());
    }

    public DomainEventWriteResult paymentIntentCreated(PaymentIntent paymentIntent,
            DomainEventActor actor, String requestId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", paymentIntent.getAmount());
        payload.put("currency", paymentIntent.getCurrency());
        payload.put("captureMethod", paymentIntent.getCaptureMethod().name());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.PAYMENT_INTENT_CREATED, paymentIntent.getCreatedAt(), actor,
                requestId, paymentIntent.getTrafficOrigin(), null, payload);
    }

    public DomainEventWriteResult paymentIntentCanceled(PaymentIntent paymentIntent,
            String cancellationReason, DomainEventActor actor, String requestId,
            UUID causationEventId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cancellationReason", requiredCode(cancellationReason, "cancellationReason"));
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.PAYMENT_INTENT_CANCELED, paymentIntent.getUpdatedAt(), actor,
                requestId, paymentIntent.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult fraudDecisionCreated(PaymentIntent paymentIntent,
            FraudDecision decision, FeatureSnapshot featureSnapshot, DomainEventActor actor,
            String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, decision.getMerchantId());
        requireSameMerchant(paymentIntent, featureSnapshot.getMerchantId());
        if (!decision.getId().equals(featureSnapshot.getFraudDecisionId())) {
            throw new IllegalArgumentException("Feature snapshot does not belong to FraudDecision");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", decision.getAction().name());
        payload.put("riskLevel", decision.getRiskLevel().name());
        ArrayNode reasonCodes = payload.putArray("reasonCodes");
        for (FraudDecisionReasonCode reasonCode : decision.getReasonCodes()) {
            reasonCodes.add(reasonCode.name());
        }
        payload.put("rulesetVersion", decision.getRulesetVersion());
        payload.put("riskScore", riskScoreBasisPoints(decision.getRiskScore()));
        payload.put("featureCatalogueVersion", featureSnapshot.getFeatureVersion());
        DomainEventWriteResult event = append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.FRAUD_DECISION_CREATED, decision.getDecisionCompletedAt(), actor,
                requestId, decision.getTrafficOrigin(), causationEventId, payload);
        sourceLineageStore.appendFraudDecision(event, decision.getId(), featureSnapshot.getId());
        return event;
    }

    public DomainEventWriteResult paymentAttemptCompleted(PaymentIntent paymentIntent,
            PaymentAttempt attempt, Authorization authorization, DomainEventActor actor,
            String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, attempt.getMerchantId());
        if (!paymentIntent.getId().equals(attempt.getPaymentIntentId())) {
            throw new IllegalArgumentException("PaymentAttempt does not belong to PaymentIntent");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("attemptNumber", attempt.getAttemptNumber());
        payload.put("outcome", attempt.getStatus().name());
        if (authorization != null) {
            requireSameMerchant(paymentIntent, authorization.getMerchantId());
            if (!attempt.getId().equals(authorization.getPaymentAttemptId())) {
                throw new IllegalArgumentException("Authorization does not belong to PaymentAttempt");
            }
            payload.put("processorCode", authorization.getProcessorResponseCode());
        }
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.PAYMENT_ATTEMPT_COMPLETED, attempt.getUpdatedAt(), actor,
                requestId, attempt.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult authorizationCreated(PaymentIntent paymentIntent,
            PaymentAttempt attempt, Authorization authorization, Instant approvedAt,
            DomainEventActor actor, String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, attempt.getMerchantId());
        requireSameMerchant(paymentIntent, authorization.getMerchantId());
        if (!paymentIntent.getId().equals(attempt.getPaymentIntentId())
                || !attempt.getId().equals(authorization.getPaymentAttemptId())) {
            throw new IllegalArgumentException("Authorization lineage does not belong to PaymentIntent");
        }
        if (authorization.getStatus() != AuthorizationStatus.APPROVED) {
            throw new IllegalArgumentException("authorization.created requires an approved Authorization");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requestedAmountMinor", authorization.getRequestedAmount());
        payload.put("authorizedAmountMinor", authorization.getAuthorizedAmount());
        payload.put("currency", authorization.getCurrency());
        payload.put("outcome", authorization.getStatus().name());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.AUTHORIZATION_CREATED, approvedAt, actor, requestId,
                paymentIntent.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult authorizationVoided(PaymentIntent paymentIntent,
            Authorization authorization, AuthorizationVoid authorizationVoid,
            DomainEventActor actor, String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, authorization.getMerchantId());
        requireSameMerchant(paymentIntent, authorizationVoid.getMerchantId());
        if (!paymentIntent.getId().equals(authorizationVoid.getPaymentIntentId())
                || !authorization.getId().equals(authorizationVoid.getAuthorizationId())) {
            throw new IllegalArgumentException("AuthorizationVoid lineage does not belong to PaymentIntent");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reasonCode", authorizationVoid.getReason().name());
        payload.put("voidedAmountMinor", authorization.getAuthorizedAmount());
        payload.put("currency", authorization.getCurrency());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.AUTHORIZATION_VOIDED, authorizationVoid.getCreatedAt(), actor,
                requestId, paymentIntent.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult captureCreated(PaymentIntent paymentIntent, Authorization authorization,
            Instant capturedAt, DomainEventActor actor, String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, authorization.getMerchantId());
        ObjectNode payload = moneyPayload(authorization.getAuthorizedAmount(), authorization.getCurrency());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.CAPTURE_CREATED, capturedAt, actor, requestId,
                paymentIntent.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult refundCreated(PaymentIntent paymentIntent, Refund refund,
            DomainEventActor actor, String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, refund.getMerchantId());
        if (!paymentIntent.getId().equals(refund.getPaymentIntentId())) {
            throw new IllegalArgumentException("Refund does not belong to PaymentIntent");
        }
        ObjectNode payload = moneyPayload(refund.getAmount(), refund.getCurrency());
        payload.put("reasonCode", refund.getReasonCode().name());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.REFUND_CREATED, refund.getCreatedAt(), actor, requestId,
                paymentIntent.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult chargebackCreated(PaymentIntent paymentIntent, Chargeback chargeback,
            DomainEventActor actor, String requestId, UUID causationEventId) {
        requireSameMerchant(paymentIntent, chargeback.getMerchantId());
        if (!paymentIntent.getId().equals(chargeback.getPaymentIntentId())) {
            throw new IllegalArgumentException("Chargeback does not belong to PaymentIntent");
        }
        ObjectNode payload = moneyPayload(chargeback.getAllocatedAmount(), chargeback.getCurrency());
        payload.put("reasonCode", chargeback.getReasonCode().name());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.CHARGEBACK_CREATED, chargeback.getAvailableAt(), actor, requestId,
                chargeback.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult authenticationChallengeCreated(PaymentIntent paymentIntent,
            AuthenticationChallenge challenge, DomainEventActor actor, String requestId,
            UUID causationEventId) {
        requireChallengeLineage(paymentIntent, challenge);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("challengeType", challenge.getSource().name());
        payload.put("expiresAt", timestamp(challenge.getExpiresAt()));
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.AUTHENTICATION_CHALLENGE_CREATED, challenge.getCreatedAt(), actor,
                requestId, challenge.getTrafficOrigin(), causationEventId, payload);
    }

    public DomainEventWriteResult authenticationChallengeCompleted(PaymentIntent paymentIntent,
            AuthenticationChallenge challenge, DomainEventActor actor, String requestId,
            UUID causationEventId) {
        requireChallengeLineage(paymentIntent, challenge);
        if (challenge.getCompletedAt() == null || challenge.getResultCode() == null) {
            throw new IllegalArgumentException("authentication_challenge.completed requires terminal evidence");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("result", challenge.getResultCode().name());
        payload.put("challengeStatus", challenge.getStatus().name());
        return append(paymentIntent.getMerchantId(), paymentIntent.getId(),
                DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED, challenge.getCompletedAt(), actor,
                requestId, challenge.getTrafficOrigin(), causationEventId, payload);
    }

    private DomainEventWriteResult append(UUID merchantId, UUID paymentIntentId,
            DomainEventType eventType, Instant occurredAt, DomainEventActor actor,
            String requestId, TrafficOrigin trafficOrigin, UUID causationEventId,
            ObjectNode payload) {
        DomainEventCommand command = new DomainEventCommand(merchantId, DomainStreamType.PAYMENT,
                paymentIntentId, eventType, EVENT_VERSION, occurredAt,
                paymentIntentId.toString(), causationEventId, normalizeRequestId(requestId),
                null, actor, trafficOrigin, payload);
        DomainEventWriteResult result;
        try {
            result = eventWriter.append(command);
        } catch (RuntimeException exception) {
            metrics.recordWriteFailure(eventType, EVENT_VERSION);
            throw exception;
        }
        metrics.recordAfterCompletion(eventType, EVENT_VERSION);
        return result;
    }

    private ObjectNode moneyPayload(long amountMinor, String currency) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", amountMinor);
        payload.put("currency", currency);
        return payload;
    }

    private void requireChallengeLineage(PaymentIntent paymentIntent,
            AuthenticationChallenge challenge) {
        requireSameMerchant(paymentIntent, challenge.getMerchantId());
        if (!paymentIntent.getId().equals(challenge.getPaymentIntentId())) {
            throw new IllegalArgumentException("AuthenticationChallenge does not belong to PaymentIntent");
        }
    }

    private void requireSameMerchant(PaymentIntent paymentIntent, UUID merchantId) {
        if (!paymentIntent.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Payment event lineage crosses merchant boundary");
        }
    }

    private long riskScoreBasisPoints(BigDecimal riskScore) {
        try {
            return riskScore.multiply(RISK_SCORE_BASIS_POINTS).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("FraudDecision risk score is not representable in basis points",
                    exception);
        }
    }

    private String timestamp(Instant instant) {
        return EventTimePrecision.toPostgreSqlMicroseconds(instant).toString();
    }

    private String requiredCode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String normalizeRequestId(String requestId) {
        return requestId == null || requestId.isBlank() ? null : requestId;
    }
}
