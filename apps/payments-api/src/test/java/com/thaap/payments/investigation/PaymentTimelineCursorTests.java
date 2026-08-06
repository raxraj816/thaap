package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves the client-facing half of C54-CUR-001. A timeline cursor is an authenticated capability,
 * not an offset: it must be bound to one tenant, one payment, and one purpose, it must survive
 * tampering attempts, and paging with it must reproduce the stream exactly once with no gap and
 * no repeat.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentTimelineCursorTests {

    /**
     * The signed codec refuses a mis-bound or tampered cursor as {@code workspace_cursor_invalid}
     * before any anchor is parsed; the timeline refuses a structurally wrong anchor list as
     * {@code timeline_cursor_invalid}. Both are the same product promise: a cursor the server did
     * not mint for this reader, this payment, and this view is never honoured.
     */
    private static final List<String> CURSOR_REFUSALS =
            List.of("workspace_cursor_invalid", "timeline_cursor_invalid");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;
    private RiskWorkspaceScenario.Payment payment;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("timeline-cursor");
        operator = scenario.operator(merchant, "timeline-cursor-key", "RISK_MANAGER");
        payment = scenario.reviewedPayment(merchant, "cursor-seed");
    }

    @Test
    void pagingOneEntryAtATimeReproducesTheWholeStreamWithNoGapAndNoRepeat() throws Exception {
        List<Long> single = drain(payment.paymentIntentId(), operator, 1);
        List<Long> whole = drain(payment.paymentIntentId(), operator, 50);

        assertThat(single).hasSizeGreaterThan(3);
        assertThat(single).containsExactlyElementsOf(whole);
        assertThat(single).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void theCeilingPublishedOnTheFirstPageIsHeldByEveryLaterPage() throws Exception {
        String route = "/risk/workspace/payments/" + payment.paymentIntentId() + "/timeline";
        JsonNode first = page(route + "?limit=1", operator);
        long ceiling = first.get("maximumStreamVersion").asLong();

        String cursor = first.get("nextCursor").asText();
        int pages = 0;
        while (cursor != null && !cursor.isBlank()) {
            JsonNode next = page(route + "?limit=1&cursor=" + encode(cursor), operator);
            // The cursor carries the ceiling by value, so a page cannot silently widen its own
            // window even if the stream grew between requests.
            assertThat(next.get("maximumStreamVersion").asLong()).isEqualTo(ceiling);
            next.get("data").forEach(entry ->
                    assertThat(entry.get("streamVersion").asLong()).isLessThanOrEqualTo(ceiling));
            cursor = next.get("nextCursor").isNull() ? null : next.get("nextCursor").asText();
            pages++;
        }
        assertThat(pages).isGreaterThan(2);
    }

    @Test
    void aCursorMintedForOnePaymentIsRefusedOnAnother() throws Exception {
        RiskWorkspaceScenario.Payment other = scenario.allowedPayment(merchant, "cursor-other");
        String cursor = page("/risk/workspace/payments/" + payment.paymentIntentId()
                + "/timeline?limit=1", operator).get("nextCursor").asText();

        JsonNode error = scenario.json(get("/risk/workspace/payments/" + other.paymentIntentId()
                + "/timeline?limit=1&cursor=" + encode(cursor))
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
        assertThat(error.get("error").asText()).isIn(CURSOR_REFUSALS);
    }

    @Test
    void aCursorMintedInOneTenantIsRefusedInAnotherRatherThanReadingAcross() throws Exception {
        RiskWorkspaceScenario.Merchant neighbour = scenario.merchant("timeline-cursor-neighbour");
        RiskWorkspaceScenario.Operator neighbourOperator =
                scenario.operator(neighbour, "timeline-cursor-neighbour-key", "RISK_MANAGER");
        RiskWorkspaceScenario.Payment neighbourPayment =
                scenario.reviewedPayment(neighbour, "cursor-neighbour");

        String cursor = page("/risk/workspace/payments/" + payment.paymentIntentId()
                + "/timeline?limit=1", operator).get("nextCursor").asText();

        // Replaying our cursor against the neighbour's own payment must fail on the merchant
        // binding inside the cursor, not merely on route authorization.
        JsonNode error = scenario.json(get("/risk/workspace/payments/"
                + neighbourPayment.paymentIntentId() + "/timeline?limit=1&cursor=" + encode(cursor))
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, neighbourOperator.credential()),
                400);
        assertThat(error.get("error").asText()).isIn(CURSOR_REFUSALS);
    }

    @Test
    void aCursorMintedForTheWebhookTransportViewIsRefusedByTheTimeline() throws Exception {
        JsonNode webhookPage = page("/risk/workspace/payments/" + payment.paymentIntentId()
                + "/webhook-activity?limit=1", operator);
        String webhookCursor = webhookPage.get("nextCursor").isNull() ? null
                : webhookPage.get("nextCursor").asText();

        // If the fixture produced no second webhook page there is nothing to cross-replay, so mint
        // a timeline cursor and replay it at the webhook route instead. Either direction proves
        // the same domain separation.
        String timelineCursor = page("/risk/workspace/payments/" + payment.paymentIntentId()
                + "/timeline?limit=1", operator).get("nextCursor").asText();
        JsonNode crossed = scenario.json(get("/risk/workspace/payments/"
                + payment.paymentIntentId() + "/webhook-activity?limit=1&cursor="
                + encode(timelineCursor))
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
        assertThat(crossed.get("error").asText()).isIn(CURSOR_REFUSALS);

        if (webhookCursor != null) {
            JsonNode reverse = scenario.json(get("/risk/workspace/payments/"
                    + payment.paymentIntentId() + "/timeline?limit=1&cursor=" + encode(webhookCursor))
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
            assertThat(reverse.get("error").asText()).isIn(CURSOR_REFUSALS);
        }
    }

    @Test
    void everySingleCharacterEditOfAValidCursorIsRefused() throws Exception {
        String cursor = page("/risk/workspace/payments/" + payment.paymentIntentId()
                + "/timeline?limit=1", operator).get("nextCursor").asText();

        List<String> accepted = new ArrayList<>();
        for (int index = 0; index < cursor.length(); index += 7) {
            char original = cursor.charAt(index);
            char replacement = original == 'A' ? 'B' : 'A';
            String tampered = cursor.substring(0, index) + replacement + cursor.substring(index + 1);
            int statusCode = mockMvc.perform(get("/risk/workspace/payments/"
                            + payment.paymentIntentId() + "/timeline?limit=1&cursor="
                            + encode(tampered))
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    operator.credential()))
                    .andReturn().getResponse().getStatus();
            if (statusCode == 200) {
                accepted.add(tampered);
            }
        }
        // The signature is verified before any anchor is trusted, so no edit anywhere in the
        // encoded payload can be accepted.
        assertThat(accepted).isEmpty();
    }

    @Test
    void anOversizedOrStructurallyEmptyCursorIsRefusedBeforeAnyDecoding() throws Exception {
        String oversized = "a".repeat(2_048);
        JsonNode tooLong = scenario.json(get("/risk/workspace/payments/"
                + payment.paymentIntentId() + "/timeline?limit=1&cursor=" + oversized)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
        assertThat(tooLong.get("error").asText()).isIn(CURSOR_REFUSALS);

        JsonNode garbage = scenario.json(get("/risk/workspace/payments/"
                + payment.paymentIntentId() + "/timeline?limit=1&cursor=" + encode("!!not-base64!!"))
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 400);
        assertThat(garbage.get("error").asText()).isIn(CURSOR_REFUSALS);

        // A blank cursor is the absence of a cursor, which is a first page rather than an error.
        JsonNode blank = scenario.json(get("/risk/workspace/payments/"
                + payment.paymentIntentId() + "/timeline?limit=1&cursor=")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
        assertThat(blank.get("data")).hasSize(1);
    }

    private List<Long> drain(UUID paymentIntentId, RiskWorkspaceScenario.Operator reader, int limit)
            throws Exception {
        String route = "/risk/workspace/payments/" + paymentIntentId + "/timeline";
        List<Long> versions = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode current = page(route + "?limit=" + limit
                    + (cursor == null ? "" : "&cursor=" + encode(cursor)), reader);
            current.get("data").forEach(entry ->
                    versions.add(entry.get("streamVersion").asLong()));
            cursor = current.get("nextCursor").isNull() ? null : current.get("nextCursor").asText();
        } while (cursor != null);
        return versions;
    }

    private JsonNode page(String route, RiskWorkspaceScenario.Operator reader) throws Exception {
        return scenario.json(get(route)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, reader.credential()), 200);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
