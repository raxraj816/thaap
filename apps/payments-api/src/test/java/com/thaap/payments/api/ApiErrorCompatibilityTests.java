package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C57-ERR-001 on the wire. The envelope a client actually receives must be stable
 * and self-sufficient: one documented field set, a machine code on every failure family,
 * a deterministic validation order, and enough information to recover without reading
 * {@code message}, the status reason, or anything the server did not intend to publish.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ApiErrorCompatibilityTests {

    /** The published envelope. Adding a field is compatible; removing or renaming one is not. */
    private static final Set<String> PUBLISHED_FIELDS = Set.of(
            "requestId", "timestamp", "status", "error", "message", "path",
            "fieldErrors", "retryable", "recoveryAction");

    private static final Set<String> ALWAYS_PRESENT_FIELDS = Set.of(
            "requestId", "timestamp", "status", "error", "path", "recoveryAction");

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
        merchant = scenario.merchant("error-compatibility");
    }

    @Test
    void everyFailureFamilyReturnsTheSameEnvelopeWithNoUndocumentedField() throws Exception {
        for (FailureFamily family : failureFamilies()) {
            JsonNode error = errorBody(family.builder());

            List<String> undocumented = error.properties().stream()
                    .map(java.util.Map.Entry::getKey)
                    .filter(name -> !PUBLISHED_FIELDS.contains(name)).toList();
            assertThat(undocumented)
                    .as("%s publishes only documented envelope fields", family.name()).isEmpty();

            for (String required : ALWAYS_PRESENT_FIELDS) {
                assertThat(error.hasNonNull(required))
                        .as("%s carries %s", family.name(), required).isTrue();
            }
            assertThat(error.path("error").asText())
                    .as("%s is machine-classifiable", family.name())
                    .isNotBlank().matches("[a-z][a-z0-9_]*");
            assertThat(error.path("status").asInt())
                    .as("%s reports its own status in the body", family.name())
                    .isGreaterThanOrEqualTo(400);
            assertThat(error.path("retryable").isBoolean())
                    .as("%s states retry safety as a boolean, not by omission", family.name())
                    .isTrue();
        }
    }

    @Test
    void aClientNeverHasToParseMessageStatusReasonOrExceptionTextToRecover() throws Exception {
        for (FailureFamily family : failureFamilies()) {
            JsonNode error = errorBody(family.builder());
            // Everything needed to branch is machine-readable, so message can be reworded or
            // localized at any time without breaking a single integration.
            assertThat(error.path("error").asText()).isNotBlank();
            assertThat(error.path("recoveryAction").asText())
                    .as("%s names a remedy without prose", family.name())
                    .isNotBlank().matches("[a-z][a-z0-9_]*");

            String message = error.path("message").asText("");
            assertThat(message).as("%s never leaks internals through message", family.name())
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContainIgnoringCase("jdbc")
                    .doesNotContainIgnoringCase("org.springframework")
                    .doesNotContain("SELECT ").doesNotContain("INSERT ")
                    .doesNotContainIgnoringCase("stacktrace");
        }
    }

    @Test
    void validationFieldErrorsAreDeterministicallyOrderedAcrossRepeatedRequests() throws Exception {
        // A client that renders or diffs validation output cannot tolerate an order that depends
        // on reflection or hash iteration. Several invalid fields at once is the real case.
        List<List<String>> orders = new ArrayList<>();
        for (int repetition = 0; repetition < 5; repetition++) {
            JsonNode error = errorBody(post("/payment_intents")
                    .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                    .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"\",\"amount\":-5}"));
            assertThat(error.path("error").asText()).isEqualTo("validation_failed");
            List<String> fields = error.path("fieldErrors").valueStream()
                    .map(entry -> entry.path("field").asText()).toList();
            assertThat(fields).as("a validation failure names the fields that failed").isNotEmpty();
            orders.add(fields);
        }

        assertThat(new LinkedHashSet<>(orders))
                .as("repeated identical requests produce one field order").hasSize(1);
        assertThat(orders.getFirst()).as("field order is stable and sorted")
                .isSorted();
    }

    @Test
    void aForeignResourceIsIndistinguishableFromAnAbsentOne() throws Exception {
        RiskWorkspaceScenario.Merchant other = scenario.merchant("error-compatibility-other");
        RiskWorkspaceScenario.Payment foreign = scenario.allowedPayment(other, "error-foreign");

        JsonNode absent = errorBody(get("/payment_intents/{id}", UUID.randomUUID())
                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey()));
        JsonNode crossTenant = errorBody(get("/payment_intents/{id}", foreign.paymentIntentId())
                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey()));

        // If these differed, the error contract itself would be an existence oracle for another
        // merchant's payments.
        assertThat(crossTenant.path("error").asText()).isEqualTo(absent.path("error").asText());
        assertThat(crossTenant.path("status").asInt()).isEqualTo(absent.path("status").asInt());
        assertThat(crossTenant.path("retryable").asBoolean())
                .isEqualTo(absent.path("retryable").asBoolean());
        assertThat(crossTenant.path("recoveryAction").asText())
                .isEqualTo(absent.path("recoveryAction").asText());
        assertThat(crossTenant.path("message").asText()).isEqualTo(absent.path("message").asText());
    }

    @Test
    void everyEmittedCodeIsPublishedInTheCatalogueClientsAreToldToBranchOn() throws Exception {
        Set<String> published = new LinkedHashSet<>();
        JsonNode catalogue = scenario.json(get("/developer/error-catalog"), 200);
        catalogue.path("errors").forEach(entry -> published.add(entry.path("code").asText()));

        for (FailureFamily family : failureFamilies()) {
            String code = errorBody(family.builder()).path("error").asText();
            assertThat(published)
                    .as("%s emits %s, which clients can only handle if it is documented",
                            family.name(), code)
                    .contains(code);
        }
    }

    @Test
    void theCatalogueAgreesWithTheEnvelopeOnStatusAndRetrySemantics() throws Exception {
        JsonNode catalogue = scenario.json(get("/developer/error-catalog"), 200);

        for (FailureFamily family : failureFamilies()) {
            JsonNode error = errorBody(family.builder());
            String code = error.path("error").asText();
            JsonNode entry = catalogue.path("errors").valueStream()
                    .filter(candidate -> code.equals(candidate.path("code").asText()))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("undocumented code " + code));

            // Documentation that disagrees with the runtime is worse than no documentation,
            // because a client will build retry behaviour on the version that is wrong.
            assertThat(entry.path("httpStatus").asInt())
                    .as("%s status agrees with the catalogue", code)
                    .isEqualTo(error.path("status").asInt());
            assertThat(entry.path("retryable").asBoolean())
                    .as("%s retry semantics agree with the catalogue", code)
                    .isEqualTo(error.path("retryable").asBoolean());
            assertThat(entry.path("recoveryAction").asText())
                    .as("%s recovery hint agrees with the catalogue", code)
                    .isEqualTo(error.path("recoveryAction").asText());
        }
    }

    private List<FailureFamily> failureFamilies() {
        UUID unknown = UUID.randomUUID();
        return List.of(
                new FailureFamily("unauthenticated mutation", post("/payment_intents")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                new FailureFamily("body validation", post("/payment_intents")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-1}")),
                new FailureFamily("unreadable body", post("/payment_intents")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json")),
                new FailureFamily("path type mismatch",
                        get("/payment_intents/{id}", "not-a-uuid")
                                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())),
                new FailureFamily("resource not found",
                        get("/payment_intents/{id}", unknown)
                                .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())),
                new FailureFamily("unknown route", get("/no-such-public-route")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())),
                new FailureFamily("method not allowed", delete("/developer/api-versions")),
                new FailureFamily("unsupported media type", post("/payment_intents")
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.TEXT_PLAIN).content("plain")),
                new FailureFamily("malformed version", get("/developer/api-versions")
                        .header(ApiVersionFilter.HEADER, "not-a-version")),
                new FailureFamily("unsupported version", get("/developer/api-versions")
                        .header(ApiVersionFilter.HEADER, "2020-01-01")),
                new FailureFamily("unknown contract version",
                        get("/developer/openapi/{version}", "2020-01-01")));
    }

    private JsonNode errorBody(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("the family under test actually failed").isGreaterThanOrEqualTo(400);
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private record FailureFamily(String name, MockHttpServletRequestBuilder builder) {
    }
}
