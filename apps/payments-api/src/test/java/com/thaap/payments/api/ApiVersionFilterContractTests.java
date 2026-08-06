package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C57-VER-001. Every intentional public success and error resolves exactly one
 * contract version. The header grammar is a closed decision with no third answer, the
 * effective version is returned on every response family, an unusable requested value is
 * never echoed back as effective truth, and the refusal is decided before authentication
 * or any handler runs.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class ApiVersionFilterContractTests {

    private static final String CURRENT = ApiVersionFilter.CURRENT_VERSION;
    private static final String MALFORMED = PublicApiErrorDescriptor.MALFORMED_API_VERSION.code();
    private static final String UNSUPPORTED = PublicApiErrorDescriptor.UNSUPPORTED_API_VERSION.code();

    /**
     * The closed grammar. Every partition names the property that makes it interesting,
     * so a future relaxation of the parser cannot silently reclassify one of them.
     */
    private static final List<VersionPartition> GRAMMAR = List.of(
            new VersionPartition("exact current value", CURRENT, null),
            new VersionPartition("leading and trailing spaces", " " + CURRENT + " ", null),
            new VersionPartition("leading and trailing tabs", "\t" + CURRENT + "\t", null),
            new VersionPartition("empty value", "", MALFORMED),
            new VersionPartition("whitespace-only value", "    ", MALFORMED),
            new VersionPartition("bare comma", ",", MALFORMED),
            new VersionPartition("trailing comma", CURRENT + ",", MALFORMED),
            new VersionPartition("comma-combined duplicate", CURRENT + "," + CURRENT, MALFORMED),
            new VersionPartition("comma-combined distinct", CURRENT + ",2025-01-01", MALFORMED),
            new VersionPartition("unpadded month and day", "2026-8-1", MALFORMED),
            new VersionPartition("slash separators", "2026/08/01", MALFORMED),
            new VersionPartition("non-leap February 29", "2026-02-29", MALFORMED),
            new VersionPartition("month above range", "2026-13-01", MALFORMED),
            new VersionPartition("month below range", "2026-00-01", MALFORMED),
            new VersionPartition("day above range", "2026-08-32", MALFORMED),
            new VersionPartition("fullwidth Unicode digits", "２０２６-08-01", MALFORMED),
            new VersionPartition("Arabic-Indic Unicode digits", "٢٠٢٦-٠٨-٠١", MALFORMED),
            new VersionPartition("trailing character past the fixed width", CURRENT + "x", MALFORMED),
            new VersionPartition("embedded NUL", "2026-08-0\u00001", MALFORMED),
            new VersionPartition("non-breaking space is not trimmed", CURRENT + "\u00a0", MALFORMED),
            new VersionPartition("oversized value", "9".repeat(4096), MALFORMED),
            new VersionPartition("SQL-shaped value", "2026-08-01' OR '1'='1", MALFORMED),
            new VersionPartition("valid leap day that is not the contract", "2024-02-29", UNSUPPORTED),
            new VersionPartition("one day before the contract", "2026-07-31", UNSUPPORTED),
            new VersionPartition("one day after the contract", "2026-08-02", UNSUPPORTED),
            new VersionPartition("distant past", "2020-01-01", UNSUPPORTED),
            new VersionPartition("distant future", "2099-12-31", UNSUPPORTED));

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
    }

    @Test
    void everyHeaderGrammarPartitionResolvesToExactlyOneDeterministicOutcome() throws Exception {
        // The absent header is the compatibility promise: an unversioned client keeps working.
        MvcResult absent = mockMvc.perform(get("/developer/api-versions")).andReturn();
        assertThat(absent.getResponse().getStatus()).as("absent header resolves compatibly").isEqualTo(200);

        for (VersionPartition partition : GRAMMAR) {
            MvcResult result = mockMvc.perform(get("/developer/api-versions")
                    .header(ApiVersionFilter.HEADER, partition.value())).andReturn();
            if (partition.expectedErrorCode() == null) {
                assertThat(result.getResponse().getStatus())
                        .as("%s selects the contract", partition.name()).isEqualTo(200);
                continue;
            }
            assertThat(result.getResponse().getStatus())
                    .as("%s is refused", partition.name()).isEqualTo(400);
            assertThat(body(result).path("error").asText())
                    .as("%s is classified exactly once", partition.name())
                    .isEqualTo(partition.expectedErrorCode());
        }
    }

    @Test
    void theEffectiveVersionIsReturnedOnEverySuccessAndErrorFamily() throws Exception {
        // Success, refused version, unauthenticated, unknown route, wrong method, wrong media.
        List<RequestBuilder> families = List.of(
                get("/developer/api-versions"),
                get("/developer/api-versions").header(ApiVersionFilter.HEADER, "2020-01-01"),
                post("/payment_intents").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/developer/no-such-contract-surface"),
                delete("/developer/api-versions"),
                post("/payment_intents").contentType(MediaType.TEXT_PLAIN).content("plain"));

        for (RequestBuilder family : families) {
            MvcResult result = mockMvc.perform(family).andReturn();
            assertThat(result.getResponse().getHeader(ApiVersionFilter.HEADER))
                    .as("status %s declares its effective contract version",
                            result.getResponse().getStatus())
                    .isEqualTo(CURRENT);
        }
    }

    @Test
    void anUnusableRequestedVersionIsNeverEchoedAsEffectiveTruth() throws Exception {
        String hostile = "2099-12-31";
        MvcResult result = mockMvc.perform(get("/developer/api-versions")
                .header(ApiVersionFilter.HEADER, hostile)).andReturn();

        assertThat(result.getResponse().getHeader(ApiVersionFilter.HEADER))
                .as("the response header states the contract that answered, not the one asked for")
                .isEqualTo(CURRENT);
        String payload = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(payload).as("a refused version is not reflected into the error body")
                .doesNotContain(hostile);
        JsonNode error = body(result);
        assertThat(error.path("error").asText()).isEqualTo(UNSUPPORTED);
        assertThat(error.path("retryable").asBoolean())
                .as("an unsupported version is permanent until the client changes").isFalse();
        assertThat(error.path("recoveryAction").asText()).isNotBlank();
        assertThat(error.path("requestId").asText())
                .as("a version refusal is still correlatable in support")
                .isNotBlank().isNotEqualTo("unknown");
    }

    @Test
    void aVersionRefusalIsDecidedBeforeAuthenticationAndBeforeAnyHandler() throws Exception {
        // No credential and an unusable version. Version resolution owns the answer, so the
        // client is told what is actually wrong instead of being sent to fix the wrong thing.
        MvcResult unauthenticatedAndMalformed = mockMvc.perform(post("/payment_intents")
                .header(ApiVersionFilter.HEADER, "not-a-version")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(body(unauthenticatedAndMalformed).path("error").asText()).isEqualTo(MALFORMED);

        // The same request with a usable version reaches the credential boundary, which
        // proves the refusal above was ordering and not an accident of the route.
        MvcResult unauthenticatedOnly = mockMvc.perform(post("/payment_intents")
                .header(ApiVersionFilter.HEADER, CURRENT)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(body(unauthenticatedOnly).path("error").asText()).isNotEqualTo(MALFORMED);
        assertThat(unauthenticatedOnly.getResponse().getStatus()).isNotEqualTo(200);

        // A body that cannot deserialize is also never reached, so no parsing runs first.
        MvcResult malformedBody = mockMvc.perform(post("/payment_intents")
                .header(ApiVersionFilter.HEADER, "2020-01-01")
                .contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn();
        assertThat(body(malformedBody).path("error").asText()).isEqualTo(UNSUPPORTED);
    }

    @Test
    void versionResolutionDoesNotGovernInternalPlatformOrToolingSurfaces() throws Exception {
        // Internal operations are not part of the public contract, so they neither advertise
        // a public version nor become unreachable when a caller sends a bad one.
        for (String internalPath : List.of("/internal/data/publications", "/actuator/health",
                "/admin/merchants", "/platform/operators", "/v3/api-docs/public")) {
            MvcResult result = mockMvc.perform(get(internalPath)
                    .header(ApiVersionFilter.HEADER, "not-a-version")).andReturn();
            assertThat(result.getResponse().getHeader(ApiVersionFilter.HEADER))
                    .as("%s is outside the public version contract", internalPath).isNull();
            assertThat(result.getResponse().getStatus())
                    .as("%s is not refused by public version grammar", internalPath)
                    .isNotEqualTo(400);
        }
    }

    @Test
    void repeatedHeaderLinesAreRefusedRatherThanSilentlyCollapsed() throws Exception {
        // Two header lines are the classic smuggling shape: one proxy reads the first value
        // and one reads the last. Refusing removes the disagreement entirely.
        MvcResult repeated = mockMvc.perform(get("/developer/api-versions")
                .header(ApiVersionFilter.HEADER, CURRENT)
                .header(ApiVersionFilter.HEADER, "2020-01-01")).andReturn();
        assertThat(repeated.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(repeated).path("error").asText()).isEqualTo(MALFORMED);

        MvcResult repeatedIdentical = mockMvc.perform(get("/developer/api-versions")
                .header(ApiVersionFilter.HEADER, CURRENT)
                .header(ApiVersionFilter.HEADER, CURRENT)).andReturn();
        assertThat(repeatedIdentical.getResponse().getStatus())
                .as("agreement between duplicated lines is still ambiguity").isEqualTo(400);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private record VersionPartition(String name, String value, String expectedErrorCode) {
    }
}
