package com.thaap.payments.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.processor.ProcessorAuthorizationRequest;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.simulation.RiskSimulationCandidateSnapshotService;
import com.thaap.payments.simulation.RiskSimulationCandidateType;
import com.thaap.payments.time.DatabaseInstant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
        "thaap.simulation.gate.minimum-eligible-population=1",
        "thaap.simulation.gate.minimum-label-coverage-percent=0"
})
class MerchantRiskPolicyContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private RiskSimulationCandidateSnapshotService simulationCandidateSnapshotService;
    @MockitoSpyBean private ProcessorAdapter processorAdapter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
        Mockito.clearInvocations(processorAdapter);
    }

    @Test
    void distinctManagersApproveThenActivationChangesOnlyFutureDecisionsWithExactLineage() throws Exception {
        MerchantAccess merchant = createMerchant("policy-dual-control");
        OperatorAccess creator = createOperator(merchant, "creator", "RISK_MANAGER");
        OperatorAccess approver = createOperator(merchant, "approver", "RISK_MANAGER");
        UUID customerId = createCustomer(merchant, "policy@example.com");
        UUID methodId = createPaymentMethod(merchant, customerId);

        UUID profileId = createProfile(creator, "ELEVATED", "profile-create");
        transitionProfile(creator, profileId, "submit", "profile-submit", 200);
        transitionProfile(creator, profileId, "approve", "profile-self-approve", 403);
        JsonNode approvedProfile = transitionProfile(approver, profileId, "approve", "profile-approve", 200);
        assertThat(approvedProfile.get("state").asText()).isEqualTo("APPROVED");

        UUID policyId = createPolicy(creator, "ELEVATED_BLOCK", "policy-create");
        createThreshold(creator, policyId, "BLOCK_ELEVATED", "merchant.risk_tier", "EQ",
                null, null, "[\"ELEVATED\"]", "BLOCK", "MERCHANT_RISK_TIER", "threshold-create", 201);
        JsonNode preview = objectMapper.readTree(mockMvc.perform(post("/risk/policies/{id}/preview", policyId)
                        .header(RISK_KEY, creator.secret()).contentType(APPLICATION_JSON)
                        .content("{\"features\":{\"merchant.risk_tier\":\"ELEVATED\"},\"currency\":\"USD\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("previewType").asText()).isEqualTo("FIXTURE_PREVIEW");
        assertThat(preview.get("strongestCandidate").asText()).isEqualTo("BLOCK");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();

        transitionPolicy(creator, policyId, "submit", "policy-submit", 200);
        transitionPolicy(creator, policyId, "approve", "policy-self-approve", 403);
        JsonNode approvedPolicy = transitionPolicy(approver, policyId, "approve", "policy-approve", 200);
        assertThat(approvedPolicy.get("state").asText()).isEqualTo("APPROVED");

        UUID beforeIntent = createIntent(merchant, customerId, 1_200L, "before-active-intent");
        JsonNode before = confirm(merchant, beforeIntent, methodId, "before-active-confirm");
        assertThat(before.get("status").asText()).isNotEqualTo("BLOCKED");
        UUID beforeDecision = UUID.fromString(before.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT observed_string FROM risk_control_evaluations
                 WHERE fraud_decision_id = ? AND control_type = 'PROFILE'
                """, String.class, beforeDecision)).isEqualTo("STANDARD");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_control_evaluations
                 WHERE fraud_decision_id = ? AND control_type = 'POLICY_THRESHOLD'
                """, Integer.class, beforeDecision)).isZero();

        transitionProfile(approver, profileId, "activate", "profile-activate", 200);
        transitionPolicy(approver, policyId, "activate", "policy-activate", 200);
        UUID afterIntent = createIntent(merchant, customerId, 1_200L, "after-active-intent");
        JsonNode after = confirm(merchant, afterIntent, methodId, "after-active-confirm");
        assertThat(after.get("status").asText()).isEqualTo("BLOCKED");
        UUID afterDecision = UUID.fromString(after.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, afterDecision)).isEqualTo("BLOCK");
        assertThat(jdbcTemplate.queryForObject("SELECT reason_codes FROM fraud_decisions WHERE id = ?",
                String.class, afterDecision)).contains("MERCHANT_POLICY_MATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_control_evaluations
                 WHERE fraud_decision_id = ? AND control_type = 'PROFILE'
                   AND configuration_status = 'ACTIVE' AND observed_string = 'ELEVATED'
                   AND profile_version_id = ?
                """, Integer.class, afterDecision, profileId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_control_evaluations
                 WHERE fraud_decision_id = ? AND control_type = 'POLICY_THRESHOLD'
                   AND matched = TRUE AND candidate_action = 'BLOCK'
                   AND policy_version_id = ?
                """, Integer.class, afterDecision, policyId)).isOne();
        Mockito.verify(processorAdapter, Mockito.times(1)).authorize(Mockito.any(ProcessorAuthorizationRequest.class));
    }

    @Test
    void governedAuthoredRuleRequiresDualControlAndExactSimulationThenPersistsDecisionEvidence() throws Exception {
        MerchantAccess merchant = createMerchant("authored-rule-control");
        OperatorAccess creator = createOperator(merchant, "authored-rule-creator", "RISK_MANAGER");
        OperatorAccess approver = createOperator(merchant, "authored-rule-approver", "RISK_MANAGER");
        UUID customerId = createCustomer(merchant, "authored@example.com");
        UUID methodId = createPaymentMethod(merchant, customerId);

        String createBody = """
                {"code":"HIGH_VALUE_BLOCK","displayName":"High value block","candidateAction":"BLOCK",
                 "expression":{"all":[
                    {"feature":"payment.currency","operator":"EQ","value":"USD"},
                    {"feature":"payment.amount_minor","operator":"GTE","value":1000}]}}
                """;
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/risk/controls/rules")
                        .header(RISK_KEY, creator.secret()).header(IDEMPOTENCY_KEY, "authored-create")
                        .contentType(APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID ruleId = UUID.fromString(created.get("id").asText());
        transitionControl(creator, "rules", ruleId, "submit", "authored-submit", null, 200);
        transitionControl(creator, "rules", ruleId, "approve", "authored-self-approve", null, 403);
        transitionControl(approver, "rules", ruleId, "approve", "authored-approve", null, 200);
        transitionControl(approver, "rules", ruleId, "activate", "authored-no-simulation", null, 400);

        UUID simulationId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                "AUTHORED_RULE", ruleId);
        JsonNode active = transitionControl(approver, "rules", ruleId, "activate",
                "authored-activate", simulationId, 200);
        assertThat(active.get("lifecycleStatus").asText()).isEqualTo("ACTIVE");

        UUID intentId = createIntent(merchant, customerId, 1_200L, "authored-intent");
        JsonNode attempt = confirm(merchant, intentId, methodId, "authored-confirm");
        assertThat(attempt.get("status").asText()).isEqualTo("BLOCKED");
        UUID decisionId = UUID.fromString(attempt.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT reason_codes FROM fraud_decisions WHERE id = ?",
                String.class, decisionId)).contains("AUTHORED_RULE_MATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_evaluations
                 WHERE fraud_decision_id = ? AND rule_version_id = ? AND matched = TRUE
                   AND candidate_action = 'BLOCK' AND exception_disposition = 'NONE'
                   AND effective_action = 'BLOCK'
                """, Integer.class, decisionId, ruleId)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authorizations WHERE payment_attempt_id = ?",
                Integer.class, UUID.fromString(attempt.get("id").asText()))).isZero();

        Instant validFrom = DatabaseInstant.now().minusSeconds(86_400)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant expiresAt = DatabaseInstant.now().plusSeconds(86_400)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String exceptionBody = "{\"code\":\"TRUSTED_CARD\",\"sourceRuleVersionId\":\"" + ruleId
                + "\",\"paymentMethodId\":\"" + methodId
                + "\",\"reasonCode\":\"FALSE_POSITIVE_REMEDIATION\",\"validFrom\":\""
                + validFrom + "\",\"expiresAt\":\"" + expiresAt + "\"}";
        String exceptionResponse = mockMvc.perform(post("/risk/controls/exceptions")
                        .header(RISK_KEY, creator.secret()).header(IDEMPOTENCY_KEY, "exception-create")
                        .contentType(APPLICATION_JSON).content(exceptionBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(exceptionResponse).doesNotContain("424242", "subjectIdentityHmac", "subjectHmacKeyVersion");
        UUID exceptionId = UUID.fromString(objectMapper.readTree(exceptionResponse).get("id").asText());
        transitionControl(creator, "exceptions", exceptionId, "submit", "exception-submit", null, 200);
        transitionControl(creator, "exceptions", exceptionId, "approve", "exception-self-approve", null, 403);
        transitionControl(approver, "exceptions", exceptionId, "approve", "exception-approve", null, 200);
        UUID exceptionSimulationId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                "SAFETY_EXCEPTION", exceptionId);
        transitionControl(approver, "exceptions", exceptionId, "activate", "exception-activate",
                exceptionSimulationId, 200);

        UUID exceptedIntentId = createIntent(merchant, customerId, 1_200L, "excepted-intent");
        JsonNode exceptedAttempt = confirm(merchant, exceptedIntentId, methodId, "excepted-confirm");
        assertThat(exceptedAttempt.get("status").asText()).isNotEqualTo("BLOCKED");
        UUID exceptedDecisionId = UUID.fromString(exceptedAttempt.get("fraudDecisionId").asText());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_evaluations
                 WHERE fraud_decision_id = ? AND rule_version_id = ? AND matched = TRUE
                   AND exception_disposition = 'APPLIED' AND applied_exception_version_id = ?
                   AND effective_action IS NULL
                """, Integer.class, exceptedDecisionId, ruleId, exceptionId)).isOne();

        transitionControl(approver, "rules", ruleId, "retire", "authored-retire", null, 200);
        UUID rollbackSimulationId = insertSuccessfulControlSimulation(merchant.id(), creator.id(),
                "AUTHORED_RULE", ruleId);
        JsonNode rolledBack = transitionControl(approver, "rules", ruleId, "rollback",
                "authored-rollback", rollbackSimulationId, 200);
        assertThat(rolledBack.get("lifecycleStatus").asText()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM authored_rule_events
                 WHERE merchant_id = ? AND rule_version_id = ?
                   AND event_type = 'ROLLBACK_ACTIVATED' AND simulation_run_id = ?
                """, Integer.class, merchant.id(), ruleId, rollbackSimulationId)).isOne();
    }

    @Test
    void thresholdGrammarRejectsAllowTypeRangeReasonAndMutableAfterSubmit() throws Exception {
        MerchantAccess merchant = createMerchant("policy-grammar");
        OperatorAccess manager = createOperator(merchant, "manager", "RISK_MANAGER");
        UUID emptyPolicy = createPolicy(manager, "EMPTY_POLICY", "empty-policy");
        transitionPolicy(manager, emptyPolicy, "submit", "empty-submit", 400);

        UUID policyId = createPolicy(manager, "STRICT_POLICY", "strict-policy");
        createThreshold(manager, policyId, "ALLOW_BAD", "payment.amount_minor", "GTE",
                "100", null, null, "ALLOW", "MERCHANT_AMOUNT_THRESHOLD", "allow-bad", 400);
        createThreshold(manager, policyId, "TYPE_BAD", "merchant.risk_tier", "GTE",
                "1", null, null, "BLOCK", "MERCHANT_RISK_TIER", "type-bad", 400);
        createThreshold(manager, policyId, "FEATURE_BAD", "buyer.email", "EQ",
                null, null, "[\"VALUE\"]", "BLOCK", "MERCHANT_CONTEXT_THRESHOLD", "feature-bad", 400);
        createThreshold(manager, policyId, "RANGE_BAD", "risk.bot_score_bps", "GTE",
                "10001", null, null, "REVIEW", "MERCHANT_CONTEXT_THRESHOLD", "range-bad", 400);
        createThreshold(manager, policyId, "REASON_BAD", "payment.amount_minor", "GTE",
                "100", null, null, "BLOCK", "MERCHANT_CONTEXT_THRESHOLD", "reason-bad", 400);

        createThreshold(manager, policyId, "VALID_AMOUNT", "payment.amount_minor", "GTE",
                "100", null, null, "REVIEW", "MERCHANT_AMOUNT_THRESHOLD", "valid-amount", 201);
        transitionPolicy(manager, policyId, "submit", "valid-submit", 200);
        createThreshold(manager, policyId, "AFTER_SUBMIT", "payment.amount_minor", "GTE",
                "200", null, null, "BLOCK", "MERCHANT_AMOUNT_THRESHOLD", "after-submit", 409);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_policy_thresholds",
                Integer.class)).isOne();
    }

    @Test
    void reviewAndThreeDsCandidatesApplyWhileProtectedBaseBlockCannotBeWeakened() throws Exception {
        PolicyOutcome review = policyOutcome("policy-review", 1_200L, "REVIEW");
        assertThat(review.attemptStatus()).isEqualTo("IN_REVIEW");
        PolicyOutcome threeDs = policyOutcome("policy-3ds", 1_200L, "REQUEST_3DS");
        assertThat(threeDs.attemptStatus()).isEqualTo("REQUIRES_ACTION");
        PolicyOutcome protectedBlock = policyOutcome("policy-protected-block", 100_000_000L, "REQUEST_3DS");
        assertThat(protectedBlock.attemptStatus()).isEqualTo("BLOCKED");
        assertThat(protectedBlock.reasonCodes()).contains("EXTREME_AMOUNT", "MERCHANT_POLICY_MATCH");
    }

    @Test
    void analystAndForeignTenantCannotMutateOrReadAnotherMerchantsPolicy() throws Exception {
        MerchantAccess owner = createMerchant("policy-owner");
        MerchantAccess other = createMerchant("policy-other");
        OperatorAccess manager = createOperator(owner, "owner-manager", "RISK_MANAGER");
        OperatorAccess analyst = createOperator(owner, "owner-analyst", "ANALYST");
        OperatorAccess otherManager = createOperator(other, "other-manager", "RISK_MANAGER");
        UUID policyId = createPolicy(manager, "OWNER_POLICY", "owner-policy");

        mockMvc.perform(post("/risk/policies/{id}/thresholds", policyId)
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-threshold")
                        .contentType(APPLICATION_JSON).content(thresholdBody("ANALYST_BAD", "payment.amount_minor",
                                "GTE", "100", null, null, "BLOCK", "MERCHANT_AMOUNT_THRESHOLD")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/policies/{id}/submit", policyId)
                        .header(RISK_KEY, otherManager.secret()).header(IDEMPOTENCY_KEY, "foreign-submit")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        String createBody = "{\"code\":\"EXACT_REPLAY\",\"displayName\":\"Exact replay\"}";
        String first = mockMvc.perform(post("/risk/policies").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "exact-policy").contentType(APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/risk/policies").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "exact-policy").contentType(APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mockMvc.perform(post("/risk/policies").header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "exact-policy").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"CHANGED_BODY\",\"displayName\":\"Changed\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void profileAndPolicyLifecycleRejectEverySkippedRepeatedAndTerminalTransition() throws Exception {
        MerchantAccess merchant = createMerchant("policy-lifecycle");
        OperatorAccess creator = createOperator(merchant, "lifecycle-creator", "RISK_MANAGER");
        OperatorAccess approver = createOperator(merchant, "lifecycle-approver", "RISK_MANAGER");

        UUID profileId = createProfile(creator, "HIGH", "lifecycle-profile-create");
        transitionProfile(approver, profileId, "approve", "profile-draft-approve", 409);
        transitionProfile(approver, profileId, "activate", "profile-draft-activate", 409);
        transitionProfile(approver, profileId, "retire", "profile-draft-retire", 409);
        transitionProfile(creator, profileId, "submit", "profile-submit", 200);
        transitionProfile(creator, profileId, "submit", "profile-repeat-submit", 409);
        transitionProfile(approver, profileId, "activate", "profile-pending-activate", 409);
        transitionProfile(approver, profileId, "retire", "profile-pending-retire", 409);
        transitionProfile(approver, profileId, "approve", "profile-approve", 200);
        transitionProfile(approver, profileId, "approve", "profile-repeat-approve", 409);
        transitionProfile(approver, profileId, "submit", "profile-approved-submit", 409);
        transitionProfile(approver, profileId, "retire", "profile-approved-retire", 409);
        transitionProfile(approver, profileId, "activate", "profile-activate", 200);
        transitionProfile(approver, profileId, "activate", "profile-repeat-activate", 409);
        transitionProfile(approver, profileId, "approve", "profile-active-approve", 409);
        transitionProfile(approver, profileId, "retire", "profile-retire", 200);
        transitionProfile(approver, profileId, "activate", "profile-retired-activate", 409);
        transitionProfile(approver, profileId, "retire", "profile-repeat-retire", 409);

        UUID policyId = createPolicy(creator, "LIFECYCLE_POLICY", "lifecycle-policy-create");
        createThreshold(creator, policyId, "LIFECYCLE_AMOUNT", "payment.amount_minor", "GTE",
                "100", null, null, "REVIEW", "MERCHANT_AMOUNT_THRESHOLD",
                "lifecycle-threshold", 201);
        transitionPolicy(approver, policyId, "approve", "policy-draft-approve", 409);
        transitionPolicy(approver, policyId, "activate", "policy-draft-activate", 409);
        transitionPolicy(approver, policyId, "retire", "policy-draft-retire", 409);
        transitionPolicy(creator, policyId, "submit", "policy-submit", 200);
        transitionPolicy(creator, policyId, "submit", "policy-repeat-submit", 409);
        transitionPolicy(approver, policyId, "activate", "policy-pending-activate", 409);
        transitionPolicy(approver, policyId, "retire", "policy-pending-retire", 409);
        transitionPolicy(approver, policyId, "approve", "policy-approve", 200);
        transitionPolicy(approver, policyId, "approve", "policy-repeat-approve", 409);
        transitionPolicy(approver, policyId, "submit", "policy-approved-submit", 409);
        transitionPolicy(approver, policyId, "retire", "policy-approved-retire", 409);
        transitionPolicy(approver, policyId, "activate", "policy-activate", 200);
        transitionPolicy(approver, policyId, "activate", "policy-repeat-activate", 409);
        transitionPolicy(approver, policyId, "approve", "policy-active-approve", 409);
        transitionPolicy(approver, policyId, "retire", "policy-retire", 200);
        transitionPolicy(approver, policyId, "activate", "policy-retired-activate", 409);
        transitionPolicy(approver, policyId, "retire", "policy-repeat-retire", 409);
    }

    @Test
    void fixturePreviewRejectsUnknownOrWrongTypedFeaturesAndNeverWritesDecisionState() throws Exception {
        MerchantAccess merchant = createMerchant("policy-preview-grammar");
        OperatorAccess manager = createOperator(merchant, "preview-manager", "RISK_MANAGER");
        UUID policyId = createPolicy(manager, "PREVIEW_POLICY", "preview-policy-create");
        createThreshold(manager, policyId, "PREVIEW_AMOUNT", "payment.amount_minor", "GTE",
                "100", null, null, "REVIEW", "MERCHANT_AMOUNT_THRESHOLD", "preview-threshold", 201);

        mockMvc.perform(post("/risk/policies/{id}/preview", policyId)
                        .header(RISK_KEY, manager.secret()).contentType(APPLICATION_JSON)
                        .content("{\"features\":{\"buyer.email\":\"canary@example.com\"},\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/policies/{id}/preview", policyId)
                        .header(RISK_KEY, manager.secret()).contentType(APPLICATION_JSON)
                        .content("{\"features\":{\"payment.amount_minor\":\"100\"},\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fraud_decisions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_control_evaluations",
                Integer.class)).isZero();
    }

    private PolicyOutcome policyOutcome(String prefix, long amount, String candidateAction) throws Exception {
        MerchantAccess merchant = createMerchant(prefix);
        OperatorAccess creator = createOperator(merchant, prefix + "-creator", "RISK_MANAGER");
        OperatorAccess approver = createOperator(merchant, prefix + "-approver", "RISK_MANAGER");
        UUID policyId = createPolicy(creator, prefix.toUpperCase().replace('-', '_'), prefix + "-policy");
        createThreshold(creator, policyId, "AMOUNT_MATCH", "payment.amount_minor", "GTE",
                "100", null, null, candidateAction, "MERCHANT_AMOUNT_THRESHOLD", prefix + "-threshold", 201);
        transitionPolicy(creator, policyId, "submit", prefix + "-submit", 200);
        transitionPolicy(approver, policyId, "approve", prefix + "-approve", 200);
        transitionPolicy(approver, policyId, "activate", prefix + "-activate", 200);
        UUID customer = createCustomer(merchant, prefix + "@example.com");
        UUID method = createPaymentMethod(merchant, customer);
        UUID intent = createIntent(merchant, customer, amount, prefix + "-intent");
        JsonNode attempt = confirm(merchant, intent, method, prefix + "-confirm");
        UUID decisionId = UUID.fromString(attempt.get("fraudDecisionId").asText());
        String reasons = jdbcTemplate.queryForObject("SELECT reason_codes FROM fraud_decisions WHERE id = ?",
                String.class, decisionId);
        return new PolicyOutcome(attempt.get("status").asText(), reasons);
    }

    private UUID createProfile(OperatorAccess operator, String tier, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/profiles")
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"riskTier\":\"" + tier
                                + "\",\"reasonCode\":\"MERCHANT_RISK_CHANGE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPolicy(OperatorAccess operator, String code, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/policies")
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"code\":\"" + code
                                + "\",\"displayName\":\"" + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private JsonNode transitionProfile(OperatorAccess operator, UUID id, String transition, String key,
            int expectedStatus) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/profiles/{id}/{transition}", id, transition)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode transitionPolicy(OperatorAccess operator, UUID id, String transition, String key,
            int expectedStatus) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/policies/{id}/{transition}", id, transition)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode transitionControl(OperatorAccess operator, String family, UUID id,
            String transition, String key, UUID simulationId, int expectedStatus) throws Exception {
        String body = simulationId == null ? "{}" : "{\"simulationRunId\":\"" + simulationId + "\"}";
        String content = mockMvc.perform(post("/risk/controls/{family}/{id}/{transition}", family, id, transition)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    private UUID insertSuccessfulControlSimulation(UUID merchantId, UUID creatorId,
            String candidateType, UUID candidateId) {
        RiskSimulationCandidateSnapshotService.FrozenCandidate candidate = simulationCandidateSnapshotService.freeze(
                merchantId, RiskSimulationCandidateType.valueOf(candidateType), candidateId);
        Instant now = DatabaseInstant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
                """, runId, merchantId, creatorId, candidateType, candidateId, candidate.schemaVersion(),
                candidate.canonicalJson(), candidate.digest(),
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

    private void createThreshold(OperatorAccess operator, UUID policyId, String code, String feature,
            String comparison, String longValue, String booleanValue, String stringValues, String action,
            String reason, String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/risk/policies/{id}/thresholds", policyId)
                        .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content(thresholdBody(code, feature, comparison,
                                longValue, booleanValue, stringValues, action, reason)))
                .andExpect(status().is(expectedStatus));
    }

    private String thresholdBody(String code, String feature, String operator, String longValue,
            String booleanValue, String stringValues, String action, String reason) {
        String longField = longValue == null ? "" : ",\"longValue\":" + longValue;
        String booleanField = booleanValue == null ? "" : ",\"booleanValue\":" + booleanValue;
        String stringsField = stringValues == null ? "" : ",\"stringValues\":" + stringValues;
        return "{\"code\":\"" + code + "\",\"featureCode\":\"" + feature
                + "\",\"operator\":\"" + operator + "\"" + longField + booleanField + stringsField
                + ",\"candidateAction\":\"" + action + "\",\"priority\":100,\"reasonCode\":\""
                + reason + "\"}";
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()), response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/risk/operators")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new OperatorAccess(UUID.fromString(response.get("id").asText()), response.get("credential").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "method-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createIntent(MerchantAccess merchant, UUID customerId, long amount, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":" + amount + ",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private JsonNode confirm(MerchantAccess merchant, UUID intentId, UUID methodId, String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record PolicyOutcome(String attemptStatus, String reasonCodes) { }
}
