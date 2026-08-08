package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class WebhookOperationsContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebhookEndpointService endpointService;
    @Autowired private WebhookEndpointRepository endpointRepository;
    @Autowired private WebhookEventRepository eventRepository;
    @Autowired private WebhookDeliveryRepository deliveryRepository;
    @Autowired private WebhookEndpointVerificationService verificationService;
    @Autowired private WebhookOperationsHealthIndicator healthIndicator;
    @Autowired private WebhookSigner signer;

    @MockitoBean
    private WebhookHttpTransport transport;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        reset(transport);
    }

    @Test
    void signedVerificationIsExactIdempotentTenantBoundAndSecretSafe() throws Exception {
        MerchantAccess owner = createMerchant("webhook-verification-owner");
        MerchantAccess foreign = createMerchant("webhook-verification-foreign");
        EndpointAccess endpoint = createEndpoint(owner, "verification-owner");
        AtomicReference<WebhookHttpRequest> dispatched = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        when(transport.send(any())).thenAnswer(invocation -> {
            WebhookHttpRequest request = invocation.getArgument(0);
            dispatched.set(request);
            calls.incrementAndGet();
            String token = objectMapper.readTree(request.payload()).get("challenge").asText();
            return new WebhookHttpResponse(204, token);
        });

        String firstBytes = mockMvc.perform(post(
                        "/webhook_endpoints/{endpointId}/verification_challenges", endpoint.id())
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "verify-once"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replayBytes = mockMvc.perform(post(
                        "/webhook_endpoints/{endpointId}/verification_challenges", endpoint.id())
                        .header(API_KEY, owner.apiKey()).header(IDEMPOTENCY_KEY, "verify-once"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(replayBytes).isEqualTo(firstBytes);
        assertThat(calls).hasValue(1);
        JsonNode verified = objectMapper.readTree(firstBytes);
        assertThat(verified.get("lifecycleStatus").asText()).isEqualTo("VERIFIED");
        assertThat(verified.get("responseStatus").asInt()).isEqualTo(204);
        assertThat(firstBytes).doesNotContain("challenge", "nonce", endpoint.signingSecret(), endpoint.url());
        JsonNode payload = objectMapper.readTree(dispatched.get().payload());
        String token = payload.get("challenge").asText();
        assertThat(token).hasSize(43);
        assertThat(payload.get("endpointId").asText()).isEqualTo(endpoint.id().toString());
        assertThat(payload.get("signingSecretGeneration").asText())
                .isEqualTo(endpointRepository.findById(endpoint.id()).orElseThrow()
                        .getSigningSecretGeneration().toString());
        String[] signatureParts = dispatched.get().signatureHeader().split(",");
        long timestamp = Long.parseLong(signatureParts[0].substring(2));
        String signature = signatureParts[1].substring(3);
        assertThat(signer.verify(endpoint.signingSecret(), dispatched.get().payload(),
                timestamp, signature)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT nonce_sha256 FROM webhook_endpoint_verifications WHERE id = ?
                """, String.class, UUID.fromString(verified.get("id").asText())))
                .isEqualTo(SecretHasher.sha256(token)).doesNotContain(token);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_endpoint_verifications
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE before_json LIKE ? OR after_json LIKE ?
                """, Integer.class, "%" + token + "%", "%" + token + "%")).isZero();

        mockMvc.perform(get(
                        "/webhook_endpoints/{endpointId}/verification_challenges/{challengeId}",
                        endpoint.id(), verified.get("id").asText())
                        .header(API_KEY, foreign.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void verificationFailsClosedForWrongProofAndConcurrentSecretRotation() throws Exception {
        MerchantAccess merchant = createMerchant("webhook-verification-races");
        EndpointAccess wrongProof = createEndpoint(merchant, "verification-wrong-proof");
        when(transport.send(any())).thenReturn(new WebhookHttpResponse(204, "z".repeat(43)));

        JsonNode failed = json(post(
                        "/webhook_endpoints/{endpointId}/verification_challenges", wrongProof.id())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "wrong-proof"), 200);
        assertThat(failed.get("lifecycleStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureCode").asText()).isEqualTo("TOKEN_MISMATCH");

        EndpointAccess rotating = createEndpoint(merchant, "verification-rotation");
        MerchantContext merchantContext = new MerchantContext(
                merchant.id(), "webhook-verification-races", merchant.apiKey().substring(0, 8));
        when(transport.send(any())).thenAnswer(invocation -> {
            WebhookHttpRequest request = invocation.getArgument(0);
            String token = objectMapper.readTree(request.payload()).get("challenge").asText();
            endpointService.rotateSecret(merchantContext, rotating.id(), "rotate-during-verification");
            return new WebhookHttpResponse(204, token);
        });

        JsonNode rotated = json(post(
                        "/webhook_endpoints/{endpointId}/verification_challenges", rotating.id())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "rotation-race"), 200);
        assertThat(rotated.get("lifecycleStatus").asText()).isEqualTo("FAILED");
        assertThat(rotated.get("failureCode").asText()).isEqualTo("ENDPOINT_CHANGED");
        assertThat(endpointRepository.findById(rotating.id()).orElseThrow().getVerificationStatus())
                .isEqualTo(WebhookEndpointVerificationStatus.NOT_REQUESTED);
    }

    @Test
    void deadLettersAndManualRedeliveryAreBoundedAuthorizedAndReuseExactEvent() throws Exception {
        MerchantAccess merchant = createMerchant("webhook-redelivery-owner");
        MerchantAccess foreign = createMerchant("webhook-redelivery-foreign");
        OperatorAccess viewer = createOperator(merchant, "redelivery-viewer", "VIEWER");
        OperatorAccess analyst = createOperator(merchant, "redelivery-analyst", "ANALYST");
        OperatorAccess manager = createOperator(merchant, "redelivery-manager", "RISK_MANAGER");
        OperatorAccess foreignManager = createOperator(foreign, "foreign-manager", "RISK_MANAGER");
        EndpointAccess endpoint = createEndpoint(merchant, "redelivery-endpoint");
        WebhookEvent event = new WebhookEventEnvelopeFactory(objectMapper).create(
                merchant.id(), WebhookEventType.REFUND_CREATED, UUID.randomUUID(), DatabaseInstant.now());
        eventRepository.saveAndFlush(event);
        WebhookDelivery delivery = new WebhookDelivery(merchant.id(), endpoint.id(), event.getId(),
                event.getEventType(), event.getResourceId(), event.getOccurredAt());
        delivery.exhaustWithoutClaim(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        deliveryRepository.saveAndFlush(delivery);
        String originalPayload = event.getPayload();

        mockMvc.perform(get("/risk/webhook-dead-letters").header(RISK_KEY, viewer.secret()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/risk/webhook-dead-letters").header(RISK_KEY, analyst.secret()))
                .andExpect(status().isForbidden());
        String deadLetters = mockMvc.perform(get("/risk/webhook-dead-letters")
                        .header(RISK_KEY, manager.secret()).param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(deadLetters)).hasSize(1);
        assertThat(deadLetters).contains(delivery.getId().toString(), event.getId().toString())
                .doesNotContain(originalPayload, endpoint.url(), endpoint.signingSecret(), "payload");
        mockMvc.perform(get("/risk/webhook-dead-letters")
                        .header(RISK_KEY, manager.secret()).param("cursorCreatedAt", DatabaseInstant.now().toString()))
                .andExpect(status().isBadRequest());
        assertThat(objectMapper.readTree(mockMvc.perform(get("/risk/webhook-dead-letters")
                        .header(RISK_KEY, foreignManager.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEmpty();

        String first = mockMvc.perform(post("/webhook_deliveries/{id}/redeliver", delivery.getId())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "redeliver-once")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"RECEIVER_REPAIRED\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/webhook_deliveries/{id}/redeliver", delivery.getId())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "redeliver-once")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"RECEIVER_REPAIRED\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        JsonNode redelivery = objectMapper.readTree(first);
        assertThat(redelivery.get("requestedGeneration").asInt()).isEqualTo(2);
        assertThat(redelivery.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_redelivery_requests",
                Integer.class)).isOne();
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getPayload())
                .isEqualTo(originalPayload);
        assertThat(eventRepository.count()).isOne();

        WebhookEvent operatorEvent = new WebhookEventEnvelopeFactory(objectMapper).create(
                merchant.id(), WebhookEventType.REFUND_CREATED, UUID.randomUUID(), DatabaseInstant.now());
        eventRepository.saveAndFlush(operatorEvent);
        WebhookDelivery operatorDelivery = new WebhookDelivery(
                merchant.id(), endpoint.id(), operatorEvent.getId(), operatorEvent.getEventType(),
                operatorEvent.getResourceId(), operatorEvent.getOccurredAt());
        operatorDelivery.exhaustWithoutClaim(WebhookDeliveryErrorCode.TRANSPORT_ERROR);
        deliveryRepository.saveAndFlush(operatorDelivery);

        mockMvc.perform(post("/risk/webhook-deliveries/{id}/redeliver", operatorDelivery.getId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-redelivery")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"MANUAL_RETRY\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_redelivery_requests",
                Integer.class)).isOne();
        String operatorFirst = mockMvc.perform(post(
                        "/risk/webhook-deliveries/{id}/redeliver", operatorDelivery.getId())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-redelivery")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"MANUAL_RETRY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String operatorReplay = mockMvc.perform(post(
                        "/risk/webhook-deliveries/{id}/redeliver", operatorDelivery.getId())
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-redelivery")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"MANUAL_RETRY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(operatorReplay).isEqualTo(operatorFirst);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT actor_role FROM webhook_redelivery_requests
                 WHERE webhook_delivery_id = ?
                """, String.class, operatorDelivery.getId())).isEqualTo("RISK_MANAGER");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_redelivery_requests",
                Integer.class)).isEqualTo(2);

        mockMvc.perform(post("/webhook_deliveries/{id}/redeliver", delivery.getId())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "second-key")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"MANUAL_RETRY\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/risk/webhook-deliveries/{id}/redeliver", delivery.getId())
                        .header(RISK_KEY, foreignManager.secret()).header(IDEMPOTENCY_KEY, "foreign-key")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"MANUAL_RETRY\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/webhook_deliveries/{id}/redeliver", delivery.getId())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "unknown-field")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"MANUAL_RETRY\",\"payload\":\"leak\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expirySweepIsBoundedIdempotentAndReadinessDetectsStalePendingWork() throws Exception {
        MerchantAccess merchant = createMerchant("webhook-expiry");
        EndpointAccess endpointAccess = createEndpoint(merchant, "verification-expiry");
        WebhookEndpoint endpoint = endpointRepository.findById(endpointAccess.id()).orElseThrow();
        UUID challengeId = UUID.randomUUID();
        Instant createdAt = DatabaseInstant.now().minusSeconds(180);
        Instant expiresAt = createdAt.plusSeconds(60);
        jdbcTemplate.update("""
                INSERT INTO webhook_endpoint_verifications
                    (id, merchant_id, webhook_endpoint_id, normalized_url,
                     signing_secret_generation, nonce_sha256, request_identity_hmac,
                     request_identity_key_version, lifecycle_status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, challengeId, merchant.id(), endpoint.getId(), endpoint.getUrl(),
                endpoint.getSigningSecretGeneration(), "a".repeat(64), "b".repeat(64),
                "test-v1", Timestamp.from(expiresAt), Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO webhook_endpoint_verification_events
                    (id, merchant_id, webhook_endpoint_verification_id,
                     event_sequence, event_type, outcome_code, occurred_at)
                VALUES (?, ?, ?, 1, 'CHALLENGE_CREATED', 'PENDING', ?)
                """, UUID.randomUUID(), merchant.id(), challengeId, Timestamp.from(createdAt));
        jdbcTemplate.update("""
                UPDATE webhook_endpoints SET verification_status = 'PENDING', pending_verification_id = ?
                 WHERE merchant_id = ? AND id = ?
                """, challengeId, merchant.id(), endpoint.getId());

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(verificationService.expireDue(1)).isOne();
        assertThat(verificationService.expireDue(1)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM webhook_endpoint_verifications WHERE id = ?
                """, String.class, challengeId)).isEqualTo("EXPIRED");
        assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(WebhookEndpointVerificationStatus.EXPIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_endpoint_verification_events
                 WHERE webhook_endpoint_verification_id = ?
                """, Integer.class, challengeId)).isEqualTo(2);
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = json(post("/admin/merchants")
                .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"), 201);
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = json(post("/risk/operators").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"), 201);
        return new OperatorAccess(response.get("credential").asText());
    }

    private EndpointAccess createEndpoint(MerchantAccess merchant, String key) throws Exception {
        String url = "http://127.0.0.1:9191/" + key;
        JsonNode response = json(post("/webhook_endpoints").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\",\"enabledEvents\":[\"refund.created\"]}"), 201);
        return new EndpointAccess(UUID.fromString(response.get("id").asText()), url,
                response.get("signingSecret").asText());
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int statusCode) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().is(statusCode))
                .andReturn().getResponse().getContentAsString());
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(String secret) { }
    private record EndpointAccess(UUID id, String url, String signingSecret) { }
}
