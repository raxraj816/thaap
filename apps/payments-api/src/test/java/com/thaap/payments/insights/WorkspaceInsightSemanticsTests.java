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
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * Proves C56-DEN-001, C56-MNY-001, and the descriptive-claim half of C56-OUT-001. Every rate must
 * publish its exact numerator, denominator, and unknown count; an empty denominator must be
 * `NOT_COMPUTABLE` rather than a confident 0%; and money must stay integer minor units separated
 * by currency, because a blended total is a number no one can act on.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class WorkspaceInsightSemanticsTests {

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

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        databaseCleaner.clean();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate, eventRepository,
                projectionConsumer);
        merchant = scenario.merchant("insight-semantics");
        manager = scenario.operator(merchant, "insight-semantics-manager", "RISK_MANAGER");
    }

    @Test
    void anEmptyDenominatorIsNotComputableAndIsNeverRenderedAsZeroPercent() throws Exception {
        // No payments at all: every rate's denominator is zero.
        JsonNode body = decisionOutcomes();

        JsonNode rates = body.get("rates");
        assertThat(rates.size()).isGreaterThanOrEqualTo(4);
        rates.properties().forEach(entry -> {
            JsonNode rate = entry.getValue();
            assertThat(rate.get("denominator").asLong())
                    .as("%s should have an empty denominator in this fixture", entry.getKey())
                    .isZero();
            assertThat(rate.get("status").asText())
                    .as("%s must declare itself not computable", entry.getKey())
                    .isEqualTo("NOT_COMPUTABLE_EMPTY_DENOMINATOR");
            assertThat(rate.get("valueBasisPoints").isNull())
                    .as("%s must not publish a value it cannot compute — a 0 here reads as "
                            + "'no fraud' rather than 'nothing to measure'", entry.getKey())
                    .isTrue();
        });
    }

    @Test
    void everyRatePublishesItsExactNumeratorDenominatorAndUnknownCount() throws Exception {
        // Three payments, none of them labelled: the coverage denominator is real and the
        // unknown count must account for every unlabelled row rather than hiding it.
        scenario.allowedPayment(merchant, "semantics-a");
        scenario.allowedPayment(merchant, "semantics-b");
        scenario.challengedPayment(merchant, "semantics-c");
        scenario.drainProjection();
        backdateProjectionsIntoTheWindow();

        JsonNode rates = decisionOutcomes().get("rates");
        JsonNode coverage = rates.get("labelCoverage");

        long projectedRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment_risk_projections WHERE merchant_id = ?
                """, Long.class, merchant.id());
        assertThat(coverage.get("denominator").asLong())
                .as("the coverage denominator must equal the label-eligible population")
                .isEqualTo(projectedRows);
        assertThat(coverage.get("numerator").asLong())
                .as("nothing is labelled in this fixture")
                .isZero();
        assertThat(coverage.get("unknownCount").asLong())
                .as("every unlabelled row must be counted as unknown, never folded into a "
                        + "negative class")
                .isEqualTo(projectedRows);
        assertThat(coverage.get("status").asText()).isEqualTo("COMPUTABLE");
        assertThat(coverage.get("valueBasisPoints").asInt())
                .as("0 of N labelled is a genuine 0%, distinct from not-computable")
                .isZero();

        // A rate whose own denominator is still empty stays not-computable even though the
        // response as a whole has data — computability is per rate, not per response.
        assertThat(rates.get("confirmedFraudRate").get("denominator").asLong()).isZero();
        assertThat(rates.get("confirmedFraudRate").get("status").asText())
                .isEqualTo("NOT_COMPUTABLE_EMPTY_DENOMINATOR");
    }

    @Test
    void numeratorNeverExceedsDenominatorAndBasisPointsStayWithinRange() throws Exception {
        scenario.allowedPayment(merchant, "semantics-bounds-a");
        scenario.reviewedPayment(merchant, "semantics-bounds-b");
        scenario.drainProjection();
        backdateProjectionsIntoTheWindow();

        decisionOutcomes().get("rates").properties().forEach(entry -> {
            JsonNode rate = entry.getValue();
            long numerator = rate.get("numerator").asLong();
            long denominator = rate.get("denominator").asLong();
            assertThat(numerator).as("%s numerator is negative", entry.getKey())
                    .isGreaterThanOrEqualTo(0);
            assertThat(denominator).as("%s denominator is negative", entry.getKey())
                    .isGreaterThanOrEqualTo(0);
            if (denominator > 0) {
                assertThat(numerator)
                        .as("%s numerator exceeds its denominator, which is arithmetically "
                                + "impossible and would render above 100%%", entry.getKey())
                        .isLessThanOrEqualTo(denominator);
                int basisPoints = rate.get("valueBasisPoints").asInt();
                assertThat(basisPoints).as("%s basis points out of range", entry.getKey())
                        .isBetween(0, 10_000);
            }
        });
    }

    @Test
    void moneyStaysSeparatedByCurrencyAndIsNeverBlendedIntoOneTotal() throws Exception {
        scenario.payment(merchant, "semantics-usd", 5_000L, "USD", 1);
        scenario.payment(merchant, "semantics-eur", 7_000L, "EUR", 1);
        scenario.payment(merchant, "semantics-gbp", 9_000L, "GBP", 1);
        scenario.drainProjection();
        backdateProjectionsIntoTheWindow();

        JsonNode body = decisionOutcomes();
        JsonNode money = body.get("moneyByCurrency");

        List<String> currencies = new ArrayList<>();
        money.forEach(row -> currencies.add(row.get("currency").asText()));
        assertThat(currencies)
                .as("each currency must be its own row; a single row would mean amounts were "
                        + "added across currencies")
                .containsExactly("EUR", "GBP", "USD");

        // Every amount is an integer minor unit, never a decimal that could lose cents.
        money.forEach(row -> {
            for (String field : List.of("capturedAmountMinor", "refundedAmountMinor",
                    "disputedAmountMinor")) {
                assertThat(row.get(field).isIntegralNumber())
                        .as("%s.%s must be an integer minor-unit amount",
                                row.get("currency").asText(), field)
                        .isTrue();
            }
        });

        // There is no blended total field anywhere in the response.
        assertThat(body.toString())
                .as("a grand total across currencies must not appear in any form")
                .doesNotContain("totalAmountMinor").doesNotContain("grandTotal")
                .doesNotContain("amountMinorTotal");
    }

    @Test
    void theDecisionDistributionReportsRealActionsAndTheClaimBoundaryStaysDescriptive()
            throws Exception {
        scenario.allowedPayment(merchant, "semantics-dist-allow");
        scenario.blockedPayment(merchant, "semantics-dist-block");
        scenario.drainProjection();
        backdateProjectionsIntoTheWindow();

        JsonNode body = decisionOutcomes();
        List<String> actions = new ArrayList<>();
        body.get("decisionDistribution").forEach(row -> actions.add(row.get("code").asText()));
        assertThat(actions).contains("ALLOW", "BLOCK");

        long distributionTotal = 0;
        for (JsonNode row : body.get("decisionDistribution")) {
            distributionTotal += row.get("count").asLong();
        }
        long projectedRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment_risk_projections WHERE merchant_id = ?
                """, Long.class, merchant.id());
        assertThat(distributionTotal)
                .as("the distribution must partition the population exactly once — a payment "
                        + "counted in two buckets would inflate every downstream rate")
                .isEqualTo(projectedRows);

        // The product refuses to claim prevented loss, profit, or causal lift.
        assertThat(body.get("claimBoundary").asText()).isEqualTo("DESCRIPTIVE_NOT_CAUSAL");
        assertThat(body.toString().toLowerCase())
                .doesNotContain("preventedfraud").doesNotContain("preventedloss")
                .doesNotContain("savings").doesNotContain("lift").doesNotContain("roi");
    }

    @Test
    void aRangeThatExcludesEveryPaymentReportsAnEmptyPopulationRatherThanTheWholeHistory()
            throws Exception {
        scenario.allowedPayment(merchant, "semantics-range-seed");
        scenario.drainProjection();

        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        // A fully historical window that ends before today's payments were recorded.
        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .param("from", startOfToday.minus(30, ChronoUnit.DAYS).toString())
                        .param("to", startOfToday.minus(23, ChronoUnit.DAYS).toString())
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("decisionDistribution")).isEmpty();
        assertThat(body.get("moneyByCurrency")).isEmpty();
        assertThat(body.at("/rates/labelCoverage/denominator").asLong())
                .as("a range with no population must report an empty denominator, not the "
                        + "counts from outside the window")
                .isZero();
    }

    /**
     * Moves already-projected rows into a complete prior UTC day.
     *
     * <p>The frozen range contract ends every window at the start of the current UTC day, so a
     * payment created during a test run is always in the excluded partial day and no valid range
     * can see it. Only the projection's observation timestamp is shifted; the row itself was
     * still produced by the real confirm and projection pipeline.
     */
    private void backdateProjectionsIntoTheWindow() {
        Instant startOfToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        // ck_payment_risk_projections_intent_time requires intent_occurred_at to stay at or
        // before both event timestamps, so the whole row is shifted by one offset rather than
        // just the column the range filter reads.
        java.sql.Timestamp shifted = java.sql.Timestamp.from(
                startOfToday.minus(2, ChronoUnit.DAYS));
        jdbcTemplate.update("""
                UPDATE payment_risk_projections
                SET intent_occurred_at = ?, last_event_occurred_at = ?,
                    last_event_recorded_at = ?
                WHERE merchant_id = ?
                """, shifted, shifted, shifted, merchant.id());
    }

    private JsonNode decisionOutcomes() throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/risk/workspace/insights/decision-outcomes")
                        .header(RISK_OPERATOR_HEADER, manager.credential()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
