package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.observability.DecisionIntelligenceHealthIndicator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class VersionedPublicContractTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DecisionIntelligenceHealthIndicator healthIndicator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
    }

    @Test
    void absentAndCurrentVersionsResolveToTheCurrentContractOnSuccessAndErrors() throws Exception {
        mockMvc.perform(get("/developer/api-versions"))
                .andExpect(status().isOk())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.defaultVersion").value(ApiVersionFilter.CURRENT_VERSION));

        mockMvc.perform(get("/developer/error-catalog")
                        .header(ApiVersionFilter.HEADER, ApiVersionFilter.CURRENT_VERSION))
                .andExpect(status().isOk())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(jsonPath("$.errors[?(@.code == 'query_timeout')].retryable")
                        .value(true));

        mockMvc.perform(get("/developer/openapi/not-a-version"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(jsonPath("$.error").value("openapi_version_not_found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void malformedUnsupportedAndDuplicateVersionHeadersFailBeforeControllerDispatch() throws Exception {
        assertVersionFailure("2026-8-1", "malformed_api_version");
        assertVersionFailure("2026-02-29", "malformed_api_version");
        assertVersionFailure("２０２６-08-01", "malformed_api_version");
        assertVersionFailure("2025-01-01", "unsupported_api_version");
        assertVersionFailure("", "malformed_api_version");

        mockMvc.perform(get("/developer/api-versions")
                        .header(ApiVersionFilter.HEADER, " 2026-08-01 "))
                .andExpect(status().isOk())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION));

        MvcResult duplicate = mockMvc.perform(get("/developer/api-versions")
                        .header(ApiVersionFilter.HEADER,
                                ApiVersionFilter.CURRENT_VERSION, ApiVersionFilter.CURRENT_VERSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_api_version"))
                .andReturn();
        assertThat(duplicate.getResponse().getContentAsString())
                .doesNotContain(ApiVersionFilter.CURRENT_VERSION + "," + ApiVersionFilter.CURRENT_VERSION);
    }

    @Test
    void servedArtifactIsByteExactAndRuntimeContractExcludesInternalSurfaces() throws Exception {
        byte[] committed = new ClassPathResource(
                "openapi/thaap-public-api-2026-08-01.yaml").getInputStream().readAllBytes();
        MvcResult served = mockMvc.perform(get("/developer/openapi/{version}",
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(status().isOk())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(header().string("X-Thaap-OpenAPI-SHA256", digest(committed)))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(committed);

        JsonNode runtime = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/public"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(runtime.at("/openapi").asText()).isEqualTo("3.0.1");
        assertThat(runtime.at("/paths").fieldNames()).toIterable()
                .noneMatch(path -> path.startsWith("/internal/") || path.startsWith("/admin/")
                        || path.startsWith("/platform/") || path.startsWith("/actuator/"));
        assertThat(runtime.at("/paths/~1risk~1workspace~1payments~1search/post/security/0/riskOperator")
                .isArray()).isTrue();
        assertThat(runtime.at("/paths/~1developer~1api-versions/get/security").isArray()).isTrue();
        assertThat(runtime.at("/paths/~1developer~1api-versions/get/security").isEmpty()).isTrue();
        assertThat(runtime.at("/paths/~1customers/post/responses/201").isObject()).isTrue();
        assertThat(runtime.at("/paths/~1customers/post/responses/200").isMissingNode()).isTrue();
        assertThat(runtime.at("/paths/~1risk~1workspace~1review-bulk-actions/post/responses/202")
                .isObject()).isTrue();
        assertThat(runtime.at("/paths/~1customers/post/responses/default/headers/Thaap-Version")
                .isObject()).isTrue();
        assertThat(runtime.at("/paths/~1risk~1operators/post/security/0/merchantApi")
                .isArray()).isTrue();
        assertThat(runtime.at("/paths/~1risk~1operators/post/security/0/riskOperator")
                .isMissingNode()).isTrue();
        for (String pointer : List.of(
                "/paths/~1developer~1scenario-runs/post",
                "/paths/~1developer~1scenario-runs~1{runId}/get",
                "/paths/~1developer~1scenario-runs~1{runId}~1claims/post",
                "/paths/~1developer~1scenario-runs~1{runId}~1steps~1{stepIndex}~1complete/post",
                "/paths/~1developer~1scenario-runs~1{runId}~1fail/post",
                "/paths/~1developer~1scenario-runs~1{runId}~1report/get")) {
            // Every scenario operation reaches DeveloperScenarioService.requireSynthetic,
            // which rejects any context whose TrafficOrigin is not SYNTHETIC_SCENARIO — an
            // origin only a valid runner token produces. A merchant key alone is therefore
            // insufficient for all six, and the contract must say so; a generated client that
            // omitted the token would fail every documented sandbox call.
            assertThat(runtime.at(pointer + "/security/0").fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("merchantApi", "scenarioRunner");
            assertThat(runtime.at(pointer + "/parameters").valueStream()
                    .noneMatch(parameter -> "X-Thaap-Scenario-Runner-Token"
                            .equals(parameter.path("name").asText())))
                    .as("%s must not publish the token as a bare header as well", pointer)
                    .isTrue();
        }
        // The optional-qualifier form still exists, on ordinary merchant operations where
        // the token only tags traffic as synthetic rather than authorising the call.
        assertThat(runtime.at("/paths/~1customers/post/parameters").valueStream()
                .anyMatch(parameter -> "X-Thaap-Scenario-Runner-Token"
                        .equals(parameter.path("name").asText())
                        && !parameter.path("required").asBoolean(false)))
                .as("ordinary merchant operations document the optional sandbox token")
                .isTrue();
        assertThat(runtime.at("/components/schemas/StepView/properties/body/additionalProperties")
                .isBoolean()).isTrue();
        assertThat(runtime.at("/components/schemas/TimelineEntry/properties/safeFacts/additionalProperties")
                .isBoolean()).isTrue();
        assertThat(runtime.at("/components/schemas/FeatureValueView/properties/value/type")
                .isMissingNode()).isTrue();
    }

    @Test
    void committedArtifactDescribesTheRunningApiAndNotAStaleSnapshot() throws Exception {
        String runtimeContract = mockMvc.perform(get("/v3/api-docs/public"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // Publish the running contract so scripts/generate-openapi-contract.py can freeze
        // the artifact from the same JVM that serves it, instead of a separately booted app.
        Path runtimeDump = Path.of("target", "openapi", "runtime-public-contract.json");
        Files.createDirectories(runtimeDump.getParent());
        Files.writeString(runtimeDump, runtimeContract, StandardCharsets.UTF_8);

        JsonNode committed = objectMapper.readTree(new ClassPathResource(
                "openapi/thaap-public-api-2026-08-01.yaml").getInputStream());
        assertThat(objectMapper.readTree(runtimeContract))
                .as("committed contract is stale; regenerate with "
                        + "scripts/generate-openapi-contract.py --source apps/payments-api/%s",
                        runtimeDump)
                .isEqualTo(committed);
    }

    @Test
    void publishedContractEnumeratesEveryStableErrorCodeSoClientsNeverParseMessages() throws Exception {
        JsonNode runtime = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/public"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode publishedCodes = runtime.at(
                "/components/schemas/ApiErrorResponse/properties/error/x-extensible-enum");
        assertThat(publishedCodes.isArray()).isTrue();
        List<String> published = new ArrayList<>();
        publishedCodes.forEach(node -> published.add(node.asText()));
        List<String> catalogue = Arrays.stream(PublicApiErrorDescriptor.values())
                .map(PublicApiErrorDescriptor::code)
                .distinct()
                .sorted()
                .toList();
        assertThat(published)
                .as("every emitted failure class must be discoverable in the published contract")
                .containsExactlyElementsOf(catalogue);

        // A closed enum would make adding a code a breaking change for strict clients.
        assertThat(runtime.at("/components/schemas/ApiErrorResponse/properties/error/enum")
                .isMissingNode()).isTrue();

        JsonNode publishedActions = runtime.at(
                "/components/schemas/ApiErrorResponse/properties/recoveryAction/x-extensible-enum");
        List<String> actions = new ArrayList<>();
        publishedActions.forEach(node -> actions.add(node.asText()));
        assertThat(actions).containsExactlyElementsOf(Arrays.stream(PublicApiErrorDescriptor.values())
                .map(PublicApiErrorDescriptor::recoveryAction)
                .distinct()
                .sorted()
                .toList());

        // message must be documented as non-contractual so SDKs and merchants do not branch on prose.
        assertThat(runtime.at("/components/schemas/ApiErrorResponse/properties/message/description").asText())
                .contains("Not a compatibility contract");
        assertThat(runtime.at("/components/schemas/ApiErrorResponse/properties/error/description").asText())
                .contains("never parse message");

        // The rendered example must itself be a member of the catalogue it advertises.
        JsonNode example = runtime.at(
                "/paths/~1payment_intents/post/responses/default/content/application~1json/example");
        assertThat(published).contains(example.at("/error").asText());
        assertThat(actions).contains(example.at("/recoveryAction").asText());
    }

    @Test
    void readinessContributorProvesDatabaseArtifactAndBacklogBoundaries() {
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("openApiArtifact", "AVAILABLE")
                .containsKeys("bulkBacklog", "relatedAnchorRows");
    }

    private void assertVersionFailure(String version, String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/developer/api-versions")
                        .header(ApiVersionFilter.HEADER, version))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(ApiVersionFilter.HEADER,
                        ApiVersionFilter.CURRENT_VERSION))
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(version + " is not supported");
    }

    private String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
