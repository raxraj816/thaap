package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C54-MAP-001. The timeline renders raw domain-event payloads, which are the most
 * PII-dense structures in the platform. The presentation mapper is therefore an allowlist, and
 * this class proves the allowlist is exhaustive over every payment-stream event type, that an
 * unrecognised field fails closed instead of leaking, and that real customer identifiers never
 * reach an analyst screen.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentTimelinePrivacyTests {

    private static final Set<String> DELIBERATELY_HIDDEN = Set.of("customerReference",
            "assigneeReference", "supersedesLabelReference");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    private PaymentTimelineService timelineService;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() throws Exception {
        // A shared cached context means a shared database. Without this the fixture
        // inherits the previous class's rows, and drainProjection replays them as if
        // they were its own. See ER-0102.
        databaseCleaner.clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("timeline-privacy");
        operator = scenario.operator(merchant, "timeline-privacy-key", "RISK_MANAGER");
    }

    @Test
    void everyPaymentStreamEventTypeHasAPresentationMappingSoNoAppendCanBlankTheTimeline()
            throws Exception {
        Method safeFacts = safeFactsMethod();
        Method category = categoryMethod();

        List<DomainEventType> paymentStreamTypes = List.of(DomainEventType.values()).stream()
                .filter(type -> type.streamType() == DomainStreamType.PAYMENT).toList();
        assertThat(paymentStreamTypes).hasSizeGreaterThan(10);

        List<String> unmapped = new ArrayList<>();
        for (DomainEventType type : paymentStreamTypes) {
            try {
                safeFacts.invoke(timelineService, type, 1, objectMapper.createObjectNode());
                category.invoke(timelineService, type);
            } catch (InvocationTargetException failure) {
                unmapped.add(type.wireValue());
            }
        }
        // A payment-stream event type with no mapping makes the entire timeline unavailable the
        // first time it is appended, so this list must stay empty as the domain grows.
        assertThat(unmapped).isEmpty();
    }

    @Test
    void anUnrecognisedPayloadFieldFailsClosedRatherThanReachingTheAnalyst() throws Exception {
        Method safeFacts = safeFactsMethod();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5_000L);
        payload.put("currency", "USD");
        payload.put("captureMethod", "MANUAL");
        payload.put("customerEmail", "victim@example.com");

        assertThatThrownBy(() -> safeFacts.invoke(timelineService,
                DomainEventType.PAYMENT_INTENT_CREATED, 1, payload))
                .isInstanceOf(InvocationTargetException.class)
                .cause().isInstanceOf(PublicApiException.class);
    }

    @Test
    void aReferenceFieldTheMapperKnowsAboutIsDroppedRatherThanRendered() throws Exception {
        Method safeFacts = safeFactsMethod();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5_000L);
        payload.put("customerReference", "cus_should_never_render");

        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) safeFacts.invoke(timelineService,
                DomainEventType.PAYMENT_INTENT_CREATED, 1, payload);

        // Known-but-hidden is a distinct outcome from unknown. Unknown fails the request; known
        // internal references are silently withheld so the timeline stays readable.
        assertThat(facts).containsOnlyKeys("amountMinor");
        assertThat(facts.keySet()).doesNotContainAnyElementsOf(DELIBERATELY_HIDDEN);
    }

    @Test
    void aPayloadWrittenUnderAFutureSchemaVersionIsRefusedRatherThanGuessed() throws Exception {
        Method safeFacts = safeFactsMethod();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5_000L);

        assertThatThrownBy(() -> safeFacts.invoke(timelineService,
                DomainEventType.PAYMENT_INTENT_CREATED, 2, payload))
                .isInstanceOf(InvocationTargetException.class)
                .cause().isInstanceOf(PublicApiException.class);
    }

    @Test
    void aStructuredPayloadValueIsRefusedBecauseTheMapperCannotVouchForItsNestedFields()
            throws Exception {
        Method safeFacts = safeFactsMethod();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("currency").put("code", "USD").put("holderName", "Real Person");

        // An allowlisted key holding an object would smuggle unreviewed fields past the allowlist.
        assertThatThrownBy(() -> safeFacts.invoke(timelineService,
                DomainEventType.PAYMENT_INTENT_CREATED, 1, payload))
                .isInstanceOf(InvocationTargetException.class)
                .cause().isInstanceOf(PublicApiException.class);
    }

    @Test
    void noRealCustomerOrCredentialValueAppearsAnywhereInARenderedTimeline() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant,
                "timeline-canary");

        Map<String, String> canaries = new LinkedHashMap<>();
        canaries.put("apiKey", merchant.apiKey());
        canaries.put("operatorCredential", operator.credential());
        canaries.put("customerId", reviewed.customerId().toString());
        canaries.put("paymentMethodId", reviewed.paymentMethodId().toString());
        Map<String, Object> customer = jdbcTemplate.queryForMap(
                "SELECT email_hash, phone_hash FROM customers WHERE merchant_id = ? AND id = ?",
                merchant.id(), reviewed.customerId());
        canaries.put("emailHash", String.valueOf(customer.get("email_hash")));
        canaries.put("phoneHash", String.valueOf(customer.get("phone_hash")));
        Map<String, Object> method = jdbcTemplate.queryForMap("""
                SELECT card_fingerprint, card_last4 FROM payment_methods
                WHERE merchant_id = ? AND id = ?
                """, merchant.id(), reviewed.paymentMethodId());
        canaries.put("cardFingerprint", String.valueOf(method.get("card_fingerprint")));

        // A canary that is blank or absent proves nothing, so require every one to be real first.
        canaries.forEach((name, value) ->
                assertThat(value).as(name).isNotBlank().isNotEqualTo("null"));

        String rendered = objectMapper.writeValueAsString(scenario.json(
                get("/risk/workspace/payments/" + reviewed.paymentIntentId() + "/timeline?limit=50")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()),
                200));
        String webhooks = objectMapper.writeValueAsString(scenario.json(
                get("/risk/workspace/payments/" + reviewed.paymentIntentId() + "/webhook-activity")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()),
                200));

        // The payment identifier is the navigation key the analyst asked for, so it is expected.
        canaries.remove("paymentIntentId");
        canaries.forEach((name, value) -> {
            assertThat(rendered).as("timeline leaks " + name).doesNotContain(value);
            assertThat(webhooks).as("webhook activity leaks " + name).doesNotContain(value);
        });
        assertThat(rendered).contains(reviewed.paymentIntentId().toString());
        assertThat(method.get("card_last4")).isNotNull();
    }

    @Test
    void theRenderedTimelineOnlyEverPublishesAllowlistedFactKeys() throws Exception {
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant,
                "timeline-keys");

        JsonNode page = scenario.json(get("/risk/workspace/payments/"
                + reviewed.paymentIntentId() + "/timeline?limit=50")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);

        Set<String> declared = declaredPresentationFields();
        assertThat(declared).isNotEmpty();
        List<String> observed = new ArrayList<>();
        page.get("data").forEach(entry ->
                entry.get("safeFacts").properties().forEach(field -> observed.add(field.getKey())));
        assertThat(observed).isNotEmpty();
        assertThat(declared).containsAll(observed);
        assertThat(observed).doesNotContainAnyElementsOf(DELIBERATELY_HIDDEN);
    }

    private Set<String> declaredPresentationFields() throws Exception {
        Method builder = PaymentTimelineService.class
                .getDeclaredMethod("presentationFields");
        builder.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<DomainEventType, Set<String>> fields =
                (Map<DomainEventType, Set<String>>) builder.invoke(null);
        return fields.values().stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Method safeFactsMethod() throws Exception {
        Method method = PaymentTimelineService.class.getDeclaredMethod("safeFacts",
                DomainEventType.class, int.class, ObjectNode.class);
        method.setAccessible(true);
        return method;
    }

    private Method categoryMethod() throws Exception {
        Method method = PaymentTimelineService.class.getDeclaredMethod("category",
                DomainEventType.class);
        method.setAccessible(true);
        return method;
    }
}
