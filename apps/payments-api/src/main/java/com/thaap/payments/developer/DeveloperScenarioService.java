package com.thaap.payments.developer;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.processor.SimulatorProcessorCatalog;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeveloperScenarioService {

    public static final String CATALOGUE_VERSION = "developer_scenarios_v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SimulatorProcessorCatalog processorCatalog;
    private final AuditLogService auditLogService;
    private final DeveloperScenarioMetrics metrics;
    private final RiskOperationsEventProducer eventProducer;
    private final Clock clock;
    private final Duration leaseDuration;
    private final boolean creationEnabled;

    public DeveloperScenarioService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            SimulatorProcessorCatalog processorCatalog, AuditLogService auditLogService,
            DeveloperScenarioMetrics metrics, RiskOperationsEventProducer eventProducer, Clock clock,
            @Value("${thaap.developer-scenarios.lease-duration:45s}") Duration leaseDuration,
            @Value("${thaap.developer-scenarios.creation-enabled:true}") boolean creationEnabled) {
        if (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Developer scenario lease must be between 5 seconds and 5 minutes");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.processorCatalog = processorCatalog;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.eventProducer = eventProducer;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.creationEnabled = creationEnabled;
    }

    public CatalogueView catalogue() {
        List<TestCardView> cards = processorCatalog.documentedFixtures().stream()
                .map(fixture -> new TestCardView(fixture.fixtureId(),
                        fixture.cardBin() + "000000" + fixture.cardLast4(),
                        fixture.outcome().status().name(), fixture.outcome().networkStatus().name(),
                        fixture.outcome().responseCode(), fixture.outcome().cvcCheck().name(),
                        fixture.outcome().avsLine1Check().name(),
                        fixture.outcome().avsPostalCodeCheck().name(),
                        fixture.outcome().threeDsResult().name(), "SIMULATOR_ONLY"))
                .toList();
        return new CatalogueView(CATALOGUE_VERSION, "THAAP_SIMULATOR", cards,
                List.of(DeveloperScenarioType.values()));
    }

    @Transactional
    public RunView create(MerchantContext context, DeveloperScenarioType scenarioType,
            String seed, String requestId) {
        requireSynthetic(context);
        if (!creationEnabled) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_CREATION_DISABLED, "Developer scenario creation is disabled");
        }
        String seedDigest = SecretHasher.sha256(seed);
        String specificationJson = canonical(Map.of(
                "catalogueVersion", CATALOGUE_VERSION,
                "scenarioType", scenarioType.name(),
                "seedDigest", seedDigest));
        String specificationDigest = SecretHasher.sha256(specificationJson);
        jdbcTemplate.queryForObject("SELECT id FROM merchants WHERE id = ? FOR UPDATE",
                UUID.class, context.merchantId());
        RunView existing = findBySpecification(context.merchantId(), specificationDigest);
        if (existing != null) {
            metrics.record("CREATE", scenarioType.name(), "DEDUPLICATED");
            return existing;
        }

        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        List<PlannedPayment> payments = plannedPayments(scenarioType);
        int totalSteps = 1 + payments.size() * 3;
        jdbcTemplate.update("""
                    INSERT INTO developer_scenario_runs
                        (id, merchant_id, scenario_type, catalogue_version, seed_digest,
                         specification_json, specification_digest, lifecycle_status,
                         total_steps, next_step_index, lease_epoch, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, 0, 0, ?, ?)
                    """, runId, context.merchantId(), scenarioType.name(), CATALOGUE_VERSION,
                    seedDigest, specificationJson, specificationDigest, totalSteps,
                    Timestamp.from(now), Timestamp.from(now));
        insertStep(context.merchantId(), runId, specificationDigest, 0, "customer",
                    "CREATE_CUSTOMER", null, null, null, "CUSTOMER", now);
        int stepIndex = 1;
        for (int paymentIndex = 0; paymentIndex < payments.size(); paymentIndex++) {
            PlannedPayment payment = payments.get(paymentIndex);
            String prefix = "payment." + paymentIndex;
            insertStep(context.merchantId(), runId, specificationDigest, stepIndex++,
                        prefix + ".method", "CREATE_PAYMENT_METHOD", payment.fixtureId(),
                        null, null, "PAYMENT_METHOD", now);
            insertStep(context.merchantId(), runId, specificationDigest, stepIndex++,
                        prefix + ".intent", "CREATE_PAYMENT_INTENT", payment.fixtureId(),
                        payment.amount(), null, "PAYMENT_INTENT", now);
            insertStep(context.merchantId(), runId, specificationDigest, stepIndex++,
                        prefix + ".confirm", "CONFIRM_PAYMENT_INTENT", payment.fixtureId(),
                        payment.amount(), payment.expectedOutcomeCode(), "PAYMENT_ATTEMPT", now);
        }
        insertEvent(context.merchantId(), runId, "RUN_CREATED", null, 0L, "CREATED", now);
        eventProducer.developerScenarioCreated(context, runId, now, requestId,
                scenarioType.name());
        auditLogService.recordMerchantApiAction(context, requestId, "developer_scenario.created",
                "developer_scenario_run", runId, "Deterministic trusted scenario created",
                null, Map.of("scenarioType", scenarioType.name(),
                        "catalogueVersion", CATALOGUE_VERSION, "totalSteps", totalSteps));
        metrics.record("CREATE", scenarioType.name(), "CREATED");
        return requireRun(context.merchantId(), runId, false);
    }

    @Transactional(readOnly = true)
    public RunView get(MerchantContext context, UUID runId) {
        requireSynthetic(context);
        return requireRun(context.merchantId(), runId, false);
    }

    @Transactional
    public RunView claim(MerchantContext context, UUID runId, UUID workerId, String requestId) {
        requireSynthetic(context);
        RunView run = requireRun(context.merchantId(), runId, true);
        if (run.lifecycleStatus() == DeveloperScenarioLifecycleStatus.SUCCEEDED
                || run.lifecycleStatus() == DeveloperScenarioLifecycleStatus.FAILED) {
            return run;
        }
        Instant now = clock.instant();
        boolean liveForeignLease = run.leaseOwner() != null
                && !run.leaseOwner().equals(workerId)
                && run.leaseExpiresAt() != null && run.leaseExpiresAt().isAfter(now);
        if (liveForeignLease) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_RUN_LEASED, "Developer scenario run is leased by another worker");
        }
        boolean renewal = run.leaseOwner() != null && run.leaseOwner().equals(workerId)
                && run.leaseExpiresAt() != null && run.leaseExpiresAt().isAfter(now)
                && run.lifecycleStatus() == DeveloperScenarioLifecycleStatus.RUNNING;
        long nextEpoch = renewal ? run.leaseEpoch() : run.leaseEpoch() + 1;
        Instant expiresAt = now.plus(leaseDuration);
        jdbcTemplate.update("""
                UPDATE developer_scenario_runs
                   SET lifecycle_status = 'RUNNING', lease_owner = ?, lease_epoch = ?,
                       lease_expires_at = ?, updated_at = ?
                 WHERE merchant_id = ? AND id = ?
                """, workerId, nextEpoch, Timestamp.from(expiresAt), Timestamp.from(now),
                context.merchantId(), runId);
        if (!renewal) {
            insertEvent(context.merchantId(), runId, "LEASE_CLAIMED", null,
                    nextEpoch, "CLAIMED", now);
            auditLogService.recordMerchantApiAction(context, requestId, "developer_scenario.claimed",
                    "developer_scenario_run", runId, "Bounded scenario execution lease claimed",
                    Map.of("status", run.lifecycleStatus().name()),
                    Map.of("status", "RUNNING", "leaseEpoch", nextEpoch));
        }
        metrics.record("CLAIM", run.scenarioType().name(), renewal ? "RENEWED" : "CLAIMED");
        return requireRun(context.merchantId(), runId, false);
    }

    @Transactional
    public RunView completeStep(MerchantContext context, UUID runId, int stepIndex,
            UUID workerId, long leaseEpoch, UUID resourceId, String requestId) {
        requireSynthetic(context);
        RunView run = requireRun(context.merchantId(), runId, true);
        StepRow step = requireStep(context.merchantId(), runId, stepIndex);
        if ("COMPLETED".equals(step.lifecycleStatus())) {
            if (!resourceId.equals(step.resourceId())) {
                throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_STEP_RESOURCE_MISMATCH, "Developer scenario step was completed with a different resource");
            }
            return requireRun(context.merchantId(), runId, false);
        }
        requireLease(run, workerId, leaseEpoch);
        if (stepIndex != run.nextStepIndex()) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_STEP_ORDER_INVALID, "Developer scenario steps must complete in plan order");
        }
        verifyResource(context.merchantId(), run, step, resourceId);
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE developer_scenario_steps
                   SET lifecycle_status = 'COMPLETED', resource_id = ?, completed_at = ?
                 WHERE merchant_id = ? AND scenario_run_id = ? AND step_index = ?
                """, resourceId, Timestamp.from(now), context.merchantId(), runId, stepIndex);
        int nextStep = stepIndex + 1;
        boolean succeeded = nextStep == run.totalSteps();
        String reportJson = succeeded ? reportJson(run, now) : null;
        String reportDigest = succeeded ? SecretHasher.sha256(reportJson) : null;
        if (succeeded) {
            jdbcTemplate.update("""
                    UPDATE developer_scenario_runs
                       SET lifecycle_status = 'SUCCEEDED', next_step_index = ?,
                           lease_owner = NULL, lease_expires_at = NULL,
                           report_json = ?, report_digest = ?, completed_at = ?, updated_at = ?
                     WHERE merchant_id = ? AND id = ?
                    """, nextStep, reportJson, reportDigest, Timestamp.from(now), Timestamp.from(now),
                    context.merchantId(), runId);
        } else {
            jdbcTemplate.update("""
                    UPDATE developer_scenario_runs
                       SET next_step_index = ?, lease_expires_at = ?, updated_at = ?
                     WHERE merchant_id = ? AND id = ?
                    """, nextStep, Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now),
                    context.merchantId(), runId);
        }
        insertEvent(context.merchantId(), runId, "STEP_COMPLETED", stepIndex,
                leaseEpoch, step.expectedOutcomeCode() == null ? "VERIFIED" : step.expectedOutcomeCode(), now);
        if (succeeded) {
            insertEvent(context.merchantId(), runId, "RUN_SUCCEEDED", null,
                    leaseEpoch, "VERIFIED", now);
            eventProducer.developerScenarioCompleted(context, runId, now, requestId,
                    "VERIFIED");
        }
        auditLogService.recordMerchantApiAction(context, requestId, "developer_scenario.step_completed",
                "developer_scenario_run", runId, "Scenario resource verified before progress advanced",
                Map.of("stepIndex", stepIndex, "operation", step.operation()),
                Map.of("nextStepIndex", nextStep, "status", succeeded ? "SUCCEEDED" : "RUNNING"));
        metrics.record("STEP_COMPLETE", run.scenarioType().name(),
                succeeded ? "SUCCEEDED" : step.operation());
        return requireRun(context.merchantId(), runId, false);
    }

    @Transactional
    public RunView fail(MerchantContext context, UUID runId, UUID workerId, long leaseEpoch,
            DeveloperScenarioFailureCode failureCode, String requestId) {
        requireSynthetic(context);
        RunView run = requireRun(context.merchantId(), runId, true);
        if (run.lifecycleStatus() == DeveloperScenarioLifecycleStatus.FAILED) {
            if (failureCode.name().equals(run.failureCode())) {
                return run;
            }
            throw conflict("Developer scenario run already failed with a different code");
        }
        requireLease(run, workerId, leaseEpoch);
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE developer_scenario_runs
                   SET lifecycle_status = 'FAILED', failure_code = ?,
                       lease_owner = NULL, lease_expires_at = NULL,
                       completed_at = ?, updated_at = ?
                 WHERE merchant_id = ? AND id = ?
                """, failureCode.name(), Timestamp.from(now), Timestamp.from(now),
                context.merchantId(), runId);
        insertEvent(context.merchantId(), runId, "RUN_FAILED", null,
                leaseEpoch, failureCode.name(), now);
        eventProducer.developerScenarioFailed(context, runId, now, requestId,
                failureCode.name());
        auditLogService.recordMerchantApiAction(context, requestId, "developer_scenario.failed",
                "developer_scenario_run", runId, "Scenario runner recorded a closed failure code",
                Map.of("status", run.lifecycleStatus().name()),
                Map.of("status", "FAILED", "failureCode", failureCode.name()));
        metrics.record("FAIL", run.scenarioType().name(), failureCode.name());
        return requireRun(context.merchantId(), runId, false);
    }

    @Transactional(readOnly = true)
    public ReportView report(MerchantContext context, UUID runId) {
        requireSynthetic(context);
        RunView run = requireRun(context.merchantId(), runId, false);
        if (run.lifecycleStatus() != DeveloperScenarioLifecycleStatus.SUCCEEDED
                || run.reportDigest() == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_REPORT_UNAVAILABLE, "Developer scenario report is not available until the run succeeds");
        }
        String reportJson = jdbcTemplate.queryForObject("""
                SELECT report_json FROM developer_scenario_runs
                 WHERE merchant_id = ? AND id = ?
                """, String.class, context.merchantId(), runId);
        return new ReportView(runId, run.reportDigest(), parseMap(reportJson));
    }

    private void verifyResource(UUID merchantId, RunView run, StepRow step, UUID resourceId) {
        switch (step.operation()) {
            case "CREATE_CUSTOMER" -> requireCount("""
                    SELECT COUNT(*) FROM customers WHERE merchant_id = ? AND id = ?
                    """, merchantId, resourceId, "Scenario customer does not belong to this merchant");
            case "CREATE_PAYMENT_METHOD" -> verifyPaymentMethod(merchantId, run.id(), step, resourceId);
            case "CREATE_PAYMENT_INTENT" -> verifyPaymentIntent(merchantId, run, step, resourceId);
            case "CONFIRM_PAYMENT_INTENT" -> verifyPaymentAttempt(merchantId, run.id(), step, resourceId);
            default -> throw new IllegalStateException("Unsupported durable scenario operation");
        }
    }

    private void verifyPaymentMethod(UUID merchantId, UUID runId, StepRow step, UUID resourceId) {
        UUID customerId = completedResource(merchantId, runId, "CREATE_CUSTOMER", null);
        SimulatorProcessorCatalog.SimulatorFixture fixture = fixture(step.fixtureId());
        requireCount("""
                SELECT COUNT(*) FROM payment_methods
                 WHERE merchant_id = ? AND id = ? AND customer_id = ?
                   AND card_bin = ? AND card_last4 = ?
                """, merchantId, resourceId, customerId, fixture.cardBin(), fixture.cardLast4(),
                "Scenario payment method does not match the governed fixture");
    }

    private void verifyPaymentIntent(UUID merchantId, RunView run, StepRow step, UUID resourceId) {
        UUID customerId = completedResource(merchantId, run.id(), "CREATE_CUSTOMER", null);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT customer_id, amount, currency, capture_method, traffic_origin, metadata
                  FROM payment_intents WHERE merchant_id = ? AND id = ?
                """, merchantId, resourceId);
        if (rows.size() != 1) {
            throw conflict("Scenario payment intent does not belong to this merchant");
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> metadata = parseMap(String.valueOf(row.get("metadata")));
        boolean matches = customerId.equals(row.get("customer_id"))
                && ((Number) row.get("amount")).longValue() == step.amount()
                && "USD".equals(row.get("currency"))
                && "MANUAL".equals(row.get("capture_method"))
                && "SYNTHETIC_SCENARIO".equals(row.get("traffic_origin"))
                && run.id().toString().equals(metadata.get("syntheticRunId"))
                && CATALOGUE_VERSION.equals(metadata.get("catalogueVersion"))
                && run.scenarioType().name().equals(metadata.get("scenarioType"));
        if (!matches) {
            throw conflict("Scenario payment intent does not match the immutable run specification");
        }
    }

    private void verifyPaymentAttempt(UUID merchantId, UUID runId, StepRow step, UUID resourceId) {
        UUID paymentIntentId = completedResource(merchantId, runId,
                "CREATE_PAYMENT_INTENT", step.fixtureId());
        UUID paymentMethodId = completedResource(merchantId, runId,
                "CREATE_PAYMENT_METHOD", step.fixtureId());
        String expectedAttemptStatus = "APPROVED".equals(step.expectedOutcomeCode())
                ? "AUTHORIZED" : "DECLINED";
        requireCount("""
                SELECT COUNT(*)
                  FROM payment_attempts attempt
                  JOIN authorizations auth
                    ON auth.merchant_id = attempt.merchant_id
                   AND auth.payment_attempt_id = attempt.id
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.id = attempt.fraud_decision_id
                  JOIN feature_snapshots snapshot
                    ON snapshot.merchant_id = decision.merchant_id
                   AND snapshot.fraud_decision_id = decision.id
                 WHERE attempt.merchant_id = ? AND attempt.id = ?
                   AND attempt.payment_intent_id = ? AND attempt.payment_method_id = ?
                   AND attempt.status = ? AND attempt.traffic_origin = 'SYNTHETIC_SCENARIO'
                   AND decision.traffic_origin = 'SYNTHETIC_SCENARIO'
                   AND snapshot.traffic_origin = 'SYNTHETIC_SCENARIO'
                   AND auth.processor_response_code = ?
                """, merchantId, resourceId, paymentIntentId, paymentMethodId,
                expectedAttemptStatus, step.expectedOutcomeCode(),
                "Scenario payment attempt or fraud lineage did not match the expected outcome");
    }

    private UUID completedResource(UUID merchantId, UUID runId, String operation, String fixtureId) {
        String fixtureClause = fixtureId == null ? "" : " AND fixture_id = ?";
        Object[] arguments = fixtureId == null
                ? new Object[] {merchantId, runId, operation}
                : new Object[] {merchantId, runId, operation, fixtureId};
        List<UUID> resources = jdbcTemplate.queryForList("""
                SELECT resource_id FROM developer_scenario_steps
                 WHERE merchant_id = ? AND scenario_run_id = ? AND operation = ?
                   AND lifecycle_status = 'COMPLETED'
                """ + fixtureClause + " ORDER BY step_index DESC LIMIT 1", UUID.class, arguments);
        if (resources.isEmpty()) {
            throw conflict("Required prior scenario resource is unavailable");
        }
        return resources.getFirst();
    }

    private void requireCount(String sql, Object first, Object second, String message) {
        requireCount(sql, new Object[] {first, second}, message);
    }

    private void requireCount(String sql, Object first, Object second, Object third,
            Object fourth, Object fifth, String message) {
        requireCount(sql, new Object[] {first, second, third, fourth, fifth}, message);
    }

    private void requireCount(String sql, Object first, Object second, Object third,
            Object fourth, Object fifth, Object sixth, String message) {
        requireCount(sql, new Object[] {first, second, third, fourth, fifth, sixth}, message);
    }

    private void requireCount(String sql, Object[] arguments, String message) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        if (count == null || count != 1) {
            throw conflict(message);
        }
    }

    private RunView requireRun(UUID merchantId, UUID runId, boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<RunView> runs = jdbcTemplate.query("""
                SELECT id, scenario_type, catalogue_version, seed_digest,
                       specification_digest, lifecycle_status, total_steps,
                       next_step_index, lease_owner, lease_epoch, lease_expires_at,
                       failure_code, report_digest, created_at, updated_at, completed_at
                  FROM developer_scenario_runs
                 WHERE merchant_id = ? AND id = ?
                """ + lockClause, (resultSet, rowNumber) -> mapRun(merchantId, resultSet), merchantId, runId);
        if (runs.isEmpty()) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_RUN_NOT_FOUND, "Developer scenario run was not found");
        }
        return runs.getFirst();
    }

    private RunView findBySpecification(UUID merchantId, String specificationDigest) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT id FROM developer_scenario_runs
                 WHERE merchant_id = ? AND specification_digest = ?
                """, UUID.class, merchantId, specificationDigest);
        return ids.isEmpty() ? null : requireRun(merchantId, ids.getFirst(), false);
    }

    private RunView mapRun(UUID merchantId, ResultSet resultSet) throws SQLException {
        UUID runId = resultSet.getObject("id", UUID.class);
        List<StepView> steps = jdbcTemplate.query("""
                SELECT id, step_index, step_key, operation, fixture_id, amount,
                       expected_outcome_code, idempotency_key, lifecycle_status,
                       resource_type, resource_id, completed_at
                  FROM developer_scenario_steps
                 WHERE merchant_id = ? AND scenario_run_id = ? ORDER BY step_index
                """, (stepResult, rowNumber) -> mapStepView(merchantId, runId, resultSet.getString("seed_digest"),
                        DeveloperScenarioType.valueOf(resultSet.getString("scenario_type")), stepResult),
                merchantId, runId);
        int nextStepIndex = resultSet.getInt("next_step_index");
        StepView nextStep = nextStepIndex < steps.size() ? steps.get(nextStepIndex) : null;
        return new RunView(runId, merchantId,
                DeveloperScenarioType.valueOf(resultSet.getString("scenario_type")),
                resultSet.getString("catalogue_version"), resultSet.getString("seed_digest"),
                resultSet.getString("specification_digest"),
                DeveloperScenarioLifecycleStatus.valueOf(resultSet.getString("lifecycle_status")),
                resultSet.getInt("total_steps"), nextStepIndex, steps, nextStep,
                resultSet.getObject("lease_owner", UUID.class), resultSet.getLong("lease_epoch"),
                instant(resultSet, "lease_expires_at"), resultSet.getString("failure_code"),
                resultSet.getString("report_digest"), instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"), instant(resultSet, "completed_at"));
    }

    private StepView mapStepView(UUID merchantId, UUID runId, String seedDigest,
            DeveloperScenarioType scenarioType, ResultSet resultSet) throws SQLException {
        String operation = resultSet.getString("operation");
        String fixtureId = resultSet.getString("fixture_id");
        UUID resourceId = resultSet.getObject("resource_id", UUID.class);
        Map<String, Object> body = instructionBody(merchantId, runId, seedDigest,
                scenarioType, operation, fixtureId, resultSet.getLong("amount"));
        String path = instructionPath(merchantId, runId, operation, fixtureId);
        return new StepView(resultSet.getInt("step_index"), resultSet.getString("step_key"),
                operation, "POST", path, resultSet.getString("idempotency_key"), body,
                fixtureId, resultSet.getString("expected_outcome_code"),
                resultSet.getString("lifecycle_status"), resultSet.getString("resource_type"),
                resourceId, instant(resultSet, "completed_at"));
    }

    private Map<String, Object> instructionBody(UUID merchantId, UUID runId, String seedDigest,
            DeveloperScenarioType scenarioType, String operation, String fixtureId, long amount) {
        if ("CREATE_CUSTOMER".equals(operation)) {
            return Map.of("email", "scenario-" + seedDigest.substring(0, 16) + "@example.test",
                    "phone", "+1555" + decimalDigits(seedDigest, 7));
        }
        if ("CREATE_PAYMENT_METHOD".equals(operation)) {
            UUID customerId = completedResourceOrNull(merchantId, runId, "CREATE_CUSTOMER", null);
            if (customerId == null) return Map.of();
            SimulatorProcessorCatalog.SimulatorFixture fixture = fixture(fixtureId);
            return Map.of("customerId", customerId.toString(),
                    "cardNumber", fixture.cardBin() + "000000" + fixture.cardLast4());
        }
        if ("CREATE_PAYMENT_INTENT".equals(operation)) {
            UUID customerId = completedResourceOrNull(merchantId, runId, "CREATE_CUSTOMER", null);
            if (customerId == null) return Map.of();
            return Map.of("customerId", customerId.toString(), "amount", amount,
                    "currency", "USD", "captureMethod", "MANUAL",
                    "description", "Trusted synthetic scenario payment",
                    "metadata", Map.of("syntheticRunId", runId.toString(),
                            "catalogueVersion", CATALOGUE_VERSION,
                            "scenarioType", scenarioType.name()));
        }
        UUID intentId = completedResourceOrNull(merchantId, runId, "CREATE_PAYMENT_INTENT", fixtureId);
        UUID methodId = completedResourceOrNull(merchantId, runId, "CREATE_PAYMENT_METHOD", fixtureId);
        if (intentId == null || methodId == null) return Map.of();
        return Map.of("paymentMethodId", methodId.toString());
    }

    private String instructionPath(UUID merchantId, UUID runId, String operation, String fixtureId) {
        if ("CREATE_CUSTOMER".equals(operation)) return "/customers";
        if ("CREATE_PAYMENT_METHOD".equals(operation)) return "/payment_methods";
        if ("CREATE_PAYMENT_INTENT".equals(operation)) return "/payment_intents";
        UUID intentId = completedResourceOrNull(merchantId, runId, "CREATE_PAYMENT_INTENT", fixtureId);
        return intentId == null ? "" : "/payment_intents/" + intentId + "/confirm";
    }

    private UUID completedResourceOrNull(UUID merchantId, UUID runId, String operation, String fixtureId) {
        String fixtureClause = fixtureId == null ? "" : " AND fixture_id = ?";
        Object[] arguments = fixtureId == null
                ? new Object[] {merchantId, runId, operation}
                : new Object[] {merchantId, runId, operation, fixtureId};
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT resource_id FROM developer_scenario_steps
                 WHERE merchant_id = ? AND scenario_run_id = ? AND operation = ?
                   AND lifecycle_status = 'COMPLETED'
                """ + fixtureClause + " ORDER BY step_index DESC LIMIT 1", UUID.class, arguments);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private StepRow requireStep(UUID merchantId, UUID runId, int stepIndex) {
        List<StepRow> steps = jdbcTemplate.query("""
                SELECT step_index, operation, fixture_id, amount, expected_outcome_code,
                       lifecycle_status, resource_id
                  FROM developer_scenario_steps
                 WHERE merchant_id = ? AND scenario_run_id = ? AND step_index = ?
                """, (resultSet, rowNumber) -> new StepRow(resultSet.getInt("step_index"),
                        resultSet.getString("operation"), resultSet.getString("fixture_id"),
                        (Long) resultSet.getObject("amount"), resultSet.getString("expected_outcome_code"),
                        resultSet.getString("lifecycle_status"),
                        resultSet.getObject("resource_id", UUID.class)), merchantId, runId, stepIndex);
        if (steps.isEmpty()) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_STEP_NOT_FOUND, "Developer scenario step was not found");
        }
        return steps.getFirst();
    }

    private void requireLease(RunView run, UUID workerId, long leaseEpoch) {
        Instant now = clock.instant();
        if (run.lifecycleStatus() != DeveloperScenarioLifecycleStatus.RUNNING
                || !workerId.equals(run.leaseOwner()) || leaseEpoch != run.leaseEpoch()
                || run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now)) {
            throw new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_LEASE_STALE, "Developer scenario lease is stale or unavailable");
        }
    }

    private String reportJson(RunView run, Instant completedAt) {
        Map<String, Long> outcomes = new TreeMap<>();
        List<Map<String, Object>> outcomeRows = jdbcTemplate.queryForList("""
                SELECT expected_outcome_code, COUNT(*) AS outcome_count
                  FROM developer_scenario_steps
                 WHERE merchant_id = ? AND scenario_run_id = ?
                   AND operation = 'CONFIRM_PAYMENT_INTENT'
                 GROUP BY expected_outcome_code ORDER BY expected_outcome_code
                """, run.merchantId(), run.id());
        for (Map<String, Object> row : outcomeRows) {
            outcomes.put(String.valueOf(row.get("expected_outcome_code")),
                    ((Number) row.get("outcome_count")).longValue());
        }
        Map<String, Long> originBreakdown = new TreeMap<>();
        List<Map<String, Object>> originRows = jdbcTemplate.queryForList("""
                SELECT attempt.traffic_origin, COUNT(*) AS origin_count
                  FROM developer_scenario_steps step
                  JOIN payment_attempts attempt
                    ON attempt.merchant_id = step.merchant_id
                   AND attempt.id = step.resource_id
                 WHERE step.merchant_id = ? AND step.scenario_run_id = ?
                   AND step.operation = 'CONFIRM_PAYMENT_INTENT'
                   AND step.lifecycle_status = 'COMPLETED'
                 GROUP BY attempt.traffic_origin ORDER BY attempt.traffic_origin
                """, run.merchantId(), run.id());
        for (Map<String, Object> row : originRows) {
            originBreakdown.put(String.valueOf(row.get("traffic_origin")),
                    ((Number) row.get("origin_count")).longValue());
        }
        Map<String, Object> report = new TreeMap<>();
        report.put("catalogueVersion", CATALOGUE_VERSION);
        report.put("completedAt", completedAt.toString());
        report.put("outcomeBreakdown", outcomes);
        report.put("originBreakdown", originBreakdown);
        report.put("scenarioType", run.scenarioType().name());
        report.put("specificationDigest", run.specificationDigest());
        report.put("stepsCompleted", run.totalSteps());
        report.put("trafficOrigin", "SYNTHETIC_SCENARIO");
        return canonical(report);
    }

    private void insertStep(UUID merchantId, UUID runId, String specificationDigest,
            int index, String stepKey, String operation, String fixtureId, Long amount,
            String expectedOutcomeCode, String resourceType, Instant now) {
        String keyMaterial = CATALOGUE_VERSION + "|" + merchantId + "|"
                + specificationDigest + "|" + index + "|" + operation;
        String idempotencyKey = "scenario-" + SecretHasher.sha256(keyMaterial).substring(0, 48);
        jdbcTemplate.update("""
                INSERT INTO developer_scenario_steps
                    (id, merchant_id, scenario_run_id, step_index, step_key, operation,
                     fixture_id, amount, expected_outcome_code, idempotency_key,
                     lifecycle_status, resource_type, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, UUID.randomUUID(), merchantId, runId, index, stepKey, operation,
                fixtureId, amount, expectedOutcomeCode, idempotencyKey, resourceType,
                Timestamp.from(now));
    }

    private void insertEvent(UUID merchantId, UUID runId, String eventType, Integer stepIndex,
            long leaseEpoch, String outcomeCode, Instant occurredAt) {
        Integer sequence = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0) + 1 FROM developer_scenario_events
                 WHERE merchant_id = ? AND scenario_run_id = ?
                """, Integer.class, merchantId, runId);
        jdbcTemplate.update("""
                INSERT INTO developer_scenario_events
                    (id, merchant_id, scenario_run_id, event_sequence, event_type,
                     step_index, lease_epoch, outcome_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchantId, runId, sequence, eventType,
                stepIndex, leaseEpoch, outcomeCode, Timestamp.from(occurredAt));
    }

    private List<PlannedPayment> plannedPayments(DeveloperScenarioType scenarioType) {
        return switch (scenarioType) {
            case APPROVED_PAYMENT -> List.of(new PlannedPayment("approved", 4200L, "APPROVED"));
            case ISSUER_DECLINE -> List.of(new PlannedPayment("issuer_decline", 3600L, "ISSUER_DECLINE"));
            case CARD_TESTING_BURST -> List.of(
                    new PlannedPayment("suspected_fraud", 1900L, "SUSPECTED_FRAUD"),
                    new PlannedPayment("cvc_failure", 2100L, "CVC_FAILURE"),
                    new PlannedPayment("avs_line1_failure", 2300L, "AVS_LINE1_FAILURE"));
        };
    }

    private SimulatorProcessorCatalog.SimulatorFixture fixture(String fixtureId) {
        return processorCatalog.documentedFixtures().stream()
                .filter(candidate -> candidate.fixtureId().equals(fixtureId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown governed scenario fixture"));
    }

    private String canonical(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize developer scenario evidence", exception);
        }
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored developer scenario evidence is invalid", exception);
        }
    }

    private static String decimalDigits(String digest, int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            digits.append(Character.digit(digest.charAt(index), 16) % 10);
        }
        return digits.toString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireSynthetic(MerchantContext context) {
        if (context.trafficOrigin() != TrafficOrigin.SYNTHETIC_SCENARIO) {
            throw new PublicApiException(PublicApiErrorDescriptor.SCENARIO_RUNNER_CREDENTIAL_REQUIRED, "Trusted scenario runner credential is required");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.DEVELOPER_SCENARIO_CONFLICT, message);
    }

    public record CatalogueView(String catalogueVersion, String processor,
            List<TestCardView> testCards, List<DeveloperScenarioType> scenarioTypes) {
    }

    public record TestCardView(String fixtureId, String cardNumber,
            String authorizationStatus, String networkStatus, String processorOutcomeCode,
            String cvcCheck, String avsLine1Check, String avsPostalCodeCheck,
            String threeDsResult, String usage) {
    }

    public record RunView(UUID id, UUID merchantId, DeveloperScenarioType scenarioType,
            String catalogueVersion, String seedDigest, String specificationDigest,
            DeveloperScenarioLifecycleStatus lifecycleStatus, int totalSteps,
            int nextStepIndex, List<StepView> steps, StepView nextStep,
            UUID leaseOwner, long leaseEpoch, Instant leaseExpiresAt,
            String failureCode, String reportDigest, Instant createdAt,
            Instant updatedAt, Instant completedAt) {
    }

    public record StepView(int stepIndex, String stepKey, String operation,
            String method, String path, String idempotencyKey, Map<String, Object> body,
            String fixtureId, String expectedOutcomeCode, String lifecycleStatus,
            String resourceType, UUID resourceId, Instant completedAt) {
    }

    public record ReportView(UUID runId, String reportDigest, Map<String, Object> report) {
    }

    private record PlannedPayment(String fixtureId, long amount, String expectedOutcomeCode) {
    }

    private record StepRow(int stepIndex, String operation, String fixtureId, Long amount,
            String expectedOutcomeCode, String lifecycleStatus, UUID resourceId) {
    }
}
