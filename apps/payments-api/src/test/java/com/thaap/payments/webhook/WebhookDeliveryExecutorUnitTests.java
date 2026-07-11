package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        when(fixture.claimService().claimDue(
                any(Instant.class), eq(1), eq(Set.of()), eq(Set.of())))
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
        order.verify(fixture.claimService()).claimDue(
                any(Instant.class), eq(1), eq(Set.of()), eq(Set.of()));
        order.verify(fixture.transport()).send(any(WebhookHttpRequest.class));
        order.verify(fixture.claimService()).claimDue(
                any(Instant.class), eq(1), eq(Set.of()), eq(Set.of()));
        order.verify(fixture.transport()).send(any(WebhookHttpRequest.class));
        order.verify(fixture.claimService()).claimDue(
                any(Instant.class), eq(1), eq(Set.of()), eq(Set.of()));
    }

    @Test
    void perPollBudgetsExcludeSaturatedMerchantAndEndpointWithoutStoppingOtherTenants()
            throws Exception {
        WebhookDeliveryClaimService claimService = mock(WebhookDeliveryClaimService.class);
        WebhookDeliveryFinalizationService finalizationService =
                mock(WebhookDeliveryFinalizationService.class);
        WebhookDeliveryRepository deliveryRepository = mock(WebhookDeliveryRepository.class);
        WebhookEndpointSecretService endpointSecretService =
                mock(WebhookEndpointSecretService.class);
        WebhookSigner signer = mock(WebhookSigner.class);
        WebhookTargetPolicy targetPolicy = mock(WebhookTargetPolicy.class);
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        WebhookMetrics metrics = mock(WebhookMetrics.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC);
        WebhookDeliveryExecutor executor = new WebhookDeliveryExecutor(
                claimService, finalizationService, deliveryRepository,
                endpointSecretService, signer, targetPolicy, transport, metrics,
                clock, 1, 1);
        WebhookDeliveryClaim noisy = claim(1);
        WebhookDeliveryClaim quiet = claim(1);
        AtomicInteger invocation = new AtomicInteger();
        when(claimService.claimDue(
                any(Instant.class), eq(1), anySet(), anySet()))
                .thenAnswer(call -> {
                    Set<UUID> merchantExclusions =
                            Set.copyOf(call.<Set<UUID>>getArgument(2));
                    Set<UUID> endpointExclusions =
                            Set.copyOf(call.<Set<UUID>>getArgument(3));
                    return switch (invocation.getAndIncrement()) {
                        case 0 -> {
                            assertThat(merchantExclusions).isEmpty();
                            assertThat(endpointExclusions).isEmpty();
                            yield List.of(noisy);
                        }
                        case 1 -> {
                            assertThat(merchantExclusions).containsExactly(noisy.merchantId());
                            assertThat(endpointExclusions).containsExactly(noisy.endpointId());
                            yield List.of(quiet);
                        }
                        default -> {
                            assertThat(merchantExclusions)
                                    .containsExactlyInAnyOrder(
                                            noisy.merchantId(), quiet.merchantId());
                            assertThat(endpointExclusions)
                                    .containsExactlyInAnyOrder(
                                            noisy.endpointId(), quiet.endpointId());
                            yield List.of();
                        }
                    };
                });
        when(endpointSecretService.loadForDelivery(any(UUID.class), any(UUID.class)))
                .thenReturn("whsec_test");
        when(targetPolicy.validate(any(String.class))).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"),
                InetAddress.getByName("127.0.0.1"), 80));
        when(claimService.prepareForDispatch(
                any(WebhookDeliveryClaim.class), any(Instant.class))).thenReturn(false);

        assertThat(executor.processDueDeliveries(
                Instant.parse("2026-06-28T10:00:00Z"), 3)).isEqualTo(2);

        verify(metrics, org.mockito.Mockito.times(2)).recordClaimThrottled("merchant");
        verify(metrics, org.mockito.Mockito.times(2)).recordClaimThrottled("endpoint");
        verifyNoInteractions(transport, finalizationService);
    }

    @Test
    void unexpectedRuntimeFailureLeavesClaimForLeaseRecovery() {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claim(1);
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"),
                java.net.InetAddress.getLoopbackAddress(), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), any(Instant.class))).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("temporary database failure"))
                .when(fixture.endpointSecretService()).signForDelivery(
                eq(claim.merchantId()), eq(claim.endpointId()), any(),
                eq(claim.payload()), any(Instant.class), eq(fixture.signer()));

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
    void signingServiceReceivesDispatchPreparationAsTimestampLowerBound() throws Exception {
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
                claimedAt.getEpochSecond(), "a".repeat(64),
                "t=" + claimedAt.getEpochSecond() + ",v1=" + "a".repeat(64));
        when(fixture.signer().sign("whsec_test", claim.payload(), claimedAt))
                .thenReturn(signature);
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(204));
        when(fixture.finalizationService().succeed(eq(claim), eq(afterLockWait), eq(204)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.SUCCEEDED, null,
                        WebhookAttemptOutcome.SUCCEEDED, null));

        fixture.executor().processClaim(claim);

        verify(fixture.endpointSecretService()).signForDelivery(
                claim.merchantId(), claim.endpointId(), null, claim.payload(),
                claimedAt, fixture.signer());
        verify(fixture.signer()).sign("whsec_test", claim.payload(), claimedAt);
        verify(fixture.transport()).send(org.mockito.ArgumentMatchers.argThat(request ->
                request.signatureHeader().startsWith("t=" + claimedAt.getEpochSecond() + ",")));
    }

    @Test
    void expiredPreviousGenerationRetriesWithoutSigningOrSending() throws Exception {
        Clock clock = mock(Clock.class);
        Instant claimedAt = Instant.parse("2026-06-28T10:00:00Z");
        Instant beforeExpiry = claimedAt.plusSeconds(899);
        Instant atExpiry = claimedAt.plusSeconds(900);
        when(clock.instant())
                .thenReturn(beforeExpiry)
                .thenReturn(atExpiry)
                .thenReturn(atExpiry);
        ExecutorFixture fixture = fixture(clock);
        WebhookDeliveryClaim claim = claimWithGeneration(1);
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(new ValidatedWebhookTarget(
                URI.create("http://127.0.0.1/webhook"), InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(eq(claim), eq(beforeExpiry))).thenReturn(true);
        doThrow(new WebhookStaleSecretGenerationException(
                "generation expired",
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE))
                .when(fixture.endpointSecretService()).signForDelivery(
                claim.merchantId(), claim.endpointId(),
                claim.signingSecretGeneration(), claim.payload(), beforeExpiry,
                fixture.signer());
        when(fixture.finalizationService().failRetryably(
                eq(claim), eq(atExpiry), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.RETRY_SCHEDULED,
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE,
                        WebhookAttemptOutcome.RETRYABLE_FAILURE,
                        WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE));

        fixture.executor().processClaim(claim);

        verifyNoInteractions(fixture.signer(), fixture.transport());
        verify(fixture.finalizationService()).failRetryably(
                claim, atExpiry, false, null,
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
    }

    @Test
    void finalAttemptStaleGenerationRebindsCurrentSecretBeforeHttp() throws Exception {
        ExecutorFixture fixture = fixture();
        WebhookDeliveryClaim claim = claimWithGeneration(WebhookRetryPolicy.MAX_ATTEMPTS);
        when(fixture.targetPolicy().validate(claim.endpointUrl())).thenReturn(
                new ValidatedWebhookTarget(
                        URI.create("http://127.0.0.1/webhook"),
                        InetAddress.getByName("127.0.0.1"), 80));
        when(fixture.claimService().prepareForDispatch(
                eq(claim), eq(claim.claimedAt()))).thenReturn(true);
        doThrow(new WebhookStaleSecretGenerationException(
                "generation expired",
                WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE))
                .when(fixture.endpointSecretService()).signForDelivery(
                claim.merchantId(), claim.endpointId(),
                claim.signingSecretGeneration(), claim.payload(), claim.claimedAt(),
                fixture.signer());
        WebhookSignature reboundSignature = new WebhookSignature(
                claim.claimedAt().getEpochSecond(), "a".repeat(64),
                "t=" + claim.claimedAt().getEpochSecond() + ",v1=" + "a".repeat(64));
        when(fixture.endpointSecretService().signForDelivery(
                claim.merchantId(), claim.endpointId(), null, claim.payload(),
                claim.claimedAt(), fixture.signer())).thenReturn(reboundSignature);
        when(fixture.transport().send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(204));
        when(fixture.finalizationService().succeed(
                eq(claim), eq(claim.claimedAt()), eq(204)))
                .thenReturn(new WebhookDeliveryFinalizationResult(
                        true, WebhookDeliveryStatus.SUCCEEDED, null,
                        WebhookAttemptOutcome.SUCCEEDED, null));

        fixture.executor().processClaim(claim);

        verify(fixture.endpointSecretService()).signForDelivery(
                claim.merchantId(), claim.endpointId(), null, claim.payload(),
                claim.claimedAt(), fixture.signer());
        verify(fixture.transport()).send(any(WebhookHttpRequest.class));
        verify(fixture.finalizationService(), never()).failRetryably(
                eq(claim), any(Instant.class), eq(false), eq(null),
                eq(WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE));
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
        when(fixture.claimService().claimDue(
                any(Instant.class), eq(1), eq(Set.of()), eq(Set.of())))
                .thenReturn(List.of());
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
        when(endpointSecretService.signForDelivery(
                any(UUID.class), any(UUID.class), any(), any(String.class),
                any(Instant.class), any(WebhookSigner.class)))
                .thenAnswer(call -> {
                    WebhookSigner selectedSigner = call.getArgument(5);
                    String payload = call.getArgument(3);
                    Instant signatureTime = call.getArgument(4);
                    try {
                        return selectedSigner.sign("whsec_test", payload, signatureTime);
                    } catch (RuntimeException exception) {
                        throw new WebhookSigningException(
                                "Webhook payload could not be signed", exception);
                    }
                });
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

    private WebhookDeliveryClaim claimWithGeneration(int attemptNumber) {
        String payload = "{\"id\":\"" + UUID.randomUUID() + "\"}";
        Instant claimedAt = Instant.parse("2026-06-28T10:00:00Z");
        return new WebhookDeliveryClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "refund.created", "http://127.0.0.1/webhook", payload, SecretHasher.sha256(payload),
                UUID.randomUUID(), attemptNumber, UUID.randomUUID(),
                claimedAt, claimedAt.minusSeconds(5));
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
