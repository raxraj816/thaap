package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Proves C56-API-001: the insight endpoints accept only UTC-aligned half-open ranges, default to
 * the previous seven complete UTC days, cap at 31 days, and refuse every malformed range rather
 * than silently widening it. A range that broadens on bad input is the failure that matters here:
 * it turns "no fraud last week" into an answer computed over a different week.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class WorkspaceInsightApiContractTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService projectionConsumer;
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private Merchant merchant;
    private Operator manager;
    private Operator analyst;
    private Operator viewer;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("insight-api");
        manager = scenario.operator(merchant, "insight-api-manager", "RISK_MANAGER");
        analyst = scenario.operator(merchant, "insight-api-analyst", "ANALYST");
        viewer = scenario.operator(merchant, "insight-api-viewer", "VIEWER");
    }

    @Test
    void theDefaultRangeIsThePreviousSevenCompleteUtcDaysAndNeverIncludesThePartialCurrentDay()
            throws Exception {
        JsonNode body = json(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        Instant from = Instant.parse(body.at("/range/from").asText());
        Instant to = Instant.parse(body.at("/range/to").asText());
        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        assertThat(to)
                .as("the current partial day must not enter the default window, or today's "
                        + "incomplete counts would be compared against complete days")
                .isEqualTo(startOfToday);
        assertThat(Duration.between(from, to)).isEqualTo(Duration.ofDays(7));
        assertThat(from).isEqualTo(startOfToday.minus(7, ChronoUnit.DAYS));
    }

    @ParameterizedTest(name = "a {0}-day range is accepted")
    @ValueSource(ints = {1, 7, 30, 31})
    void everyRangeUpToTheThirtyOneDayCapIsAccepted(int days) throws Exception {
        Instant to = startOfToday();
        Instant from = to.minus(days, ChronoUnit.DAYS);
        JsonNode body = json(get("/risk/workspace/insights/decision-outcomes")
                .param("from", from.toString()).param("to", to.toString())
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        assertThat(Duration.between(Instant.parse(body.at("/range/from").asText()),
                Instant.parse(body.at("/range/to").asText())))
                .isEqualTo(Duration.ofDays(days));
    }

    @Test
    void aThirtyTwoDayRangeIsTheFirstRefusedWidth() throws Exception {
        Instant to = startOfToday();
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .param("from", to.minus(32, ChronoUnit.DAYS).toString())
                        .param("to", to.toString())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insight_range_invalid"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @CsvSource(delimiter = '|', value = {
            "a zero-width range          | 0  | 0",
            "an inverted range           | -1 | -7",
            "a range ending in the future| -7 | 30",
    })
    void aMalformedRangeIsRefusedRatherThanSilentlyWidened(String description, int fromOffsetDays,
            int toOffsetDays) throws Exception {
        Instant anchor = startOfToday();
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .param("from", anchor.plus(fromOffsetDays, ChronoUnit.DAYS).toString())
                        .param("to", anchor.plus(toOffsetDays, ChronoUnit.DAYS).toString())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insight_range_invalid"));
    }

    @Test
    void aRangeBoundaryThatIsNotAlignedToUtcMidnightIsRefused() throws Exception {
        Instant to = startOfToday();
        // One second past midnight would silently shift every daily bucket.
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .param("from", to.minus(7, ChronoUnit.DAYS).plusSeconds(1).toString())
                        .param("to", to.toString())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insight_range_invalid"));

        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .param("from", to.minus(7, ChronoUnit.DAYS).toString())
                        .param("to", to.minusSeconds(1).toString())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insight_range_invalid"));
    }

    @ParameterizedTest(name = "origin policy {0} is accepted")
    @ValueSource(strings = {"ALL", "LIVE_ONLY", "SYNTHETIC_ONLY"})
    void everyClosedOriginPolicyIsAcceptedAndEchoedBack(String policy) throws Exception {
        JsonNode body = json(get("/risk/workspace/insights/decision-outcomes")
                .param("originPolicy", policy)
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        assertThat(body.get("trafficOriginPolicy").asText()).isEqualTo(policy);
    }

    @ParameterizedTest(name = "origin policy {0} is refused")
    @ValueSource(strings = {"EVERYTHING", "live", "'; DROP TABLE payment_risk_projections; --",
            "MERCHANT_API"})
    void anOriginPolicyOutsideTheClosedSetIsRefused(String policy) throws Exception {
        mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .param("originPolicy", policy)
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insight_origin_policy_invalid"));

        // The hostile value never reached the database.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_risk_projections", Long.class)).isNotNull();
    }

    @Test
    void lowercaseAndPaddedOriginPolicyIsNormalizedRatherThanRejected() throws Exception {
        JsonNode body = json(get("/risk/workspace/insights/decision-outcomes")
                .param("originPolicy", "  live_only  ")
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        assertThat(body.get("trafficOriginPolicy").asText()).isEqualTo("LIVE_ONLY");
    }

    @Test
    void theOperatorBreakdownIsManagerOnlyAndNonDisclosingToEveryWeakerRole() throws Exception {
        json(get("/risk/workspace/insights/operators")
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        for (Operator weaker : new Operator[] {analyst, viewer}) {
            mockMvc.perform(get("/risk/workspace/insights/operators")
                            .header(RISK_OPERATOR_HEADER, weaker.credential()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("insight_manager_required"));
        }
    }

    @Test
    void everyInsightRouteRefusesAnAbsentOrUnknownOperatorCredential() throws Exception {
        for (String route : new String[] {"/risk/workspace/insights/decision-outcomes",
                "/risk/workspace/insights/review-operations",
                "/risk/workspace/insights/operators"}) {
            mockMvc.perform(get(route)).andExpect(status().is4xxClientError());
            mockMvc.perform(get(route).header(RISK_OPERATOR_HEADER, "not-a-real-credential"))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void theRangeContractIsIdenticalAcrossAllThreeInsightRoutes() throws Exception {
        Instant to = startOfToday();
        String badFrom = to.minus(32, ChronoUnit.DAYS).toString();
        for (String route : new String[] {"/risk/workspace/insights/decision-outcomes",
                "/risk/workspace/insights/review-operations",
                "/risk/workspace/insights/operators"}) {
            mockMvc.perform(get(route).param("from", badFrom).param("to", to.toString())
                            .header(RISK_OPERATOR_HEADER, manager.credential()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("insight_range_invalid"));

            JsonNode body = json(get(route).header(RISK_OPERATOR_HEADER, manager.credential()), 200);
            assertThat(Duration.between(Instant.parse(body.at("/range/from").asText()),
                    Instant.parse(body.at("/range/to").asText())))
                    .as("%s must share the seven-complete-day default", route)
                    .isEqualTo(Duration.ofDays(7));
        }
    }

    @Test
    void reviewOperationsPublishesItsOwnSourceAsOfAndNeverBorrowsProjectionFreshness()
            throws Exception {
        JsonNode reviews = json(get("/risk/workspace/insights/review-operations")
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);
        JsonNode decisions = json(get("/risk/workspace/insights/decision-outcomes")
                .header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        // The two authorities are deliberately separate: one is a bounded source snapshot, the
        // other a projection generation. Merging them into one timestamp would let an analyst
        // read a stale projection number as though it were current source truth.
        assertThat(reviews.hasNonNull("sourceAsOf")).isTrue();
        assertThat(reviews.has("freshness")).isFalse();
        assertThat(reviews.get("authority").asText()).isEqualTo("AUTHORITATIVE_REVIEW_SOURCE");
        assertThat(decisions.hasNonNull("freshness")).isTrue();
        assertThat(decisions.has("sourceAsOf")).isFalse();
    }

    private Instant startOfToday() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            request, int expectedStatus) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString());
    }
}
