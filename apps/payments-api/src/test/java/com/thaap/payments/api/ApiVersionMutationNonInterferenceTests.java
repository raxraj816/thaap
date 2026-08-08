package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C57-PAY-001. Version resolution, error classification, and contract serving are
 * documentation concerns and must never become payment behaviour. A refused version stops
 * a mutation before any money, decision, event, ledger, or idempotency state exists; a
 * usable version leaves the lifecycle exactly as it was before the contract had a version.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ApiVersionMutationNonInterferenceTests {

    private static final String CURRENT = ApiVersionFilter.CURRENT_VERSION;

    /** Durable state a contract concern must never be able to touch. */
    private static final List<String> DOMAIN_TABLES = List.of(
            "payment_intents", "payment_attempts", "fraud_decisions", "ledger_entries",
            "domain_events", "idempotency_records", "audit_logs", "review_cases",
            "customers", "payment_methods");

    /** Every requested version that must be refused before a handler runs. */
    private static final List<String> REFUSED_VERSIONS =
            List.of("", "not-a-version", "2026-8-1", "2026-02-29", "2020-01-01", "2099-12-31");

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired @Qualifier("requestIdFilter") private OncePerRequestFilter requestIdFilter;
    @Autowired @Qualifier("apiVersionFilter") private OncePerRequestFilter apiVersionFilter;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private RiskWorkspaceScenario.Merchant merchant;
    private UUID customerId;
    private UUID paymentMethodId;

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
        merchant = scenario.merchant("version-non-interference");
        RiskWorkspaceScenario.Payment seed =
                scenario.allowedPayment(merchant, "version-non-interference");
        customerId = seed.customerId();
        paymentMethodId = seed.paymentMethodId();
    }

    @Test
    void aRefusedVersionStopsEveryMutationBeforeAnyDurableStateExists() throws Exception {
        String before = domainFingerprint();

        for (String version : REFUSED_VERSIONS) {
            for (MutationAttempt attempt : mutations(version)) {
                MvcResult result = mockMvc.perform(attempt.builder()).andReturn();
                assertThat(result.getResponse().getStatus())
                        .as("%s under version '%s' is refused", attempt.name(), version)
                        .isEqualTo(400);
                JsonNode error = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8));
                assertThat(error.path("error").asText())
                        .as("%s under version '%s' fails as a version problem", attempt.name(), version)
                        .isIn(PublicApiErrorDescriptor.MALFORMED_API_VERSION.code(),
                                PublicApiErrorDescriptor.UNSUPPORTED_API_VERSION.code());
            }
        }

        // No payment was created, no attempt authorized, no decision recorded, no ledger entry
        // posted, no event appended, and no idempotency key was consumed. A client that sent a
        // bad version header can safely correct it and send the identical request again.
        assertThat(domainFingerprint())
                .as("refused versions leave the payment domain untouched").isEqualTo(before);
    }

    @Test
    void aRefusedVersionDoesNotConsumeTheIdempotencyKeyItCarried() throws Exception {
        String idempotencyKey = "version-refusal-" + UUID.randomUUID();
        String body = createPaymentIntentBody();

        mockMvc.perform(post("/payment_intents")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, idempotencyKey)
                        .header(ApiVersionFilter.HEADER, "2020-01-01")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        // If the refusal had burned the key, the client's corrected retry would collapse into a
        // conflict it can never escape without inventing a new identity for the same intent.
        MvcResult retry = mockMvc.perform(post("/payment_intents")
                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, idempotencyKey)
                .header(ApiVersionFilter.HEADER, CURRENT)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(retry.getResponse().getStatus())
                .as("the corrected retry succeeds under the same mutation identity").isEqualTo(201);
    }

    @Test
    void anAbsentVersionAndTheExactVersionDriveIdenticalPaymentBehaviour() throws Exception {
        JsonNode withoutHeader = scenario.json(post("/payment_intents")
                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(createPaymentIntentBody()), 201);

        JsonNode withHeader = scenario.json(post("/payment_intents")
                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .header(ApiVersionFilter.HEADER, CURRENT)
                .contentType(MediaType.APPLICATION_JSON).content(createPaymentIntentBody()), 201);

        // Same published fields and same lifecycle answer, so an unversioned client and a
        // pinned client are looking at one contract rather than two that happen to agree today.
        assertThat(shape(withoutHeader)).as("the default contract is the current contract")
                .isEqualTo(shape(withHeader));
        assertThat(withoutHeader.path("status").asText())
                .isEqualTo(withHeader.path("status").asText());
        assertThat(withoutHeader.path("amount").asLong())
                .isEqualTo(withHeader.path("amount").asLong())
                .isEqualTo(RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR);
    }

    @Test
    void readingTheContractSurfaceChangesNothingAboutThePaymentDomain() throws Exception {
        String before = domainFingerprint();

        for (int repetition = 0; repetition < 3; repetition++) {
            mockMvc.perform(get("/developer/api-versions")).andReturn();
            mockMvc.perform(get("/developer/error-catalog")).andReturn();
            mockMvc.perform(get("/developer/openapi/{version}", CURRENT)).andReturn();
            mockMvc.perform(get("/developer/openapi/{version}/digest", CURRENT)).andReturn();
            mockMvc.perform(get("/developer/openapi/{version}", "2020-01-01")).andReturn();
        }

        // Serving documentation is a read of a bundled resource. If generating or serving the
        // contract could boot a fixture or touch a projection, the act of publishing the API
        // would be a payment event.
        assertThat(domainFingerprint())
                .as("contract metadata is not payment state").isEqualTo(before);
    }

    @Test
    void paymentOrchestrationDoesNotDependOnDocumentationOrVersionMetadata() {
        // A dependency in this direction would let a documentation change alter a decision.
        // The version constant is the one deliberate exception, so the check is for the
        // documentation packages rather than for the constant that names the contract.
        List<String> offenders = new ArrayList<>();
        for (String domainPackage : List.of("payment", "fraud", "ledger", "review", "risk")) {
            offenders.addAll(sourceMentions("com.thaap.payments.developer",
                    "src/main/java/com/thaap/payments/" + domainPackage));
        }
        assertThat(offenders)
                .as("payment, fraud, ledger, and review orchestration must not import the "
                        + "documentation surface").isEmpty();
    }

    private List<MutationAttempt> mutations(String version) {
        UUID unknownPayment = UUID.randomUUID();
        return List.of(
                new MutationAttempt("create payment intent", post("/payment_intents")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON).content(createPaymentIntentBody())),
                new MutationAttempt("confirm payment intent",
                        post("/payment_intents/{id}/confirm", unknownPayment)
                                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                                .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                                .header(ApiVersionFilter.HEADER, version)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}")),
                new MutationAttempt("cancel payment intent",
                        post("/payment_intents/{id}/cancel", unknownPayment)
                                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                                .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                                .header(ApiVersionFilter.HEADER, version)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cancellationReason\":\"REQUESTED_BY_CUSTOMER\"}")),
                new MutationAttempt("create customer", post("/customers")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"refused.version@example.com\","
                                + "\"phone\":\"+14155550000\"}")),
                new MutationAttempt("refund", post("/refunds")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + unknownPayment + "\"}")),
                new MutationAttempt("record chargeback", post("/chargebacks")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + unknownPayment + "\"}")),
                new MutationAttempt("post-authorization signal", post("/post_authorization_signals")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + unknownPayment + "\"}")),
                new MutationAttempt("register payment method", post("/payment_methods")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .header(ApiVersionFilter.HEADER, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}")));
    }

    private String createPaymentIntentBody() {
        return "{\"customerId\":\"" + customerId + "\",\"amount\":"
                + RiskWorkspaceScenario.ALLOW_AMOUNT_MINOR
                + ",\"currency\":\"USD\",\"captureMethod\":\"MANUAL\"}";
    }

    /** Field names only, so two responses can be compared without their generated identities. */
    private List<String> shape(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey).sorted().toList();
    }

    private String domainFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (String table : DOMAIN_TABLES) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            fingerprint.append(table).append('=').append(count).append(';');
        }
        return fingerprint.toString();
    }

    private List<String> sourceMentions(String needle, String relativeDirectory) {
        Path directory = Path.of(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).filter(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains(needle);
                } catch (IOException failure) {
                    throw new IllegalStateException("unreadable source " + file, failure);
                }
            }).map(Path::toString).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("unreadable directory " + directory, failure);
        }
    }

    private record MutationAttempt(String name,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    }
}
