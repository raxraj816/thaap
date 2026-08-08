package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.time.DatabaseInstant;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class WebhookDeliveryExecutionContractTests {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private WebhookEndpointService endpointService;

    @Autowired
    private WebhookEndpointSecretService endpointSecretService;

    @Autowired
    private WebhookSecretCipher secretCipher;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookDeliveryExecutor executor;

    @Autowired
    private WebhookDeliveryClaimService claimService;

    @Autowired
    private WebhookDeliveryFinalizationService finalizationService;

    @Autowired
    private WebhookSigner signer;

    @Autowired
    private WebhookTargetPolicy targetPolicy;

    @Autowired
    private WebhookMetrics webhookMetrics;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer callbackServer;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @AfterEach
    void stopServer() {
        if (callbackServer != null) {
            callbackServer.stop(0);
        }
    }

    @Test
    void routingRequiresExistingDomainTransaction() {
        assertThatThrownBy(() -> deliveryService.createDeliveriesInCurrentTransaction(
                java.util.UUID.randomUUID(), "refund.created", java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction");

        assertThat(eventRepository.count()).isZero();
        assertThat(deliveryRepository.count()).isZero();
    }

    @Test
    void signedFailureRetrySuccessPreservesOneEventAndPaymentTruth() throws Exception {
        List<ReceivedRequest> received = new ArrayList<>();
        AtomicInteger responseNumber = new AtomicInteger();
        callbackServer = callbackServer(exchange -> {
            received.add(received(exchange));
            int status = responseNumber.incrementAndGet() == 1 ? 500 : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("webhook-merchant", "thaap_test_webhook"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "webhook-merchant", merchant.getApiKeyPrefix());
        String url = "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/thaap";
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                merchantContext, url, Set.of("payment_intent.created"), "request-endpoint-create");

        Customer customer = customerRepository.saveAndFlush(new Customer(
                merchant.getId(), "buyer-email-hash", null));
        PaymentIntent paymentIntent = new TransactionTemplate(transactionManager).execute(status -> {
            PaymentIntent stored = paymentIntentRepository.save(new PaymentIntent(
                    merchant.getId(), customer.getId(), 2500, "USD"));
            deliveryService.createDeliveriesInCurrentTransaction(
                    merchant.getId(), "payment_intent.created", stored.getId());
            return stored;
        });
        assertThat(paymentIntent).isNotNull();

        WebhookDelivery initial = deliveryRepository.findAll().getFirst();
        WebhookEvent event = eventRepository.findById(initial.getWebhookEventId()).orElseThrow();
        Instant firstAttemptAt = DatabaseInstant.now().plusSeconds(10);

        assertThat(executor.processDueDeliveries(firstAttemptAt, 10)).isEqualTo(1);

        WebhookDelivery afterFailure = deliveryRepository.findById(initial.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_SCHEDULED);
        assertThat(afterFailure.getAttemptCount()).isEqualTo(1);
        assertThat(afterFailure.getNextAttemptAt()).isEqualTo(firstAttemptAt.plusSeconds(60));
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(initial.getId(), 1)
                .orElseThrow().getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);

        assertThat(executor.processDueDeliveries(afterFailure.getNextAttemptAt(), 10)).isEqualTo(1);

        WebhookDelivery succeeded = deliveryRepository.findById(initial.getId()).orElseThrow();
        assertThat(succeeded.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
        assertThat(succeeded.getAttemptCount()).isEqualTo(2);
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(initial.getId(), 2)
                .orElseThrow().getOutcome()).isEqualTo(WebhookAttemptOutcome.SUCCEEDED);
        assertThat(received).hasSize(2);
        assertThat(received).allSatisfy(request -> {
            assertThat(request.body()).isEqualTo(event.getPayload());
            assertThat(request.eventId()).isEqualTo(event.getId().toString());
            assertThat(request.deliveryId()).isEqualTo(initial.getId().toString());
            assertSignature(endpoint.rawSigningSecret(), request);
        });
        assertThat(received.get(0).signature()).isNotEqualTo(received.get(1).signature());
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(paymentIntentRepository.findById(paymentIntent.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        assertThat(meterRegistry.get("thaap.webhook.attempts")
                .tag("event.type", "payment_intent.created")
                .tag("outcome", "RETRYABLE_FAILURE")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("thaap.webhook.failures")
                .tag("event.type", "payment_intent.created")
                .tag("error.code", "HTTP_NON_SUCCESS")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("thaap.webhook.delivery.terminal")
                .tag("event.type", "payment_intent.created")
                .tag("status", "SUCCEEDED")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("thaap.webhook"))
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain("merchant.id", "endpoint.id", "delivery.id", "resource.id", "url");
    }

    @Test
    void malformedSuccessfulResponseHeadCannotBecomeDurableSuccess() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    consumeHttpRequest(socket);
                    socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nnot-a-header\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The delivery assertions own the malformed-response outcome.
                }
            });

            Merchant merchant = merchantRepository.saveAndFlush(
                    new Merchant("malformed-response", "thaap_test_malformed_response"));
            MerchantContext merchantContext = new MerchantContext(
                    merchant.getId(), "malformed-response", merchant.getApiKeyPrefix());
            endpointService.create(merchantContext,
                    "http://127.0.0.1:" + server.getLocalPort() + "/thaap",
                    Set.of("refund.created"), "malformed-response-endpoint");
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    deliveryService.createDeliveriesInCurrentTransaction(
                            merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
            WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
            Instant attemptAt = DatabaseInstant.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                    .plusSeconds(1);

            assertThat(executor.processDueDeliveries(attemptAt, 1)).isEqualTo(1);

            WebhookDelivery retry = deliveryRepository.findById(delivery.getId()).orElseThrow();
            WebhookDeliveryAttempt attempt = attemptRepository
                    .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                    .orElseThrow();
            assertThat(retry.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_SCHEDULED);
            assertThat(retry.getLastErrorCode()).isEqualTo(
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR);
            assertThat(retry.getNextAttemptAt()).isEqualTo(attemptAt.plusSeconds(60));
            assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.getHttpRequestSent()).isTrue();
            assertThat(attempt.getResponseStatus()).isNull();
            assertThat(attempt.getErrorCode()).isEqualTo(
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        }
    }

    private void consumeHttpRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int firstPrevious = -1;
        int secondPrevious = -1;
        int thirdPrevious = -1;
        boolean headerComplete = false;
        while (headerBytes.size() < 16_384) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("Webhook request ended before its header boundary");
            }
            headerBytes.write(current);
            if (firstPrevious == '\r'
                    && secondPrevious == '\n'
                    && thirdPrevious == '\r'
                    && current == '\n') {
                headerComplete = true;
                break;
            }
            firstPrevious = secondPrevious;
            secondPrevious = thirdPrevious;
            thirdPrevious = current;
        }
        if (!headerComplete) {
            throw new IOException("Webhook request headers exceeded the fixture limit");
        }
        String headers = headerBytes.toString(StandardCharsets.US_ASCII);
        int contentLength = -1;
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        if (contentLength < 0 || input.readNBytes(contentLength).length != contentLength) {
            throw new IOException("Webhook request body was incomplete");
        }
    }

    @Test
    void expiredPreDispatchLeaseRecordsNoSendFailure() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("lease-merchant", "thaap_test_lease"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "lease-merchant", merchant.getApiKeyPrefix());
        endpointService.create(merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "lease-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant firstClaimAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        WebhookDeliveryClaim first = claimService.claimDue(firstClaimAt, 1).getFirst();
        WebhookDeliveryClaim recovered = claimService.claimDue(firstClaimAt.plusSeconds(31), 1).getFirst();

        assertThat(recovered.eventId()).isEqualTo(first.eventId());
        assertThat(recovered.attemptNumber()).isEqualTo(2);
        WebhookDeliveryAttempt expiredAttempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(expiredAttempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(expiredAttempt.getHttpRequestSent()).isFalse();
        assertThat(expiredAttempt.getDispatchStartedAt()).isNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 2)
                .orElseThrow().getOutcome()).isEqualTo(WebhookAttemptOutcome.IN_PROGRESS);
        assertThat(finalizationService.succeed(
                first, firstClaimAt.plusSeconds(32), 204).finalized()).isFalse();
        assertThat(claimService.prepareForDispatch(recovered, firstClaimAt.plusSeconds(32))).isTrue();
        assertThat(finalizationService.succeed(
                recovered, firstClaimAt.plusSeconds(33), 204).finalized()).isTrue();
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
    }

    @Test
    void expiredPostDispatchLeaseRecordsOutcomeUnknown() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("dispatch-lease", "thaap_test_dispatch_lease"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "dispatch-lease", merchant.getApiKeyPrefix());
        endpointService.create(merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "dispatch-lease-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant firstClaimAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        WebhookDeliveryClaim first = claimService.claimDue(firstClaimAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(first, firstClaimAt.plusSeconds(1))).isTrue();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow().getHttpRequestSent()).isNull();
        WebhookDeliveryClaim recovered = claimService.claimDue(firstClaimAt.plusSeconds(32), 1).getFirst();

        WebhookDeliveryAttempt expiredAttempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(expiredAttempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(expiredAttempt.getHttpRequestSent()).isNull();
        assertThat(expiredAttempt.getDispatchStartedAt()).isEqualTo(firstClaimAt.plusSeconds(1));
        assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThat(finalizationService.succeed(
                first, firstClaimAt.plusSeconds(33), 204).finalized()).isFalse();
    }

    @Test
    void ambiguousTransportFailureRecordsOutcomeUnknownAndSchedulesRetry() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("ambiguous-write", "thaap_test_ambiguous_write"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "ambiguous-write", merchant.getApiKeyPrefix());
        endpointService.create(merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "ambiguous-write-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant completedAt = claimedAt.plusSeconds(2);

        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt.plusSeconds(1))).isTrue();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow().getHttpRequestSent()).isNull();
        assertThat(finalizationService.failRetryably(
                claim, completedAt, null, null,
                WebhookDeliveryErrorCode.TRANSPORT_ERROR).finalized()).isTrue();

        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        WebhookDelivery retry = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(attempt.getHttpRequestSent()).isNull();
        assertThat(attempt.getDispatchStartedAt()).isEqualTo(claimedAt.plusSeconds(1));
        assertThat(attempt.getErrorCode()).isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        assertThat(retry.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_SCHEDULED);
        assertThat(retry.getNextAttemptAt()).isEqualTo(completedAt.plusSeconds(60));
    }

    @Test
    void expiredClaimGenerationRetriesAndNextClaimBindsCurrentSecret() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("stale-generation", "thaap_test_stale_generation"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "stale-generation", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "stale-generation-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        Instant claimedAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        WebhookDeliveryClaim staleClaim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(staleClaim.signingSecretGeneration())
                .isEqualTo(created.endpoint().getSigningSecretGeneration());

        WebhookEndpointService.EndpointSecretResult rotated = endpointService.rotateSecret(
                merchantContext, created.endpoint().getId(), "stale-generation-rotate");
        WebhookEndpoint stored = endpointRepository.findById(created.endpoint().getId()).orElseThrow();
        Instant expiresAt = stored.getPreviousSigningSecretExpiresAt();
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        WebhookEndpointSecretService clockedSecretService = endpointSecretService(
                java.time.Clock.fixed(expiresAt, java.time.ZoneOffset.UTC));
        WebhookDeliveryExecutor staleExecutor = new WebhookDeliveryExecutor(
                claimService, finalizationService, deliveryRepository, clockedSecretService,
                signer, targetPolicy, transport, webhookMetrics,
                java.time.Clock.fixed(expiresAt, java.time.ZoneOffset.UTC));

        staleExecutor.processClaim(staleClaim);

        WebhookDelivery retry = deliveryRepository.findAll().getFirst();
        WebhookDeliveryAttempt staleAttempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(retry.getId(), 1)
                .orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_SCHEDULED);
        assertThat(retry.getLastErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.SIGNING_SECRET_UNAVAILABLE);
        assertThat(retry.getNextAttemptAt()).isEqualTo(expiresAt.plusSeconds(60));
        assertThat(staleAttempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(staleAttempt.getHttpRequestSent()).isFalse();
        verify(transport, never()).send(org.mockito.ArgumentMatchers.any(WebhookHttpRequest.class));

        WebhookDeliveryClaim rebound = claimService.claimDue(expiresAt.plusSeconds(60), 1).getFirst();
        assertThat(rebound.signingSecretGeneration())
                .isEqualTo(rotated.endpoint().getSigningSecretGeneration());
    }

    @Test
    void finalAttemptStaleGenerationUsesCurrentSecretBeforeFirstHttpSend() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("stale-final-generation", "thaap_test_stale_final_generation"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "stale-final-generation", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult created = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "stale-final-generation-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET attempt_count = 4, generation_attempt_count = 4 WHERE id = ?",
                delivery.getId());
        Instant claimedAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        WebhookDeliveryClaim staleFinalClaim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(staleFinalClaim.attemptNumber()).isEqualTo(WebhookRetryPolicy.MAX_ATTEMPTS);
        assertThat(staleFinalClaim.signingSecretGeneration())
                .isEqualTo(created.endpoint().getSigningSecretGeneration());

        WebhookEndpointService.EndpointSecretResult rotated = endpointService.rotateSecret(
                merchantContext, created.endpoint().getId(), "stale-final-generation-rotate");
        Instant expiresAt = endpointRepository.findById(created.endpoint().getId())
                .orElseThrow().getPreviousSigningSecretExpiresAt();
        WebhookEndpointSecretService clockedSecretService = endpointSecretService(
                java.time.Clock.fixed(expiresAt, java.time.ZoneOffset.UTC));
        AtomicReference<WebhookHttpRequest> sentRequest = new AtomicReference<>();
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        when(transport.send(org.mockito.ArgumentMatchers.any(WebhookHttpRequest.class)))
                .thenAnswer(invocation -> {
                    sentRequest.set(invocation.getArgument(0));
                    return new WebhookHttpResponse(204);
                });
        WebhookDeliveryExecutor staleFinalExecutor = new WebhookDeliveryExecutor(
                claimService, finalizationService, deliveryRepository, clockedSecretService,
                signer, targetPolicy, transport, webhookMetrics,
                java.time.Clock.fixed(expiresAt, java.time.ZoneOffset.UTC));

        staleFinalExecutor.processClaim(staleFinalClaim);

        WebhookDelivery stored = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(
                        delivery.getId(), WebhookRetryPolicy.MAX_ATTEMPTS)
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.SUCCEEDED);
        WebhookHttpRequest request = sentRequest.get();
        assertThat(request).isNotNull();
        String[] signatureParts = request.signatureHeader().split(",", 2);
        long timestamp = Long.parseLong(signatureParts[0].substring(2));
        String signatureValue = signatureParts[1].substring(3);
        assertThat(signer.verify(
                rotated.rawSigningSecret(), staleFinalClaim.payload(),
                timestamp, signatureValue)).isTrue();
    }

    @Test
    void disablingEndpointDuringInFlightFailurePreventsAnotherRetry() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("disable-race", "thaap_test_disable"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "disable-race", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-race-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = DatabaseInstant.now().minusSeconds(10);
        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt)).isTrue();

        endpointService.disable(merchantContext, endpoint.endpoint().getId(), "disable-race-request");
        assertThat(finalizationService.failRetryably(claim, claimedAt.plusSeconds(1), true, 503,
                WebhookDeliveryErrorCode.HTTP_NON_SUCCESS).finalized()).isTrue();

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.TERMINAL_FAILURE);
        assertThat(attempt.getErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(claimService.claimDue(claimedAt.plusSeconds(600), 1)).isEmpty();
    }

    private WebhookEndpointSecretService endpointSecretService(java.time.Clock serviceClock) {
        return new WebhookEndpointSecretService(
                endpointRepository, secretCipher, serviceClock) {
            @Override
            public WebhookSignature signForDelivery(
                    java.util.UUID merchantId, java.util.UUID endpointId,
                    java.util.UUID secretGeneration, String payload,
                    Instant notBefore, WebhookSigner selectedSigner) {
                return new TransactionTemplate(transactionManager).execute(status ->
                        super.signForDelivery(
                                merchantId, endpointId, secretGeneration, payload,
                                notBefore, selectedSigner));
            }
        };
    }

    @Test
    void disablingEndpointAfterClaimButBeforeDispatchPreventsHttpSend() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-before-dispatch", "thaap_test_disable_before_dispatch"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "disable-before-dispatch", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-before-dispatch-create");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        WebhookEndpointSecretService endpointSecretService = mock(WebhookEndpointSecretService.class);
        when(endpointSecretService.loadForDelivery(claim.merchantId(), claim.endpointId()))
                .thenReturn(endpoint.rawSigningSecret());
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        WebhookDeliveryExecutor raceExecutor = new WebhookDeliveryExecutor(
                claimService, finalizationService, deliveryRepository, endpointSecretService,
                signer, targetPolicy, transport, webhookMetrics, clock);

        endpointService.disable(
                merchantContext, endpoint.endpoint().getId(), "disable-before-dispatch-action");
        raceExecutor.processClaim(claim);

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.TERMINAL_FAILURE);
        assertThat(attempt.getHttpRequestSent()).isFalse();
        assertThat(attempt.getDispatchStartedAt()).isNull();
        assertThat(attempt.getErrorCode()).isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        verify(transport, never()).send(org.mockito.ArgumentMatchers.any(WebhookHttpRequest.class));
    }

    @Test
    void disabledEndpointStillRecoversAndExhaustsExpiredInFlightAttempt() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("disable-crash", "thaap_test_disable_crash"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "disable-crash", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "disable-crash-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimedAt = DatabaseInstant.now().minusSeconds(60)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        WebhookDeliveryClaim claim = claimService.claimDue(claimedAt, 1).getFirst();
        assertThat(claimService.prepareForDispatch(claim, claimedAt.plusSeconds(1))).isTrue();
        endpointService.disable(
                merchantContext, endpoint.endpoint().getId(), "disable-crash-request");

        assertThat(claimService.claimDue(claimedAt.plusSeconds(32), 1)).isEmpty();

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        WebhookDeliveryAttempt attempt = attemptRepository
                .findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 1)
                .orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(
                WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getOutcome()).isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(attempt.getHttpRequestSent()).isNull();
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 2))
                .isEmpty();
    }

    @Test
    void expiredFifthAttemptLeaseExhaustsWithoutCreatingAttemptSix() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("lease-five", "thaap_test_lease_five"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "lease-five", merchant.getApiKeyPrefix());
        endpointService.create(merchantContext, "http://127.0.0.1:9191/thaap",
                Set.of("refund.created"), "lease-five-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant claimAt = DatabaseInstant.now().minusSeconds(300);
        double unknownBefore = meterRegistry.counter("thaap.webhook.attempts",
                "event.type", "refund.created", "outcome", "OUTCOME_UNKNOWN").count();
        double exhaustedBefore = meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count();
        double leaseExpiredBefore = meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "LEASE_EXPIRED").count();

        for (int attempt = 1; attempt <= WebhookRetryPolicy.MAX_ATTEMPTS; attempt++) {
            List<WebhookDeliveryClaim> claims = claimService.claimDue(claimAt, 1);
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().attemptNumber()).isEqualTo(attempt);
            assertThat(claimService.prepareForDispatch(
                    claims.getFirst(), claimAt.plusSeconds(1))).isTrue();
            claimAt = claimAt.plusSeconds(32);
        }

        assertThat(claimService.claimDue(claimAt, 1)).isEmpty();
        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(WebhookRetryPolicy.MAX_ATTEMPTS);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.LEASE_EXPIRED);
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(
                delivery.getId(), WebhookRetryPolicy.MAX_ATTEMPTS).orElseThrow().getOutcome())
                .isEqualTo(WebhookAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(attemptRepository.findByWebhookDeliveryIdAndAttemptNumber(delivery.getId(), 6)).isEmpty();
        assertThat(meterRegistry.counter("thaap.webhook.attempts",
                "event.type", "refund.created", "outcome", "OUTCOME_UNKNOWN").count() - unknownBefore)
                .isEqualTo(5.0);
        assertThat(meterRegistry.counter("thaap.webhook.delivery.terminal",
                "event.type", "refund.created", "status", "EXHAUSTED").count() - exhaustedBefore)
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("thaap.webhook.failures",
                "event.type", "refund.created", "error.code", "LEASE_EXPIRED").count()
                - leaseExpiredBefore).isEqualTo(5.0);
    }

    @Test
    void fiveFailedHttpAttemptsExhaustWithoutCreatingNewEventOrDelivery() throws Exception {
        AtomicInteger receivedCount = new AtomicInteger();
        callbackServer = callbackServer(exchange -> {
            receivedCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("exhaust-merchant", "thaap_test_exhaust"));
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), "exhaust-merchant", merchant.getApiKeyPrefix());
        endpointService.create(merchantContext,
                "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/thaap",
                Set.of("refund.created"), "exhaust-endpoint");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                deliveryService.createDeliveriesInCurrentTransaction(
                        merchant.getId(), "refund.created", java.util.UUID.randomUUID()));
        WebhookDelivery delivery = deliveryRepository.findAll().getFirst();
        Instant dueAt = DatabaseInstant.now();

        for (int attempt = 1; attempt <= WebhookRetryPolicy.MAX_ATTEMPTS; attempt++) {
            assertThat(executor.processDueDeliveries(dueAt, 1)).isEqualTo(1);
            WebhookDelivery current = deliveryRepository.findById(delivery.getId()).orElseThrow();
            if (attempt < WebhookRetryPolicy.MAX_ATTEMPTS) {
                assertThat(current.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_SCHEDULED);
                Instant fastForwardedDueAt = DatabaseInstant.now().minusSeconds(1);
                jdbcTemplate.update(
                        "UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(fastForwardedDueAt), delivery.getId());
                dueAt = DatabaseInstant.now();
            }
        }

        WebhookDelivery exhausted = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(5);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(exhausted.getLastErrorCode()).isEqualTo(WebhookDeliveryErrorCode.HTTP_NON_SUCCESS);
        assertThat(attemptRepository.count()).isEqualTo(5);
        assertThat(receivedCount).hasValue(5);
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
    }

    private void assertSignature(String rawSecret, ReceivedRequest request) {
        String[] fields = request.signature().split(",");
        long timestamp = Long.parseLong(fields[0].substring("t=".length()));
        String signature = fields[1].substring("v1=".length());
        assertThat(signer.verify(rawSecret, request.body(), timestamp, signature)).isTrue();
    }

    private HttpServer callbackServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/thaap", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private ReceivedRequest received(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new ReceivedRequest(
                body,
                exchange.getRequestHeaders().getFirst("THAAP-Event-Id"),
                exchange.getRequestHeaders().getFirst("THAAP-Delivery-Id"),
                exchange.getRequestHeaders().getFirst("THAAP-Signature"));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record ReceivedRequest(String body, String eventId, String deliveryId, String signature) {
    }
}
