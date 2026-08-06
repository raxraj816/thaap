package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Proves C57-TEN-001. A security declaration is only worth what the runtime enforces, so this
 * suite never reads the contract against itself. It derives the credential each operation
 * actually requires from the handler signature that Spring will invoke, and requires the
 * published document to say exactly that: no operation may under-declare a credential it
 * enforces, over-declare one it does not, or leave a caller unable to tell which key opens it.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class OpenApiSecurityContractTests {

    private static final String MERCHANT_SCHEME = "merchantApi";
    private static final String RISK_OPERATOR_SCHEME = "riskOperator";
    private static final String SCENARIO_RUNNER_SCHEME = "scenarioRunner";

    /**
     * Documentation surfaces are deliberately open so an integrator can read the contract before
     * they hold any credential. Every other public operation must name a credential.
     */
    private static final Set<String> PUBLIC_DOCUMENTATION_OPERATIONS = Set.of(
            "GET /developer/api-versions",
            "GET /developer/error-catalog",
            "GET /developer/openapi/{version}",
            "GET /developer/openapi/{version}/digest",
            "GET /developer/test-cards");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private JsonNode contract;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("openapi/thaap-public-api-2026-08-01.yaml")) {
            assertThat(stream).as("the bundled public contract is present").isNotNull();
            contract = new YAMLMapper().readTree(stream);
        }
    }

    @Test
    void everyDeclaredCredentialIsTheCredentialTheHandlerActuallyEnforces() {
        Map<String, Set<String>> enforced = enforcedCredentials();
        Map<String, Set<String>> declared = declaredCredentials();

        List<String> disagreements = new ArrayList<>();
        declared.forEach((operation, declaredSchemes) -> {
            Set<String> enforcedSchemes = enforced.get(operation);
            if (enforcedSchemes == null) {
                disagreements.add(operation + " is published but has no handler");
                return;
            }
            if (!declaredSchemes.equals(enforcedSchemes)) {
                disagreements.add(operation + " declares " + declaredSchemes
                        + " but enforces " + enforcedSchemes);
            }
        });
        assertThat(disagreements)
                .as("the published credential boundary matches the enforced one").isEmpty();
    }

    @Test
    void noOperationOutsideTheDocumentationSurfaceIsPublishedAsOpen() {
        List<String> open = declaredCredentials().entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).sorted().toList();

        // An operation published with security:[] tells every generated client it needs no key.
        // If that ever becomes true of a tenant-scoped route, the contract itself is the breach.
        assertThat(open).as("only the documentation surface is published as unauthenticated")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_DOCUMENTATION_OPERATIONS);
    }

    @Test
    void anOpenOperationReadsNoTenantScopedStateAtRuntime() {
        Map<String, Set<String>> enforced = enforcedCredentials();
        for (String operation : PUBLIC_DOCUMENTATION_OPERATIONS) {
            assertThat(enforced.get(operation))
                    .as("%s must not resolve any tenant or operator identity", operation)
                    .isEmpty();
        }
    }

    @Test
    void merchantAndRiskOperatorCredentialsAreNeverInterchangeable() {
        Map<String, Set<String>> declared = declaredCredentials();

        List<String> dualAccess = declared.entrySet().stream()
                .filter(entry -> entry.getValue().contains(MERCHANT_SCHEME)
                        && entry.getValue().contains(RISK_OPERATOR_SCHEME))
                .map(Map.Entry::getKey).sorted().toList();
        assertThat(dualAccess)
                .as("no operation accepts a merchant key and an operator key as equivalents")
                .isEmpty();

        // OpenAPI encodes alternatives as separate requirement objects. If any operation ever
        // published two of them, a client could substitute one credential class for another.
        List<String> alternatives = new ArrayList<>();
        forEachOperation((operation, node) -> {
            JsonNode security = node.path("security");
            if (security.isArray() && security.size() > 1) {
                alternatives.add(operation);
            }
        });
        assertThat(alternatives)
                .as("no operation publishes interchangeable credential alternatives").isEmpty();
    }

    @Test
    void everyReferencedSchemeIsDefinedAsAHeaderCredentialAndNothingElseIsDefined() {
        JsonNode schemes = contract.path("components").path("securitySchemes");
        Set<String> defined = new LinkedHashSet<>();
        schemes.fieldNames().forEachRemaining(defined::add);
        assertThat(defined).containsExactlyInAnyOrder(
                MERCHANT_SCHEME, RISK_OPERATOR_SCHEME, SCENARIO_RUNNER_SCHEME);

        Map<String, String> expectedHeaders = Map.of(
                MERCHANT_SCHEME, "X-Api-Key",
                RISK_OPERATOR_SCHEME, "X-Risk-Operator-Key",
                SCENARIO_RUNNER_SCHEME, "X-Thaap-Scenario-Runner-Token");
        expectedHeaders.forEach((scheme, header) -> {
            JsonNode definition = schemes.path(scheme);
            assertThat(definition.path("type").asText()).as("%s is an apiKey", scheme)
                    .isEqualTo("apiKey");
            assertThat(definition.path("in").asText()).as("%s travels in a header", scheme)
                    .isEqualTo("header");
            assertThat(definition.path("name").asText()).as("%s names its header", scheme)
                    .isEqualTo(header);
        });

        Set<String> referenced = new LinkedHashSet<>();
        declaredCredentials().values().forEach(referenced::addAll);
        assertThat(defined).as("no credential is defined that no operation can use")
                .containsAll(referenced);
    }

    @Test
    void noCredentialIsEverPublishedWithAnExampleOrDefaultValue() {
        JsonNode schemes = contract.path("components").path("securitySchemes");
        schemes.properties().forEach(entry -> {
            JsonNode definition = entry.getValue();
            assertThat(definition.has("example")).as("%s ships no example key", entry.getKey())
                    .isFalse();
            assertThat(definition.has("default")).as("%s ships no default key", entry.getKey())
                    .isFalse();
        });

        // The optional sandbox token is documented as a parameter, so it gets the same treatment.
        forEachOperation((operation, node) -> node.path("parameters").forEach(parameter -> {
            if (!"X-Thaap-Scenario-Runner-Token".equals(parameter.path("name").asText())) {
                return;
            }
            assertThat(parameter.path("required").asBoolean(false))
                    .as("%s does not require the sandbox token", operation).isFalse();
            assertThat(parameter.path("schema").has("example"))
                    .as("%s ships no sandbox token value", operation).isFalse();
            assertThat(parameter.path("schema").has("default"))
                    .as("%s ships no sandbox token default", operation).isFalse();
        }));
    }

    /**
     * Reads the credential each public operation actually requires from the handler Spring will
     * invoke. {@code MerchantContext} cannot be resolved without a valid {@code X-Api-Key} and
     * {@code RiskOperatorContext} cannot be resolved without a valid operator key, so the
     * presence of those parameters is the enforcement fact the contract must mirror.
     */
    private Map<String, Set<String>> enforcedCredentials() {
        Map<String, Set<String>> enforced = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String operation : operationKeys(info)) {
                enforced.put(operation, credentialsOf(handler));
            }
        });
        return enforced;
    }

    private Set<String> credentialsOf(HandlerMethod handler) {
        Set<String> credentials = new LinkedHashSet<>();
        for (MethodParameter parameter : handler.getMethodParameters()) {
            Class<?> type = parameter.getParameterType();
            if (MerchantContext.class.equals(type)) {
                credentials.add(MERCHANT_SCHEME);
            } else if (RiskOperatorContext.class.equals(type)) {
                credentials.add(RISK_OPERATOR_SCHEME);
            }
        }
        // A parameter scan sees resolver-enforced credentials only. The scenario runner
        // requirement lives in a method body, so it is declared with @RequiresScenarioRunner
        // at the enforcement point and read from there by both this test and the contract
        // generator. Before ER-0098 each generalised over the path prefix instead, and they
        // were wrong in opposite directions.
        if (handler.getMethodAnnotation(RequiresScenarioRunner.class) != null) {
            credentials.add(SCENARIO_RUNNER_SCHEME);
        }
        return credentials;
    }

    private List<String> operationKeys(RequestMappingInfo info) {
        PatternsRequestCondition patterns = info.getPatternsCondition();
        Set<String> paths = patterns != null ? patterns.getPatterns()
                : info.getPathPatternsCondition() == null ? Set.of()
                        : info.getPathPatternsCondition().getPatternValues();
        List<String> keys = new ArrayList<>();
        for (String path : paths) {
            if (isInternal(path)) continue;
            info.getMethodsCondition().getMethods()
                    .forEach(method -> keys.add(method.name() + " " + path));
        }
        return keys;
    }

    private boolean isInternal(String path) {
        return path.startsWith("/internal") || path.startsWith("/actuator")
                || path.startsWith("/admin") || path.startsWith("/platform")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")
                || path.contains("*");
    }

    private Map<String, Set<String>> declaredCredentials() {
        Map<String, Set<String>> declared = new TreeMap<>();
        forEachOperation((operation, node) -> {
            Set<String> schemes = new LinkedHashSet<>();
            node.path("security").forEach(requirement ->
                    requirement.fieldNames().forEachRemaining(schemes::add));
            declared.put(operation, schemes);
        });
        return declared;
    }

    private void forEachOperation(java.util.function.BiConsumer<String, JsonNode> consumer) {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        contract.path("paths").properties().forEach(pathEntry ->
                pathEntry.getValue().properties().forEach(methodEntry -> {
                    String method = methodEntry.getKey().toUpperCase();
                    if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) return;
                    operations.put(method + " " + pathEntry.getKey(), methodEntry.getValue());
                }));
        assertThat(operations).as("the contract publishes operations").isNotEmpty();
        operations.forEach(consumer);
    }
}
