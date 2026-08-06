package com.thaap.payments.insights;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Contact;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C56-TEN-001 and C56-SEC-001. Two deliberately equal-shaped merchants transact the same
 * card and contact, so a query missing its tenant predicate returns the neighbour's rows rather
 * than nothing and no assertion here can pass vacuously. Insights are aggregates: they must never
 * become an identity oracle for a card, a contact, or another tenant's volume.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class WorkspaceInsightTenantAndPrivacyTests {

    private static final String CARD_NUMBER = "4999990000000003";
    private static final Contact CONTACT = new Contact("insight-canary@example.com", "+14155550142");
    private static final Pattern PAN_SHAPED = Pattern.compile("\\b[0-9]{13,19}\\b");
    private static final Pattern DATABASE_TEXT = Pattern.compile(
            "(?i)(select |insert into|from payment_risk_projections|violates .* constraint|"
                    + "SQLException|org\\.postgresql|org\\.h2\\.jdbc)");

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
    private Merchant first;
    private Merchant second;
    private Operator firstManager;
    private Operator firstAnalyst;
    private Operator firstViewer;
    private Operator secondManager;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        first = scenario.merchant("insight-tenant-first");
        second = scenario.merchant("insight-tenant-second");
        firstManager = scenario.operator(first, "insight-first-manager", "RISK_MANAGER");
        firstAnalyst = scenario.operator(first, "insight-first-analyst", "ANALYST");
        firstViewer = scenario.operator(first, "insight-first-viewer", "VIEWER");
        secondManager = scenario.operator(second, "insight-second-manager", "RISK_MANAGER");
    }

    @Test
    void oneTenantsVolumeNeverAppearsInAnothersAggregateEvenOnTheSameCardAndContact()
            throws Exception {
        // The first merchant has one payment; the second has three, on the same card and contact.
        scenario.payment(first, "tenant-first-a", 5_000L, "USD", 1, CONTACT, CARD_NUMBER);
        for (String prefix : List.of("tenant-second-a", "tenant-second-b", "tenant-second-c")) {
            scenario.payment(second, prefix, 5_000L, "USD", 1, CONTACT, CARD_NUMBER);
        }
        scenario.drainProjection();
        backdate(first);
        backdate(second);

        // The fixture is only meaningful if both tenants really share one card identity.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT card_fingerprint) FROM payment_methods", Long.class))
                .as("the cross-tenant claim is vacuous unless both merchants used one card")
                .isEqualTo(1L);

        assertThat(populationOf(firstManager)).isEqualTo(1L);
        assertThat(populationOf(secondManager)).isEqualTo(3L);
    }

    @Test
    void aViewerSeesMerchantAggregatesButNeverIndividualOperatorWorkload() throws Exception {
        JsonNode viewerBody = reviewOperations(firstViewer);
        JsonNode analystBody = reviewOperations(firstAnalyst);

        assertThat(viewerBody.hasNonNull("backlog"))
                .as("a viewer still gets merchant-wide aggregates").isTrue();
        assertThat(viewerBody.get("ownWorkload").isNull())
                .as("a viewer has no queue of their own, so an own-workload block would be "
                        + "either meaningless or another operator's data")
                .isTrue();
        assertThat(analystBody.hasNonNull("ownWorkload"))
                .as("an analyst may see their own workload").isTrue();
        assertThat(analystBody.at("/ownWorkload/operatorId").asText())
                .isEqualTo(firstAnalyst.id().toString());
    }

    @Test
    void theOperatorBreakdownNamesOperatorsOnlyForAManagerAndOnlyWithinTheirOwnTenant()
            throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/operators")
                        .header(RISK_OPERATOR_HEADER, firstManager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> ids = body.get("operators").valueStream()
                .map(row -> row.get("operatorId").asText()).toList();
        assertThat(ids).contains(firstManager.id().toString(), firstAnalyst.id().toString());
        assertThat(ids)
                .as("another tenant's operator must never appear in this breakdown")
                .doesNotContain(secondManager.id().toString());
        assertThat(ids)
                .as("a viewer holds no review workload and is not part of the breakdown")
                .doesNotContain(firstViewer.id().toString());
        assertThat(body.get("visibility").asText()).isEqualTo("MANAGER_ONLY_IDENTIFIED_BREAKDOWN");
    }

    @Test
    void theBreakdownReportsWorkloadWithoutRankingOrComparativeScoring() throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/operators")
                        .header(RISK_OPERATOR_HEADER, firstManager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Counts are facts; a rank or score is a judgement the product does not make.
        assertThat(body.toString().toLowerCase())
                .doesNotContain("\"rank\"").doesNotContain("percentile")
                .doesNotContain("score").doesNotContain("performance")
                .doesNotContain("efficiency").doesNotContain("productivityindex");
        body.get("operators").forEach(row -> {
            assertThat(row.get("currentWorkload").isIntegralNumber()).isTrue();
            assertThat(row.get("assignments").isIntegralNumber()).isTrue();
            assertThat(row.get("resolutions").isIntegralNumber()).isTrue();
        });
    }

    @Test
    void noInsightResponseCarriesCardContactCredentialOrDatabaseText() throws Exception {
        scenario.payment(first, "tenant-privacy", 5_000L, "USD", 1, CONTACT, CARD_NUMBER);
        scenario.drainProjection();
        backdate(first);

        List<String> prohibited = List.of(CARD_NUMBER, CONTACT.email(), CONTACT.phone(),
                first.apiKey(), firstManager.credential());

        for (String route : List.of("/risk/workspace/insights/decision-outcomes",
                "/risk/workspace/insights/review-operations",
                "/risk/workspace/insights/operators")) {
            String body = mockMvc.perform(get(route)
                            .header(RISK_OPERATOR_HEADER, firstManager.credential()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (String canary : prohibited) {
                assertThat(body).as("%s leaked a prohibited value", route).doesNotContain(canary);
            }
            assertThat(PAN_SHAPED.matcher(body).find())
                    .as("%s contains a PAN-shaped digit run", route).isFalse();
            assertThat(DATABASE_TEXT.matcher(body).find())
                    .as("%s contains database or driver text", route).isFalse();
        }
    }

    @Test
    void anInsightSurfaceExposesNoPaymentReviewOrCustomerIdentifierList() throws Exception {
        scenario.reviewedPayment(first, "tenant-no-ids");
        scenario.drainProjection();
        backdate(first);

        // An aggregate that ships the underlying row identifiers is a transaction list wearing a
        // chart's clothes, and bypasses the authorized drill-through it was meant to replace.
        String body = mockMvc.perform(get("/risk/workspace/insights/decision-outcomes")
                        .header(RISK_OPERATOR_HEADER, firstManager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("paymentIntentId").doesNotContain("reviewCaseId")
                .doesNotContain("customerId").doesNotContain("paymentAttemptId");

        List<String> paymentIds = jdbcTemplate.queryForList(
                "SELECT CAST(payment_intent_id AS VARCHAR) FROM payment_risk_projections",
                String.class);
        assertThat(paymentIds).isNotEmpty();
        paymentIds.forEach(id -> assertThat(body)
                .as("a payment identifier reached an aggregate response").doesNotContain(id));
    }

    @Test
    void thereIsNoInsightExportOrDownloadRoute() throws Exception {
        for (String route : List.of("/risk/workspace/insights/decision-outcomes/export",
                "/risk/workspace/insights/export", "/risk/workspace/insights/download",
                "/risk/workspace/insights/decision-outcomes.csv")) {
            int status = mockMvc.perform(get(route)
                            .header(RISK_OPERATOR_HEADER, firstManager.credential()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("%s must not exist; file export is a separately frozen "
                    + "security boundary", route).isIn(404, 405);
        }
    }

    private long populationOf(Operator operator) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .header(RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return body.at("/rates/labelCoverage/denominator").asLong();
    }

    private JsonNode reviewOperations(Operator operator) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/review-operations")
                        .header(RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Shifts projected rows into a complete prior UTC day so the frozen range can see them. */
    private void backdate(Merchant merchant) {
        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        java.sql.Timestamp shifted = java.sql.Timestamp.from(
                startOfToday.minus(2, ChronoUnit.DAYS));
        jdbcTemplate.update("""
                UPDATE payment_risk_projections
                SET intent_occurred_at = ?, last_event_occurred_at = ?, last_event_recorded_at = ?
                WHERE merchant_id = ?
                """, shifted, shifted, shifted, merchant.id());
    }
}
