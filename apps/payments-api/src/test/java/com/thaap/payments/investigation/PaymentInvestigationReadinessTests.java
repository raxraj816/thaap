package com.thaap.payments.investigation;

import static com.thaap.payments.support.RiskWorkspaceScenario.RISK_OPERATOR_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.observability.DecisionIntelligenceHealthIndicator;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import com.thaap.payments.support.RiskWorkspaceScenario.Merchant;
import com.thaap.payments.support.RiskWorkspaceScenario.Operator;
import com.thaap.payments.support.RiskWorkspaceScenario.Payment;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves C53-OPS-001: an outage in a derived dependency degrades the section that depends on it
 * and nothing else. A workspace that goes dark because a read model is rebuilding is a workspace
 * an analyst cannot use during exactly the incident it exists for.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
class PaymentInvestigationReadinessTests {

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ApiVersionFilter apiVersionFilter;
    @Autowired private DecisionIntelligenceHealthIndicator healthIndicator;
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
        merchant = scenario.merchant("investigation-readiness-owner");
        manager = scenario.operator(merchant, "readiness-manager", "RISK_MANAGER");
    }

    @AfterEach
    void restoreProjection() {
        jdbcTemplate.update("""
                UPDATE projection_definitions SET lifecycle_status = 'ENABLED'
                WHERE projection_name = 'payment-risk-v1'
                """);
    }

    @Test
    void aDisabledProjectionStillLeavesAuthoritativeSourceDetailFullyReadable() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "readiness-outage");
        scenario.drainProjection();
        disableProjection();

        JsonNode detail = scenario.json(get("/risk/workspace/payments/{id}",
                reviewed.paymentIntentId()).header(RISK_OPERATOR_HEADER, manager.credential()), 200);

        assertThat(detail.at("/paymentLifecycle/status").asText()).isEqualTo(
                jdbcTemplate.queryForObject(
                        "SELECT status FROM payment_intents WHERE merchant_id = ? AND id = ?",
                        String.class, merchant.id(), reviewed.paymentIntentId()));
        assertThat(detail.get("decisionExplanation").isNull()).isFalse();
        assertThat(detail.at("/sectionStatuses/analyticsContext/state").asText())
                .isEqualTo("UNAVAILABLE");
        assertThat(detail.at("/sectionStatuses/analyticsContext/reasonCode").asText())
                .isEqualTo("active_projection_unavailable");
        assertThat(detail.get("analyticsContext").isNull()).isTrue();
    }

    @Test
    void aDisabledProjectionMakesSearchFailLoudlyRatherThanReturnAnEmptyPage() throws Exception {
        scenario.reviewedPayment(merchant, "readiness-search-outage");
        scenario.drainProjection();
        disableProjection();

        // An empty page during an outage is the dangerous answer: it reads as "no risky
        // payments" to the analyst who is deciding whether an attack is underway.
        int status = mockMvc.perform(post("/risk/workspace/payments/search")
                        .header(RISK_OPERATOR_HEADER, manager.credential())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).as("search must not silently report no results during an outage")
                .isNotEqualTo(200);
        assertThat(status).isIn(500, 503);
    }

    @Test
    void sourceOnlyRoutesKeepWorkingWhileTheProjectionIsDown() throws Exception {
        Payment reviewed = scenario.reviewedPayment(merchant, "readiness-source-routes");
        scenario.drainProjection();
        disableProjection();

        for (String route : List.of("/risk/workspace/payments/{id}",
                "/risk/workspace/payments/{id}/timeline",
                "/risk/workspace/payments/{id}/webhook-activity")) {
            mockMvc.perform(get(route, reviewed.paymentIntentId())
                            .header(RISK_OPERATOR_HEADER, manager.credential()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("route %s depends only on source truth", route).isEqualTo(200));
        }
    }

    @Test
    void investigationReadinessReportsUpRepeatedlyAndIsSafeToPollFromAProbe() {
        // A probe runs forever. A health check that mutates state or drifts between calls
        // becomes its own outage, so the same answer must survive repetition.
        for (int poll = 0; poll < 3; poll++) {
            var health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("openApiArtifact", "AVAILABLE")
                    .containsKeys("bulkBacklog", "relatedAnchorRows");
        }
    }

    @Test
    void investigationReadinessReportsDownWithASafeCauseWhenSourceTruthIsUnreachable() {
        jdbcTemplate.execute("DROP TABLE payment_investigation_relation_anchors");
        try {
            var health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("cause", "SOURCE_DATABASE_UNAVAILABLE");
            // The probe body reaches operators and dashboards, so it names a closed cause
            // rather than the driver text that would disclose schema internals.
            assertThat(health.getDetails().toString()).doesNotContain("SELECT", "org.h2");
        } finally {
            jdbcTemplate.execute("""
                    CREATE TABLE payment_investigation_relation_anchors (
                        id UUID NOT NULL, merchant_id UUID NOT NULL,
                        payment_intent_id UUID NOT NULL, payment_attempt_id UUID NOT NULL,
                        relation_type VARCHAR(32) NOT NULL,
                        relation_identity_hmac VARCHAR(255) NOT NULL,
                        identity_key_version VARCHAR(32) NOT NULL,
                        observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        traffic_origin VARCHAR(32) NOT NULL,
                        source_type VARCHAR(32) NOT NULL, source_id UUID NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL)
                    """);
        }
    }

    private void disableProjection() {
        jdbcTemplate.update("""
                UPDATE projection_definitions SET lifecycle_status = 'DISABLED'
                WHERE projection_name = 'payment-risk-v1'
                """);
    }
}
