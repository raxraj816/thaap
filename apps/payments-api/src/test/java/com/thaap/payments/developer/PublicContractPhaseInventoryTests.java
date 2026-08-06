package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves C59-API-001: the final phase-wide inventory.
 *
 * <p>Each chapter proved its own routes. This class owns the question none of them could
 * answer alone — whether the one document every integrator and code generator reads still
 * describes the whole product, exactly once, and whether the shipped client was built from
 * that same document. The failure this forecloses is quiet: a route added late by one
 * chapter never reaches the contract, and the omission is only discovered by a merchant
 * whose generated client has no method for it.
 */
class PublicContractPhaseInventoryTests {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path COMMITTED_CONTRACT =
            REPOSITORY_ROOT.resolve("openapi/thaap-public-api-2026-08-01.yaml");
    private static final Path SDK_CONTRACT_DIGEST = REPOSITORY_ROOT.resolve(
            "clients/python/thaap_sdk/thaap_sdk/generated/CONTRACT_SHA256");
    private static final Path SDK_OPERATIONS =
            REPOSITORY_ROOT.resolve("clients/python/thaap_sdk/thaap_sdk/generated/api");

    /**
     * The capability families Phase 6 promised as public surface. Listing the families
     * rather than 133 individual paths keeps the assertion about the product promise: if a
     * whole capability silently stops being published, this fails, and it does not churn
     * every time one operation is added inside a family that is already covered.
     */
    private static final List<String> PROMISED_PHASE_6_SURFACES = List.of(
            "/risk/workspace/payments/search",
            "/risk/workspace/payments/{paymentIntentId}",
            "/risk/workspace/payments/{paymentIntentId}/timeline",
            "/risk/workspace/payments/{paymentIntentId}/related",
            "/risk/workspace/payments/{paymentIntentId}/webhook-activity",
            "/risk/workspace/payment-views",
            "/risk/workspace/review-bulk-actions",
            "/risk/workspace/insights/decision-outcomes",
            "/risk/workspace/insights/review-operations",
            "/risk/workspace/insights/operators",
            "/developer/api-versions",
            "/developer/error-catalog");

    private static final List<String> INTERNAL_PREFIXES =
            List.of("/internal", "/actuator", "/admin", "/platform", "/v3/api-docs", "/swagger-ui");

    private JsonNode contract;

    @BeforeEach
    void setUp() throws Exception {
        contract = new YAMLMapper().readTree(Files.readString(COMMITTED_CONTRACT, StandardCharsets.UTF_8));
    }

    @Test
    void everyPromisedPhaseSixSurfaceIsPublishedExactlyOnce() {
        Set<String> publishedPaths = new LinkedHashSet<>();
        contract.get("paths").fieldNames().forEachRemaining(publishedPaths::add);

        List<String> missing = PROMISED_PHASE_6_SURFACES.stream()
                .filter(promised -> publishedPaths.stream().noneMatch(
                        published -> published.equals(promised)))
                .toList();

        assertThat(missing)
                .as("Phase 6 promised these surfaces; a merchant's generated client has no "
                        + "method for anything absent here")
                .isEmpty();
    }

    @Test
    void noInternalOrFrameworkSurfaceReachesThePublishedContract() {
        List<String> leaked = new ArrayList<>();
        contract.get("paths").fieldNames().forEachRemaining(path -> {
            if (INTERNAL_PREFIXES.stream().anyMatch(path::startsWith)) {
                leaked.add(path);
            }
        });
        assertThat(leaked)
                .as("an internal recovery or platform route in the public contract is an "
                        + "invitation, not documentation")
                .isEmpty();
    }

    @Test
    void everyOperationIdIsPresentAndUniqueAcrossTheWholeContract() {
        // A duplicate operation id does not fail generation. It silently produces one client
        // method where there should be two, and the second endpoint becomes unreachable.
        TreeMap<String, List<String>> operationIdOwners = new TreeMap<>();
        contract.get("paths").fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                    if (!isHttpMethod(methodEntry.getKey())) {
                        return;
                    }
                    JsonNode operationId = methodEntry.getValue().get("operationId");
                    assertThat(operationId)
                            .as("%s %s has no operationId", methodEntry.getKey(), pathEntry.getKey())
                            .isNotNull();
                    operationIdOwners
                            .computeIfAbsent(operationId.asText(), key -> new ArrayList<>())
                            .add(methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey());
                }));

        List<String> duplicated = operationIdOwners.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .toList();

        assertThat(duplicated).isEmpty();
        assertThat(operationIdOwners).hasSizeGreaterThanOrEqualTo(133);
    }

    @Test
    void theShippedClientWasGeneratedFromTheContractThatIsActuallyCommitted() throws Exception {
        // This is the assertion whose absence let a stale SDK ship: the regeneration script
        // proved byte-stability, but nothing compared the committed client's source digest to
        // the committed contract, so a client generated from an older contract passed every
        // gate that ran. See ER-0096.
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(COMMITTED_CONTRACT)));
        String recorded = Files.readString(SDK_CONTRACT_DIGEST, StandardCharsets.UTF_8).strip();

        assertThat(recorded)
                .as("the committed Python SDK must be generated from the committed contract; "
                        + "run scripts/generate-python-sdk.sh")
                .isEqualTo(expected);
    }

    @Test
    void theShippedClientExposesTheInvestigationAndInsightOperationsPhaseSixPromised() throws Exception {
        // Publishing an endpoint in the contract while the generated client lacks the module
        // would satisfy every contract-side assertion and still leave the promise unmet.
        List<String> requiredModules = List.of(
                "payment_investigation_controller_api.py",
                "workspace_insights_controller_api.py",
                "review_bulk_action_controller_api.py",
                "payment_investigation_saved_view_controller_api.py",
                "developer_contract_controller_api.py",
                "risk_analytics_controller_api.py");

        List<String> absent = new ArrayList<>();
        for (String module : requiredModules) {
            if (!Files.isRegularFile(SDK_OPERATIONS.resolve(module))) {
                absent.add(module);
            }
        }
        assertThat(absent).isEmpty();
    }

    @Test
    void everyCursorPagedInvestigationRoutePublishesOneCollectionFieldName() {
        // Closes TD-0006. The three navigation routes were otherwise the identical cursor
        // contract while naming their rows differently, so an integrator who wrote one correct
        // pager had to special-case the collection key per route — and a per-route branch is
        // exactly where pagination defects hide. Asserting the single name is the point;
        // tolerating either would have preserved the wart it is meant to remove.
        List<String> cursorPagedRoutes = List.of(
                "/risk/workspace/payments/search",
                "/risk/workspace/payments/{paymentIntentId}/timeline",
                "/risk/workspace/payments/{paymentIntentId}/related",
                "/risk/workspace/payments/{paymentIntentId}/webhook-activity");

        List<String> disagreements = new ArrayList<>();
        for (String route : cursorPagedRoutes) {
            JsonNode pathItem = contract.get("paths").get(route);
            assertThat(pathItem).as("%s is published", route).isNotNull();
            pathItem.fields().forEachRemaining(methodEntry -> {
                if (!isHttpMethod(methodEntry.getKey())) {
                    return;
                }
                JsonNode schema = successSchema(methodEntry.getValue());
                if (schema == null) {
                    return;
                }
                JsonNode properties = resolve(schema).path("properties");
                if (!properties.has("data")) {
                    disagreements.add(route + " does not publish its rows as 'data'");
                }
                if (properties.has("entries")) {
                    disagreements.add(route + " still publishes the superseded 'entries' field");
                }
            });
        }
        assertThat(disagreements).isEmpty();
    }

    private JsonNode successSchema(JsonNode operation) {
        JsonNode responses = operation.path("responses");
        for (String status : List.of("200", "201", "202")) {
            JsonNode content = responses.path(status).path("content");
            if (content.isObject()) {
                JsonNode media = content.elements().next();
                return media.path("schema");
            }
        }
        return null;
    }

    private JsonNode resolve(JsonNode schema) {
        JsonNode reference = schema.get("$ref");
        if (reference == null) {
            return schema;
        }
        String name = reference.asText().substring("#/components/schemas/".length());
        return contract.get("components").get("schemas").get(name);
    }

    private static boolean isHttpMethod(String candidate) {
        return Set.of("get", "post", "put", "patch", "delete").contains(candidate);
    }
}
