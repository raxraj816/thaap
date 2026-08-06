package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves the runtime half of C57-SEC-001. The developer contract surface is the only part of the
 * product that answers an unauthenticated caller, which makes it the cheapest reconnaissance
 * target in the system. It must be useful to an integrator and useless to an attacker: no tenant
 * data, no internal topology, no cache that could serve one caller another caller's response, and
 * no failure that discloses how the artifact is stored.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class DeveloperContractPrivacyTests {

    private static final List<String> OPEN_ROUTES = List.of(
            "/developer/api-versions",
            "/developer/error-catalog",
            "/developer/openapi/2026-08-01",
            "/developer/openapi/2026-08-01/digest",
            "/developer/test-cards");

    private static final List<Pattern> DISCLOSURE_PATTERNS = List.of(
            Pattern.compile("(?i)(\\bstacktrace\\b|caused by:|\\borg\\.postgresql|\\borg\\.h2\\b)"),
            Pattern.compile("(?i)\\bjdbc:"),
            Pattern.compile("(?i)\\b(select\\s+[a-z_*]|insert\\s+into\\s+[a-z_]|update\\s+[a-z_]+\\s+set\\b|delete\\s+from\\s+[a-z_])"),
            Pattern.compile("(?i)\\bclasspath\\b|\\bfile:/"),
            Pattern.compile("(?i)\\bcom\\.thaap\\.payments\\.[a-z]"),
            Pattern.compile("/(internal|actuator|admin|platform)(/|\\b)"));

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("requestIdFilter") private OncePerRequestFilter requestIdFilter;
    @Autowired @Qualifier("apiVersionFilter") private OncePerRequestFilter apiVersionFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
    }

    @Test
    void everyOpenRouteAnswersWithoutAnyCredentialAndReturnsIdenticalBytesToEveryCaller()
            throws Exception {
        for (String route : OPEN_ROUTES) {
            String anonymous = body(get(route));
            // Supplying a credential must not unlock a richer document. If it did, the public
            // contract would differ per caller and the reviewed artifact would not be the one
            // integrators read.
            String credentialed = body(get(route)
                    .header("X-Api-Key", "not-a-real-key")
                    .header("X-Risk-Operator-Key", "not-a-real-operator-key"));
            assertThat(credentialed).as("%s answers every caller identically", route)
                    .isEqualTo(anonymous);
        }
    }

    @Test
    void noOpenRouteDisclosesInternalTopologyStorageOrImplementationDetail() throws Exception {
        List<String> disclosures = new ArrayList<>();
        for (String route : OPEN_ROUTES) {
            String payload = body(get(route));
            for (Pattern pattern : DISCLOSURE_PATTERNS) {
                Matcher matcher = pattern.matcher(payload);
                if (matcher.find()) {
                    disclosures.add(route + " leaked '" + matcher.group() + "'");
                }
            }
        }
        assertThat(disclosures).as("the open surface describes the product, not the deployment")
                .isEmpty();
    }

    @Test
    void theDisclosureScannerDetectsEachClassOfLeakItClaimsToPrevent() {
        // Without this the previous assertion would pass just as well against a broken pattern.
        List<String> canaries = List.of(
                "caused by: java.lang.NullPointerException",
                "org.postgresql.util.PSQLException",
                "jdbc:postgresql://db:5432/thaap",
                "select * from payment_intents",
                "classpath:openapi/thaap-public-api-2026-08-01.yaml",
                "com.thaap.payments.developer.DeveloperContractController",
                "GET /internal/reconciliation");
        for (String canary : canaries) {
            assertThat(DISCLOSURE_PATTERNS.stream().anyMatch(p -> p.matcher(canary).find()))
                    .as("the scanner catches '%s'", canary).isTrue();
        }

        List<String> legitimate = List.of(
                "payment_intent_not_capturable",
                "correct_the_request_before_retry",
                "/payment_intents/{paymentIntentId}/confirm",
                "The developer sandbox accepts test cards only");
        for (String phrase : legitimate) {
            assertThat(DISCLOSURE_PATTERNS.stream().anyMatch(p -> p.matcher(phrase).find()))
                    .as("the scanner tolerates '%s'", phrase).isFalse();
        }
    }

    @Test
    void anUnknownContractVersionIsRefusedWithoutRevealingWhichVersionsExistOnDisk()
            throws Exception {
        MvcResult result = mockMvc.perform(get("/developer/openapi/{version}", "2020-01-01"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);

        JsonNode error = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(error.path("error").asText()).isEqualTo("openapi_version_not_found");
        // A refusal that echoes the requested value turns the endpoint into a reflection gadget,
        // and one that names a file path turns it into a storage map.
        assertThat(error.path("message").asText()).doesNotContain("2020-01-01")
                .doesNotContainIgnoringCase("classpath").doesNotContain(".yaml");
        assertThat(error.path("path").asText()).as("the echoed path is the route the server saw")
                .isEqualTo("/developer/openapi/2020-01-01");
    }

    @Test
    void theServedContractIsNeverCachedByAnIntermediaryAndCarriesItsOwnIntegrityDigest()
            throws Exception {
        MvcResult result = mockMvc.perform(get("/developer/openapi/2026-08-01")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertThat(cacheControl).as("a stale contract is a wrong contract").isNotNull()
                .contains("no-cache");

        String digest = result.getResponse().getHeader("X-Thaap-OpenAPI-SHA256");
        assertThat(digest).as("the served artifact publishes its own digest").isNotNull()
                .matches("[0-9a-f]{64}");

        // The digest endpoint must agree, or an integrator verifying the download would be
        // comparing against a value that no longer describes what they received.
        String publishedDigest = body(get("/developer/openapi/2026-08-01/digest")).strip();
        assertThat(publishedDigest)
                .as("the digest route agrees with the served bytes").isEqualTo(digest);
    }

    @Test
    void everyResponseOnTheOpenSurfaceCarriesACorrelationIdentityForSupport() throws Exception {
        for (String route : OPEN_ROUTES) {
            MvcResult result = mockMvc.perform(get(route)).andReturn();
            String requestId = result.getResponse().getHeader("X-Request-Id");
            // Support cannot investigate a report about an unauthenticated call without one, and
            // an integrator has nothing else to quote.
            assertThat(requestId).as("%s is traceable", route).isNotNull().isNotBlank();
            assertThat(result.getResponse().getHeader("Thaap-Version"))
                    .as("%s states the contract version it answered under", route)
                    .isEqualTo("2026-08-01");
        }
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).as("the open surface answers").isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
