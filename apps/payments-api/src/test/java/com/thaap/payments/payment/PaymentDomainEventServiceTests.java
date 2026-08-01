package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.authentication.AuthenticationChallenge;
import com.thaap.payments.authentication.AuthenticationChallengeSource;
import com.thaap.payments.authentication.AuthenticationChallengeStatus;
import com.thaap.payments.authentication.AuthenticationResultCode;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.AuthorizationVoid;
import com.thaap.payments.authorization.AuthorizationVoidReason;
import com.thaap.payments.dispute.Chargeback;
import com.thaap.payments.dispute.ChargebackReasonCode;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainEventSourceLineageStore;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.event.EncodedDomainEvent;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptStatus;
import com.thaap.payments.tenant.TrafficOrigin;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PaymentDomainEventServiceTests {

    private static final Instant OCCURRED_AT = Instant.parse("2026-07-27T12:00:00.123456Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-07-27T12:01:00.123456Z");

    @Test
    void allPaymentSchemasAreAllowlistedCanonicalAndPreserveSafeLineage() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingWriter writer = new RecordingWriter(objectMapper);
        PaymentDomainEventMetrics metrics = mock(PaymentDomainEventMetrics.class);
        DomainEventSourceLineageStore sourceLineageStore = mock(
                DomainEventSourceLineageStore.class);
        PaymentDomainEventService service = new PaymentDomainEventService(
                writer, objectMapper, metrics, sourceLineageStore);
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID fraudDecisionId = UUID.randomUUID();
        DomainEventActor actor = new DomainEventActor(
                DomainEventActorType.MERCHANT_API_KEY, "thaap_test_safe_prefix");

        PaymentIntent paymentIntent = paymentIntent(merchantId, paymentIntentId);
        PaymentAttempt attempt = paymentAttempt(merchantId, paymentIntentId, attemptId);
        FraudDecision decision = fraudDecision(merchantId, attemptId, fraudDecisionId,
                new BigDecimal("0.0500"));
        FeatureSnapshot snapshot = featureSnapshot(merchantId, attemptId, fraudDecisionId);
        UUID featureSnapshotId = snapshot.getId();
        Authorization authorization = authorization(merchantId, attemptId, authorizationId);
        AuthorizationVoid authorizationVoid = authorizationVoid(
                merchantId, paymentIntentId, authorizationId);
        Refund refund = refund(merchantId, paymentIntentId);
        Chargeback chargeback = chargeback(merchantId, paymentIntentId);
        AuthenticationChallenge challenge = challenge(merchantId, paymentIntentId, attemptId,
                fraudDecisionId, authorizationId);

        UUID cause = UUID.randomUUID();
        service.paymentIntentCreated(paymentIntent, actor, "request-safe");
        service.fraudDecisionCreated(paymentIntent, decision, snapshot, actor, "request-safe", cause);
        verify(sourceLineageStore).appendFraudDecision(any(), eq(fraudDecisionId),
                eq(featureSnapshotId));
        service.authorizationCreated(paymentIntent, attempt, authorization, OCCURRED_AT,
                actor, "request-safe", cause);
        service.paymentAttemptCompleted(paymentIntent, attempt, authorization,
                actor, "request-safe", cause);
        service.authenticationChallengeCreated(paymentIntent, challenge,
                actor, "request-safe", cause);
        service.authenticationChallengeCompleted(paymentIntent, challenge,
                actor, "request-safe", cause);
        service.captureCreated(paymentIntent, authorization, OCCURRED_AT,
                actor, "request-safe", cause);
        service.authorizationVoided(paymentIntent, authorization, authorizationVoid,
                actor, "request-safe", cause);
        service.refundCreated(paymentIntent, refund, actor, "request-safe", cause);
        service.chargebackCreated(paymentIntent, chargeback, actor, "request-safe", cause);
        service.paymentIntentCanceled(paymentIntent, "MERCHANT_REQUESTED",
                actor, "request-safe", cause);

        assertThat(writer.commands()).extracting(DomainEventCommand::eventType).containsExactly(
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventType.FRAUD_DECISION_CREATED,
                DomainEventType.AUTHORIZATION_CREATED,
                DomainEventType.PAYMENT_ATTEMPT_COMPLETED,
                DomainEventType.AUTHENTICATION_CHALLENGE_CREATED,
                DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED,
                DomainEventType.CAPTURE_CREATED,
                DomainEventType.AUTHORIZATION_VOIDED,
                DomainEventType.REFUND_CREATED,
                DomainEventType.CHARGEBACK_CREATED,
                DomainEventType.PAYMENT_INTENT_CANCELED);
        assertThat(writer.commands()).allSatisfy(command -> {
            assertThat(command.merchantId()).isEqualTo(merchantId);
            assertThat(command.streamType()).isEqualTo(DomainStreamType.PAYMENT);
            assertThat(command.streamId()).isEqualTo(paymentIntentId);
            assertThat(command.correlationId()).isEqualTo(paymentIntentId.toString());
            assertThat(command.requestId()).isEqualTo("request-safe");
            assertThat(command.actor()).isEqualTo(actor);
            assertThat(command.trafficOrigin()).isEqualTo(TrafficOrigin.SYNTHETIC_SCENARIO);
        });
        assertThat(writer.command(DomainEventType.PAYMENT_INTENT_CREATED).causationEventId()).isNull();
        assertThat(writer.commands().subList(1, writer.commands().size()))
                .extracting(DomainEventCommand::causationEventId)
                .containsOnly(cause);
        assertThat(writer.command(DomainEventType.PAYMENT_INTENT_CREATED).payload().toString())
                .isEqualTo("{\"amountMinor\":1250,\"currency\":\"USD\","
                        + "\"captureMethod\":\"MANUAL\"}");
        assertThat(writer.command(DomainEventType.FRAUD_DECISION_CREATED).payload().toString())
                .isEqualTo("{\"action\":\"ALLOW\",\"riskLevel\":\"LOW\","
                        + "\"reasonCodes\":[\"NO_RULE_MATCH\"],\"rulesetVersion\":\"rules_v1\","
                        + "\"riskScore\":500,\"featureCatalogueVersion\":\"features_v1\"}");
        assertThat(writer.command(DomainEventType.PAYMENT_ATTEMPT_COMPLETED).payload().toString())
                .isEqualTo("{\"attemptNumber\":2,\"outcome\":\"AUTHORIZED\","
                        + "\"processorCode\":\"APPROVED\"}");
        assertThat(writer.command(DomainEventType.AUTHORIZATION_CREATED).payload().toString())
                .isEqualTo("{\"requestedAmountMinor\":1250,\"authorizedAmountMinor\":1250,"
                        + "\"currency\":\"USD\",\"outcome\":\"APPROVED\"}");
        assertThat(writer.command(DomainEventType.AUTHENTICATION_CHALLENGE_CREATED).payload().toString())
                .isEqualTo("{\"challengeType\":\"PROCESSOR\","
                        + "\"expiresAt\":\"2026-07-27T12:05:00.123456Z\"}");
        assertThat(writer.command(DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED).payload().toString())
                .isEqualTo("{\"result\":\"AUTHENTICATED\",\"challengeStatus\":\"SUCCEEDED\"}");
        assertThat(writer.command(DomainEventType.CAPTURE_CREATED).payload().toString())
                .isEqualTo("{\"amountMinor\":1250,\"currency\":\"USD\"}");
        assertThat(writer.command(DomainEventType.AUTHORIZATION_VOIDED).payload().toString())
                .isEqualTo("{\"reasonCode\":\"MERCHANT_CANCELED\","
                        + "\"voidedAmountMinor\":1250,\"currency\":\"USD\"}");
        assertThat(writer.command(DomainEventType.REFUND_CREATED).payload().toString())
                .isEqualTo("{\"amountMinor\":1250,\"currency\":\"USD\","
                        + "\"reasonCode\":\"MERCHANT_REQUESTED\"}");
        assertThat(writer.command(DomainEventType.CHARGEBACK_CREATED).payload().toString())
                .isEqualTo("{\"amountMinor\":400,\"currency\":\"USD\","
                        + "\"reasonCode\":\"FRAUDULENT_STOLEN_CREDENTIAL\"}");
        assertThat(writer.command(DomainEventType.PAYMENT_INTENT_CANCELED).payload().toString())
                .isEqualTo("{\"cancellationReason\":\"MERCHANT_REQUESTED\"}");

        String encodedRecords = String.join("\n", writer.records());
        assertThat(encodedRecords).doesNotContain(
                "4111111111111111", "\"cvv\":\"123\"", "raw-idempotency-canary",
                "processor-reference-canary", "authc_secret_canary", "metadata-canary");
        for (DomainEventType eventType : writer.commands().stream()
                .map(DomainEventCommand::eventType).toList()) {
            verify(metrics).recordAfterCompletion(eventType, 1);
        }
    }

    @Test
    void mapperRejectsCrossTenantLineageAndNonIntegralBasisPointScoresBeforeAppend() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingWriter writer = new RecordingWriter(objectMapper);
        PaymentDomainEventMetrics metrics = mock(PaymentDomainEventMetrics.class);
        DomainEventSourceLineageStore sourceLineageStore = mock(
                DomainEventSourceLineageStore.class);
        PaymentDomainEventService service = new PaymentDomainEventService(
                writer, objectMapper, metrics, sourceLineageStore);
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        PaymentIntent paymentIntent = paymentIntent(merchantId, paymentIntentId);
        DomainEventActor actor = new DomainEventActor(DomainEventActorType.SYSTEM, "test-system");
        Refund foreignRefund = refund(UUID.randomUUID(), paymentIntentId);

        assertThatThrownBy(() -> service.refundCreated(
                paymentIntent, foreignRefund, actor, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crosses merchant boundary");

        UUID attemptId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        FraudDecision nonCanonicalScore = fraudDecision(
                merchantId, attemptId, decisionId, new BigDecimal("0.05001"));
        FeatureSnapshot snapshot = featureSnapshot(merchantId, attemptId, decisionId);
        assertThatThrownBy(() -> service.fraudDecisionCreated(paymentIntent,
                nonCanonicalScore, snapshot, actor, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis points");
        assertThat(writer.commands()).isEmpty();
        verifyNoInteractions(metrics);
    }

    @Test
    void writerFailureIsObservableWithoutReportingACommittedEvent() {
        ObjectMapper objectMapper = new ObjectMapper();
        DomainEventWriter writer = mock(DomainEventWriter.class);
        PaymentDomainEventMetrics metrics = mock(PaymentDomainEventMetrics.class);
        DomainEventSourceLineageStore sourceLineageStore = mock(
                DomainEventSourceLineageStore.class);
        PaymentDomainEventService service = new PaymentDomainEventService(
                writer, objectMapper, metrics, sourceLineageStore);
        PaymentIntent paymentIntent = paymentIntent(UUID.randomUUID(), UUID.randomUUID());
        DomainEventActor actor = new DomainEventActor(DomainEventActorType.SYSTEM, "test-system");
        when(writer.append(org.mockito.ArgumentMatchers.any(DomainEventCommand.class)))
                .thenThrow(new IllegalStateException("database-canary-must-not-be-a-tag"));

        assertThatThrownBy(() -> service.paymentIntentCreated(paymentIntent, actor, "request-safe"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database-canary-must-not-be-a-tag");

        verify(metrics).recordWriteFailure(DomainEventType.PAYMENT_INTENT_CREATED, 1);
        verify(metrics, never()).recordAfterCompletion(DomainEventType.PAYMENT_INTENT_CREATED, 1);
    }

    private PaymentIntent paymentIntent(UUID merchantId, UUID paymentIntentId) {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getId()).thenReturn(paymentIntentId);
        when(paymentIntent.getMerchantId()).thenReturn(merchantId);
        when(paymentIntent.getAmount()).thenReturn(1_250L);
        when(paymentIntent.getCurrency()).thenReturn("USD");
        when(paymentIntent.getCaptureMethod()).thenReturn(CaptureMethod.MANUAL);
        when(paymentIntent.getTrafficOrigin()).thenReturn(TrafficOrigin.SYNTHETIC_SCENARIO);
        when(paymentIntent.getCreatedAt()).thenReturn(OCCURRED_AT);
        when(paymentIntent.getUpdatedAt()).thenReturn(OCCURRED_AT);
        when(paymentIntent.getDescription()).thenReturn("metadata-canary");
        when(paymentIntent.getMetadata()).thenReturn(java.util.Map.of("unsafe", "metadata-canary"));
        return paymentIntent;
    }

    private PaymentAttempt paymentAttempt(UUID merchantId, UUID paymentIntentId, UUID attemptId) {
        PaymentAttempt attempt = mock(PaymentAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getMerchantId()).thenReturn(merchantId);
        when(attempt.getPaymentIntentId()).thenReturn(paymentIntentId);
        when(attempt.getAttemptNumber()).thenReturn(2);
        when(attempt.getStatus()).thenReturn(PaymentAttemptStatus.AUTHORIZED);
        when(attempt.getTrafficOrigin()).thenReturn(TrafficOrigin.SYNTHETIC_SCENARIO);
        when(attempt.getUpdatedAt()).thenReturn(OCCURRED_AT);
        when(attempt.getIdempotencyKey()).thenReturn("raw-idempotency-canary");
        return attempt;
    }

    private FraudDecision fraudDecision(UUID merchantId, UUID attemptId, UUID decisionId,
            BigDecimal score) {
        FraudDecision decision = mock(FraudDecision.class);
        when(decision.getId()).thenReturn(decisionId);
        when(decision.getMerchantId()).thenReturn(merchantId);
        when(decision.getPaymentAttemptId()).thenReturn(attemptId);
        when(decision.getAction()).thenReturn(FraudDecisionAction.ALLOW);
        when(decision.getRiskLevel()).thenReturn(FraudRiskLevel.LOW);
        when(decision.getReasonCodes()).thenReturn(List.of(FraudDecisionReasonCode.NO_RULE_MATCH));
        when(decision.getRulesetVersion()).thenReturn("rules_v1");
        when(decision.getRiskScore()).thenReturn(score);
        when(decision.getDecisionCompletedAt()).thenReturn(OCCURRED_AT);
        when(decision.getTrafficOrigin()).thenReturn(TrafficOrigin.SYNTHETIC_SCENARIO);
        return decision;
    }

    private FeatureSnapshot featureSnapshot(UUID merchantId, UUID attemptId, UUID decisionId) {
        FeatureSnapshot snapshot = mock(FeatureSnapshot.class);
        when(snapshot.getId()).thenReturn(UUID.randomUUID());
        when(snapshot.getMerchantId()).thenReturn(merchantId);
        when(snapshot.getPaymentAttemptId()).thenReturn(attemptId);
        when(snapshot.getFraudDecisionId()).thenReturn(decisionId);
        when(snapshot.getFeatureVersion()).thenReturn(FeatureSnapshot.FEATURES_V1);
        return snapshot;
    }

    private Authorization authorization(UUID merchantId, UUID attemptId, UUID authorizationId) {
        Authorization authorization = mock(Authorization.class);
        when(authorization.getId()).thenReturn(authorizationId);
        when(authorization.getMerchantId()).thenReturn(merchantId);
        when(authorization.getPaymentAttemptId()).thenReturn(attemptId);
        when(authorization.getStatus()).thenReturn(AuthorizationStatus.APPROVED);
        when(authorization.getRequestedAmount()).thenReturn(1_250L);
        when(authorization.getAuthorizedAmount()).thenReturn(1_250L);
        when(authorization.getCurrency()).thenReturn("USD");
        when(authorization.getProcessorResponseCode()).thenReturn("APPROVED");
        when(authorization.getProcessorReferenceId()).thenReturn("processor-reference-canary");
        when(authorization.getCreatedAt()).thenReturn(OCCURRED_AT);
        return authorization;
    }

    private AuthorizationVoid authorizationVoid(UUID merchantId, UUID paymentIntentId,
            UUID authorizationId) {
        AuthorizationVoid authorizationVoid = mock(AuthorizationVoid.class);
        when(authorizationVoid.getMerchantId()).thenReturn(merchantId);
        when(authorizationVoid.getPaymentIntentId()).thenReturn(paymentIntentId);
        when(authorizationVoid.getAuthorizationId()).thenReturn(authorizationId);
        when(authorizationVoid.getReason()).thenReturn(AuthorizationVoidReason.MERCHANT_CANCELED);
        when(authorizationVoid.getCreatedAt()).thenReturn(OCCURRED_AT);
        return authorizationVoid;
    }

    private Refund refund(UUID merchantId, UUID paymentIntentId) {
        Refund refund = mock(Refund.class);
        when(refund.getMerchantId()).thenReturn(merchantId);
        when(refund.getPaymentIntentId()).thenReturn(paymentIntentId);
        when(refund.getAmount()).thenReturn(1_250L);
        when(refund.getCurrency()).thenReturn("USD");
        when(refund.getReasonCode()).thenReturn(RefundReasonCode.MERCHANT_REQUESTED);
        when(refund.getCreatedAt()).thenReturn(OCCURRED_AT);
        return refund;
    }

    private Chargeback chargeback(UUID merchantId, UUID paymentIntentId) {
        Chargeback chargeback = mock(Chargeback.class);
        when(chargeback.getMerchantId()).thenReturn(merchantId);
        when(chargeback.getPaymentIntentId()).thenReturn(paymentIntentId);
        when(chargeback.getAmount()).thenReturn(700L);
        when(chargeback.getAllocatedAmount()).thenReturn(400L);
        when(chargeback.getCurrency()).thenReturn("USD");
        when(chargeback.getReasonCode()).thenReturn(ChargebackReasonCode.FRAUDULENT_STOLEN_CREDENTIAL);
        when(chargeback.getTrafficOrigin()).thenReturn(TrafficOrigin.SYNTHETIC_SCENARIO);
        when(chargeback.getAvailableAt()).thenReturn(OCCURRED_AT);
        return chargeback;
    }

    private AuthenticationChallenge challenge(UUID merchantId, UUID paymentIntentId,
            UUID attemptId, UUID fraudDecisionId, UUID authorizationId) {
        AuthenticationChallenge challenge = mock(AuthenticationChallenge.class);
        when(challenge.getMerchantId()).thenReturn(merchantId);
        when(challenge.getPaymentIntentId()).thenReturn(paymentIntentId);
        when(challenge.getPaymentAttemptId()).thenReturn(attemptId);
        when(challenge.getFraudDecisionId()).thenReturn(fraudDecisionId);
        when(challenge.getAuthorizationId()).thenReturn(authorizationId);
        when(challenge.getSource()).thenReturn(AuthenticationChallengeSource.PROCESSOR);
        when(challenge.getStatus()).thenReturn(AuthenticationChallengeStatus.SUCCEEDED);
        when(challenge.getResultCode()).thenReturn(AuthenticationResultCode.AUTHENTICATED);
        when(challenge.getTrafficOrigin()).thenReturn(TrafficOrigin.SYNTHETIC_SCENARIO);
        when(challenge.getCreatedAt()).thenReturn(OCCURRED_AT);
        when(challenge.getExpiresAt()).thenReturn(OCCURRED_AT.plusSeconds(300));
        when(challenge.getCompletedAt()).thenReturn(OCCURRED_AT.plusSeconds(30));
        when(challenge.getClientSecretPrefix()).thenReturn("authc_secret_canary");
        return challenge;
    }

    private static final class RecordingWriter implements DomainEventWriter {
        private final CanonicalEventCodec codec;
        private final List<DomainEventCommand> commands = new ArrayList<>();
        private final List<String> records = new ArrayList<>();
        private final AtomicLong version = new AtomicLong();

        private RecordingWriter(ObjectMapper objectMapper) {
            this.codec = new CanonicalEventCodec(new DomainEventCatalogue(), objectMapper);
        }

        @Override
        public DomainEventWriteResult append(DomainEventCommand command) {
            long streamVersion = version.incrementAndGet();
            UUID eventId = UUID.randomUUID();
            EncodedDomainEvent encoded = codec.encode(command, eventId, streamVersion, RECORDED_AT);
            commands.add(command);
            records.add(new String(encoded.eventRecordBytes(), StandardCharsets.UTF_8));
            return new DomainEventWriteResult(eventId, UUID.randomUUID(), command.merchantId(),
                    command.streamType(), command.streamId(), streamVersion, command.eventType(),
                    command.eventVersion(), encoded.occurredAt(), encoded.recordedAt(),
                    encoded.payloadSha256(), encoded.eventRecordSha256());
        }

        private List<DomainEventCommand> commands() {
            return List.copyOf(commands);
        }

        private DomainEventCommand command(DomainEventType eventType) {
            return commands.stream().filter(command -> command.eventType() == eventType)
                    .findFirst().orElseThrow();
        }

        private List<String> records() {
            return List.copyOf(records);
        }
    }
}
