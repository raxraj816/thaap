package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaap.payments.security.SecretHasher;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

class WebhookDeliveryExecutorUnitTests {

    @Test
    void claimsEachDeliveryOnlyAfterThePreviousSendFinishes() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim first = claim(1);
        WebhookDeliveryClaim second = claim(1);
        when(fixture.claimService().claimDue(any(Instant.class), eq(1)))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        when(fixture.endpointSecretService().loadForDelivery(any(UUID.class), any(UUID.class)))
                .thenReturn("whsec_test");
        when(fixture.claimService().prepareForDispatch(any(WebhookDeliveryClaim.class), any(Instant.class)))
                .thenReturn(true);
        when(fixture.targetPolicy().validate(any(String.class))).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(new WebhookSignature(1L, "a".repeat(64), "t=1,v1=" + "a".repeat(64)));
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(204));
        when(fixture.finalizationService().succeed(any(WebhookDeliveryClaim.class), any(Instant.class), eq(204)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.SUCCEEDED, null,
                        WebhookAttemptOutcome.SUCCEEDED, null));

        assertThat(fixture.executor().processDueDeliveries(Instant.parse("2026-06-28T10:00:00Z"), 3))
                .isEqualTo(2);

        InOrder order = inOrder(fixture.claimService(), fixture.transport());
        order.verify(fixture.claimService()).claimDue(any(Instant.class), eq(1));
        order.verify(fixture.transport()).send(any(WebhookHttpRequest.class));
        order.verify(fixture.claimService()).claimDue(any(Instant.class), eq(1));
        order.verify(fixture.transport()).send(any(WebhookHttpRequest.class));
        order.verify(fixture.claimService()).claimDue(any(Instant.class), eq(1));
    }

    @Test
    void unexpectedRuntimeFailureLeavesClaimForLeaseRecovery() {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenThrow(new DataAccessResourceFailureException("temporary database failure"));

        fixture.executor().processClaim(claim);

        verifyNoInteractions(fixture.finalizationService());
    }

    @Test
    void signingFailureAfterPreparationFinalizesAsKnownNoSend() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(true);
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("signing unavailable"));
        when(fixture.finalizationService().failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.SIGNING_FAILED)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.RETRY_SCHEDULED,
                        WebhookDeliveryErrorCode.SIGNING_FAILED,
                        WebhookAttemptOutcome.RETRYABLE_FAILURE,
                        WebhookDeliveryErrorCode.SIGNING_FAILED));

        fixture.executor().processClaim(claim);

        verifyNoInteractions(fixture.transport());
        verify(fixture.finalizationService()).failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.SIGNING_FAILED));
        verify(fixture.metrics()).recordFailure(
                claim.eventType(), WebhookDeliveryErrorCode.SIGNING_FAILED);
    }

    @Test
    void staleClaimAfterPreparationNeverPerformsHttp() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(new WebhookSignature(1L, "a".repeat(64), "t=1,v1=" + "a".repeat(64)));
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(false);

        fixture.executor().processClaim(claim);

        verifyNoInteractions(fixture.transport(), fixture.finalizationService());
        verifyNoInteractions(fixture.signer());
    }

    @Test
    void signatureTimestampIsMintedAfterDispatchPreparationCompletes() throws Exception {
        Clock clock = mock(Clock.class);
        Instant claimedAt = Instant.parse("2026-06-28T10:00:00Z");
        Instant afterLockWait = claimedAt.plusSeconds(301);
        when(clock.instant())
                .thenReturn(claimedAt)
                .thenReturn(afterLockWait)
                .thenReturn(afterLockWait);
        ExecutorFixture fixture = fixture(clock);
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), eq(claimedAt))).thenReturn(true);
        WebhookSignature signature = new WebhookSignature(
                afterLockWait.getEpochSecond(), "a".repeat(64),
                "t=" + afterLockWait.getEpochSecond() + ",v1=" + "a".repeat(64));
        when(fixture.signer().sign("whsec_test", claim.payload(), afterLockWait))
                .thenReturn(signature);
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(204));
        when(fixture.finalizationService().succeed(eq(claim), eq(afterLockWait), eq(204)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.SUCCEEDED, null,
                        WebhookAttemptOutcome.SUCCEEDED, null));

        fixture.executor().processClaim(claim);

        verify(fixture.signer()).sign("whsec_test", claim.payload(), afterLockWait);
        verify(fixture.transport()).send(org.mockito.ArgumentMatchers.argThat(request ->
                request.signatureHeader().startsWith("t=" + afterLockWait.getEpochSecond() + ",")));
    }

    @Test
    void oversizedPersistedPayloadFailsTerminallyBeforeSecretOrHttp() {
        ExecutorFixture fixture = fixture();
        String payload = "x".repeat(WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES + 1);
        Instant claimedAt = Instant.parse("2026-06-28T10:00:00Z");
        WebhookDeliveryClaim claim = new WebhookDeliveryClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "refund.created", "http://127.0.0.1/webhook", payload, SecretHasher.sha256(payload),
                UUID.randomUUID(), 1, claimedAt, claimedAt.minusSeconds(5));
        when(fixture.finalizationService().failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.EXHAUSTED,
                        WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED,
                        WebhookAttemptOutcome.TERMINAL_FAILURE,
                        WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED));
        verifyNoInteractions(
                fixture.endpointSecretService(), fixture.targetPolicy(), fixture.signer(), fixture.transport());
    }

    @Test
    void transportPayloadIntegrityDefenseFinalizesTerminallyWhenNoRequestWasSent() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(true);
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(new WebhookSignature(1L, "a".repeat(64), "t=1,v1=" + "a".repeat(64)));
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenThrow(new WebhookTransportException(
                        "payload exceeded the transport limit",
                        WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED,
                        false,
                        new IllegalArgumentException("too large")));
        when(fixture.finalizationService().failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.EXHAUSTED,
                        WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED,
                        WebhookAttemptOutcome.TERMINAL_FAILURE,
                        WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED));
        verify(fixture.finalizationService(), never()).failRetryably(
                eq(claim), any(Instant.class), any(Boolean.class), eq(null),
                eq(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED));
    }

    @Test
    void processingBudgetRemainsBoundedAtTheExecutorBoundary() {
        ExecutorFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.executor().processDueDeliveries(
                Instant.parse("2026-06-28T10:00:00Z"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.executor().processDueDeliveries(
                Instant.parse("2026-06-28T10:00:00Z"), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiedTransportFailureFinalizesWithoutLeaseRecovery() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(true);
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(new WebhookSignature(1L, "a".repeat(64), "t=1,v1=" + "a".repeat(64)));
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenThrow(new WebhookTransportException(
                        "invalid response status",
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                        true,
                        new java.io.IOException("status 999")));
        when(fixture.finalizationService().failRetryably(
                eq(claim), any(Instant.class), eq(true), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.RETRY_SCHEDULED,
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                        WebhookAttemptOutcome.RETRYABLE_FAILURE,
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failRetryably(
                eq(claim), any(Instant.class), eq(true), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR));
        verify(fixture.metrics()).recordFailure(
                claim.eventType(), WebhookDeliveryErrorCode.TRANSPORT_ERROR);
    }

    @Test
    void resolverCapacityFailureRetriesWithoutTerminalTargetDenialOrHttp() {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl()))
                .thenThrow(new WebhookTargetResolutionException(
                        "Webhook target hostname resolver capacity is exhausted",
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                        new java.util.concurrent.RejectedExecutionException("full")));
        when(fixture.finalizationService().failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.RETRY_SCHEDULED,
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                        WebhookAttemptOutcome.RETRYABLE_FAILURE,
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR));
        verify(fixture.finalizationService(), never()).failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED));
        verify(fixture.metrics()).recordFailure(
                claim.eventType(), WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        verifyNoInteractions(fixture.transport());
    }

    @Test
    void targetPolicyDenialEmitsBoundedTerminalFailureCause() {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl()))
                .thenThrow(new WebhookTargetPolicyException("target is not allowed"));
        when(fixture.finalizationService().failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.EXHAUSTED,
                        WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED,
                        WebhookAttemptOutcome.TERMINAL_FAILURE,
                        WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED));

        fixture.executor().processClaim(claim);

        verify(fixture.metrics()).recordFailure(
                claim.eventType(), WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED);
        verifyNoInteractions(fixture.transport());
    }

    @Test
    void dueGaugeReportsRemainingEligibleBacklog() {
        ExecutorFixture fixture = fixture();
        when(fixture.claimService().claimDue(any(Instant.class), eq(1))).thenReturn(List.of());
        when(fixture.deliveryRepository().countDue(any(Instant.class))).thenReturn(7L);

        assertThat(fixture.executor().processDueDeliveries(
                Instant.parse("2026-06-28T10:00:00Z"), 1)).isZero();

        verify(fixture.metrics()).setDueDeliveries(7L);
    }

    @Test
    void successfulFinalizationLogIncludesBoundedLatencyField() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(true);
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(new WebhookSignature(1L, "a".repeat(64), "t=1,v1=" + "a".repeat(64)));
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(204));
        when(fixture.finalizationService().succeed(eq(claim), any(Instant.class), eq(204)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.SUCCEEDED, null,
                        WebhookAttemptOutcome.SUCCEEDED, null));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WebhookDeliveryExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            fixture.executor().processClaim(claim);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("outcome=SUCCEEDED", "errorCode=null", "latencyMs="));
    }

    private ExecutorFixture fixture() {
        return fixture(Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC));
    }

    private ExecutorFixture fixture(Clock clock) {
        WebhookDeliveryClaimService claimService = mock(WebhookDeliveryClaimService.class);
        WebhookDeliveryFinalizationService finalizationService = mock(WebhookDeliveryFinalizationService.class);
        WebhookDeliveryRepository deliveryRepository = mock(WebhookDeliveryRepository.class);
        WebhookEndpointSecretService endpointSecretService = mock(WebhookEndpointSecretService.class);
        WebhookSigner signer = mock(WebhookSigner.class);
        WebhookTargetPolicy targetPolicy = mock(WebhookTargetPolicy.class);
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        WebhookMetrics metrics = mock(WebhookMetrics.class);
        WebhookDeliveryExecutor executor = new WebhookDeliveryExecutor(
                claimService, finalizationService, deliveryRepository, endpointSecretService,
                signer, targetPolicy, transport, metrics, clock);
        return new ExecutorFixture(executor, claimService, finalizationService,
                deliveryRepository, endpointSecretService, signer, targetPolicy, transport, metrics);
    }

    private WebhookDeliveryClaim claim(int attemptNumber) {
        String payload = "{\"id\":\"" + UUID.randomUUID() + "\"}";
        Instant claimedAt = Instant.parse("2026-06-28T10:00:00Z");
        return new WebhookDeliveryClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "refund.created", "http://127.0.0.1/webhook", payload, SecretHasher.sha256(payload),
                UUID.randomUUID(), attemptNumber, claimedAt, claimedAt.minusSeconds(5));
    }

    private record ExecutorFixture(
            WebhookDeliveryExecutor executor,
            WebhookDeliveryClaimService claimService,
            WebhookDeliveryFinalizationService finalizationService,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointSecretService endpointSecretService,
            WebhookSigner signer,
            WebhookTargetPolicy targetPolicy,
            WebhookHttpTransport transport,
            WebhookMetrics metrics) {
    }
}
