package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves C53-SCP-001. The investigation surface is a read of authoritative state. This class
 * proves it stays one: no mutation authority, no fabricated model or cross-merchant intelligence,
 * no declared consistency boundary it does not actually enforce, and a public demo that reaches
 * the product only through the same HTTP routes a customer would use.
 */
@ActiveProfiles("test")
@SpringBootTest
class PaymentInvestigationScopeBoundaryTests {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").normalize();

    /** Names a later phase would introduce; none may appear in an investigation response. */
    private static final Pattern OUT_OF_SCOPE_FIELD = Pattern.compile(
            "(?i).*(modelScore|modelVersion|modelProbability|featureImportance|shapValue"
                    + "|networkRisk|consortium|crossMerchant|globalCount|industryRate).*");

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
    @org.springframework.beans.factory.annotation.Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
    }

    @Test
    void theInvestigationSurfaceDeclaresNoMutationRouteAndRefusesMutationVerbs() throws Exception {
        List<String> mutationMappings = new ArrayList<>();
        for (Method method : PaymentInvestigationController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)) {
                mutationMappings.add(method.getName());
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null && !List.of(post.value()).equals(List.of("/search"))) {
                mutationMappings.add(method.getName());
            }
        }
        // Search is a POST only because a bounded filter belongs in a body, never because it writes.
        assertThat(mutationMappings).isEmpty();
        assertThat(Stream.of(PaymentInvestigationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)).count())
                .isEqualTo(4);

        RiskWorkspaceScenario.Merchant merchant = scenario.merchant("scope-verbs");
        RiskWorkspaceScenario.Operator operator = scenario.operator(merchant, "scope-verb-key",
                "RISK_MANAGER");
        RiskWorkspaceScenario.Payment payment = scenario.allowedPayment(merchant, "scope-verb");
        String route = "/risk/workspace/payments/" + payment.paymentIntentId();

        for (org.springframework.test.web.servlet.RequestBuilder request : List.of(
                put(route).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()),
                patch(route).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()),
                delete(route).header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                        operator.credential()))) {
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
            assertThat(status).isIn(404, 405);
        }
    }

    @Test
    void noInvestigationResponseCarriesAModelOrCrossMerchantFieldThatChapterFiftyThreeCannotEarn()
            throws Exception {
        RiskWorkspaceScenario.Merchant merchant = scenario.merchant("scope-fields");
        RiskWorkspaceScenario.Operator operator = scenario.operator(merchant, "scope-field-key",
                "RISK_MANAGER");
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "scope-field");
        scenario.drainProjection();

        JsonNode detail = scenario.json(get("/risk/workspace/payments/" + reviewed.paymentIntentId())
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
        JsonNode related = scenario.json(get("/risk/workspace/payments/"
                + reviewed.paymentIntentId() + "/related")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);

        for (JsonNode response : List.of(detail, related)) {
            assertThat(propertyNames(response)).allSatisfy(name ->
                    assertThat(OUT_OF_SCOPE_FIELD.matcher(name).matches())
                            .as("response field %s claims capability the investigation surface does not own", name)
                            .isFalse());
        }
        // A missing model is stated as a closed state rather than implied by an absent field.
        assertThat(detail.at("/decisionExplanation/modelState").asText()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void anIdenticalCardInAnotherMerchantNeverRaisesARelatedCountOrCoverageClaim() throws Exception {
        RiskWorkspaceScenario.Merchant first = scenario.merchant("scope-tenant-first");
        RiskWorkspaceScenario.Operator operator = scenario.operator(first, "scope-tenant-key",
                "RISK_MANAGER");
        RiskWorkspaceScenario.Payment seed = scenario.allowedPayment(first, "scope-seed");

        RiskWorkspaceScenario.Merchant second = scenario.merchant("scope-tenant-second");
        scenario.allowedPayment(second, "scope-neighbour");
        scenario.allowedPayment(second, "scope-neighbour-two");

        JsonNode related = scenario.json(get("/risk/workspace/payments/" + seed.paymentIntentId()
                + "/related").header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER,
                        operator.credential()), 200);

        // Both merchants transact the same card, so an unscoped anchor join would link them.
        assertThat(distinctCardFingerprints()).as("the fixture used two different cards, so the "
                + "cross-tenant relation proof is vacuous").isEqualTo(1);
        assertThat(related.get("data")).isEmpty();
        assertThat(related.get("scope").asText()).isEqualTo("SAME_MERCHANT_SERVER_DERIVED");
    }

    @Test
    void everyMultiStatementInvestigationReadEnforcesTheCutoffItPublishes() throws NoSuchMethodException {
        // A page that publishes one asOf or one stream ceiling must read under one snapshot,
        // otherwise the declared boundary is decoration.
        Transactional detail = PaymentInvestigationService.class
                .getMethod("detail", com.thaap.payments.risk.RiskOperatorContext.class,
                        java.util.UUID.class).getAnnotation(Transactional.class);
        assertThat(detail).isNotNull();
        assertThat(detail.readOnly()).isTrue();

        Transactional related = RelatedPaymentService.class
                .getMethod("related", com.thaap.payments.risk.RiskOperatorContext.class,
                        java.util.UUID.class, java.time.Instant.class, java.time.Instant.class,
                        Integer.class, String.class).getAnnotation(Transactional.class);
        assertThat(related).as("related() fans out to per-row projection lookups that no value "
                + "ceiling bounds, so it must hold a snapshot").isNotNull();
        assertThat(related.readOnly()).isTrue();
        assertThat(related.isolation())
                .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    @Test
    void aPagedTimelineHoldsTheStreamCeilingItFirstPublishedAcrossEveryPage() throws Exception {
        // Timeline is the remaining member of the declared-cutoff family. It does not hold a
        // snapshot; it pins its ceiling by value into the cursor, which must survive paging.
        RiskWorkspaceScenario.Merchant merchant = scenario.merchant("scope-ceiling");
        RiskWorkspaceScenario.Operator operator = scenario.operator(merchant, "scope-ceiling-key",
                "RISK_MANAGER");
        RiskWorkspaceScenario.Payment reviewed = scenario.reviewedPayment(merchant, "scope-ceiling");
        String route = "/risk/workspace/payments/" + reviewed.paymentIntentId() + "/timeline";

        JsonNode first = scenario.json(get(route + "?limit=1")
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
        long ceiling = first.get("maximumStreamVersion").asLong();
        assertThat(first.get("nextCursor").isNull()).isFalse();

        long seen = 0;
        String cursor = first.get("nextCursor").asText();
        while (cursor != null) {
            JsonNode page = scenario.json(get(route + "?limit=1&cursor="
                    + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8))
                    .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
            assertThat(page.get("maximumStreamVersion").asLong()).isEqualTo(ceiling);
            for (JsonNode entry : page.get("data")) {
                assertThat(entry.get("streamVersion").asLong()).isLessThanOrEqualTo(ceiling);
                seen++;
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        }
        assertThat(seen).isGreaterThan(0);
    }

    @Test
    void thePublicInvestigationDemoDrivesTheProductOnlyThroughItsPublishedRoutes() throws IOException {
        Path demo = REPOSITORY_ROOT.resolve("scripts/demo-payment-investigation.py");
        assertThat(Files.exists(demo)).as("the investigation surface claims a public demo of risk-operator "
                + "search and detail, so one must exist").isTrue();
        String source = Files.readString(demo, StandardCharsets.UTF_8);

        // The demo may reconcile against the database, but only by reading. Every product effect
        // has to travel the same HTTP surface a customer would use.
        assertThat(source).contains("/risk/workspace/payments");
        assertThat(source.indexOf("subprocess.run")).isEqualTo(source.lastIndexOf("subprocess.run"));
        assertThat(source).contains("accepts only SELECT");
        for (String laterSurface : List.of("/risk/workspace/bulk", "/risk/workspace/insights",
                "/risk/workspace/saved-searches", "/risk/models")) {
            assertThat(source).as("the investigation demo reaches an out-of-scope surface %s",
                    laterSurface).doesNotContain(laterSurface);
        }
    }

    private List<String> propertyNames(JsonNode root) {
        List<String> names = new ArrayList<>();
        Deque<JsonNode> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    names.add(field.getKey());
                    pending.push(field.getValue());
                }
            } else if (node.isArray()) {
                node.valueStream().forEach(pending::push);
            }
        }
        return names;
    }

    private int distinctCardFingerprints() {
        List<String> fingerprints = jdbcTemplate.queryForList(
                "SELECT DISTINCT card_fingerprint FROM payment_methods", String.class);
        return fingerprints.size();
    }
}
