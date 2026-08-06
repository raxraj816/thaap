package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * Proves C54-SCP-001. The investigation navigation surface owns canonical timeline reading,
 * transport evidence, and bounded same-merchant pivots. It owns no mutation authority, no fraud
 * feature, no cross-merchant intelligence, and no saved-view, bulk, or insight capability. A
 * relationship anchor is evidence an analyst reads; the moment it becomes a scored feature or an
 * authorization input, it stops being reviewable and starts being a model nobody can explain.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentInvestigationNavigationScopeBoundaryTests {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").normalize();

    private static final List<String> NAVIGATION_ROUTES =
            List.of("/timeline", "/related", "/webhook-activity");

    /**
     * Capability names the roadmap introduces later. None may appear on a navigation response,
     * because publishing one now would freeze a contract nobody has designed.
     * The deterministic rule score already published by the decision explanation is deliberately
     * not listed: it is existing reviewable output, not a model artefact.
     */
    private static final Pattern OUT_OF_SCOPE_FIELD = Pattern.compile(
            "(?i).*(modelScore|modelVersion|modelProbability|featureImportance|shapValue"
                    + "|networkRisk|consortium|crossMerchant|globalCount|industryRate"
                    + "|savedView|bulkAction|insight).*");

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

    private MockMvc mockMvc;
    private RiskWorkspaceScenario scenario;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator manager;
    private RiskWorkspaceScenario.Payment payment;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("navigation-scope");
        manager = scenario.operator(merchant, "navigation-scope-key", "RISK_MANAGER");
        payment = scenario.allowedPayment(merchant, "navigation-scope");
        scenario.drainProjection();
    }

    @Test
    void noNavigationRouteAcceptsAMutationVerb() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            String path = base() + route;
            // A navigation route that quietly accepts a write is a privilege escalation dressed
            // as a page load: a VIEWER may read every one of these.
            mockMvc.perform(post(path).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                    manager.credential())).andExpect(status().is4xxClientError());
            mockMvc.perform(put(path).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                    manager.credential())).andExpect(status().is4xxClientError());
            mockMvc.perform(patch(path).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                    manager.credential())).andExpect(status().is4xxClientError());
            mockMvc.perform(delete(path).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                    manager.credential())).andExpect(status().is4xxClientError());
        }
    }

    @Test
    void navigatingAPaymentChangesNothingAboutIt() throws Exception {
        String before = sourceFingerprint();
        for (String route : NAVIGATION_ROUTES) {
            mockMvc.perform(get(base() + route)
                            .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                                    manager.credential()))
                    .andExpect(status().isOk());
        }

        // Reading evidence must never move money, change a decision, extend a stream, or create
        // an anchor. If it did, the act of investigating would alter what is being investigated.
        assertThat(sourceFingerprint()).isEqualTo(before);
    }

    @Test
    void noNavigationResponseCarriesAFieldTheNavigationSurfaceHasNotEarned() throws Exception {
        for (String route : NAVIGATION_ROUTES) {
            JsonNode page = scenario.json(get(base() + route)
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, manager.credential()), 200);
            List<String> offending = fieldNames(page)
                    .filter(name -> OUT_OF_SCOPE_FIELD.matcher(name).matches()).toList();
            assertThat(offending).as(route + " publishes only navigation fields").isEmpty();
        }
    }

    @Test
    void aRelationshipAnchorIsNeverConsultedAsAnAuthorizationOrDecisionInput() {
        // An anchor answers "who else looks like this". Letting it answer "may this operator act"
        // or "should this payment be blocked" would turn unlabelled correlation into silent policy.
        assertThat(sourceMentions("payment_investigation_relation_anchors",
                "src/main/java/com/thaap/payments/risk"))
                .as("risk decisioning must not read relationship anchors").isEmpty();
        assertThat(sourceMentions("payment_investigation_relation_anchors",
                "src/main/java/com/thaap/payments/security"))
                .as("authorization must not read relationship anchors").isEmpty();
    }

    @Test
    void thePublicNavigationDemoReachesTheProductOnlyThroughPublishedRoutes() throws IOException {
        Path demo = REPOSITORY_ROOT.resolve("scripts/demo-payment-investigation.py");
        assertThat(Files.exists(demo)).isTrue();
        String source = Files.readString(demo, StandardCharsets.UTF_8);

        // The demo reconciles what the API said against the database, which is exactly the kind of
        // independent check a chapter should make. What it must never do is manufacture the state
        // it then claims to have proven, so its only database channel is guarded read-only.
        assertThat(source).contains("accepts only SELECT");
        assertThat(source).contains("rejected mutating SQL");
        // Every mutation the demo performs must travel over HTTP like a customer's would.
        assertThat(source).contains("urllib.request.Request");
        assertThat(source).doesNotContain("payment_investigation_relation_anchors");
    }

    private Stream<String> fieldNames(JsonNode node) {
        if (node.isObject()) {
            return node.properties().stream().flatMap(property ->
                    Stream.concat(Stream.of(property.getKey()), fieldNames(property.getValue())));
        }
        if (node.isArray()) {
            return node.valueStream().flatMap(this::fieldNames);
        }
        return Stream.empty();
    }

    private List<String> sourceMentions(String needle, String relativeDirectory) {
        Path directory = Path.of(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).filter(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains(needle);
                } catch (IOException failure) {
                    throw new IllegalStateException("unreadable source " + file, failure);
                }
            }).map(Path::toString).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("unreadable directory " + directory, failure);
        }
    }

    private String sourceFingerprint() {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(counted), 0) || ':' || COUNT(*) FROM (
                    SELECT COUNT(*) AS counted FROM domain_events WHERE stream_id = ?
                    UNION ALL
                    SELECT COUNT(*) FROM payment_investigation_relation_anchors
                        WHERE payment_intent_id = ?
                    UNION ALL
                    SELECT COUNT(*) FROM payment_attempts WHERE payment_intent_id = ?
                    UNION ALL
                    SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = ?
                ) AS counts
                """, String.class, payment.paymentIntentId(), payment.paymentIntentId(),
                payment.paymentIntentId(), merchant.id());
    }

    private String base() {
        return "/risk/workspace/payments/" + payment.paymentIntentId();
    }
}
