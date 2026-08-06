package com.thaap.payments.controls;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.simulation.RiskSimulationActivationGate;
import com.thaap.payments.simulation.RiskSimulationCandidateType;
import com.thaap.payments.simulation.RiskSimulationGateDisposition;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GovernedControlService {

    private static final String EXCEPTION_HMAC_DOMAIN = "risk_exception.card_fingerprint";
    private static final Duration MAX_EXCEPTION_DURATION = Duration.ofDays(30);

    private final JdbcTemplate jdbcTemplate;
    private final AuthoredRuleCodec ruleCodec;
    private final RiskSimulationActivationGate activationGate;
    private final PaymentMethodRepository paymentMethodRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final GovernedControlMetrics metrics;
    private final RiskOperationsEventProducer eventProducer;

    public GovernedControlService(JdbcTemplate jdbcTemplate, AuthoredRuleCodec ruleCodec,
            RiskSimulationActivationGate activationGate, PaymentMethodRepository paymentMethodRepository,
            SensitiveValueHasher sensitiveValueHasher, AuditLogService auditLogService,
            ObjectMapper objectMapper, Clock clock, GovernedControlMetrics metrics,
            RiskOperationsEventProducer eventProducer) {
        this.jdbcTemplate = jdbcTemplate;
        this.ruleCodec = ruleCodec;
        this.activationGate = activationGate;
        this.paymentMethodRepository = paymentMethodRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
        this.eventProducer = eventProducer;
    }

    @Transactional(readOnly = true)
    public List<RuleView> listRules(RiskOperatorContext context) {
        return jdbcTemplate.query(ruleSelect() + " WHERE merchant_id = ? ORDER BY rule_code, version_number",
                (resultSet, rowNumber) -> mapRule(resultSet), context.merchantId());
    }

    @Transactional
    public RuleView createRule(RiskOperatorContext context, String code, String displayName,
            FraudDecisionAction action, Map<String, Object> expression, String requestId) {
        lockManager(context);
        if (action == null || action == FraudDecisionAction.ALLOW) {
            throw badRequest("Authored rules may emit only BLOCK, REVIEW, or REQUEST_3DS");
        }
        AuthoredRuleCodec.EncodedRule encoded = ruleCodec.encode(expression);
        int version = nextVersion("authored_rule_versions", "rule_code", context.merchantId(), code);
        UUID id = UUID.randomUUID();
        Instant now = now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO authored_rule_versions
                        (id, merchant_id, rule_code, display_name, version_number, schema_version,
                         canonical_rule_json, rule_digest, candidate_action, reason_code,
                         feature_catalogue_version, lifecycle_status, projection_version,
                         created_by_operator_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTHORED_RULE_MATCH', ?, 'DRAFT', 0, ?, ?)
                    """, id, context.merchantId(), code, displayName, version,
                    AuthoredRuleCodec.SCHEMA_VERSION, encoded.canonicalJson(), encoded.digest(),
                    action.name(), FeatureCatalogue.VERSION, context.operatorId(), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Authored rule identity is already in use");
        }
        insertRuleEvent(context, id, "CREATED", null, now);
        metrics.recordLifecycle("RULE", "CREATED");
        audit(context, requestId, "authored_rule.created", id, code, "DRAFT");
        return requireRule(context.merchantId(), id, false);
    }

    @Transactional
    public RuleView transitionRule(RiskOperatorContext context, UUID id, ControlLifecycleStatus target,
            UUID simulationRunId, String requestId) {
        lockManager(context);
        RuleView current = requireRule(context.merchantId(), id, true);
        Instant now = now();
        String event;
        if (current.lifecycleStatus() == ControlLifecycleStatus.DRAFT
                && target == ControlLifecycleStatus.SUBMITTED) {
            event = "SUBMITTED";
            updateRule(id, context.merchantId(), "SUBMITTED", "submitted_at = ?", now, now);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.SUBMITTED
                && target == ControlLifecycleStatus.APPROVED) {
            requireIndependentApprover(current.createdByOperatorId(), context.operatorId());
            event = "APPROVED";
            jdbcTemplate.update("""
                    UPDATE authored_rule_versions
                       SET lifecycle_status = 'APPROVED', approved_by_operator_id = ?, approved_at = ?,
                           projection_version = projection_version + 1
                     WHERE merchant_id = ? AND id = ?
                    """, context.operatorId(), Timestamp.from(now), context.merchantId(), id);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.APPROVED
                && target == ControlLifecycleStatus.ACTIVE) {
            requireGate(context, RiskSimulationCandidateType.AUTHORED_RULE, id, simulationRunId);
            retireActiveRules(context, current.ruleCode(), id, now, requestId);
            event = "ACTIVATED";
            activateRule(context, id, simulationRunId, now);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.RETIRED
                && target == ControlLifecycleStatus.ACTIVE) {
            requireGate(context, RiskSimulationCandidateType.AUTHORED_RULE, id, simulationRunId);
            retireActiveRules(context, current.ruleCode(), id, now, requestId);
            event = "ROLLBACK_ACTIVATED";
            activateRule(context, id, simulationRunId, now);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.ACTIVE
                && target == ControlLifecycleStatus.RETIRED) {
            event = "RETIRED";
            jdbcTemplate.update("""
                    UPDATE authored_rule_versions
                       SET lifecycle_status = 'RETIRED', retired_at = ?, projection_version = projection_version + 1
                     WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), context.merchantId(), id);
        } else {
            throw conflict("Authored rule lifecycle transition is invalid");
        }
        insertRuleEvent(context, id, event, simulationRunId, now);
        if ("ACTIVATED".equals(event)) {
            eventProducer.riskControlChanged(context, id, DomainEventType.AUTHORED_RULE_ACTIVATED,
                    now, requestId, current.versionNumber(), "ACTIVE", null);
        } else if ("ROLLBACK_ACTIVATED".equals(event)) {
            eventProducer.riskControlChanged(context, id, DomainEventType.AUTHORED_RULE_ROLLED_BACK,
                    now, requestId, current.versionNumber(), "ACTIVE", "ROLLBACK_ACTIVATED");
        } else if ("RETIRED".equals(event)) {
            eventProducer.riskControlChanged(context, id, DomainEventType.AUTHORED_RULE_DISABLED,
                    now, requestId, current.versionNumber(), "RETIRED", "OPERATOR_DISABLED");
        }
        metrics.recordLifecycle("RULE", event);
        audit(context, requestId, "authored_rule." + event.toLowerCase(), id, current.ruleCode(), target.name());
        return requireRule(context.merchantId(), id, false);
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> listExceptions(RiskOperatorContext context) {
        return jdbcTemplate.query(exceptionSelect()
                        + " WHERE exception.merchant_id = ? ORDER BY exception.exception_code, exception.version_number",
                (resultSet, rowNumber) -> mapException(resultSet), context.merchantId());
    }

    @Transactional
    public ExceptionView createException(RiskOperatorContext context, String code, UUID sourceRuleVersionId,
            UUID paymentMethodId, String reasonCode, Instant validFrom, Instant expiresAt, String requestId) {
        lockManager(context);
        RuleView source = requireRule(context.merchantId(), sourceRuleVersionId, true);
        if (source.lifecycleStatus() != ControlLifecycleStatus.APPROVED
                && source.lifecycleStatus() != ControlLifecycleStatus.ACTIVE) {
            throw conflict("Safety exception source rule must be approved or active");
        }
        Instant start = exactInstant(validFrom, "validFrom");
        Instant end = exactInstant(expiresAt, "expiresAt");
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_EXCEPTION_DURATION) > 0) {
            throw badRequest("Safety exception validity must be positive and at most 30 days");
        }
        if (!List.of("TRUSTED_CUSTOMER_REMEDIATION", "FALSE_POSITIVE_REMEDIATION").contains(reasonCode)) {
            throw badRequest("Safety exception reason is unsupported");
        }
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMerchantId(paymentMethodId, context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_METHOD_NOT_FOUND, "PaymentMethod was not found"));
        String keyVersion = sensitiveValueHasher.currentKeyVersion();
        String subjectHmac = sensitiveValueHasher.hmacSha256(
                EXCEPTION_HMAC_DOMAIN, paymentMethod.getCardFingerprint(), keyVersion);
        int version = nextVersion("risk_safety_exception_versions", "exception_code",
                context.merchantId(), code);
        UUID id = UUID.randomUUID();
        Instant now = now();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", SafetyExceptionSnapshot.SCHEMA_VERSION);
        canonical.put("sourceRuleVersionId", sourceRuleVersionId.toString());
        canonical.put("subjectType", "CARD_FINGERPRINT");
        canonical.put("subjectIdentityHmac", subjectHmac);
        canonical.put("subjectHmacKeyVersion", keyVersion);
        canonical.put("reasonCode", reasonCode);
        canonical.put("validFrom", start.toString());
        canonical.put("expiresAt", end.toString());
        String json = json(canonical);
        String digest = SecretHasher.sha256(json);
        jdbcTemplate.update("""
                INSERT INTO risk_safety_exception_versions
                    (id, merchant_id, exception_code, version_number, schema_version,
                     source_rule_version_id, subject_type, subject_identity_hmac,
                     subject_hmac_key_version, reason_code, valid_from, expires_at,
                     canonical_exception_json, exception_digest, lifecycle_status,
                     projection_version, created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CARD_FINGERPRINT', ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 0, ?, ?)
                """, id, context.merchantId(), code, version, SafetyExceptionSnapshot.SCHEMA_VERSION,
                sourceRuleVersionId, subjectHmac, keyVersion, reasonCode, Timestamp.from(start),
                Timestamp.from(end), json, digest, context.operatorId(), Timestamp.from(now));
        insertExceptionEvent(context, id, "CREATED", null, now);
        metrics.recordLifecycle("EXCEPTION", "CREATED");
        audit(context, requestId, "risk_safety_exception.created", id, code, "DRAFT");
        return requireException(context.merchantId(), id, false);
    }

    @Transactional
    public ExceptionView transitionException(RiskOperatorContext context, UUID id,
            ControlLifecycleStatus target, UUID simulationRunId, String requestId) {
        lockManager(context);
        ExceptionView current = requireException(context.merchantId(), id, true);
        Instant now = now();
        String event;
        if (current.lifecycleStatus() == ControlLifecycleStatus.DRAFT
                && target == ControlLifecycleStatus.SUBMITTED) {
            event = "SUBMITTED";
            updateException(id, context.merchantId(), "SUBMITTED", "submitted_at = ?", now, now);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.SUBMITTED
                && target == ControlLifecycleStatus.APPROVED) {
            requireIndependentApprover(current.createdByOperatorId(), context.operatorId());
            event = "APPROVED";
            jdbcTemplate.update("""
                    UPDATE risk_safety_exception_versions
                       SET lifecycle_status = 'APPROVED', approved_by_operator_id = ?, approved_at = ?,
                           projection_version = projection_version + 1
                     WHERE merchant_id = ? AND id = ?
                    """, context.operatorId(), Timestamp.from(now), context.merchantId(), id);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.APPROVED
                && target == ControlLifecycleStatus.ACTIVE) {
            if (!now.isBefore(current.expiresAt())) {
                throw conflict("Expired safety exception cannot activate");
            }
            requireGate(context, RiskSimulationCandidateType.SAFETY_EXCEPTION, id, simulationRunId);
            retireActiveExceptions(context, current.exceptionCode(), id, now, requestId);
            event = "ACTIVATED";
            jdbcTemplate.update("""
                    UPDATE risk_safety_exception_versions
                       SET lifecycle_status = 'ACTIVE', activated_at = ?, retired_at = NULL,
                           activation_simulation_run_id = ?, projection_version = projection_version + 1
                     WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), simulationRunId, context.merchantId(), id);
        } else if (current.lifecycleStatus() == ControlLifecycleStatus.ACTIVE
                && target == ControlLifecycleStatus.RETIRED) {
            event = "RETIRED";
            jdbcTemplate.update("""
                    UPDATE risk_safety_exception_versions
                       SET lifecycle_status = 'RETIRED', retired_at = ?, projection_version = projection_version + 1
                     WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), context.merchantId(), id);
        } else {
            throw conflict("Safety exception lifecycle transition is invalid");
        }
        insertExceptionEvent(context, id, event, simulationRunId, now);
        if ("ACTIVATED".equals(event)) {
            eventProducer.riskControlChanged(context, id, DomainEventType.SAFETY_EXCEPTION_ACTIVATED,
                    now, requestId, current.versionNumber(), "ACTIVE", current.reasonCode());
        } else if ("RETIRED".equals(event) && !now.isBefore(current.expiresAt())) {
            eventProducer.riskControlChanged(context, id, DomainEventType.SAFETY_EXCEPTION_EXPIRED,
                    now, requestId, current.versionNumber(), "RETIRED", "VALIDITY_EXPIRED");
        } else if ("RETIRED".equals(event)) {
            eventProducer.riskControlChanged(context, id, DomainEventType.SAFETY_EXCEPTION_DISABLED,
                    now, requestId, current.versionNumber(), "RETIRED", "OPERATOR_DISABLED");
        }
        metrics.recordLifecycle("EXCEPTION", event);
        audit(context, requestId, "risk_safety_exception." + event.toLowerCase(), id,
                current.exceptionCode(), target.name());
        return requireException(context.merchantId(), id, false);
    }

    private void requireGate(RiskOperatorContext context, RiskSimulationCandidateType type,
            UUID candidateId, UUID runId) {
        if (runId == null) {
            throw badRequest("A successful simulation run is required for activation");
        }
        Boolean exactCandidate = jdbcTemplate.query("""
                SELECT candidate_type = ? AND candidate_version_id = ?
                  FROM risk_simulation_runs WHERE merchant_id = ? AND id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getBoolean(1) : null,
                type.name(), candidateId, context.merchantId(), runId);
        if (!Boolean.TRUE.equals(exactCandidate)) {
            throw conflict("Simulation is not bound to this exact candidate");
        }
        RiskSimulationActivationGate.GateDecision gate = activationGate.evaluate(
                context.merchantId(), runId, context.operatorId());
        if (gate.disposition() != RiskSimulationGateDisposition.ACCEPTED) {
            throw conflict("Simulation activation gate rejected the candidate: " + gate.reasons());
        }
    }

    private void activateRule(RiskOperatorContext context, UUID id, UUID runId, Instant now) {
        jdbcTemplate.update("""
                UPDATE authored_rule_versions
                   SET lifecycle_status = 'ACTIVE', activated_at = ?, retired_at = NULL,
                       activation_simulation_run_id = ?, projection_version = projection_version + 1
                 WHERE merchant_id = ? AND id = ?
                """, Timestamp.from(now), runId, context.merchantId(), id);
    }

    private void retireActiveRules(RiskOperatorContext context, String code, UUID target,
            Instant now, String requestId) {
        List<UUID> active = jdbcTemplate.query("""
                SELECT id FROM authored_rule_versions
                 WHERE merchant_id = ? AND rule_code = ? AND lifecycle_status = 'ACTIVE' AND id <> ?
                 FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                context.merchantId(), code, target);
        for (UUID id : active) {
            jdbcTemplate.update("""
                    UPDATE authored_rule_versions SET lifecycle_status = 'RETIRED', retired_at = ?,
                           projection_version = projection_version + 1 WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), context.merchantId(), id);
            insertRuleEvent(context, id, "RETIRED", null, now);
            RuleView retired = requireRule(context.merchantId(), id, false);
            eventProducer.riskControlChanged(context, id, DomainEventType.AUTHORED_RULE_DISABLED,
                    now, requestId, retired.versionNumber(), "RETIRED", "SUPERSEDED_BY_ACTIVATION");
        }
    }

    private void retireActiveExceptions(RiskOperatorContext context, String code, UUID target,
            Instant now, String requestId) {
        List<UUID> active = jdbcTemplate.query("""
                SELECT id FROM risk_safety_exception_versions
                 WHERE merchant_id = ? AND exception_code = ? AND lifecycle_status = 'ACTIVE' AND id <> ?
                 FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                context.merchantId(), code, target);
        for (UUID id : active) {
            jdbcTemplate.update("""
                    UPDATE risk_safety_exception_versions SET lifecycle_status = 'RETIRED', retired_at = ?,
                           projection_version = projection_version + 1 WHERE merchant_id = ? AND id = ?
                    """, Timestamp.from(now), context.merchantId(), id);
            insertExceptionEvent(context, id, "RETIRED", null, now);
            ExceptionView retired = requireException(context.merchantId(), id, false);
            eventProducer.riskControlChanged(context, id, DomainEventType.SAFETY_EXCEPTION_DISABLED,
                    now, requestId, retired.versionNumber(), "RETIRED", "SUPERSEDED_BY_ACTIVATION");
        }
    }

    private void updateRule(UUID id, UUID merchantId, String status, String timestampAssignment,
            Instant timestamp, Instant unused) {
        jdbcTemplate.update("UPDATE authored_rule_versions SET lifecycle_status = ?, "
                + timestampAssignment + ", projection_version = projection_version + 1"
                + " WHERE merchant_id = ? AND id = ?", status, Timestamp.from(timestamp), merchantId, id);
    }

    private void updateException(UUID id, UUID merchantId, String status, String timestampAssignment,
            Instant timestamp, Instant unused) {
        jdbcTemplate.update("UPDATE risk_safety_exception_versions SET lifecycle_status = ?, "
                + timestampAssignment + ", projection_version = projection_version + 1"
                + " WHERE merchant_id = ? AND id = ?", status, Timestamp.from(timestamp), merchantId, id);
    }

    private RuleView requireRule(UUID merchantId, UUID id, boolean lock) {
        RuleView view = jdbcTemplate.query(ruleSelect() + " WHERE merchant_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""), resultSet -> resultSet.next() ? mapRule(resultSet) : null,
                merchantId, id);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.AUTHORED_RULE_NOT_FOUND, "Authored rule was not found");
        }
        return view;
    }

    private String ruleSelect() {
        return """
                SELECT id, rule_code, display_name, version_number, canonical_rule_json, rule_digest,
                       candidate_action, lifecycle_status, projection_version, created_by_operator_id,
                       approved_by_operator_id, activation_simulation_run_id, created_at, submitted_at,
                       approved_at, activated_at, retired_at FROM authored_rule_versions
                """;
    }

    private RuleView mapRule(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new RuleView(resultSet.getObject(1, UUID.class), resultSet.getString(2), resultSet.getString(3),
                resultSet.getInt(4), resultSet.getString(5), resultSet.getString(6),
                FraudDecisionAction.valueOf(resultSet.getString(7)),
                ControlLifecycleStatus.valueOf(resultSet.getString(8)), resultSet.getLong(9),
                resultSet.getObject(10, UUID.class), resultSet.getObject(11, UUID.class),
                resultSet.getObject(12, UUID.class), instant(resultSet.getTimestamp(13)),
                instant(resultSet.getTimestamp(14)), instant(resultSet.getTimestamp(15)),
                instant(resultSet.getTimestamp(16)), instant(resultSet.getTimestamp(17)));
    }

    private ExceptionView requireException(UUID merchantId, UUID id, boolean lock) {
        ExceptionView view = jdbcTemplate.query(exceptionSelect()
                        + " WHERE exception.merchant_id = ? AND exception.id = ?" + (lock ? " FOR UPDATE" : ""),
                resultSet -> resultSet.next() ? mapException(resultSet) : null, merchantId, id);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAFETY_EXCEPTION_NOT_FOUND, "Safety exception was not found");
        }
        return view;
    }

    private String exceptionSelect() {
        return """
                SELECT exception.id, exception.exception_code, exception.version_number,
                       exception.source_rule_version_id, rule.rule_code, exception.reason_code,
                       exception.valid_from, exception.expires_at, exception.exception_digest,
                       exception.lifecycle_status, exception.projection_version,
                       exception.created_by_operator_id, exception.approved_by_operator_id,
                       exception.activation_simulation_run_id, exception.created_at,
                       exception.submitted_at, exception.approved_at, exception.activated_at,
                       exception.retired_at
                  FROM risk_safety_exception_versions exception
                  JOIN authored_rule_versions rule ON rule.merchant_id = exception.merchant_id
                       AND rule.id = exception.source_rule_version_id
                """;
    }

    private ExceptionView mapException(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ExceptionView(resultSet.getObject(1, UUID.class), resultSet.getString(2), resultSet.getInt(3),
                resultSet.getObject(4, UUID.class), resultSet.getString(5), resultSet.getString(6),
                resultSet.getTimestamp(7).toInstant(), resultSet.getTimestamp(8).toInstant(),
                resultSet.getString(9), ControlLifecycleStatus.valueOf(resultSet.getString(10)),
                resultSet.getLong(11), resultSet.getObject(12, UUID.class), resultSet.getObject(13, UUID.class),
                resultSet.getObject(14, UUID.class), instant(resultSet.getTimestamp(15)),
                instant(resultSet.getTimestamp(16)), instant(resultSet.getTimestamp(17)),
                instant(resultSet.getTimestamp(18)), instant(resultSet.getTimestamp(19)));
    }

    private void lockManager(RiskOperatorContext context) {
        if (context.role() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_MANAGER_ROLE_REQUIRED, "RISK_MANAGER role is required");
        }
        String role = jdbcTemplate.query("""
                SELECT role FROM risk_operators
                 WHERE merchant_id = ? AND id = ? AND status = 'ACTIVE' FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                context.merchantId(), context.operatorId());
        if (!RiskOperatorRole.RISK_MANAGER.name().equals(role)) {
            throw new PublicApiException(PublicApiErrorDescriptor.ACTIVE_RISK_MANAGER_ROLE_REQUIRED, "Active RISK_MANAGER role is required");
        }
        Integer guardCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_risk_configuration_guards"
                + " WHERE merchant_id = ?", Integer.class, context.merchantId());
        if (guardCount == null || guardCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO merchant_risk_configuration_guards (merchant_id, created_at)
                    SELECT id, created_at FROM merchants WHERE id = ? AND status = 'ACTIVE'
                    """, context.merchantId());
        }
        Boolean guard = jdbcTemplate.query("SELECT merchant_id FROM merchant_risk_configuration_guards"
                        + " WHERE merchant_id = ? FOR UPDATE",
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) resultSet -> resultSet.next(),
                context.merchantId());
        if (!Boolean.TRUE.equals(guard)) {
            throw new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_CONFIGURATION_GUARD_UNAVAILABLE, "Merchant risk configuration guard is unavailable");
        }
    }

    private int nextVersion(String table, String codeColumn, UUID merchantId, String code) {
        Integer version = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version_number), 0) + 1 FROM "
                + table + " WHERE merchant_id = ? AND " + codeColumn + " = ?", Integer.class, merchantId, code);
        return version == null ? 1 : version;
    }

    private void requireIndependentApprover(UUID creator, UUID approver) {
        if (creator.equals(approver)) {
            throw new PublicApiException(PublicApiErrorDescriptor.GOVERNED_CONTROL_SELF_APPROVAL_FORBIDDEN, "Control creator cannot approve the same version");
        }
    }

    private void insertRuleEvent(RiskOperatorContext context, UUID id, String type, UUID runId, Instant at) {
        jdbcTemplate.update("""
                INSERT INTO authored_rule_events
                    (id, merchant_id, rule_version_id, event_type, actor_operator_id,
                     simulation_run_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.merchantId(), id, type, context.operatorId(), runId,
                Timestamp.from(at));
    }

    private void insertExceptionEvent(RiskOperatorContext context, UUID id, String type, UUID runId, Instant at) {
        jdbcTemplate.update("""
                INSERT INTO risk_safety_exception_events
                    (id, merchant_id, exception_version_id, event_type, actor_operator_id,
                     simulation_run_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.merchantId(), id, type, context.operatorId(), runId,
                Timestamp.from(at));
    }

    private void audit(RiskOperatorContext context, String requestId, String action, UUID id,
            String code, String state) {
        auditLogService.recordRiskOperatorAction(context, requestId, action, "GovernedRiskControl", id,
                "GOVERNED_RISK_CONTROL", Map.of(), Map.of("code", code, "state", state));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Safety exception cannot be canonicalized", exception);
        }
    }

    private Instant exactInstant(Instant value, String name) {
        if (value == null || !value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw badRequest(name + " must be an exact UTC microsecond instant");
        }
        return value;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private ResponseStatusException badRequest(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.GOVERNED_CONTROL_REQUEST_INVALID, message);
    }

    private ResponseStatusException conflict(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.GOVERNED_CONTROL_CONFLICT, message);
    }

    public record RuleView(UUID id, String ruleCode, String displayName, int versionNumber,
            String canonicalRuleJson, String ruleDigest, FraudDecisionAction candidateAction,
            ControlLifecycleStatus lifecycleStatus, long projectionVersion, UUID createdByOperatorId,
            UUID approvedByOperatorId, UUID activationSimulationRunId, Instant createdAt,
            Instant submittedAt, Instant approvedAt, Instant activatedAt, Instant retiredAt) { }

    public record ExceptionView(UUID id, String exceptionCode, int versionNumber, UUID sourceRuleVersionId,
            String sourceRuleCode, String reasonCode, Instant validFrom, Instant expiresAt,
            String exceptionDigest, ControlLifecycleStatus lifecycleStatus, long projectionVersion,
            UUID createdByOperatorId, UUID approvedByOperatorId, UUID activationSimulationRunId,
            Instant createdAt, Instant submittedAt, Instant approvedAt, Instant activatedAt,
            Instant retiredAt) { }
}
