package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-SEC-001: investigation responses, errors, and logs carry no cardholder data,
 * raw contact identifiers, correlation HMACs, credentials, idempotency keys, webhook targets,
 * or database text. The canaries are planted in the fixture so a leak has something to leak.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class PaymentInvestigationPrivacyTests {

    /** The exact card number the fixture tokenizes. Its PAN must never surface. */
    private static final String CARD_NUMBER = "4999990000000003";

    private static final Pattern PAN_SHAPED = Pattern.compile("\\b[0-9]{13,19}\\b");
    private static final Pattern DATABASE_TEXT = Pattern.compile(
            "(?i)(select |insert into|update .* set |from payment_intents|"
                    + "violates .* constraint|Failing row contains|SQLException|"
                    + "org\\.postgresql|org\\.h2\\.jdbc)");

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;
    private Payment reviewed;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("investigation-privacy-owner");
        manager = scenario.operator(merchant, "privacy-manager", "RISK_MANAGER");
        reviewed = scenario.reviewedPayment(merchant, "privacy-reviewed");
        scenario.drainProjection();
    }

    @Test
    void noWorkspaceResponseCarriesCardContactCredentialOrCorrelationSecrets() throws Exception {
        List<String> prohibited = new ArrayList<>(List.of(
                CARD_NUMBER, "privacy-reviewed@example.com",
                merchant.apiKey(), manager.credential(),
                "privacy-reviewed-intent", "privacy-reviewed-confirm-1"));
        prohibited.addAll(cardCorrelationSecrets());
        prohibited.addAll(relationIdentityHmacs());

        for (String body : workspaceResponses()) {
            for (String canary : prohibited) {
                assertThat(body).as("workspace response leaked a prohibited value")
                        .doesNotContain(canary);
            }
            assertThat(PAN_SHAPED.matcher(body).find())
                    .as("workspace response contains a PAN-shaped digit run").isFalse();
            assertThat(DATABASE_TEXT.matcher(body).find())
                    .as("workspace response contains database or driver text").isFalse();
        }
    }

    @Test
    void aFailingSectionReturnsASafeDescriptorInsteadOfDriverOrRowText(CapturedOutput output)
            throws Exception {
        // A malformed cursor is the cheapest way to drive the section error path with a value
        // the caller controls, which is exactly where echoed internals usually escape.
        String body = mockMvc.perform(get("/risk/workspace/payments/{id}/timeline",
                        reviewed.paymentIntentId())
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .param("cursor", "'; DROP TABLE payment_intents; --"))
                .andReturn().getResponse().getContentAsString();

        JsonNode error = objectMapper.readTree(body);
        assertThat(error.get("error").asText()).isEqualTo("workspace_cursor_invalid");
        assertThat(error.get("message").asText()).doesNotContain("DROP TABLE", "payment_intents");
        assertThat(DATABASE_TEXT.matcher(body).find()).isFalse();
        assertThat(output.getAll())
                .doesNotContain("Failing row contains", "DROP TABLE payment_intents");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_intents", Integer.class)).isPositive();
    }

    @Test
    void investigationLogsRecordOnlyTheRequestIdAndNeverThePaymentOrOperatorSubject(
            CapturedOutput output) throws Exception {
        workspaceResponses();

        String logs = output.getAll();
        for (String canary : Stream.of(
                        Stream.of(CARD_NUMBER, "privacy-reviewed@example.com", merchant.apiKey(),
                                manager.credential()),
                        cardCorrelationSecrets().stream(), relationIdentityHmacs().stream())
                .flatMap(values -> values).toList()) {
            assertThat(logs).as("application log leaked a prohibited value").doesNotContain(canary);
        }
    }

    @Test
    void theInvestigationSurfaceSerializesClosedDtosRatherThanPersistenceEntities()
            throws Exception {
        Path investigationPackage = Path.of("src/main/java/com/thaap/payments/investigation");
        try (Stream<Path> sources = Files.list(investigationPackage)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = Files.readString(source);
                // Returning an @Entity or a free-form map from a controller is how a newly added
                // column silently becomes a published field months after this review.
                assertThat(code).as("%s returns a raw JPA entity from the workspace surface",
                                source.getFileName())
                        .doesNotContain("public PaymentIntent ", "public PaymentAttempt ",
                                "public FraudDecision ", "public ManualReviewCase ");
            }
        }
        String controller = Files.readString(
                investigationPackage.resolve("PaymentInvestigationController.java"));
        assertThat(controller).doesNotContain("Map<String, Object>", "JsonNode ", "Object ");
    }

    /**
     * Relation anchors store keyed correlation digests. These must exist for the canary to mean
     * anything: asserting the absence of a value nothing ever produced proves nothing.
     */
    private List<String> relationIdentityHmacs() {
        List<String> hmacs = jdbcTemplate.queryForList("""
                SELECT relation_identity_hmac FROM payment_investigation_relation_anchors
                WHERE merchant_id = ?
                """, String.class, merchant.id()).stream().distinct().toList();
        assertThat(hmacs).as("the fixture wrote no relation anchor, so the HMAC canary is vacuous")
                .isNotEmpty();
        return hmacs;
    }

    /** The tokenized card's stored correlation values, which reidentify a cardholder across tenants. */
    private List<String> cardCorrelationSecrets() {
        Map<String, Object> card = jdbcTemplate.queryForMap("""
                SELECT token, card_fingerprint FROM payment_methods WHERE id = ?
                """, reviewed.paymentMethodId());
        List<String> secrets = card.values().stream().map(Object::toString).distinct().toList();
        assertThat(secrets).as("the fixture stored no card token or fingerprint").isNotEmpty();
        return secrets;
    }

    private List<String> workspaceResponses() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String route : List.of("/risk/workspace/payments/{id}",
                "/risk/workspace/payments/{id}/timeline",
                "/risk/workspace/payments/{id}/related",
                "/risk/workspace/payments/{id}/webhook-activity")) {
            bodies.add(mockMvc.perform(get(route, reviewed.paymentIntentId())
                            .header(RISK_OPERATOR_HEADER, manager.credential()))
                    .andReturn().getResponse().getContentAsString());
        }
        bodies.add(mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sort\":\"CREATED_AT_DESC\"}"))
                .andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/risk/workspace/payments/{id}", UUID.randomUUID())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andReturn().getResponse().getContentAsString());
        return bodies;
    }
}
