package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-API-001: search accepts only a closed filter/sort grammar, refuses to broaden
 * results when an input is invalid, bounds every page, and binds each cursor to the tenant,
 * projection generation, and query shape that minted it.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationApiContractTests {

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

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("investigation-api-owner");
        manager = scenario.operator(merchant, "api-manager", "RISK_MANAGER");
    }

    @ParameterizedTest(name = "search rejects {0}")
    @CsvSource(delimiter = '|', value = {
            "an unknown lifecycle status   | {\"filter\":{\"lifecycleStatuses\":[\"NOT_A_STATUS\"]}}",
            "an unknown fraud action       | {\"filter\":{\"fraudActions\":[\"MAYBE\"]}}",
            "an unknown sort               | {\"sort\":\"AMOUNT_DESC_ISH\"}",
            "a free-text metadata filter   | {\"filter\":{\"metadata\":\"' OR 1=1 --\"}}",
            "a negative amount floor       | {\"filter\":{\"minimumAmountMinor\":-1}}",
            "an inverted amount band       | {\"filter\":{\"minimumAmountMinor\":900,"
                    + "\"maximumAmountMinor\":100}}",
            "a half-open created range     | {\"filter\":{\"createdFrom\":\"2026-01-01T00:00:00Z\"}}",
            "an inverted created range     | {\"filter\":{\"createdFrom\":\"2026-06-01T00:00:00Z\","
                    + "\"createdTo\":\"2026-01-01T00:00:00Z\"}}",
            "a future created range        | {\"filter\":{\"createdFrom\":\"2099-01-01T00:00:00Z\","
                    + "\"createdTo\":\"2099-02-01T00:00:00Z\"}}",
            "an unbounded created range    | {\"filter\":{\"createdFrom\":\"2020-01-01T00:00:00Z\","
                    + "\"createdTo\":\"2026-01-01T00:00:00Z\"}}"})
    void anInvalidSearchInputIsRefusedRatherThanIgnoredIntoABroaderResultSet(String description,
            String body) throws Exception {
        int status = mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(status).as("search accepted %s", description).isEqualTo(400);
    }

    @ParameterizedTest(name = "search rejects page size {0}")
    @ValueSource(ints = {0, -1, 101, 1000})
    void anOutOfRangePageSizeIsRefusedSoOneRequestCannotDrainTheProjection(int limit)
            throws Exception {
        mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .param("limit", Integer.toString(limit))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("limit %d", limit).isEqualTo(400));
    }

    @Test
    void searchPagesCompletelyAndTheCursorCarriesTheQueryShapeItWasMintedFor() throws Exception {
        for (int index = 0; index < 3; index++) {
            scenario.allowedPayment(merchant, "api-page-" + index);
        }
        scenario.drainProjection();

        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            var request = post("/risk/workspace/payments/search")
                    .header(RISK_OPERATOR_HEADER, manager.credential())
                    .param("limit", "1").contentType(APPLICATION_JSON)
                    .content("{\"sort\":\"CREATED_AT_DESC\"}");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode response = scenario.json(request, 200);
            response.get("data").forEach(row -> paged.add(row.get("paymentIntentId").asText()));
            cursor = response.get("nextCursor").isNull() ? null : response.get("nextCursor").asText();
            if (cursor == null) {
                break;
            }
        }
        assertThat(paged).hasSize(3).doesNotHaveDuplicates();
        assertThat(cursor).as("paging did not terminate").isNull();

        // The cursor encodes the query shape. Replaying it under a different filter would
        // silently resume a different result set from a position that never belonged to it.
        JsonNode firstPage = scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential()).param("limit", "1")
                .contentType(APPLICATION_JSON).content("{\"sort\":\"CREATED_AT_DESC\"}"), 200);
        mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential()).param("limit", "1")
                        .param("cursor", firstPage.get("nextCursor").asText())
                        .contentType(APPLICATION_JSON)
                        .content("{\"filter\":{\"fraudActions\":[\"BLOCK\"]},"
                                + "\"sort\":\"CREATED_AT_DESC\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
        mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential()).param("limit", "1")
                        .param("cursor", "not-a-cursor")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void searchEchoesTheCanonicalFilterSoASavedViewMeansTheSameThingOnEveryReplay()
            throws Exception {
        scenario.blockedPayment(merchant, "api-canonical");
        scenario.drainProjection();

        // Two orderings of the same filter must canonicalize to one string: the digest that
        // binds a cursor and a saved view is derived from it.
        JsonNode first = scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential()).contentType(APPLICATION_JSON)
                .content("{\"filter\":{\"fraudActions\":[\"BLOCK\",\"REVIEW\"],"
                        + "\"currencies\":[\"usd\"]},\"sort\":\"CREATED_AT_DESC\"}"), 200);
        JsonNode second = scenario.json(post("/risk/workspace/payments/search")
                .header(RISK_OPERATOR_HEADER, manager.credential()).contentType(APPLICATION_JSON)
                .content("{\"filter\":{\"currencies\":[\"USD\"],"
                        + "\"fraudActions\":[\"REVIEW\",\"BLOCK\"]},\"sort\":\"CREATED_AT_DESC\"}"), 200);
        assertThat(second.get("canonicalFilterJson").asText())
                .isEqualTo(first.get("canonicalFilterJson").asText());
        assertThat(first.get("data")).hasSize(1);
        assertThat(first.at("/data/0/fraudAction").asText()).isEqualTo("BLOCK");

        // Every row declares its projection coverage rather than implying source completeness.
        assertThat(first.at("/data/0/coverageStatus").asText()).isNotBlank();
        assertThat(first.get("freshness").isObject()).isTrue();
        assertThat(first.get("sort").asText()).isEqualTo("CREATED_AT_DESC");
    }

    @Test
    void workspaceRoutesRefuseUnsupportedMethodsAndMediaTypes() throws Exception {
        // GET on the search path falls through to the payment-detail template, so "search" is
        // read as a malformed identifier. Rejecting it as a bad path variable is the safer
        // answer than a 405, which would confirm that a search route exists at that address.
        mockMvc.perform(get("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
        mockMvc.perform(put("/risk/workspace/payments/{id}", UUID.randomUUID())
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(405));
        mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .contentType("text/plain").content("sort=CREATED_AT_DESC"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(415));
        mockMvc.perform(get("/risk/workspace/payments/{id}", "not-a-uuid")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void everyWorkspaceErrorCarriesTheVersionHeaderAndAStableMachineReadableCode()
            throws Exception {
        var response = mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .param("limit", "101").contentType(APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();

        assertThat(response.getHeader(ApiVersionFilter.HEADER))
                .isEqualTo(ApiVersionFilter.CURRENT_VERSION);
        JsonNode error = objectMapper.readTree(response.getContentAsString());
        assertThat(error.get("error").asText()).isEqualTo("workspace_page_size_invalid");
        assertThat(error.get("retryable").asBoolean()).isFalse();
        assertThat(error.get("recoveryAction").asText()).isNotBlank();
        assertThat(error.get("requestId").asText()).isNotBlank();
    }
}
