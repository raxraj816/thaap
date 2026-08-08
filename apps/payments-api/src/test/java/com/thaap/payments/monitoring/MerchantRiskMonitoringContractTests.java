package com.thaap.payments.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class MerchantRiskMonitoringContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String SCENARIO_TOKEN = "X-Thaap-Scenario-Runner-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private MerchantRiskMonitoringHealthIndicator monitoringHealthIndicator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void monitoringUsesDurableDenominatorsAndExcludesSyntheticTrafficByDefault() throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-origin");
        OperatorAccess viewer = createOperator(merchant, "monitoring-viewer", "VIEWER");
        UUID customerId = createCustomer(merchant, "monitoring@example.test");
        UUID approvedMethod = createPaymentMethod(merchant, customerId, "4242424242424242", "approved-method");
        UUID declinedMethod = createPaymentMethod(merchant, customerId, "4999990000000002", "declined-method");

        confirm(merchant, customerId, approvedMethod, "live-approved", false);
        confirm(merchant, customerId, declinedMethod, "live-declined", false);
        confirm(merchant, customerId, approvedMethod, "synthetic-approved", true);
        Map<String, Long> domainTruthBeforeMonitoring = domainTruthCounts();

        Instant asOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        Instant start = asOf.minus(1, ChronoUnit.HOURS);
        JsonNode live = summary(viewer, start, asOf, asOf, null);
        JsonNode liveApproval = metric(live, "AUTHORIZATION_APPROVAL_RATE");
        assertThat(liveApproval.get("numerator").asLong()).isEqualTo(1);
        assertThat(liveApproval.get("denominator").asLong()).isEqualTo(2);
        assertThat(liveApproval.get("value").decimalValue()).isEqualByComparingTo("0.500000");
        assertThat(liveApproval.get("trafficOriginPolicy").asText()).isEqualTo("MERCHANT_API_ONLY");

        JsonNode all = summary(viewer, start, asOf, asOf, "INCLUDE_SYNTHETIC");
        JsonNode allApproval = metric(all, "AUTHORIZATION_APPROVAL_RATE");
        assertThat(allApproval.get("numerator").asLong()).isEqualTo(2);
        assertThat(allApproval.get("denominator").asLong()).isEqualTo(3);
        assertThat(allApproval.get("value").decimalValue()).isEqualByComparingTo("0.666667");
        assertThat(metric(live, "CONFIRMED_FRAUD_RATE").get("coverageStatus").asText())
                .isEqualTo("PARTIAL_LABEL_COVERAGE");
        assertThat(metric(live, "REVIEW_BACKLOG").get("denominator").isNull()).isTrue();

        JsonNode firstPage = monitoringCohort(viewer, "AUTHORIZATION_APPROVAL_RATE",
                start, asOf, asOf, null, 1, null);
        assertThat(firstPage.get("rows")).hasSize(1);
        String cursor = firstPage.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode secondPage = monitoringCohort(viewer, "AUTHORIZATION_APPROVAL_RATE",
                start, asOf, asOf, null, 1, cursor);
        assertThat(secondPage.get("rows")).hasSize(1);
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();
        long drillDownNumerator = firstPage.at("/rows/0/numeratorMember").asBoolean() ? 1 : 0;
        drillDownNumerator += secondPage.at("/rows/0/numeratorMember").asBoolean() ? 1 : 0;
        assertThat(drillDownNumerator).isEqualTo(1);
        String drillDownBytes = firstPage.toString() + secondPage;
        assertThat(drillDownBytes).doesNotContain(merchant.apiKey(), "4242424242424242",
                "4999990000000002", "monitoring@example.test", "fingerprint", "identity");
        mockMvc.perform(get("/risk/monitoring/metrics/AUTHORIZATION_APPROVAL_RATE/cohort")
                        .header(RISK_KEY, viewer.secret())
                        .param("windowStart", start.toString()).param("windowEnd", asOf.toString())
                        .param("asOf", asOf.toString()).param("limit", "1")
                        .param("trafficOriginPolicy", "INCLUDE_SYNTHETIC")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/monitoring/metrics/AUTHORIZATION_APPROVAL_RATE/cohort")
                        .header(RISK_KEY, viewer.secret()).param("limit", "101"))
                .andExpect(status().isBadRequest());
        assertThat(domainTruthCounts()).isEqualTo(domainTruthBeforeMonitoring);
    }

    @Test
    void snapshotIsManagerOnlyActorBoundImmutableAndNeverActivatesProfile() throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-snapshot");
        MerchantAccess other = createMerchant("monitoring-snapshot-other");
        OperatorAccess manager = createOperator(merchant, "monitoring-manager", "RISK_MANAGER");
        OperatorAccess analyst = createOperator(merchant, "monitoring-analyst", "ANALYST");
        OperatorAccess otherManager = createOperator(other, "monitoring-other-manager", "RISK_MANAGER");
        Instant asOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        String body = snapshotBody(asOf.minus(1, ChronoUnit.HOURS), asOf, asOf, "MERCHANT_API_ONLY");

        mockMvc.perform(post("/risk/monitoring/snapshots")
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-snapshot")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        String first = mockMvc.perform(post("/risk/monitoring/snapshots")
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-snapshot")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/risk/monitoring/snapshots")
                        .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-snapshot")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        JsonNode snapshot = objectMapper.readTree(first);
        UUID snapshotId = UUID.fromString(snapshot.get("id").asText());
        assertThat(snapshot.get("resultSha256").asText()).hasSize(64);
        assertThat(snapshot.get("recommendation").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_metric_snapshots",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_profile_recommendations",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_profile_events",
                Integer.class)).isZero();

        mockMvc.perform(get("/risk/monitoring/snapshots/{id}", snapshotId)
                        .header(RISK_KEY, otherManager.secret()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/risk/monitoring/snapshots")
                        .header(RISK_KEY, otherManager.secret()).header(IDEMPOTENCY_KEY, "manager-snapshot")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_metric_snapshots",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void liveEnumerationEvidenceCreatesHighRecommendationWithoutActivatingProfile() throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-recommendation");
        OperatorAccess manager = createOperator(merchant, "recommendation-manager", "RISK_MANAGER");
        UUID customerId = createCustomer(merchant, "recommendation@example.test");
        UUID cvcFailureMethod = createPaymentMethod(merchant, customerId,
                "4999990000000005", "recommendation-method");
        for (int index = 0; index < 20; index++) {
            confirm(merchant, customerId, cvcFailureMethod, "enumeration-" + index, false);
        }
        Instant asOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        String body = snapshotBody(asOf.minus(1, ChronoUnit.HOURS), asOf, asOf,
                "MERCHANT_API_ONLY");

        JsonNode snapshot = objectMapper.readTree(mockMvc.perform(post("/risk/monitoring/snapshots")
                        .header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "recommendation-snapshot")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(snapshot.at("/recommendation/recommendedTier").asText()).isEqualTo("HIGH");
        assertThat(snapshot.at("/recommendation/reasonMetricCode").asText())
                .isEqualTo("ENUMERATION_RATIO");
        assertThat(snapshot.at("/recommendation/automaticallyActivated").asBoolean()).isFalse();
        assertThat(metric(snapshot.get("summary"), "ENUMERATION_RATIO").get("denominator").asLong())
                .isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_profile_events",
                Integer.class)).isZero();
    }

    @Test
    void lateChargebackAndFraudLabelEnterOnlyReportsWhoseAsOfCanObserveThem() throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-point-in-time");
        OperatorAccess viewer = createOperator(merchant, "monitoring-point-in-time-viewer", "VIEWER");
        UUID customerId = createCustomer(merchant, "point-in-time@example.test");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId,
                "4242424242424242", "point-in-time-method");
        UUID paymentIntentId = createAndConfirmCapturedPayment(
                merchant, customerId, paymentMethodId, "point-in-time-capture");
        Instant beforeEvidence = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        Instant receivedAt = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        if (!receivedAt.isAfter(beforeEvidence)) {
            receivedAt = beforeEvidence.plus(1, ChronoUnit.MICROS);
        }
        String chargebackBody = """
                {"externalEventKey":"monitoring-point-in-time-event",
                 "paymentIntentId":"%s","amount":1200,"currency":"USD",
                 "reasonCode":"FRAUDULENT_STOLEN_CREDENTIAL",
                 "evidenceOccurredAt":"%s","receivedAt":"%s","evidenceDueAt":"%s"}
                """.formatted(paymentIntentId, beforeEvidence.minusSeconds(60), receivedAt,
                receivedAt.plus(1, ChronoUnit.DAYS));
        mockMvc.perform(post("/chargebacks").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "monitoring-point-in-time-chargeback")
                        .contentType(APPLICATION_JSON).content(chargebackBody))
                .andExpect(status().isCreated());
        Timestamp availableTimestamp = jdbcTemplate.queryForObject(
                "SELECT available_at FROM chargebacks WHERE payment_intent_id = ?",
                Timestamp.class, paymentIntentId);
        assertThat(availableTimestamp).isNotNull();
        Instant availableAt = availableTimestamp.toInstant();
        Instant afterEvidence = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        Instant windowStart = beforeEvidence.minus(1, ChronoUnit.HOURS);

        JsonNode before = summary(viewer, windowStart, beforeEvidence, beforeEvidence, null);
        JsonNode atAvailability = summary(viewer, windowStart, beforeEvidence, availableAt, null);
        JsonNode after = summary(viewer, windowStart, beforeEvidence, afterEvidence, null);
        assertThat(metric(before, "CHARGEBACK_COUNT_RATE").get("numerator").asLong()).isZero();
        assertThat(metric(before, "CHARGEBACK_COUNT_RATE").get("denominator").asLong()).isOne();
        assertThat(metric(after, "CHARGEBACK_COUNT_RATE").get("numerator").asLong()).isOne();
        assertThat(metric(after, "CHARGEBACK_COUNT_RATE").get("denominator").asLong()).isOne();
        assertThat(metric(atAvailability, "CHARGEBACK_COUNT_RATE").get("numerator").asLong())
                .isOne();
        assertThat(metric(before, "CONFIRMED_FRAUD_RATE").get("numerator").asLong()).isZero();
        assertThat(metric(before, "CONFIRMED_FRAUD_RATE").get("coverageStatus").asText())
                .isEqualTo("PARTIAL_LABEL_COVERAGE");
        assertThat(metric(after, "CONFIRMED_FRAUD_RATE").get("numerator").asLong()).isOne();
        assertThat(metric(atAvailability, "CONFIRMED_FRAUD_RATE").get("numerator").asLong())
                .isOne();
        assertThat(metric(after, "CONFIRMED_FRAUD_RATE").get("coverageStatus").asText())
                .isEqualTo("COMPLETE");

        JsonNode disputeWindow = summary(viewer, windowStart, afterEvidence, afterEvidence, null);
        JsonNode overlap = metric(disputeWindow, "FRAUD_DISPUTE_OVERLAP_RATE");
        assertThat(overlap.get("numerator").asLong()).isOne();
        assertThat(overlap.get("denominator").asLong()).isOne();
        JsonNode overlapCohort = monitoringCohort(viewer, "FRAUD_DISPUTE_OVERLAP_RATE",
                windowStart, afterEvidence, afterEvidence, null, 10, null);
        assertThat(overlapCohort.get("rows")).hasSize(1);
        assertThat(overlapCohort.at("/rows/0/numeratorMember").asBoolean()).isTrue();
    }

    @Test
    void lateIncidentEvidenceCannotRewriteEnumerationSummaryOrCohortBeforeLinkedAt()
            throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-enumeration-point-in-time");
        OperatorAccess viewer = createOperator(
                merchant, "monitoring-enumeration-viewer", "VIEWER");
        UUID customerId = createCustomer(merchant, "enumeration-point-in-time@example.test");
        UUID issuerDecline = createPaymentMethod(merchant, customerId,
                "4999990000000002", "enumeration-issuer-decline");
        UUID insufficientFunds = createPaymentMethod(merchant, customerId,
                "4999990000000003", "enumeration-insufficient-funds");
        UUID suspectedFraud = createPaymentMethod(merchant, customerId,
                "4999990000000004", "enumeration-suspected-fraud");

        confirm(merchant, customerId, issuerDecline, "enumeration-before-link-1", false);
        confirm(merchant, customerId, insufficientFunds, "enumeration-before-link-2", false);
        confirm(merchant, customerId, suspectedFraud, "enumeration-before-link-3", false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident_evidence_links", Integer.class)).isZero();

        Instant frozenAsOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        Instant windowStart = frozenAsOf.minus(1, ChronoUnit.HOURS);
        JsonNode summaryBeforeLink = summary(
                viewer, windowStart, frozenAsOf, frozenAsOf, null);
        JsonNode cohortBeforeLink = monitoringCohort(viewer, "ENUMERATION_RATIO",
                windowStart, frozenAsOf, frozenAsOf, null, 10, null);
        assertThat(metric(summaryBeforeLink, "ENUMERATION_RATIO").get("numerator").asLong())
                .isZero();
        assertThat(metric(summaryBeforeLink, "ENUMERATION_RATIO").get("denominator").asLong())
                .isEqualTo(3);
        assertThat(cohortBeforeLink.get("rows")).hasSize(3);
        assertThat(numeratorMemberCount(cohortBeforeLink)).isZero();

        Instant linkedAt = linkEnumerationEvidence(merchant.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident_evidence_links", Integer.class)).isEqualTo(3);
        Timestamp linkedTimestamp = jdbcTemplate.queryForObject(
                "SELECT MIN(linked_at) FROM incident_evidence_links", Timestamp.class);
        assertThat(linkedTimestamp).isNotNull();
        assertThat(linkedTimestamp.toInstant()).isEqualTo(linkedAt);
        assertThat(linkedAt).isAfter(frozenAsOf);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT linked_at) FROM incident_evidence_links", Integer.class))
                .isOne();
        Instant afterLinkedAt = linkedAt.plus(1, ChronoUnit.MICROS);
        while (DatabaseInstant.now().isBefore(afterLinkedAt)) {
            Thread.onSpinWait();
        }

        JsonNode summaryReplayedBeforeLink = summary(
                viewer, windowStart, frozenAsOf, frozenAsOf, null);
        JsonNode cohortReplayedBeforeLink = monitoringCohort(viewer, "ENUMERATION_RATIO",
                windowStart, frozenAsOf, frozenAsOf, null, 10, null);
        assertThat(summaryReplayedBeforeLink).isEqualTo(summaryBeforeLink);
        assertThat(cohortReplayedBeforeLink).isEqualTo(cohortBeforeLink);

        JsonNode summaryAtLink = summary(viewer, windowStart, frozenAsOf, linkedAt, null);
        JsonNode summaryAfterLink = summary(
                viewer, windowStart, frozenAsOf, afterLinkedAt, null);
        assertThat(metric(summaryAtLink, "ENUMERATION_RATIO").get("numerator").asLong())
                .isEqualTo(3);
        assertThat(metric(summaryAtLink, "ENUMERATION_RATIO").get("denominator").asLong())
                .isEqualTo(3);
        assertThat(metric(summaryAfterLink, "ENUMERATION_RATIO").get("numerator").asLong())
                .isEqualTo(3);
        assertThat(metric(summaryAfterLink, "ENUMERATION_RATIO").get("denominator").asLong())
                .isEqualTo(3);

        JsonNode cohortAtLink = monitoringCohort(viewer, "ENUMERATION_RATIO",
                windowStart, frozenAsOf, linkedAt, null, 10, null);
        JsonNode cohortAfterLink = monitoringCohort(viewer, "ENUMERATION_RATIO",
                windowStart, frozenAsOf, afterLinkedAt, null, 10, null);
        assertThat(cohortAtLink.get("rows")).hasSize(3);
        assertThat(cohortAfterLink.get("rows")).hasSize(3);
        assertThat(numeratorMemberCount(cohortAtLink)).isEqualTo(3);
        assertThat(numeratorMemberCount(cohortAfterLink)).isEqualTo(3);
    }

    @Test
    void taxonomyIsVersionedCompleteAndMonitoringGrammarRejectsFalseTimeTruth() throws Exception {
        MerchantAccess merchant = createMerchant("monitoring-taxonomy");
        OperatorAccess viewer = createOperator(merchant, "taxonomy-viewer", "VIEWER");

        JsonNode taxonomy = objectMapper.readTree(mockMvc.perform(get("/risk/monitoring/taxonomy")
                        .header(RISK_KEY, viewer.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(taxonomy).hasSize(18);
        assertThat(taxonomy.findValuesAsText("taxonomyVersion"))
                .allMatch("fraud_taxonomy_v1"::equals);
        JsonNode fraudTypes = objectMapper.readTree(mockMvc.perform(get("/risk/monitoring/taxonomy")
                        .param("sourceType", "FRAUD_TYPE").header(RISK_KEY, viewer.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(fraudTypes).hasSize(5);
        JsonNode reportingBuckets = objectMapper.readTree(mockMvc.perform(get("/risk/monitoring/taxonomy")
                        .param("sourceType", "REPORTING_BUCKET").header(RISK_KEY, viewer.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reportingBuckets).hasSize(1);
        assertThat(reportingBuckets.get(0).get("sourceCode").asText()).isEqualTo("UNCLASSIFIED");

        Instant now = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        mockMvc.perform(get("/risk/monitoring/summary").header(RISK_KEY, viewer.secret())
                        .param("windowStart", now.toString())
                        .param("windowEnd", now.minusSeconds(1).toString())
                        .param("asOf", now.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/monitoring/summary").header(RISK_KEY, viewer.secret())
                        .param("windowStart", now.minus(91, ChronoUnit.DAYS).toString())
                        .param("windowEnd", now.toString()).param("asOf", now.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/monitoring/summary").header(RISK_KEY, viewer.secret())
                        .param("asOf", now.plus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/monitoring/metrics/NOT_A_METRIC")
                        .header(RISK_KEY, viewer.secret()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readinessFailsClosedForSemanticTaxonomyDriftNotOnlyWrongRowCount() {
        try {
            replaceTaxonomyLabelForReadinessFixture("Wrong semantic mapping");

            Health health = monitoringHealthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("cause", "TAXONOMY_INVENTORY_MISMATCH");
        } finally {
            replaceTaxonomyLabelForReadinessFixture("Card testing");
        }
        assertThat(monitoringHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    protected void replaceTaxonomyLabelForReadinessFixture(String displayLabel) {
        jdbcTemplate.update("""
                UPDATE fraud_taxonomy_definitions SET display_label = ?
                 WHERE taxonomy_version = 'fraud_taxonomy_v1'
                   AND source_type = 'FRAUD_TYPE' AND source_code = 'CARD_TESTING'
                """, displayLabel);
    }

    private JsonNode summary(OperatorAccess operator, Instant start, Instant end, Instant asOf,
            String originPolicy) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/risk/monitoring/summary").header(RISK_KEY, operator.secret())
                        .param("windowStart", start.toString()).param("windowEnd", end.toString())
                        .param("asOf", asOf.toString());
        if (originPolicy != null) {
            request.param("trafficOriginPolicy", originPolicy);
        }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode monitoringCohort(OperatorAccess operator, String metricCode,
            Instant start, Instant end, Instant asOf, String originPolicy,
            int limit, String cursor) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/risk/monitoring/metrics/{metricCode}/cohort", metricCode)
                        .header(RISK_KEY, operator.secret())
                        .param("windowStart", start.toString()).param("windowEnd", end.toString())
                        .param("asOf", asOf.toString()).param("limit", Integer.toString(limit));
        if (originPolicy != null) {
            request.param("trafficOriginPolicy", originPolicy);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode metric(JsonNode summary, String code) {
        for (JsonNode metric : summary.get("metrics")) {
            if (code.equals(metric.get("metricCode").asText())) {
                return metric;
            }
        }
        throw new AssertionError("Missing metric " + code);
    }

    private long numeratorMemberCount(JsonNode cohort) {
        return java.util.stream.StreamSupport.stream(cohort.get("rows").spliterator(), false)
                .filter(row -> row.get("numeratorMember").asBoolean())
                .count();
    }

    private Instant linkEnumerationEvidence(UUID merchantId) {
        List<IncidentEvidenceFixture> evidence = jdbcTemplate.query("""
                SELECT attempt.id AS payment_attempt_id,
                       decision.id AS fraud_decision_id,
                       attempt.created_at AS observed_at
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                 WHERE attempt.merchant_id = ?
                 ORDER BY attempt.created_at, attempt.id
                """, (resultSet, rowNumber) -> new IncidentEvidenceFixture(
                        resultSet.getObject("payment_attempt_id", UUID.class),
                        resultSet.getObject("fraud_decision_id", UUID.class),
                        resultSet.getTimestamp("observed_at").toInstant()), merchantId);
        assertThat(evidence).hasSize(3);
        Instant linkedAt = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        Instant firstEvidenceAt = evidence.getFirst().observedAt();
        Instant lastEvidenceAt = evidence.getLast().observedAt();
        UUID incidentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO card_testing_incidents (
                    id, merchant_id, status, open_slot, trigger_code, source_attestation,
                    detection_window_started_at, detection_window_ended_at,
                    attempt_count, decline_count, distinct_card_count, distinct_bin_count,
                    distinct_ip_count, first_evidence_at, last_evidence_at,
                    created_at, updated_at)
                VALUES (?, ?, 'OPEN', TRUE, 'PERSISTED_CARD_TESTING_PATTERN',
                    'THAAP_PERSISTED_CONTROL_EVIDENCE', ?, ?, 3, 3, 3, 1, 0, ?, ?, ?, ?)
                """, incidentId, merchantId, Timestamp.from(firstEvidenceAt.minusSeconds(1)),
                Timestamp.from(linkedAt), Timestamp.from(firstEvidenceAt),
                Timestamp.from(lastEvidenceAt), Timestamp.from(linkedAt), Timestamp.from(linkedAt));
        for (IncidentEvidenceFixture row : evidence) {
            jdbcTemplate.update("""
                    INSERT INTO incident_evidence_links (
                        id, merchant_id, incident_id, payment_attempt_id, fraud_decision_id,
                        evidence_type, observed_at, linked_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSOR_DECLINE', ?, ?)
                    """, UUID.randomUUID(), merchantId, incidentId, row.paymentAttemptId(),
                    row.fraudDecisionId(), Timestamp.from(row.observedAt()), Timestamp.from(linkedAt));
        }
        return linkedAt;
    }

    private void confirm(MerchantAccess merchant, UUID customerId, UUID methodId,
            String key, boolean synthetic) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create =
                post("/payment_intents").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-intent").contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"captureMethod\":\"MANUAL\"}");
        if (synthetic) {
            create.header(SCENARIO_TOKEN, "test-scenario-runner-token");
        }
        JsonNode intent = objectMapper.readTree(mockMvc.perform(create)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID intentId = UUID.fromString(intent.get("id").asText());
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm =
                post("/payment_intents/{id}/confirm", intentId).header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-confirm").contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}");
        if (synthetic) {
            confirm.header(SCENARIO_TOKEN, "test-scenario-runner-token");
        }
        mockMvc.perform(confirm).andExpect(status().isCreated());
    }

    private UUID createAndConfirmCapturedPayment(MerchantAccess merchant, UUID customerId,
            UUID methodId, String key) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-intent")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"captureMethod\":\"AUTOMATIC\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID intentId = UUID.fromString(intent.get("id").asText());
        mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated());
        return intentId;
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId,
            String cardNumber, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"" + cardNumber + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private String snapshotBody(Instant start, Instant end, Instant asOf, String policy) {
        return "{\"windowStart\":\"" + start + "\",\"windowEnd\":\"" + end
                + "\",\"asOf\":\"" + asOf + "\",\"trafficOriginPolicy\":\""
                + policy + "\"}";
    }

    private Map<String, Long> domainTruthCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {"payment_intents", "payment_attempts", "authorizations",
                "fraud_decisions", "fraud_labels", "ledger_entries", "manual_review_cases",
                "chargebacks", "merchant_risk_profile_events"}) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return Map.copyOf(counts);
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record IncidentEvidenceFixture(
            UUID paymentAttemptId, UUID fraudDecisionId, Instant observedAt) { }
}
