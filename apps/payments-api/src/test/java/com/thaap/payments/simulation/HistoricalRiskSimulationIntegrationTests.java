package com.thaap.payments.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.simulation.creation-enabled=true",
        "thaap.simulation.gate.minimum-eligible-population=1",
        "thaap.simulation.gate.minimum-label-coverage-percent=0"
})
class HistoricalRiskSimulationIntegrationTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private RiskSimulationService service;
    @Autowired private RiskSimulationDispatcher dispatcher;
    @Autowired private RiskSimulationActivationGate activationGate;
    @Autowired private RiskSimulationCandidateSnapshotService snapshotService;
    @MockitoSpyBean private RiskSimulationReportService reportService;
    @MockitoSpyBean private RiskSimulationExecutionProbe executionProbe;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void jdbcBatchRunUsesFrozenFeaturesAndReconcilesItsImmutableReport() throws Exception {
        PreparedRun prepared = prepareRun();
        RiskSimulationService.SimulationView queued = prepared.run();
        RiskOperatorContext operator = prepared.operator();

        dispatcher.launchNow(queued.id());

        assertSuccessfulRun(queued, operator);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_step_execution",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void failedFinalizerRestartsFromJdbcMetadataWithoutDuplicatingResults() throws Exception {
        PreparedRun prepared = prepareRun();
        doThrow(new IllegalStateException("injected finalizer boundary failure"))
                .doCallRealMethod().when(reportService).finalizeSuccess(any());

        dispatcher.launchNow(prepared.run().id());

        RiskSimulationService.SimulationView failedExecution = service.get(
                prepared.operator(), prepared.run().id());
        assertThat(failedExecution.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.RUNNING);
        assertThat(failedExecution.failureCode()).isEqualTo("RECOVERABLE_EXECUTION_FAILURE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_results",
                Integer.class)).isOne();

        dispatcher.launchNow(prepared.run().id());

        assertSuccessfulRun(prepared.run(), prepared.operator());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_results",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_reports",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_execution",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void failedChunkRollsBackResultsAndProgressThenRestartsWithoutDuplication() throws Exception {
        PreparedRun prepared = prepareRun();
        doThrow(new IllegalStateException("injected chunk commit-edge failure"))
                .doNothing().when(executionProbe).afterResultWritesBeforeProgress(prepared.run().id());

        dispatcher.launchNow(prepared.run().id());

        RiskSimulationService.SimulationView failed = service.get(
                prepared.operator(), prepared.run().id());
        assertThat(failed.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.RUNNING);
        assertThat(failed.processedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_results",
                Integer.class)).isZero();

        dispatcher.launchNow(prepared.run().id());

        assertSuccessfulRun(prepared.run(), prepared.operator());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_results",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_execution",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void canceledQueuedRunCannotBeRevivedByADelayedWorker() throws Exception {
        PreparedRun prepared = prepareRun();

        RiskSimulationService.SimulationView canceled = service.cancel(
                prepared.operator(), prepared.run().id(), "cancel-before-launch");

        assertThat(canceled.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.CANCELED);
        dispatcher.launchNow(prepared.run().id());
        assertThat(service.get(prepared.operator(), prepared.run().id()).lifecycleStatus())
                .isEqualTo(RiskSimulationLifecycleStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_results",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_reports",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_simulation_events
                 WHERE simulation_run_id = ? AND event_type = 'CANCELED'
                """, Integer.class, prepared.run().id())).isOne();
    }

    @Test
    void committedCancellationWinsAgainstPausedFinalization() throws Exception {
        PreparedRun prepared = prepareRun();
        CountDownLatch finalizerEntered = new CountDownLatch(1);
        CountDownLatch allowFinalizer = new CountDownLatch(1);
        doAnswer(invocation -> {
            finalizerEntered.countDown();
            if (!allowFinalizer.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("finalizer test probe timed out");
            }
            return invocation.callRealMethod();
        }).when(reportService).finalizeSuccess(prepared.run().id());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> worker = executor.submit(() -> dispatcher.launchNow(prepared.run().id()));
            assertThat(finalizerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            RiskSimulationService.SimulationView canceled = service.cancel(
                    prepared.operator(), prepared.run().id(), "cancel-finalizer-race");
            assertThat(canceled.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.CANCELED);
            allowFinalizer.countDown();
            worker.get(5, TimeUnit.SECONDS);
        } finally {
            allowFinalizer.countDown();
        }

        assertThat(service.get(prepared.operator(), prepared.run().id()).lifecycleStatus())
                .isEqualTo(RiskSimulationLifecycleStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_simulation_reports",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_simulation_events
                 WHERE simulation_run_id = ? AND event_type IN ('SUCCEEDED', 'CANCELED')
                """, Integer.class, prepared.run().id())).isOne();
    }

    @Test
    void activationGateRequiresDistinctManagerAndExactCurrentCandidateDigest() throws Exception {
        PreparedRun prepared = prepareRun();
        dispatcher.launchNow(prepared.run().id());

        RiskSimulationActivationGate.GateDecision selfApproval = activationGate.evaluate(
                prepared.merchant().id(), prepared.run().id(), prepared.manager().id());
        assertThat(selfApproval.disposition()).isEqualTo(RiskSimulationGateDisposition.REJECTED);
        assertThat(selfApproval.reasons()).containsExactly(
                RiskSimulationGateReason.CREATOR_CANNOT_APPROVE);

        OperatorAccess independentManager = createManager(prepared.merchant(), "simulation-approver");
        RiskSimulationActivationGate.GateDecision accepted = activationGate.evaluate(
                prepared.merchant().id(), prepared.run().id(), independentManager.id());
        assertThat(accepted.disposition()).isEqualTo(RiskSimulationGateDisposition.ACCEPTED);
        assertThat(accepted.reasons()).isEmpty();

        mockMvc.perform(post("/risk/policies/{id}/thresholds", prepared.run().candidateVersionId())
                        .header("X-Risk-Operator-Key", prepared.manager().secret())
                        .header(IDEMPOTENCY_KEY, "simulation-digest-drift")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"AMOUNT_BLOCK","featureCode":"payment.amount_minor",
                                 "operator":"GTE","longValue":500,"candidateAction":"BLOCK",
                                 "priority":200,"reasonCode":"MERCHANT_AMOUNT_THRESHOLD"}
                                """))
                .andExpect(status().isCreated());
        RiskSimulationActivationGate.GateDecision drifted = activationGate.evaluate(
                prepared.merchant().id(), prepared.run().id(), independentManager.id());
        assertThat(drifted.disposition()).isEqualTo(RiskSimulationGateDisposition.REJECTED);
        assertThat(drifted.reasons()).containsExactly(
                RiskSimulationGateReason.CANDIDATE_DIGEST_MISMATCH);
    }

    @Test
    void publicApiEnforcesProgressReportRoleAndTenantBoundaries() throws Exception {
        PreparedRun prepared = prepareRun();
        OperatorAccess viewer = createOperator(prepared.merchant(), "VIEWER", "simulation-viewer");

        mockMvc.perform(get("/risk/simulations/{id}", prepared.run().id())
                        .header("X-Risk-Operator-Key", viewer.secret()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/simulations")
                        .header("X-Risk-Operator-Key", viewer.secret())
                        .header(IDEMPOTENCY_KEY, "viewer-cannot-create")
                        .contentType(APPLICATION_JSON).content("""
                                {"candidateType":"MERCHANT_RISK_POLICY",
                                 "candidateVersionId":"%s",
                                 "cohortStart":"%s","cohortEnd":"%s",
                                 "asOf":"%s","labelCutoff":"%s",
                                 "trafficOriginPolicy":"LIVE_ONLY"}
                                """.formatted(prepared.run().candidateVersionId(),
                                prepared.run().cohortStart(), prepared.run().cohortEnd(),
                                prepared.run().asOf(), prepared.run().labelCutoff())))
                .andExpect(status().isForbidden());

        dispatcher.launchNow(prepared.run().id());
        String publicReport = mockMvc.perform(get("/risk/simulations/{id}/report", prepared.run().id())
                        .header("X-Risk-Operator-Key", viewer.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(publicReport).at("/report/population/processed").asLong()).isOne();
        assertThat(json(publicReport).at("/report/actionTransitions/ALLOW->REVIEW").asLong()).isOne();

        MerchantAccess foreignMerchant = createMerchant("simulation-foreign-merchant");
        OperatorAccess foreignManager = createManager(foreignMerchant, "simulation-foreign-manager");
        mockMvc.perform(get("/risk/simulations/{id}", prepared.run().id())
                        .header("X-Risk-Operator-Key", foreignManager.secret()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/risk/simulations/{id}/report", prepared.run().id())
                        .header("X-Risk-Operator-Key", foreignManager.secret()))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyCandidateFamilyPreservesUnrelatedHistoricalControlEvidence() throws Exception {
        MerchantAccess merchant = createMerchant("simulation-mixed-controls");
        OperatorAccess creator = createManager(merchant, "mixed-control-creator");
        OperatorAccess approver = createManager(merchant, "mixed-control-approver");
        UUID customerId = createCustomer(merchant);
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);

        UUID activePolicyId = createPolicy(creator, "ACTIVE_REVIEW_POLICY",
                "active-review-policy", FraudDecisionAction.REVIEW);
        transitionPolicy(creator, activePolicyId, "submit", "active-policy-submit");
        transitionPolicy(approver, activePolicyId, "approve", "active-policy-approve");
        transitionPolicy(approver, activePolicyId, "activate", "active-policy-activate");

        UUID activeRuleId = createRule(creator, "ACTIVE_BLOCK_RULE",
                "active-block-rule", FraudDecisionAction.BLOCK);
        transitionControl(creator, "rules", activeRuleId, "submit", "active-rule-submit", null);
        transitionControl(approver, "rules", activeRuleId, "approve", "active-rule-approve", null);
        UUID activationRunId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                RiskSimulationCandidateType.AUTHORED_RULE, activeRuleId);
        transitionControl(approver, "rules", activeRuleId, "activate",
                "active-rule-activate", activationRunId);

        UUID paymentIntentId = createPaymentIntent(merchant, customerId);
        JsonNode confirmation = confirm(merchant, paymentIntentId, paymentMethodId);
        UUID decisionId = UUID.fromString(confirmation.get("fraudDecisionId").asText());
        Instant decisionAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class, decisionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_control_evaluations
                 WHERE fraud_decision_id = ? AND control_type = 'POLICY_THRESHOLD'
                   AND matched = TRUE AND candidate_action = 'REVIEW'
                """, Integer.class, decisionId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_evaluations
                 WHERE fraud_decision_id = ? AND rule_version_id = ?
                   AND effective_action = 'BLOCK'
                """, Integer.class, decisionId, activeRuleId)).isOne();

        UUID candidatePolicyId = createPolicy(creator, "CANDIDATE_3DS_POLICY",
                "candidate-3ds-policy", FraudDecisionAction.REQUEST_3DS);
        UUID candidateRuleId = createRule(creator, "CANDIDATE_3DS_RULE",
                "candidate-3ds-rule", FraudDecisionAction.REQUEST_3DS);
        UUID replacementRuleId = createRule(creator, "ACTIVE_BLOCK_RULE",
                "active-block-rule-v2", FraudDecisionAction.REVIEW);
        Instant validFrom = decisionAt.minusSeconds(60);
        Instant expiresAt = decisionAt.plusSeconds(86_400);
        UUID safetyExceptionId = createSafetyException(creator, "CANDIDATE_TRUSTED_CARD",
                "candidate-safety-exception", activeRuleId, paymentMethodId, validFrom, expiresAt);
        RiskOperatorContext operator = new RiskOperatorContext(merchant.id(), creator.id(),
                "mixed-control-creator", RiskOperatorRole.RISK_MANAGER, "risk_test");

        assertThat(simulatedAction(operator, RiskSimulationCandidateType.MERCHANT_RISK_POLICY,
                candidatePolicyId, decisionAt)).isEqualTo(FraudDecisionAction.BLOCK);
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.AUTHORED_RULE,
                candidateRuleId, decisionAt)).isEqualTo(FraudDecisionAction.BLOCK);
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.AUTHORED_RULE,
                replacementRuleId, decisionAt)).isEqualTo(FraudDecisionAction.REVIEW);
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.SAFETY_EXCEPTION,
                safetyExceptionId, decisionAt)).isEqualTo(FraudDecisionAction.REVIEW);

        UUID activeSuccessorRuleId = createRule(creator, "ACTIVE_BLOCK_RULE",
                "active-block-rule-v3", FraudDecisionAction.BLOCK);
        transitionControl(creator, "rules", activeSuccessorRuleId, "submit",
                "successor-rule-submit", null);
        transitionControl(approver, "rules", activeSuccessorRuleId, "approve",
                "successor-rule-approve", null);
        UUID successorActivationRunId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                RiskSimulationCandidateType.AUTHORED_RULE, activeSuccessorRuleId);
        transitionControl(approver, "rules", activeSuccessorRuleId, "activate",
                "successor-rule-activate", successorActivationRunId);

        UUID successorIntentId = createPaymentIntent(merchant, customerId, "simulation-successor-intent");
        JsonNode successorConfirmation = confirm(merchant, successorIntentId, paymentMethodId,
                "simulation-successor-confirm");
        UUID successorDecisionId = UUID.fromString(successorConfirmation.get("fraudDecisionId").asText());
        Instant successorDecisionAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class, successorDecisionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_evaluations
                 WHERE fraud_decision_id = ? AND rule_version_id = ? AND effective_action = 'BLOCK'
                """, Integer.class, successorDecisionId, activeSuccessorRuleId)).isOne();
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.SAFETY_EXCEPTION,
                safetyExceptionId, successorDecisionAt)).isEqualTo(FraudDecisionAction.BLOCK);

        Instant broadValidFrom = successorDecisionAt.minusSeconds(60);
        Instant broadExpiresAt = successorDecisionAt.plusSeconds(86_400);
        UUID activeExceptionId = createSafetyException(creator, "ACTIVE_TRUSTED_CARD",
                "active-safety-exception", activeSuccessorRuleId, paymentMethodId,
                broadValidFrom, broadExpiresAt);
        transitionControl(creator, "exceptions", activeExceptionId, "submit",
                "active-exception-submit", null);
        transitionControl(approver, "exceptions", activeExceptionId, "approve",
                "active-exception-approve", null);
        UUID exceptionActivationRunId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                RiskSimulationCandidateType.SAFETY_EXCEPTION, activeExceptionId);
        transitionControl(approver, "exceptions", activeExceptionId, "activate",
                "active-exception-activate", exceptionActivationRunId);

        UUID suppressedIntentId = createPaymentIntent(merchant, customerId, "simulation-suppressed-intent");
        JsonNode suppressedConfirmation = confirm(merchant, suppressedIntentId, paymentMethodId,
                "simulation-suppressed-confirm");
        UUID suppressedDecisionId = UUID.fromString(suppressedConfirmation.get("fraudDecisionId").asText());
        Instant suppressedDecisionAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class, suppressedDecisionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_evaluations
                 WHERE fraud_decision_id = ? AND rule_version_id = ? AND matched = TRUE
                   AND exception_disposition = 'APPLIED' AND effective_action IS NULL
                """, Integer.class, suppressedDecisionId, activeSuccessorRuleId)).isOne();

        UUID otherPaymentMethodId = createPaymentMethod(merchant, customerId,
                "simulation-other-method", "5555555555554444");
        UUID nonMatchingExceptionId = createSafetyException(creator, "OTHER_TRUSTED_CARD",
                "other-safety-exception", activeSuccessorRuleId, otherPaymentMethodId,
                broadValidFrom, broadExpiresAt);
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.SAFETY_EXCEPTION,
                nonMatchingExceptionId, suppressedDecisionAt)).isEqualTo(FraudDecisionAction.REVIEW);

        UUID neverActiveRuleId = createRule(creator, "NEVER_ACTIVE_BLOCK_RULE",
                "never-active-block-rule", FraudDecisionAction.BLOCK);
        transitionControl(creator, "rules", neverActiveRuleId, "submit",
                "never-active-rule-submit", null);
        transitionControl(approver, "rules", neverActiveRuleId, "approve",
                "never-active-rule-approve", null);
        UUID neverActiveExceptionId = createSafetyException(creator, "NEVER_ACTIVE_TRUSTED_CARD",
                "never-active-safety-exception", neverActiveRuleId, paymentMethodId,
                broadValidFrom, broadExpiresAt);
        assertThat(simulatedAction(operator, RiskSimulationCandidateType.SAFETY_EXCEPTION,
                neverActiveExceptionId, suppressedDecisionAt)).isEqualTo(FraudDecisionAction.REVIEW);
    }

    private PreparedRun prepareRun() throws Exception {
        MerchantAccess merchant = createMerchant("simulation-merchant");
        OperatorAccess manager = createManager(merchant, "simulation-manager");
        UUID policyId = createCandidatePolicy(manager);
        UUID customerId = createCustomer(merchant);
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        UUID paymentIntentId = createPaymentIntent(merchant, customerId);
        JsonNode confirmation = confirm(merchant, paymentIntentId, paymentMethodId);
        UUID decisionId = UUID.fromString(confirmation.get("fraudDecisionId").asText());
        Instant decisionAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM fraud_decisions WHERE id = ?", Instant.class, decisionId);
        Instant asOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        RiskOperatorContext operator = new RiskOperatorContext(merchant.id(), manager.id(),
                "simulation-manager", RiskOperatorRole.RISK_MANAGER, "risk_test");

        RiskSimulationService.SimulationView queued = service.create(operator,
                RiskSimulationCandidateType.MERCHANT_RISK_POLICY, policyId,
                decisionAt.minusSeconds(1), decisionAt.plus(1, ChronoUnit.MICROS), asOf,
                asOf, RiskSimulationTrafficOriginPolicy.LIVE_ONLY, "simulation-test");
        assertThat(queued.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.QUEUED);
        return new PreparedRun(merchant, manager, operator, queued);
    }

    private void assertSuccessfulRun(RiskSimulationService.SimulationView queued,
            RiskOperatorContext operator) {
        RiskSimulationService.SimulationView completed = service.get(operator, queued.id());
        assertThat(completed.lifecycleStatus()).isEqualTo(RiskSimulationLifecycleStatus.SUCCEEDED);
        assertThat(completed.processedCount()).isOne();
        assertThat(completed.eligibleCount()).isOne();
        assertThat(completed.excludedCount()).isZero();
        RiskSimulationService.SimulationReportView report = service.report(operator, queued.id());
        assertThat(report.report()).extractingByKey("population")
                .isEqualTo(Map.of("eligible", 1, "excluded", 0, "processed", 1));
        assertThat(report.reportDigest()).hasSize(64);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_simulation_events
                 WHERE simulation_run_id = ? AND event_type IN ('CREATED','QUEUED','STARTED','SUCCEEDED')
                """, Integer.class, queued.id())).isEqualTo(4);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/admin/merchants")
                        .header("X-Admin-Bootstrap-Token", "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createManager(MerchantAccess merchant, String idempotencyKey) throws Exception {
        return createOperator(merchant, "RISK_MANAGER", idempotencyKey);
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String role,
            String idempotencyKey) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText());
    }

    private UUID createCandidatePolicy(OperatorAccess manager) throws Exception {
        JsonNode policy = json(mockMvc.perform(post("/risk/policies")
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, "simulation-policy")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"SIMULATION_POLICY\",\"displayName\":\"Simulation policy\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID policyId = UUID.fromString(policy.get("id").asText());
        mockMvc.perform(post("/risk/policies/{id}/thresholds", policyId)
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, "simulation-threshold")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"AMOUNT_REVIEW","featureCode":"payment.amount_minor",
                                 "operator":"GTE","longValue":100,"candidateAction":"REVIEW",
                                 "priority":100,"reasonCode":"MERCHANT_AMOUNT_THRESHOLD"}
                                """))
                .andExpect(status().isCreated());
        return policyId;
    }

    private UUID createPolicy(OperatorAccess manager, String code, String key,
            FraudDecisionAction action) throws Exception {
        JsonNode policy = json(mockMvc.perform(post("/risk/policies")
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"" + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID policyId = UUID.fromString(policy.get("id").asText());
        mockMvc.perform(post("/risk/policies/{id}/thresholds", policyId)
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key + "-threshold")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"AMOUNT_MATCH","featureCode":"payment.amount_minor",
                                 "operator":"GTE","longValue":100,"candidateAction":"%s",
                                 "priority":100,"reasonCode":"MERCHANT_AMOUNT_THRESHOLD"}
                                """.formatted(action.name())))
                .andExpect(status().isCreated());
        return policyId;
    }

    private UUID createRule(OperatorAccess manager, String code, String key,
            FraudDecisionAction action) throws Exception {
        JsonNode rule = json(mockMvc.perform(post("/risk/controls/rules")
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"%s","displayName":"%s","candidateAction":"%s",
                                 "expression":{"feature":"payment.amount_minor",
                                 "operator":"GTE","value":100}}
                                """.formatted(code, code, action.name())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(rule.get("id").asText());
    }

    private UUID createSafetyException(OperatorAccess manager, String code, String key,
            UUID sourceRuleVersionId, UUID paymentMethodId, Instant validFrom,
            Instant expiresAt) throws Exception {
        JsonNode safetyException = json(mockMvc.perform(post("/risk/controls/exceptions")
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"%s","sourceRuleVersionId":"%s",
                                 "paymentMethodId":"%s",
                                 "reasonCode":"FALSE_POSITIVE_REMEDIATION",
                                 "validFrom":"%s","expiresAt":"%s"}
                                """.formatted(code, sourceRuleVersionId, paymentMethodId,
                                validFrom, expiresAt)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(safetyException.get("id").asText());
    }

    private void transitionPolicy(OperatorAccess manager, UUID policyId, String transition,
            String key) throws Exception {
        mockMvc.perform(post("/risk/policies/{id}/{transition}", policyId, transition)
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void transitionControl(OperatorAccess manager, String family, UUID controlId,
            String transition, String key, UUID simulationRunId) throws Exception {
        String body = simulationRunId == null
                ? "{}" : "{\"simulationRunId\":\"" + simulationRunId + "\"}";
        mockMvc.perform(post("/risk/controls/{family}/{id}/{transition}",
                        family, controlId, transition)
                        .header("X-Risk-Operator-Key", manager.secret())
                        .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private UUID insertSuccessfulControlSimulation(UUID merchantId, UUID creatorId,
            RiskSimulationCandidateType candidateType, UUID candidateId) {
        RiskSimulationCandidateSnapshotService.FrozenCandidate candidate = snapshotService.freeze(
                merchantId, candidateType, candidateId);
        Instant now = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_runs
                    (id, merchant_id, created_by_operator_id, candidate_type, candidate_version_id,
                     candidate_schema_version, candidate_json, candidate_digest, cohort_start, cohort_end,
                     as_of, traffic_origin_policy, label_cutoff, label_resolution_policy, evaluator_version,
                     feature_catalogue_version, rule_catalogue_version, taxonomy_version, query_digest,
                     lifecycle_status, execution_token, projection_version, processed_count, eligible_count,
                     excluded_count, created_at, queued_at, started_at, completed_at, report_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LIVE_ONLY', ?,
                        'CURRENT_AT_CUTOFF', 'historical_policy_v1', 'v1', 'rules_v1',
                        'fraud_taxonomy_v1', ?, 'SUCCEEDED', ?, 2, 1, 1, 0, ?, ?, ?, ?, ?)
                """, runId, merchantId, creatorId, candidateType.name(), candidateId,
                candidate.schemaVersion(), candidate.canonicalJson(), candidate.digest(),
                Timestamp.from(now.minusSeconds(3_600)), Timestamp.from(now.minusSeconds(1_800)),
                Timestamp.from(now.minusSeconds(1_200)), Timestamp.from(now.minusSeconds(1_200)),
                "1".repeat(64), UUID.randomUUID(), Timestamp.from(now.minusSeconds(600)),
                Timestamp.from(now.minusSeconds(600)), Timestamp.from(now.minusSeconds(500)),
                Timestamp.from(now.minusSeconds(400)), Timestamp.from(now.plusSeconds(86_400)));
        jdbcTemplate.update("""
                INSERT INTO risk_simulation_reports
                    (id, merchant_id, simulation_run_id, canonical_report_json, report_digest,
                     processed_count, eligible_count, excluded_count, protected_suppression_count,
                     blocking_data_quality_count, labelled_count, created_at, expires_at)
                VALUES (?, ?, ?, '{}', ?, 1, 1, 0, 0, 0, 0, ?, ?)
                """, UUID.randomUUID(), merchantId, runId, "2".repeat(64),
                Timestamp.from(now.minusSeconds(400)), Timestamp.from(now.plusSeconds(86_400)));
        return runId;
    }

    private FraudDecisionAction simulatedAction(RiskOperatorContext operator,
            RiskSimulationCandidateType candidateType, UUID candidateId, Instant decisionAt) {
        Instant asOf = DatabaseInstant.now().truncatedTo(ChronoUnit.MICROS);
        RiskSimulationService.SimulationView queued = service.create(operator, candidateType,
                candidateId, decisionAt, decisionAt.plus(1, ChronoUnit.MICROS),
                asOf, asOf, RiskSimulationTrafficOriginPolicy.LIVE_ONLY,
                "mixed-control-" + candidateType.name());
        dispatcher.launchNow(queued.id());
        assertThat(service.get(operator, queued.id()).lifecycleStatus())
                .isEqualTo(RiskSimulationLifecycleStatus.SUCCEEDED);
        String action = jdbcTemplate.queryForObject("""
                SELECT candidate_action FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                """, String.class, operator.merchantId(), queued.id());
        return FraudDecisionAction.valueOf(action);
    }

    private UUID createCustomer(MerchantAccess merchant) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/customers").header(API_KEY, merchant.apiKey())
                        .contentType(APPLICATION_JSON).content("{\"email\":\"simulation@example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        return createPaymentMethod(merchant, customerId, "simulation-method", "4242424242424242");
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId,
            String idempotencyKey, String cardNumber) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"" + cardNumber + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId) throws Exception {
        return createPaymentIntent(merchant, customerId, "simulation-intent");
    }

    private UUID createPaymentIntent(MerchantAccess merchant, UUID customerId,
            String idempotencyKey) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId) throws Exception {
        return confirm(merchant, intentId, methodId, "simulation-confirm");
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId,
            String idempotencyKey) throws Exception {
        return json(mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record PreparedRun(MerchantAccess merchant, OperatorAccess manager,
            RiskOperatorContext operator,
            RiskSimulationService.SimulationView run) { }
}
