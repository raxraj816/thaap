package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Proves C57-OAS-001. The published contract is only a product promise if it describes the API
 * that is actually running: every public operation appears exactly once, nothing appears that has
 * no handler, the identifiers a generated SDK builds method names from are derivable rather than
 * incidental, and the bytes served to an integrator are the bytes that were reviewed.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class OpenApiRuntimeContractTests {

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * Routes that exist in the servlet container but are not part of the product. Spring's error
     * dispatch target is not callable as an API operation, and the contract surfaces themselves
     * are documented explicitly rather than derived.
     */
    private static final Set<String> NON_PRODUCT_PREFIXES = Set.of(
            "/error", "/internal", "/actuator", "/admin", "/platform",
            "/v3/api-docs", "/swagger-ui", "/webjars");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("requestIdFilter") private OncePerRequestFilter requestIdFilter;
    @Autowired @Qualifier("apiVersionFilter") private OncePerRequestFilter apiVersionFilter;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;
    private JsonNode contract;
    private byte[] bundledBytes;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("openapi/thaap-public-api-2026-08-01.yaml")) {
            assertThat(stream).as("the bundled public contract is present").isNotNull();
            bundledBytes = stream.readAllBytes();
        }
        contract = new YAMLMapper().readTree(bundledBytes);
    }

    @Test
    void everyPublicOperationTheServerRoutesIsPublishedExactlyOnce() {
        Set<String> routed = routedPublicOperations();
        Set<String> published = publishedOperations();

        List<String> undocumented = routed.stream().filter(op -> !published.contains(op))
                .sorted().toList();
        // An operation that exists but is undocumented is unreachable through every generated
        // client, and unreviewable by anyone who reads the contract instead of the source.
        assertThat(undocumented).as("no public operation is missing from the contract").isEmpty();

        List<String> phantom = published.stream().filter(op -> !routed.contains(op))
                .sorted().toList();
        // The reverse is worse: a documented operation with no handler is a promise the product
        // cannot keep, and an integrator only discovers it in production.
        assertThat(phantom).as("no published operation lacks a handler").isEmpty();
    }

    @Test
    void everyOperationIdIsDerivableFromItsMethodAndPathSoItCannotDriftSilently() {
        List<String> unstable = new ArrayList<>();
        forEachOperation((method, path, node) -> {
            String expected = derivedOperationId(method, path);
            String actual = node.path("operationId").asText("");
            if (!expected.equals(actual)) {
                unstable.add(method + " " + path + " published " + actual
                        + " but its route derives " + expected);
            }
        });
        // If an identifier were hand-written, renaming a method in Java would silently rename a
        // method in every published SDK. Deriving it from the route makes the breaking change
        // visible as a route change, which is already a reviewed event.
        assertThat(unstable).as("SDK method names follow the route, not the implementation")
                .isEmpty();
    }

    @Test
    void theServedContractIsByteIdenticalToTheBundledAndReviewedArtifact() throws Exception {
        MvcResult result = mockMvc.perform(get("/developer/openapi/2026-08-01")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        byte[] served = result.getResponse().getContentAsByteArray();
        assertThat(served).as("the runtime serves the reviewed bytes without re-rendering")
                .isEqualTo(bundledBytes);

        String declaredDigest = result.getResponse().getHeader("X-Thaap-OpenAPI-SHA256");
        assertThat(declaredDigest).as("the response states the digest of what it sent")
                .isEqualTo(sha256(served));
    }

    @Test
    void theContractIsServedFromAnImmutableResourceRatherThanRegeneratedPerRequest()
            throws Exception {
        // Regenerating per request would make the digest a function of ordering, timestamps, and
        // classpath scan order, so two integrators could verify different artifacts on the same
        // deployment. Repeated reads must be indistinguishable.
        Set<String> digests = new LinkedHashSet<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            MvcResult result = mockMvc.perform(get("/developer/openapi/2026-08-01")).andReturn();
            digests.add(sha256(result.getResponse().getContentAsByteArray()));
        }
        assertThat(digests).as("every read of the contract returns the same artifact").hasSize(1);
    }

    @Test
    void theContractDeclaresTheVersionItIsServedUnderAndTheCatalogueAgrees() throws Exception {
        assertThat(contract.path("openapi").asText()).as("a supported OpenAPI dialect")
                .startsWith("3.");
        assertThat(contract.path("info").path("version").asText())
                .as("the document names the contract version it describes")
                .isEqualTo("2026-08-01");

        MvcResult result = mockMvc.perform(get("/developer/api-versions")).andReturn();
        JsonNode catalogue = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(catalogue.path("defaultVersion").asText())
                .as("the version an absent header resolves to is the version documented")
                .isEqualTo(contract.path("info").path("version").asText());
    }

    @Test
    void everyOperationDocumentsTheVersionHeaderOnItsRequestAndOnEveryResponse() {
        List<String> gaps = new ArrayList<>();
        forEachOperation((method, path, node) -> {
            long versionParameters = node.path("parameters").valueStream()
                    .filter(parameter -> "header".equals(parameter.path("in").asText())
                            && "Thaap-Version".equals(parameter.path("name").asText()))
                    .count();
            if (versionParameters != 1) {
                gaps.add(method + " " + path + " declares " + versionParameters
                        + " version headers");
            }
            node.path("responses").properties().forEach(response -> {
                if (!response.getValue().path("headers").has("Thaap-Version")) {
                    gaps.add(method + " " + path + " response " + response.getKey()
                            + " does not state its effective version");
                }
            });
        });
        assertThat(gaps).as("version negotiation is documented on every request and response")
                .isEmpty();
    }

    @Test
    void everyOperationDocumentsTheStableErrorEnvelopeAsItsDefaultOutcome() {
        List<String> gaps = new ArrayList<>();
        forEachOperation((method, path, node) -> {
            JsonNode fallback = node.path("responses").path("default");
            if (fallback.isMissingNode()) {
                gaps.add(method + " " + path + " has no default error response");
                return;
            }
            String reference = fallback.path("content").path("application/json")
                    .path("schema").path("$ref").asText("");
            if (!reference.endsWith("/ApiErrorResponse")) {
                gaps.add(method + " " + path + " does not use the shared error envelope");
            }
        });
        // Without this a client must write a distinct failure branch per operation, which is how
        // integrators end up parsing message.
        assertThat(gaps).as("one error envelope covers every operation").isEmpty();
    }

    private Set<String> routedPublicOperations() {
        Set<String> operations = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : patterns(info)) {
                if (isNonProduct(path) || path.contains("*")) continue;
                info.getMethodsCondition().getMethods()
                        .forEach(method -> operations.add(method.name() + " " + path));
            }
        });
        return operations;
    }

    private Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of()
                : info.getPatternsCondition().getPatterns();
    }

    private boolean isNonProduct(String path) {
        return NON_PRODUCT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Set<String> publishedOperations() {
        Set<String> operations = new TreeSet<>();
        forEachOperation((method, path, node) -> operations.add(method + " " + path));
        return operations;
    }

    private void forEachOperation(OperationConsumer consumer) {
        List<String> seen = new ArrayList<>();
        contract.path("paths").properties().forEach(pathEntry ->
                pathEntry.getValue().properties().forEach(methodEntry -> {
                    String method = methodEntry.getKey().toUpperCase(Locale.ROOT);
                    if (!HTTP_METHODS.contains(method)) return;
                    seen.add(method + " " + pathEntry.getKey());
                    consumer.accept(method, pathEntry.getKey(), methodEntry.getValue());
                }));
        assertThat(seen).as("the contract publishes operations").isNotEmpty();
    }

    /** Mirrors the generator's rule so a change to either side has to be a deliberate one. */
    private String derivedOperationId(String method, String path) {
        StringBuilder result = new StringBuilder(method.toLowerCase(Locale.ROOT));
        boolean uppercase = true;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '{') {
                result.append("By");
                uppercase = true;
                continue;
            }
            if (Character.isLetterOrDigit(character)) {
                result.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            } else {
                uppercase = true;
            }
        }
        return result.toString();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @FunctionalInterface
    private interface OperationConsumer {
        void accept(String method, String path, JsonNode operation);
    }
}
