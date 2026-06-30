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
import java.util.Optional;
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
                .thenReturn(true);

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
    void signingFailureRemainsBeforeDurableDispatchBoundary() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.endpointSecretService().loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn("whsec_test");
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.signer().sign(any(String.class), any(String.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("signing unavailable"));

        fixture.executor().processClaim(claim);

        verifyNoInteractions(fixture.transport(), fixture.finalizationService());
        verify(fixture.claimService(), never())
                .prepareForDispatch(any(WebhookDeliveryClaim.class), any(Instant.class));
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
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR))).thenReturn(true);
        WebhookDelivery retryScheduled = mock(WebhookDelivery.class);
        when(retryScheduled.getStatus()).thenReturn(WebhookDeliveryStatus.RETRY_SCHEDULED);
        when(fixture.deliveryRepository().findById(claim.deliveryId()))
                .thenReturn(Optional.of(retryScheduled));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failRetryably(
                eq(claim), any(Instant.class), eq(true), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR));
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
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR))).thenReturn(true);
        WebhookDelivery retryScheduled = mock(WebhookDelivery.class);
        when(retryScheduled.getStatus()).thenReturn(WebhookDeliveryStatus.RETRY_SCHEDULED);
        when(fixture.deliveryRepository().findById(claim.deliveryId()))
                .thenReturn(Optional.of(retryScheduled));

        fixture.executor().processClaim(claim);

        verify(fixture.finalizationService()).failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.TRANSPORT_ERROR));
        verify(fixture.finalizationService(), never()).failTerminally(
                eq(claim), any(Instant.class), eq(WebhookDeliveryErrorCode.TARGET_NOT_ALLOWED));
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
                .thenReturn(true);
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
        WebhookDeliveryClaimService claimService = mock(WebhookDeliveryClaimService.class);
        WebhookDeliveryFinalizationService finalizationService = mock(WebhookDeliveryFinalizationService.class);
        WebhookDeliveryRepository deliveryRepository = mock(WebhookDeliveryRepository.class);
        WebhookEndpointSecretService endpointSecretService = mock(WebhookEndpointSecretService.class);
        WebhookSigner signer = mock(WebhookSigner.class);
        WebhookTargetPolicy targetPolicy = mock(WebhookTargetPolicy.class);
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        WebhookMetrics metrics = mock(WebhookMetrics.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC);
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
