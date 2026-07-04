package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.thaap.payments.security.SecretHasher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class WebhookSecurityAndRetryTests {

    private static final String ENCODED_KEY = Base64.getEncoder().encodeToString(
            "test-webhook-key-32-bytes-123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void encryptedSecretRequiresMatchingTenantEndpointAndAuthenticatedCiphertext() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(ENCODED_KEY, "test-v1", new SecureRandom());
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String rawSecret = "whsec_test-secret";
        WebhookEncryptedSecret encrypted = cipher.encrypt(rawSecret, merchantId, endpointId);
        WebhookEndpoint endpoint = new WebhookEndpoint(endpointId, merchantId, "http://127.0.0.1/webhook",
                rawSecret, encrypted, Instant.parse("2026-06-28T10:00:00Z"));

        assertThat(cipher.decrypt(endpoint)).isEqualTo(rawSecret);

        WebhookEndpoint wrongTenant = new WebhookEndpoint(endpointId, UUID.randomUUID(),
                "http://127.0.0.1/webhook", rawSecret, encrypted, Instant.parse("2026-06-28T10:00:00Z"));
        assertThatThrownBy(() -> cipher.decrypt(wrongTenant))
                .isInstanceOf(WebhookSecretException.class)
                .extracting(exception -> ((WebhookSecretException) exception).getErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.SIGNING_SECRET_TAMPERED);

        WebhookEncryptedSecret tampered = new WebhookEncryptedSecret(
                flipLastCharacter(encrypted.ciphertext()), encrypted.nonce(), encrypted.keyVersion());
        WebhookEndpoint tamperedEndpoint = new WebhookEndpoint(endpointId, merchantId,
                "http://127.0.0.1/webhook", rawSecret, tampered, Instant.parse("2026-06-28T10:00:00Z"));
        assertThatThrownBy(() -> cipher.decrypt(tamperedEndpoint))
                .isInstanceOf(WebhookSecretException.class);

        String otherEncodedKey = Base64.getEncoder().encodeToString(
                "other-webhook-key-32-bytes-12345".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WebhookSecretCipher wrongKeyCipher = new WebhookSecretCipher(
                otherEncodedKey, "test-v1", new SecureRandom());
        assertThatThrownBy(() -> wrongKeyCipher.decrypt(endpoint))
                .isInstanceOf(WebhookSecretException.class)
                .extracting(exception -> ((WebhookSecretException) exception).getErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.SIGNING_SECRET_TAMPERED);
    }

    @Test
    void allZeroWebhookEncryptionKeyFailsClosed() {
        String allZeroKey = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new WebhookSecretCipher(allZeroKey, "test-v1", new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret-encryption-key");
    }

    @Test
    void signerBindsTimestampAndExactPayloadBytes() {
        WebhookSigner signer = new WebhookSigner();
        Instant timestamp = Instant.parse("2026-06-28T10:15:30Z");
        String payload = "{\"id\":\"event-1\"}";

        WebhookSignature signature = signer.sign("whsec_test", payload, timestamp);

        assertThat(signature.headerValue())
                .isEqualTo("t=1782641730,v1=" + signature.value());
        assertThat(signer.verify("whsec_test", payload, signature.timestampSeconds(), signature.value())).isTrue();
        assertThat(signer.verify("whsec_test", payload + " ", signature.timestampSeconds(), signature.value()))
                .isFalse();
        assertThat(signer.verify("whsec_wrong", payload, signature.timestampSeconds(), signature.value())).isFalse();
        assertThat(signer.verify("whsec_test", payload, signature.timestampSeconds() + 1, signature.value())).isFalse();
    }

    @Test
    void eventEnvelopeUsesOnlyCanonicalAllowlistedFieldsAndMatchingHash() throws Exception {
        UUID merchantId = UUID.fromString("00000000-0000-0000-0000-000000000019");
        UUID resourceId = UUID.fromString("00000000-0000-0000-0000-000000000119");
        Instant occurredAt = Instant.parse("2026-06-28T10:15:30Z");
        WebhookEvent event = new WebhookEventEnvelopeFactory(OBJECT_MAPPER).create(
                merchantId, WebhookEventType.REFUND_CREATED, resourceId, occurredAt);

        assertThat(event.getPayload()).isEqualTo(
                "{\"id\":\"" + event.getId()
                        + "\",\"type\":\"refund.created\""
                        + ",\"createdAt\":\"2026-06-28T10:15:30Z\""
                        + ",\"apiVersion\":\"v1\""
                        + ",\"merchantId\":\"00000000-0000-0000-0000-000000000019\""
                        + ",\"trafficOrigin\":\"MERCHANT_API\""
                        + ",\"data\":{\"object\":{\"id\":\"00000000-0000-0000-0000-000000000119\""
                        + ",\"type\":\"REFUND\"}}}");
        JsonNode payload = OBJECT_MAPPER.readTree(event.getPayload());
        assertThat(payload.get("id").asText()).isEqualTo(event.getId().toString());
        assertThat(payload.get("type").asText()).isEqualTo("refund.created");
        assertThat(payload.get("createdAt").asText()).isEqualTo("2026-06-28T10:15:30Z");
        assertThat(payload.get("apiVersion").asText()).isEqualTo("v1");
        assertThat(payload.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(payload.get("trafficOrigin").asText()).isEqualTo("MERCHANT_API");
        assertThat(payload.path("data").path("object").get("id").asText())
                .isEqualTo(resourceId.toString());
        assertThat(payload.path("data").path("object").get("type").asText()).isEqualTo("REFUND");
        List<String> fieldNames = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly(
                "id", "type", "createdAt", "apiVersion", "merchantId",
                "trafficOrigin", "data");
        assertThat(event.getPayloadSha256()).isEqualTo(SecretHasher.sha256(event.getPayload()));
        assertThat(event.getPayload()).doesNotContain(
                "email", "phone", "card", "pan", "cvv", "fingerprint",
                "apiKey", "signingSecret", "idempotency", "metadata");
    }

    @Test
    void eventEnvelopeNormalizesTimestampToDatabasePrecisionBeforeSigning() {
        Instant nanosecondTime = Instant.parse("2026-06-28T10:15:30.123456789Z");

        WebhookEvent event = new WebhookEventEnvelopeFactory(OBJECT_MAPPER).create(
                UUID.randomUUID(), WebhookEventType.PAYMENT_INTENT_CREATED,
                UUID.randomUUID(), nanosecondTime);

        assertThat(event.getOccurredAt())
                .isEqualTo(Instant.parse("2026-06-28T10:15:30.123456Z"));
        assertThat(event.getCreatedAt()).isEqualTo(event.getOccurredAt());
        assertThat(event.getPayload())
                .contains("\"createdAt\":\"2026-06-28T10:15:30.123456Z\"");
        assertThat(event.getPayloadSha256()).isEqualTo(SecretHasher.sha256(event.getPayload()));
    }

    @Test
    void eventEnvelopeRejectsPayloadLargerThanSignedFixtureBoundary() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        String oversizedPayload = "\u00e9".repeat((WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES / 2) + 1);
        when(objectMapper.writeValueAsString(any())).thenReturn(oversizedPayload);

        WebhookEventEnvelopeFactory factory = new WebhookEventEnvelopeFactory(objectMapper);

        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), WebhookEventType.PAYMENT_INTENT_CREATED,
                UUID.randomUUID(), Instant.parse("2026-06-28T10:15:30Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded " + WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES)
                .hasMessageContaining("UTF-8 bytes");
    }

    @Test
    void localTargetPolicyAllowsOnlyLoopbackAndRejectsUrlConfusion() {
        WebhookTargetPolicy policy = new WebhookTargetPolicy(true, Set.of());

        ValidatedWebhookTarget target = policy.validate("http://127.0.0.1:9080/webhook?source=thaap");

        assertThat(target.address().isLoopbackAddress()).isTrue();
        assertThat(target.port()).isEqualTo(9080);
        assertThatThrownBy(() -> policy.validate("http://10.0.0.2/webhook"))
                .isInstanceOf(WebhookTargetPolicyException.class);
        assertThatThrownBy(() -> policy.validate("http://user@127.0.0.1/webhook"))
                .isInstanceOf(WebhookTargetPolicyException.class);
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1/webhook#fragment"))
                .isInstanceOf(WebhookTargetPolicyException.class);
        assertThatThrownBy(() -> policy.validate("file:///etc/passwd"))
                .isInstanceOf(WebhookTargetPolicyException.class);
    }

    @Test
    void nonLocalTargetPolicyRequiresHttpsAllowlistAndPublicAddress() {
        WebhookTargetPolicy noAllowlist = new WebhookTargetPolicy(false, Set.of());
        assertThatThrownBy(() -> noAllowlist.validate("https://example.com/webhook"))
                .isInstanceOf(WebhookTargetPolicyException.class)
                .hasMessageContaining("allowlisted");

        WebhookTargetPolicy metadataAllowlist = new WebhookTargetPolicy(false, Set.of("169.254.169.254"));
        assertThatThrownBy(() -> metadataAllowlist.validate("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(WebhookTargetPolicyException.class)
                .hasMessageContaining("prohibited network");

        WebhookTargetPolicy loopbackAllowlist = new WebhookTargetPolicy(false, Set.of("127.0.0.1"));
        assertThatThrownBy(() -> loopbackAllowlist.validate("http://127.0.0.1/webhook"))
                .isInstanceOf(WebhookTargetPolicyException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> loopbackAllowlist.validate("https://127.0.0.1/webhook"))
                .isInstanceOf(WebhookTargetPolicyException.class)
                .hasMessageContaining("prohibited network");
    }

    @Test
    void emptyDnsAnswerIsRetryableResolutionFailureNotTerminalTargetDenial() {
        WebhookTargetPolicy policy = new WebhookTargetPolicy(
                false, Set.of("callback.example"), host -> new InetAddress[0]);

        assertThatThrownBy(() -> policy.validate("https://callback.example/webhook"))
                .isInstanceOf(WebhookTargetResolutionException.class)
                .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
    }

    @Test
    void publicAddressPolicyRejectsSpecialIpv4AndIpv6Cidrs() throws Exception {
        WebhookTargetPolicy policy = new WebhookTargetPolicy(false, Set.of());

        assertThat(policy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(policy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        assertThat(policy.isPublic(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("198.18.0.1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("192.0.2.1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("240.0.0.1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("fd12:3456::1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("2001::1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("2001:db8::1"))).isFalse();
        assertThat(policy.isPublic(InetAddress.getByName("2002::1"))).isFalse();
    }

    @Test
    void responseClassificationTreatsOnlyEveryTwoHundredStatusAsSuccess() {
        assertThat(new WebhookHttpResponse(199).isSuccessful()).isFalse();
        assertThat(new WebhookHttpResponse(200).isSuccessful()).isTrue();
        assertThat(new WebhookHttpResponse(204).isSuccessful()).isTrue();
        assertThat(new WebhookHttpResponse(299).isSuccessful()).isTrue();
        assertThat(new WebhookHttpResponse(300).isSuccessful()).isFalse();
        assertThat(new WebhookHttpResponse(404).isSuccessful()).isFalse();
        assertThat(new WebhookHttpResponse(503).isSuccessful()).isFalse();
    }

    @Test
    void socketTransportNeverFollowsRedirectAndBoundsResponseWait() throws Exception {
        AtomicServer redirect = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            WebhookHttpResponse response = transport.send(requestFor(redirect.server()));
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(redirect.requestCount()).isEqualTo(1);
        } finally {
            redirect.server().stop(0);
        }

        AtomicServer slow = startServer(exchange -> {
            try {
                Thread.sleep(250);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try {
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofMillis(30));
            assertThatThrownBy(() -> transport.send(requestFor(slow.server())))
                    .isInstanceOf(WebhookTransportException.class)
                    .extracting(exception -> ((WebhookTransportException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.REQUEST_TIMEOUT);
        } finally {
            slow.server().stop(0);
        }
    }

    @Test
    void socketTransportPercentEncodesUnicodeRequestTargetWithoutChangingExistingEscapes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            Future<String> requestLine = serverExecutor.submit(() -> captureRequestLineAndRespond(server));
            java.net.URI uri = new java.net.URI(
                    "http://127.0.0.1:" + server.getLocalPort()
                            + "/caf\u00e9/already%20encoded?q=na\u00efve&literal=%2F");
            WebhookHttpRequest request = new WebhookHttpRequest(
                    new ValidatedWebhookTarget(uri, InetAddress.getByName("127.0.0.1"),
                            server.getLocalPort()),
                    UUID.randomUUID(), UUID.randomUUID(),
                    "t=1,v1=" + "a".repeat(64), "{}");
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThat(transport.send(request).statusCode()).isEqualTo(204);
            assertThat(requestLine.get()).isEqualTo(
                    "POST /caf%C3%A9/already%20encoded?q=na%C3%AFve&literal=%2F HTTP/1.1");
        }
    }

    @Test
    void socketTransportRejectsOutOfRangeNumericStatusAsTransportFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, "HTTP/1.1 999 Invalid\r\n\r\n"));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void socketTransportRejectsMalformedHttpVersionAsTransportFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, "HTTP/not-http 204 No Content\r\n\r\n"));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void socketTransportRejectsEveryMalformedStatusLineGrammarPartition() throws Exception {
        List<String> malformedStatusLines = List.of(
                "HTTP/1.1 204",
                "HTTP/1.1\t204 No Content",
                "HTTP/1.1  204 No Content",
                "HTTP/1.1 20 No Content",
                "HTTP/1.1 2000 No Content",
                "HTTP/1.1 2O4 No Content");

        for (String malformedStatusLine : malformedStatusLines) {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                    ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
                serverExecutor.submit(() -> sendRawResponse(
                        server, malformedStatusLine + "\r\n\r\n"));
                SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                        Duration.ofSeconds(1), Duration.ofSeconds(1));

                assertThatThrownBy(() -> transport.send(requestFor(server)))
                        .as("malformed status line %s", malformedStatusLine)
                        .isInstanceOf(WebhookTransportException.class)
                        .satisfies(exception -> {
                            WebhookTransportException transportException =
                                    (WebhookTransportException) exception;
                            assertThat(transportException.getErrorCode())
                                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                            assertThat(transportException.getRequestSent()).isTrue();
                        });
            }
        }

        for (String validStatusLine : List.of("HTTP/1.0 204 ", "HTTP/1.1 204 ")) {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                    ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
                serverExecutor.submit(() -> sendRawResponse(
                        server, validStatusLine + "\r\n\r\n"));
                SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                        Duration.ofSeconds(1), Duration.ofSeconds(1));

                assertThat(transport.send(requestFor(server)).statusCode()).isEqualTo(204);
            }
        }
    }

    @Test
    void socketTransportRejectsControlBearingReasonPhrase() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server,
                    "HTTP/1.1 204 Invalid\u0001Reason\r\n\r\n"));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void responseHeaderGrammarClassifiesEveryOctetForTheAsciiContract() {
        String tokenPunctuation = "!#$%&'*+-.^_`|~";
        for (int codePoint = 0; codePoint <= 0xFF; codePoint++) {
            char candidate = (char) codePoint;
            if (candidate != ':') {
                boolean expectedNameCharacter =
                        (candidate >= 'a' && candidate <= 'z')
                                || (candidate >= 'A' && candidate <= 'Z')
                                || (candidate >= '0' && candidate <= '9')
                                || tokenPunctuation.indexOf(candidate) >= 0;
                assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine(
                        "X" + candidate + "Name: value"))
                        .as("header-name octet 0x%02X", codePoint)
                        .isEqualTo(expectedNameCharacter);
            }

            boolean expectedValueCharacter =
                    candidate == '\t' || (candidate >= 0x20 && candidate <= 0x7E);
            assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine(
                    "X-Test: before" + candidate + "after"))
                    .as("header-value octet 0x%02X", codePoint)
                    .isEqualTo(expectedValueCharacter);
        }

        assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine(
                tokenPunctuation + "Az09:")).isTrue();
        assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine("No-Colon")).isFalse();
        assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine("Bad Name: value")).isFalse();
        assertThat(SocketWebhookHttpTransport.isValidResponseHeaderLine(" continuation")).isFalse();
    }

    @Test
    void socketTransportRejectsMalformedHeaderFieldsBeforeAcknowledgingSuccess() throws Exception {
        List<String> malformedHeaders = List.of(
                "No-Colon",
                "Bad Name: value",
                "Bad(Name: value",
                "X-Test: before\u0001after",
                " continuation");

        for (String malformedHeader : malformedHeaders) {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                    ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
                serverExecutor.submit(() -> sendRawResponse(server,
                        "HTTP/1.1 204 No Content\r\n" + malformedHeader + "\r\n\r\n"));
                SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                        Duration.ofSeconds(1), Duration.ofSeconds(1));

                assertThatThrownBy(() -> transport.send(requestFor(server)))
                        .as("malformed response header %s", malformedHeader)
                        .isInstanceOf(WebhookTransportException.class)
                        .satisfies(exception -> {
                            WebhookTransportException transportException =
                                    (WebhookTransportException) exception;
                            assertThat(transportException.getErrorCode())
                                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                            assertThat(transportException.getRequestSent()).isTrue();
                        });
            }
        }

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            byte[] nonAsciiField = "HTTP/1.1 204 No Content\r\nX-Test: before\u0080after\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            serverExecutor.submit(() -> sendRawResponse(server, nonAsciiField));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .extracting(exception -> ((WebhookTransportException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        }
    }

    @Test
    void webhookTimeoutConfigurationRejectsEveryUnrepresentableOrUnboundedDuration() {
        assertThat(SocketWebhookHttpTransport.checkedMillis(
                Duration.ofMillis(1), "connect timeout")).isEqualTo(1);
        assertThat(SocketWebhookHttpTransport.checkedMillis(
                Duration.ofMillis(Integer.MAX_VALUE), "request timeout"))
                .isEqualTo(Integer.MAX_VALUE);

        List<Duration> invalidDurations = List.of(
                Duration.ZERO,
                Duration.ofNanos(1),
                Duration.ofNanos(999_999),
                Duration.ofNanos(1_000_001),
                Duration.ofMillis(-1),
                Duration.ofMillis((long) Integer.MAX_VALUE + 1),
                Duration.ofMillis(Integer.MAX_VALUE).plusNanos(1),
                Duration.ofSeconds(Long.MIN_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE));
        for (Duration invalidDuration : invalidDurations) {
            assertThatThrownBy(() -> SocketWebhookHttpTransport.checkedMillis(
                    invalidDuration, "request timeout"))
                    .as("invalid socket duration %s", invalidDuration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Webhook request timeout");
        }
        assertThatThrownBy(() -> SocketWebhookHttpTransport.checkedMillis(
                null, "request timeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 millisecond");
        assertThatThrownBy(() -> new SocketWebhookHttpTransport(
                Duration.ofNanos(999_999), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocketWebhookHttpTransport(
                Duration.ofSeconds(1), Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class);

        WebhookHostResolver maximumResolver = new WebhookHostResolver(
                Duration.ofMinutes(1), 1, host -> new InetAddress[0]);
        maximumResolver.close();
        for (Duration invalidResolutionTimeout : List.of(
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.ofNanos(999_999),
                Duration.ofNanos(1_000_001),
                Duration.ofMinutes(1).plusMillis(1),
                Duration.ofSeconds(Long.MIN_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new WebhookHostResolver(
                    invalidResolutionTimeout, 1, host -> new InetAddress[0]))
                    .as("invalid resolver duration %s", invalidResolutionTimeout)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Webhook target resolution timeout");
        }
        assertThatThrownBy(() -> new WebhookHostResolver(
                null, 1, host -> new InetAddress[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Webhook target resolution timeout");
    }

    @Test
    void socketTransportConsumesInformationalResponseBeforeFinalStatus() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server,
                    "HTTP/1.1 100 Continue\r\nX-Provisional: true\r\n\r\n"
                            + "HTTP/1.1 204 No Content\r\n\r\n"));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThat(transport.send(requestFor(server)).statusCode()).isEqualTo(204);
        }
    }

    @Test
    void socketTransportRejectsTruncatedFinalResponseHead() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, "HTTP/1.1 204 No Content\r\n"));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void socketTransportBoundsFinalResponseHeaders() throws Exception {
        String exactBoundedHead = responseHeadOfSize(SocketWebhookHttpTransport.MAX_RESPONSE_HEAD_BYTES);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, exactBoundedHead));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThat(transport.send(requestFor(server)).statusCode()).isEqualTo(204);
        }

        String oversizedHead = responseHeadOfSize(
                SocketWebhookHttpTransport.MAX_RESPONSE_HEAD_BYTES + 1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, oversizedHead));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void socketTransportRejectsOversizedPayloadBeforeConnecting() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        java.net.URI uri = new java.net.URI("http://127.0.0.1:1/webhook");
        WebhookHttpRequest request = new WebhookHttpRequest(
                new ValidatedWebhookTarget(uri, loopback, 1),
                UUID.randomUUID(), UUID.randomUUID(),
                "t=1,v1=" + "a".repeat(64),
                "x".repeat(WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES + 1));
        SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> transport.send(request))
                .isInstanceOf(WebhookTransportException.class)
                .satisfies(exception -> {
                    WebhookTransportException transportException =
                            (WebhookTransportException) exception;
                    assertThat(transportException.getErrorCode())
                            .isEqualTo(WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED);
                    assertThat(transportException.getRequestSent()).isFalse();
                });
    }

    @Test
    void socketTransportUsesOneResponseHeadBudgetAcrossInformationalAndFinalSections() throws Exception {
        String informational = responseHeadOfSize(
                "HTTP/1.1 100 Continue\r\n", 20_000);
        String finalResponse = responseHeadOfSize(20_000);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> sendRawResponse(server, informational + finalResponse));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> transport.send(requestFor(server)))
                    .isInstanceOf(WebhookTransportException.class)
                    .satisfies(exception -> {
                        WebhookTransportException transportException =
                                (WebhookTransportException) exception;
                        assertThat(transportException.getErrorCode())
                                .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
                        assertThat(transportException.getRequestSent()).isTrue();
                    });
        }
    }

    @Test
    void hostHeaderBracketsOnlyIpv6LiteralsNotHostnamesResolvedToIpv6() throws Exception {
        SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        InetAddress ipv6Address = InetAddress.getByName("2001:4860:4860::8888");

        ValidatedWebhookTarget hostnameTarget = new ValidatedWebhookTarget(
                new java.net.URI("https://merchant.example:8443/webhook"), ipv6Address, 8443);
        ValidatedWebhookTarget literalTarget = new ValidatedWebhookTarget(
                new java.net.URI("https://[2001:4860:4860::8888]:8443/webhook"), ipv6Address, 8443);

        assertThat(transport.hostHeader(hostnameTarget)).isEqualTo("merchant.example:8443");
        assertThat(transport.hostHeader(literalTarget))
                .isEqualTo("[2001:4860:4860::8888]:8443");
    }

    @Test
    void socketTransportEnforcesWallClockDeadlineAgainstTrickledStatusLine() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                ExecutorService serverExecutor = Executors.newSingleThreadExecutor()) {
            serverExecutor.submit(() -> trickleStatusLine(server));
            SocketWebhookHttpTransport transport = new SocketWebhookHttpTransport(
                    Duration.ofSeconds(1), Duration.ofMillis(80));
            java.net.URI uri = new java.net.URI(
                    "http://127.0.0.1:" + server.getLocalPort() + "/webhook");
            WebhookHttpRequest request = new WebhookHttpRequest(
                    new ValidatedWebhookTarget(uri, loopback, server.getLocalPort()),
                    UUID.randomUUID(), UUID.randomUUID(), "t=1,v1=" + "a".repeat(64), "{}");
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> transport.send(request))
                    .isInstanceOf(WebhookTransportException.class)
                    .extracting(exception -> ((WebhookTransportException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.REQUEST_TIMEOUT);

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofMillis(500));
        }
    }

    @Test
    void retryPolicyUsesExactBackoffScheduleAndStopsAfterFiveAttempts() {
        WebhookRetryPolicy policy = new WebhookRetryPolicy();

        assertThat(policy.delayBeforeNextAttempt(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayBeforeNextAttempt(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delayBeforeNextAttempt(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.delayBeforeNextAttempt(4)).isEqualTo(Duration.ofHours(2));
        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(5)).isTrue();
        assertThatThrownBy(() -> policy.delayBeforeNextAttempt(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledExecutionFailsClosedWithoutEncryptionKey() {
        WebhookSecretCipher unconfiguredCipher = new WebhookSecretCipher("", "v1", new SecureRandom());
        WebhookExecutionConfiguration configuration = new WebhookExecutionConfiguration(
                true, unconfiguredCipher, new WebhookTargetPolicy(true, Set.of()));

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-encryption-key");

        WebhookExecutionConfiguration noNonLocalAllowlist = new WebhookExecutionConfiguration(
                true, new WebhookSecretCipher(ENCODED_KEY, "v1", new SecureRandom()),
                new WebhookTargetPolicy(false, Set.of()));
        assertThatThrownBy(noNonLocalAllowlist::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-hosts");
    }

    @Test
    void targetResolutionDeadlineBoundsNonReturningResolver() {
        CountDownLatch releaseResolver = new CountDownLatch(1);
        WebhookHostResolver resolver = new WebhookHostResolver(
                Duration.ofMillis(30), 1, host -> {
                    while (releaseResolver.getCount() > 0) {
                        try {
                            releaseResolver.await();
                        } catch (InterruptedException ignored) {
                            // Model a platform resolver that does not honor cancellation.
                        }
                    }
                    return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                });
        long startedAt = System.nanoTime();
        try {
            assertThatThrownBy(() -> resolver.resolve("callback.example"))
                    .isInstanceOf(WebhookTargetResolutionException.class)
                    .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.CONNECT_TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofMillis(500));
            assertThatThrownBy(() -> resolver.resolve("queued.example"))
                    .isInstanceOf(WebhookTargetResolutionException.class)
                    .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.CONNECT_TIMEOUT);
            assertThatThrownBy(() -> resolver.resolve("rejected.example"))
                    .isInstanceOf(WebhookTargetResolutionException.class)
                    .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        } finally {
            releaseResolver.countDown();
            resolver.close();
        }
    }

    @Test
    void unknownHostAndResolverFailuresRemainRetryableKnownNoSendOutcomes() {
        WebhookHostResolver unknownHostResolver = new WebhookHostResolver(
                Duration.ofSeconds(1), 1, host -> {
                    throw new java.net.UnknownHostException(host);
                });
        WebhookHostResolver failedResolver = new WebhookHostResolver(
                Duration.ofSeconds(1), 1, host -> {
                    throw new IOException("resolver unavailable");
                });
        try {
            assertThatThrownBy(() -> unknownHostResolver.resolve("missing.example"))
                    .isInstanceOf(WebhookTargetResolutionException.class)
                    .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
            assertThatThrownBy(() -> failedResolver.resolve("callback.example"))
                    .isInstanceOf(WebhookTargetResolutionException.class)
                    .extracting(exception -> ((WebhookTargetResolutionException) exception).getErrorCode())
                    .isEqualTo(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        } finally {
            unknownHostResolver.close();
            failedResolver.close();
        }
    }

    @Test
    void enabledExecutionFailsClosedWhenPersistedEndpointKeyVersionIsUnavailable() {
        WebhookEndpointRepository endpointRepository = mock(WebhookEndpointRepository.class);
        Instant validationTime = Instant.parse("2026-06-28T10:00:00Z");
        when(endpointRepository.findRequiredSecretKeyVersions(validationTime))
                .thenReturn(java.util.List.of(
                        "retired-current-v0",
                        "retired-previous-v0",
                        "retired-replay-v0"));
        WebhookExecutionConfiguration configuration = new WebhookExecutionConfiguration(
                true, new WebhookSecretCipher(ENCODED_KEY, "current-v1", new SecureRandom()),
                new WebhookTargetPolicy(true, Set.of()), endpointRepository,
                Clock.fixed(validationTime, ZoneOffset.UTC),
                Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired-");
    }

    @Test
    void versionedKeyringDecryptsAndLazilyReencryptsWithCurrentKey() {
        String nextEncodedKey = Base64.getEncoder().encodeToString(
                "next-webhook-key-32-bytes-123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String rawSecret = "whsec_rotation-test";
        WebhookSecretCipher oldCipher = new WebhookSecretCipher(
                ENCODED_KEY, "old-v1", new SecureRandom());
        WebhookEncryptedSecret oldEncrypted = oldCipher.encrypt(rawSecret, merchantId, endpointId);
        WebhookEndpoint endpoint = new WebhookEndpoint(endpointId, merchantId,
                "http://127.0.0.1/webhook", rawSecret, oldEncrypted,
                Instant.parse("2026-06-28T10:00:00Z"));
        WebhookSecretCipher rotatingCipher = new WebhookSecretCipher(
                nextEncodedKey, "current-v2", "old-v1=" + ENCODED_KEY, new SecureRandom());
        WebhookEndpointRepository endpointRepository = mock(WebhookEndpointRepository.class);
        when(endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId))
                .thenReturn(Optional.of(endpoint));
        WebhookEndpointSecretService secretService = new WebhookEndpointSecretService(
                endpointRepository, rotatingCipher);

        assertThat(secretService.loadForDelivery(merchantId, endpointId)).isEqualTo(rawSecret);
        assertThat(endpoint.getSigningSecretKeyVersion()).isEqualTo("current-v2");
        assertThat(rotatingCipher.decrypt(endpoint)).isEqualTo(rawSecret);
    }

    @Test
    void disabledEndpointCannotLoadDeliverySecretOrTriggerDecrypt() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String rawSecret = "whsec_disabled-test";
        WebhookSecretCipher creationCipher = new WebhookSecretCipher(
                ENCODED_KEY, "current-v1", new SecureRandom());
        WebhookEncryptedSecret encrypted = creationCipher.encrypt(rawSecret, merchantId, endpointId);
        WebhookEndpoint endpoint = new WebhookEndpoint(endpointId, merchantId,
                "http://127.0.0.1/webhook", rawSecret, encrypted,
                Instant.parse("2026-06-28T10:00:00Z"));
        endpoint.disable();
        WebhookEndpointRepository endpointRepository = mock(WebhookEndpointRepository.class);
        when(endpointRepository.findByIdAndMerchantIdForUpdate(endpointId, merchantId))
                .thenReturn(Optional.of(endpoint));
        WebhookSecretCipher deliveryCipher = mock(WebhookSecretCipher.class);
        WebhookEndpointSecretService secretService = new WebhookEndpointSecretService(
                endpointRepository, deliveryCipher);

        assertThatThrownBy(() -> secretService.loadForDelivery(merchantId, endpointId))
                .isInstanceOf(WebhookSecretException.class)
                .extracting(exception -> ((WebhookSecretException) exception).getErrorCode())
                .isEqualTo(WebhookDeliveryErrorCode.ENDPOINT_DISABLED);
        verifyNoInteractions(deliveryCipher);
    }

    private void trickleStatusLine(ServerSocket server) {
        try (Socket socket = server.accept()) {
            socket.getInputStream().read();
            byte[] statusLine = "HTTP/1.1 204 No Content\r\n".getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
            for (byte value : statusLine) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
                Thread.sleep(20);
            }
        } catch (IOException ignored) {
            // The client is expected to close the socket when its request deadline expires.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendRawResponse(ServerSocket server, String response) {
        sendRawResponse(server, response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private void sendRawResponse(ServerSocket server, byte[] response) {
        try (Socket socket = server.accept()) {
            socket.getInputStream().read();
            socket.getOutputStream().write(response);
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
            // The client owns the assertion for malformed or timed-out protocol behavior.
        }
    }

    private String captureRequestLineAndRespond(ServerSocket server) throws IOException {
        try (Socket socket = server.accept()) {
            java.io.BufferedInputStream input = new java.io.BufferedInputStream(socket.getInputStream());
            java.io.ByteArrayOutputStream requestLine = new java.io.ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) {
                    throw new IOException("Webhook request ended before its request-line boundary");
                }
                if (previous == '\r' && current == '\n') {
                    break;
                }
                requestLine.write(current);
                previous = current;
            }
            java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
            int firstPrevious = -1;
            int secondPrevious = -1;
            int thirdPrevious = -1;
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
                    break;
                }
                firstPrevious = secondPrevious;
                secondPrevious = thirdPrevious;
                thirdPrevious = current;
            }
            String headers = headerBytes.toString(java.nio.charset.StandardCharsets.US_ASCII);
            int contentLength = -1;
            for (String line : headers.split("\r\n")) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    contentLength = Integer.parseInt(
                            line.substring("Content-Length:".length()).trim());
                }
            }
            if (contentLength < 0 || input.readNBytes(contentLength).length != contentLength) {
                throw new IOException("Webhook request body was incomplete");
            }
            socket.getOutputStream().write(
                    "HTTP/1.1 204 No Content\r\n\r\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String captured = requestLine.toString(java.nio.charset.StandardCharsets.US_ASCII);
            return captured.substring(0, captured.length() - 1);
        }
    }

    private String responseHeadOfSize(int targetBytes) {
        return responseHeadOfSize("HTTP/1.1 204 No Content\r\n", targetBytes);
    }

    private String responseHeadOfSize(String statusLine, int targetBytes) {
        int remainingHeaderBytes = targetBytes
                - statusLine.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length
                - 2;
        if (remainingHeaderBytes < 0) {
            throw new IllegalArgumentException("Response head target is too small");
        }
        StringBuilder response = new StringBuilder(statusLine);
        while (remainingHeaderBytes > 0) {
            int lineBytes = Math.min(SocketWebhookHttpTransport.MAX_LINE_BYTES, remainingHeaderBytes);
            int bytesAfterLine = remainingHeaderBytes - lineBytes;
            if (bytesAfterLine > 0 && bytesAfterLine < 4) {
                lineBytes -= 4 - bytesAfterLine;
            }
            if (lineBytes < 4) {
                throw new IllegalArgumentException("Response head target cannot form a complete header line");
            }
            response.append("X:");
            response.append("a".repeat(lineBytes - 4));
            response.append("\r\n");
            remainingHeaderBytes -= lineBytes;
        }
        response.append("\r\n");
        assertThat(response.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .hasSize(targetBytes);
        return response.toString();
    }

    private String flipLastCharacter(String value) {
        char replacement = value.charAt(value.length() - 1) == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }

    private WebhookHttpRequest requestFor(HttpServer server) throws Exception {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        java.net.URI uri = new java.net.URI(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
        return new WebhookHttpRequest(new ValidatedWebhookTarget(
                uri, address, server.getAddress().getPort()), UUID.randomUUID(), UUID.randomUUID(),
                "t=1,v1=" + "a".repeat(64), "{}");
    }

    private WebhookHttpRequest requestFor(ServerSocket server) throws Exception {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        java.net.URI uri = new java.net.URI(
                "http://127.0.0.1:" + server.getLocalPort() + "/webhook");
        return new WebhookHttpRequest(new ValidatedWebhookTarget(
                uri, address, server.getLocalPort()), UUID.randomUUID(), UUID.randomUUID(),
                "t=1,v1=" + "a".repeat(64), "{}");
    }

    private AtomicServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            requests.incrementAndGet();
            handler.handle(exchange);
        });
        server.createContext("/redirect-target", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return new AtomicServer(server, requests);
    }

    private record AtomicServer(HttpServer server, java.util.concurrent.atomic.AtomicInteger requests) {
        int requestCount() {
            return requests.get();
        }
    }
}
