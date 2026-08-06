package com.thaap.payments.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.projection.ProjectionProcessingOutcome;
import com.thaap.payments.projection.TransportedDomainEvent;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Builds real merchant, operator, and payment-risk state through the public and risk APIs so
 * proofs observe the same rows production writes. Fixtures never insert domain rows directly:
 * a hand-seeded payment could satisfy an assertion the confirm pipeline would never produce.
 */
public final class RiskWorkspaceScenario {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String RISK_OPERATOR_HEADER = "X-Risk-Operator-Key";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String ADMIN_BOOTSTRAP_HEADER = "X-Admin-Bootstrap-Token";
    public static final String ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    /** Below every rules_v1 threshold, so the decision is ALLOW with no matched control. */
    public static final long ALLOW_AMOUNT_MINOR = 5_000L;
    /** One minor unit below the elevated-amount threshold: ALLOW, and re-confirmable. */
    public static final long BELOW_ELEVATED_AMOUNT_MINOR = 99_999L;
    /** At or above the REQUEST_3DS_USD_ELEVATED_AMOUNT_V1 threshold but below BLOCK. */
    public static final long ELEVATED_AMOUNT_MINOR = 150_000L;
    /** At or above BLOCK_USD_EXTREME_AMOUNT_V1, which outranks every weaker matched control. */
    public static final long EXTREME_AMOUNT_MINOR = 6_000_000L;
    /** The approving test card every fixture uses unless a proof needs card identity to differ. */
    public static final String DEFAULT_CARD_NUMBER = "4999990000000003";
    /** A second card so "same card fingerprint" claims can be shown to be discriminating. */
    public static final String ALTERNATE_CARD_NUMBER = "4999990000000011";

    /**
     * The customer identity a payment is created under. Email and phone both become correlation
     * digests, so holding them fixed across two payments is what makes them relatable.
     */
    public record Contact(String email, String phone) {
        public static Contact forPrefix(String prefix) {
            int digits = Math.abs(prefix.hashCode() % 10_000_000);
            return new Contact(prefix + "@example.com",
                    "+1415" + String.format("%07d", digits));
        }
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DomainEventRepository eventRepository;
    private final PaymentRiskProjectionConsumerService projectionConsumer;

    public RiskWorkspaceScenario(MockMvc mockMvc, ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate, DomainEventRepository eventRepository,
            PaymentRiskProjectionConsumerService projectionConsumer) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.eventRepository = eventRepository;
        this.projectionConsumer = projectionConsumer;
    }

    public Merchant merchant(String name) throws Exception {
        JsonNode response = json(post("/admin/merchants")
                .header(ADMIN_BOOTSTRAP_HEADER, ADMIN_BOOTSTRAP_TOKEN)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"), 201);
        return new Merchant(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    public Operator operator(Merchant merchant, String key, String role) throws Exception {
        JsonNode response = json(post("/risk/operators").header(API_KEY_HEADER, merchant.apiKey())
                .header(IDEMPOTENCY_HEADER, key).contentType(APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"), 201);
        return new Operator(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText(), role);
    }

    /** A single successful confirm below every rule threshold: the ALLOW/no-match partition. */
    public Payment allowedPayment(Merchant merchant, String prefix) throws Exception {
        return payment(merchant, prefix, ALLOW_AMOUNT_MINOR, "USD", 1);
    }

    /** One confirm over the elevated-amount threshold: the single-matched-control partition. */
    public Payment challengedPayment(Merchant merchant, String prefix) throws Exception {
        return payment(merchant, prefix, ELEVATED_AMOUNT_MINOR, "USD", 1);
    }

    /**
     * Three confirms so attempt_number reaches the repeated-attempt threshold and opens review.
     * The amount stays under the elevated threshold because a REQUEST_3DS decision parks the
     * payment in a state that rejects the next confirm, which would never reach attempt three.
     */
    public Payment reviewedPayment(Merchant merchant, String prefix) throws Exception {
        return payment(merchant, prefix, BELOW_ELEVATED_AMOUNT_MINOR, "USD", 3);
    }

    /** Above the extreme-amount threshold so BLOCK outranks the weaker elevated-amount match. */
    public Payment blockedPayment(Merchant merchant, String prefix) throws Exception {
        return payment(merchant, prefix, EXTREME_AMOUNT_MINOR, "USD", 1);
    }

    /** A created-but-never-confirmed intent: the zero-attempt, zero-decision partition. */
    public Payment unconfirmedPayment(Merchant merchant, String prefix) throws Exception {
        return payment(merchant, prefix, ALLOW_AMOUNT_MINOR, "USD", 0);
    }

    public Payment payment(Merchant merchant, String prefix, long amountMinor, String currency,
            int confirmations) throws Exception {
        return payment(merchant, prefix, amountMinor, currency, confirmations, Contact.forPrefix(prefix),
                DEFAULT_CARD_NUMBER);
    }

    /**
     * The identity-controlled overload. Related-payment proofs need two payments that deliberately
     * share a contact or a card while differing everywhere else, which the prefix-derived form
     * cannot express because the prefix also seeds the idempotency keys.
     */
    public Payment payment(Merchant merchant, String prefix, long amountMinor, String currency,
            int confirmations, Contact contact, String cardNumber) throws Exception {
        JsonNode customer = json(post("/customers").header(API_KEY_HEADER, merchant.apiKey())
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + contact.email() + "\",\"phone\":\""
                        + contact.phone() + "\"}"), 201);
        UUID customerId = UUID.fromString(customer.get("id").asText());
        JsonNode method = json(post("/payment_methods").header(API_KEY_HEADER, merchant.apiKey())
                .header(IDEMPOTENCY_HEADER, prefix + "-method").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId
                        + "\",\"cardNumber\":\"" + cardNumber + "\"}"), 201);
        UUID paymentMethodId = UUID.fromString(method.get("id").asText());
        JsonNode intent = json(post("/payment_intents").header(API_KEY_HEADER, merchant.apiKey())
                .header(IDEMPOTENCY_HEADER, prefix + "-intent").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"amount\":" + amountMinor
                        + ",\"currency\":\"" + currency + "\",\"captureMethod\":\"MANUAL\"}"), 201);
        UUID paymentIntentId = UUID.fromString(intent.get("id").asText());
        for (int attempt = 1; attempt <= confirmations; attempt++) {
            mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                            .header(API_KEY_HEADER, merchant.apiKey())
                            .header(IDEMPOTENCY_HEADER, prefix + "-confirm-" + attempt)
                            .contentType(APPLICATION_JSON)
                            .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                    .andExpect(status().isCreated());
        }
        return new Payment(paymentIntentId, customerId, paymentMethodId, amountMinor, currency,
                reviewCaseId(paymentIntentId));
    }

    private UUID reviewCaseId(UUID paymentIntentId) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?",
                UUID.class, paymentIntentId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * Drives every recorded domain event through the real projection consumer in the same
     * total order the transport guarantees, so projection sections observe production
     * generation, cutoff, and coverage state instead of a hand-written projection row.
     */
    public void drainProjection() {
        List<DomainEvent> events = eventRepository.findAll().stream()
                .sorted(Comparator.comparing(DomainEvent::getRecordedAt)
                        .thenComparing(DomainEvent::getMerchantId)
                        .thenComparing(DomainEvent::getStreamId)
                        .thenComparingLong(DomainEvent::getStreamVersion))
                .toList();
        long offset = 0;
        for (DomainEvent event : events) {
            TransportedDomainEvent transported = new TransportedDomainEvent(
                    PaymentRiskProjectionConsumerService.TOPIC_NAME, 0, offset++,
                    TenantQualifiedStreamKey.encode(event.getMerchantId(), event.getStreamType(),
                            event.getStreamId()),
                    event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                    event.getEventRecordSha256());
            assertThat(projectionConsumer.process(transported))
                    .isIn(ProjectionProcessingOutcome.APPLIED, ProjectionProcessingOutcome.DUPLICATE);
        }
    }

    public JsonNode json(MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());
    }

    public record Merchant(UUID id, String apiKey) { }

    public record Operator(UUID id, String credential, String role) { }

    public record Payment(UUID paymentIntentId, UUID customerId, UUID paymentMethodId,
            long amountMinor, String currency, UUID reviewCaseId) { }
}
