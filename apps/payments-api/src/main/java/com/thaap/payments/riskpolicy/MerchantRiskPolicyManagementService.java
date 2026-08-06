package com.thaap.payments.riskpolicy;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.FeatureDefinition;
import com.thaap.payments.fraud.FeatureDefinitionRepository;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.payment.PaymentIntentController;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantRiskPolicyManagementService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_PROFILE_VERSIONS = 64;
    private static final int MAX_POLICY_VERSIONS = 128;
    private static final int MAX_THRESHOLDS = 32;
    private static final int MAX_IN_VALUES = 32;
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "INR");

    private final JdbcTemplate jdbcTemplate;
    private final FeatureDefinitionRepository featureDefinitionRepository;
    private final MerchantRiskPolicyEvaluator evaluator;
    private final MerchantRiskPolicyMetrics metrics;
    private final AuditLogService auditLogService;
    private final RiskOperationsEventProducer eventProducer;
    private final Clock clock;

    public MerchantRiskPolicyManagementService(JdbcTemplate jdbcTemplate,
            FeatureDefinitionRepository featureDefinitionRepository, MerchantRiskPolicyEvaluator evaluator,
            MerchantRiskPolicyMetrics metrics, AuditLogService auditLogService,
            RiskOperationsEventProducer eventProducer, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.featureDefinitionRepository = featureDefinitionRepository;
        this.evaluator = evaluator;
        this.metrics = metrics;
        this.auditLogService = auditLogService;
        this.eventProducer = eventProducer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProfileView> listProfiles(RiskOperatorContext context) {
        return jdbcTemplate.query("""
                SELECT profile.id, profile.version_number, profile.risk_tier, profile.source,
                       profile.reason_code, profile.created_by_operator_id, profile.created_at
                  FROM merchant_risk_profile_versions profile
                 WHERE profile.merchant_id = ? ORDER BY profile.version_number
                """, (resultSet, rowNumber) -> profileView(context.merchantId(), resultSet), context.merchantId());
    }

    @Transactional
    public ProfileView createProfile(RiskOperatorContext context, MerchantRiskTier tier,
            MerchantRiskProfileReasonCode reasonCode, String requestId) {
        lockManager(context);
        int count = count("merchant_risk_profile_versions", context.merchantId());
        if (count >= MAX_PROFILE_VERSIONS) {
            throw conflict("Merchant has reached its 64 risk-profile-version limit");
        }
        int version = nextVersion("merchant_risk_profile_versions", context.merchantId(), null);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO merchant_risk_profile_versions
                    (id, merchant_id, version_number, risk_tier, source, reason_code,
                     created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, 'RISK_MANAGER', ?, ?, ?)
                """, id, context.merchantId(), version, tier.name(), reasonCode.name(),
                context.operatorId(), Timestamp.from(now));
        auditLogService.recordRiskOperatorAction(context, requestId, "merchant_risk_profile.created",
                "MerchantRiskProfile", id, reasonCode.name(), Map.of(),
                Map.of("version", version, "riskTier", tier.name(), "state", "DRAFT"));
        metrics.recordManagement("PROFILE", "CREATED");
        return new ProfileView(id, version, tier, MerchantRiskProfileSource.RISK_MANAGER, reasonCode,
                MerchantRiskLifecycleState.DRAFT, context.operatorId(), null, now);
    }

    @Transactional
    public ProfileView transitionProfile(RiskOperatorContext context, UUID profileId,
            MerchantRiskLifecycleState target, String requestId) {
        lockManager(context);
        ProfileView profile = requireProfile(context.merchantId(), profileId);
        MerchantRiskLifecycleState current = profile.state();
        Instant now = clock.instant();
        String event;
        if (target == MerchantRiskLifecycleState.PENDING_APPROVAL
                && current == MerchantRiskLifecycleState.DRAFT) {
            event = "SUBMITTED";
        } else if (target == MerchantRiskLifecycleState.APPROVED
                && current == MerchantRiskLifecycleState.PENDING_APPROVAL) {
            if (profile.createdByOperatorId().equals(context.operatorId())) {
                throw new PublicApiException(PublicApiErrorDescriptor.RISK_PROFILE_SELF_APPROVAL_FORBIDDEN, "Risk-profile creator cannot approve the same version");
            }
            event = "APPROVED";
        } else if (target == MerchantRiskLifecycleState.ACTIVE
                && current == MerchantRiskLifecycleState.APPROVED) {
            retireActiveProfile(context, profileId, now, requestId);
            event = "ACTIVATED";
        } else if (target == MerchantRiskLifecycleState.RETIRED
                && current == MerchantRiskLifecycleState.ACTIVE) {
            event = "RETIRED";
        } else {
            throw conflict("Risk-profile lifecycle transition is invalid");
        }
        insertProfileEvent(context, profileId, event, now);
        if ("ACTIVATED".equals(event)) {
            eventProducer.riskControlChanged(context, profileId,
                    DomainEventType.MERCHANT_RISK_PROFILE_ACTIVATED, now, requestId,
                    profile.versionNumber(), "ACTIVE", profile.reasonCode().name());
        } else if ("RETIRED".equals(event)) {
            eventProducer.riskControlChanged(context, profileId,
                    DomainEventType.MERCHANT_RISK_PROFILE_ROLLED_BACK, now, requestId,
                    profile.versionNumber(), "RETIRED", profile.reasonCode().name());
        }
        auditLogService.recordRiskOperatorAction(context, requestId,
                "merchant_risk_profile." + event.toLowerCase(), "MerchantRiskProfile", profileId,
                profile.reasonCode().name(), Map.of("state", current.name()), Map.of("state", target.name()));
        metrics.recordManagement("PROFILE", event);
        return requireProfile(context.merchantId(), profileId);
    }

    @Transactional(readOnly = true)
    public List<PolicyView> listPolicies(RiskOperatorContext context) {
        return jdbcTemplate.query("""
                SELECT policy.id, policy.policy_code, policy.display_name, policy.version_number,
                       policy.created_by_operator_id, policy.created_at
                  FROM merchant_risk_policy_versions policy
                 WHERE policy.merchant_id = ?
                 ORDER BY policy.policy_code, policy.version_number
                """, (resultSet, rowNumber) -> policyView(context.merchantId(), resultSet), context.merchantId());
    }

    @Transactional
    public PolicyView createPolicy(RiskOperatorContext context, String code, String displayName, String requestId) {
        lockManager(context);
        int count = count("merchant_risk_policy_versions", context.merchantId());
        if (count >= MAX_POLICY_VERSIONS) {
            throw conflict("Merchant has reached its 128 risk-policy-version limit");
        }
        int version = nextVersion("merchant_risk_policy_versions", context.merchantId(), code);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO merchant_risk_policy_versions
                    (id, merchant_id, policy_code, display_name, version_number,
                     created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, context.merchantId(), code, displayName, version, context.operatorId(),
                Timestamp.from(now));
        auditLogService.recordRiskOperatorAction(context, requestId, "merchant_risk_policy.created",
                "MerchantRiskPolicy", id, "MERCHANT_RISK_CHANGE", Map.of(),
                Map.of("policyCode", code, "version", version, "state", "DRAFT"));
        metrics.recordManagement("POLICY", "CREATED");
        return new PolicyView(id, code, displayName, version, MerchantRiskLifecycleState.DRAFT,
                context.operatorId(), null, 0, now);
    }

    @Transactional
    public ThresholdView addThreshold(RiskOperatorContext context, UUID policyId, String thresholdCode,
            String featureCode, PolicyThresholdOperator operator, Long longValue, Boolean booleanValue,
            List<String> stringValues, FraudDecisionAction action, int priority,
            PolicyThresholdReasonCode reasonCode, String currencyScope, String requestId) {
        lockManager(context);
        PolicyView policy = requirePolicy(context.merchantId(), policyId);
        if (policy.state() != MerchantRiskLifecycleState.DRAFT) {
            throw conflict("Risk-policy thresholds are immutable after submission");
        }
        if (policy.thresholdCount() >= MAX_THRESHOLDS) {
            throw conflict("Risk-policy version has reached its 32-threshold limit");
        }
        if (action == FraudDecisionAction.ALLOW) {
            throw badRequest("Merchant risk policy cannot produce ALLOW");
        }
        FeatureDefinition definition = featureDefinitionRepository
                .findByFeatureCodeAndFeatureVersion(featureCode, FeatureCatalogue.VERSION)
                .filter(candidate -> candidate.getValueType() != FeatureValueType.DECIMAL)
                .orElseThrow(() -> badRequest("Feature is not allowlisted for merchant risk policy"));
        NormalizedOperand operand = normalizeOperand(featureCode, definition.getValueType(), operator,
                longValue, booleanValue, stringValues);
        validateReason(featureCode, reasonCode);
        String currency = normalizeCurrency(currencyScope);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String stringsJson = operand.stringValues().isEmpty() ? null : json(operand.stringValues());
        try {
            jdbcTemplate.update("""
                    INSERT INTO merchant_risk_policy_thresholds
                        (id, merchant_id, policy_version_id, threshold_code,
                         feature_definition_id, feature_code, feature_version, value_type,
                         operator, long_operand, boolean_operand, string_operands_json,
                         candidate_action, priority, reason_code, currency_scope,
                         created_by_operator_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, context.merchantId(), policyId, thresholdCode, definition.getId(),
                    definition.getFeatureCode(), definition.getFeatureVersion(), definition.getValueType().name(),
                    operator.name(), operand.longValue(), operand.booleanValue(), stringsJson, action.name(),
                    priority, reasonCode.name(), currency, context.operatorId(), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Equivalent threshold code already exists in this policy", exception);
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "merchant_risk_policy.threshold.created",
                "MerchantRiskPolicyThreshold", id, reasonCode.name(), Map.of(),
                Map.of("policyId", policyId.toString(), "thresholdCode", thresholdCode,
                        "featureCode", featureCode, "operator", operator.name(), "action", action.name()));
        metrics.recordManagement("THRESHOLD", "CREATED");
        return new ThresholdView(id, policyId, thresholdCode, featureCode, definition.getValueType(), operator,
                operand.longValue(), operand.booleanValue(), operand.stringValues(), action, priority,
                reasonCode, currency, now);
    }

    @Transactional
    public PolicyView transitionPolicy(RiskOperatorContext context, UUID policyId,
            MerchantRiskLifecycleState target, String requestId) {
        lockManager(context);
        PolicyView policy = requirePolicy(context.merchantId(), policyId);
        MerchantRiskLifecycleState current = policy.state();
        Instant now = clock.instant();
        String event;
        if (target == MerchantRiskLifecycleState.PENDING_APPROVAL
                && current == MerchantRiskLifecycleState.DRAFT) {
            if (policy.thresholdCount() == 0) {
                throw badRequest("Risk policy must contain at least one threshold before submission");
            }
            event = "SUBMITTED";
        } else if (target == MerchantRiskLifecycleState.APPROVED
                && current == MerchantRiskLifecycleState.PENDING_APPROVAL) {
            if (policy.createdByOperatorId().equals(context.operatorId())) {
                throw new PublicApiException(PublicApiErrorDescriptor.RISK_POLICY_SELF_APPROVAL_FORBIDDEN, "Risk-policy creator cannot approve the same version");
            }
            event = "APPROVED";
        } else if (target == MerchantRiskLifecycleState.ACTIVE
                && current == MerchantRiskLifecycleState.APPROVED) {
            retireActivePolicy(context, policyId, now, requestId);
            event = "ACTIVATED";
        } else if (target == MerchantRiskLifecycleState.RETIRED
                && current == MerchantRiskLifecycleState.ACTIVE) {
            event = "RETIRED";
        } else {
            throw conflict("Risk-policy lifecycle transition is invalid");
        }
        insertPolicyEvent(context, policyId, event, now);
        if ("ACTIVATED".equals(event)) {
            eventProducer.riskControlChanged(context, policyId,
                    DomainEventType.RISK_POLICY_ACTIVATED, now, requestId,
                    policy.versionNumber(), "ACTIVE", "MERCHANT_RISK_CHANGE");
        } else if ("RETIRED".equals(event)) {
            eventProducer.riskControlChanged(context, policyId,
                    DomainEventType.RISK_POLICY_ROLLED_BACK, now, requestId,
                    policy.versionNumber(), "RETIRED", "MERCHANT_RISK_CHANGE");
        }
        auditLogService.recordRiskOperatorAction(context, requestId,
                "merchant_risk_policy." + event.toLowerCase(), "MerchantRiskPolicy", policyId,
                "MERCHANT_RISK_CHANGE", Map.of("state", current.name()), Map.of("state", target.name()));
        metrics.recordManagement("POLICY", event);
        return requirePolicy(context.merchantId(), policyId);
    }

    @Transactional(readOnly = true)
    public PreviewView preview(RiskOperatorContext context, UUID policyId, Map<String, Object> fixture,
            String currency) {
        PolicyView policy = requirePolicy(context.merchantId(), policyId, false);
        if (policy.state() != MerchantRiskLifecycleState.DRAFT
                && policy.state() != MerchantRiskLifecycleState.APPROVED) {
            throw conflict("Fixture preview supports only DRAFT or APPROVED policy versions");
        }
        if (fixture.size() > 32) {
            throw badRequest("Fixture preview accepts at most 32 feature values");
        }
        String normalizedCurrency = normalizeCurrency(currency);
        List<MerchantPolicyThreshold> thresholds = evaluator.thresholdsForPolicy(context.merchantId(), policyId);
        Set<String> allowedFixtureFeatures = thresholds.stream()
                .map(MerchantPolicyThreshold::featureCode).collect(java.util.stream.Collectors.toSet());
        if (!allowedFixtureFeatures.containsAll(fixture.keySet())) {
            throw badRequest("Fixture contains a feature not referenced by this policy version");
        }
        List<PreviewEvaluation> evaluations = new ArrayList<>();
        FraudDecisionAction strongest = null;
        for (MerchantPolicyThreshold threshold : thresholds) {
            Object value = normalizeFixtureValue(threshold, fixture.get(threshold.featureCode()));
            boolean matched = evaluator.matchesFixture(threshold, value, normalizedCurrency);
            evaluations.add(new PreviewEvaluation(threshold.code(), threshold.featureCode(), matched,
                    threshold.candidateAction(), threshold.reasonCode()));
            if (matched && (strongest == null || strength(threshold.candidateAction()) > strength(strongest))) {
                strongest = threshold.candidateAction();
            }
        }
        PreviewView preview = new PreviewView(policyId, policy.policyCode(), policy.versionNumber(),
                "FIXTURE_PREVIEW", strongest, List.copyOf(evaluations));
        metrics.recordPreview(strongest != null, strongest);
        return preview;
    }

    private void lockManager(RiskOperatorContext context) {
        UUID merchantId = jdbcTemplate.query("""
                SELECT id FROM merchants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.merchantId());
        if (merchantId == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_CONFIGURATION_MERCHANT_INACTIVE, "Active merchant is required for merchant risk configuration");
        }
        Integer guardCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_risk_configuration_guards WHERE merchant_id = ?
                """, Integer.class, context.merchantId());
        if (guardCount == null || guardCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO merchant_risk_configuration_guards (merchant_id, created_at)
                    SELECT id, created_at FROM merchants WHERE id = ?
                    """, context.merchantId());
        }
        jdbcTemplate.query("""
                SELECT merchant_id FROM merchant_risk_configuration_guards
                 WHERE merchant_id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.merchantId());
        String role = jdbcTemplate.query("""
                SELECT role FROM risk_operators
                 WHERE merchant_id = ? AND id = ? AND status = 'ACTIVE' FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                context.merchantId(), context.operatorId());
        if (!RiskOperatorRole.RISK_MANAGER.name().equals(role)) {
            throw new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_CONFIGURATION_MANAGER_REQUIRED, "RISK_MANAGER role is required for merchant risk configuration");
        }
    }

    private void retireActiveProfile(RiskOperatorContext context, UUID targetId,
            Instant now, String requestId) {
        List<UUID> active = activeIds("merchant_risk_profile_versions", "merchant_risk_profile_events",
                "profile_version_id", context.merchantId());
        if (active.size() > 1) {
            throw conflict("Merchant risk profile configuration is ambiguous");
        }
        if (!active.isEmpty() && !active.getFirst().equals(targetId)) {
            UUID retiredId = active.getFirst();
            ProfileView retired = requireProfile(context.merchantId(), retiredId);
            insertProfileEvent(context, retiredId, "RETIRED", now);
            eventProducer.riskControlChanged(context, retiredId,
                    DomainEventType.MERCHANT_RISK_PROFILE_ROLLED_BACK, now, requestId,
                    retired.versionNumber(), "RETIRED", "SUPERSEDED_BY_ACTIVATION");
        }
    }

    private void retireActivePolicy(RiskOperatorContext context, UUID targetId,
            Instant now, String requestId) {
        List<UUID> active = activeIds("merchant_risk_policy_versions", "merchant_risk_policy_events",
                "policy_version_id", context.merchantId());
        if (active.size() > 1) {
            throw conflict("Merchant risk policy configuration is ambiguous");
        }
        if (!active.isEmpty() && !active.getFirst().equals(targetId)) {
            UUID retiredId = active.getFirst();
            PolicyView retired = requirePolicy(context.merchantId(), retiredId);
            insertPolicyEvent(context, retiredId, "RETIRED", now);
            eventProducer.riskControlChanged(context, retiredId,
                    DomainEventType.RISK_POLICY_ROLLED_BACK, now, requestId,
                    retired.versionNumber(), "RETIRED", "SUPERSEDED_BY_ACTIVATION");
        }
    }

    private List<UUID> activeIds(String versionTable, String eventTable, String versionColumn, UUID merchantId) {
        String idColumn = versionTable.contains("profile") ? "profile.id" : "policy.id";
        String alias = versionTable.contains("profile") ? "profile" : "policy";
        String sql = "SELECT " + idColumn + " FROM " + versionTable + " " + alias
                + " WHERE " + alias + ".merchant_id = ?"
                + " AND EXISTS (SELECT 1 FROM " + eventTable + " active_event WHERE active_event.merchant_id = "
                + alias + ".merchant_id AND active_event." + versionColumn + " = " + idColumn
                + " AND active_event.event_type = 'ACTIVATED')"
                + " AND NOT EXISTS (SELECT 1 FROM " + eventTable
                + " retired_event WHERE retired_event.merchant_id = " + alias
                + ".merchant_id AND retired_event." + versionColumn + " = " + idColumn
                + " AND retired_event.event_type = 'RETIRED') ORDER BY " + idColumn;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), merchantId);
    }

    private void insertProfileEvent(RiskOperatorContext context, UUID id, String event, Instant now) {
        insertEvent("merchant_risk_profile_events", "profile_version_id",
                context, id, event, now);
    }

    private void insertPolicyEvent(RiskOperatorContext context, UUID id, String event, Instant now) {
        insertEvent("merchant_risk_policy_events", "policy_version_id",
                context, id, event, now);
    }

    private void insertEvent(String table, String versionColumn, RiskOperatorContext context,
            UUID versionId, String event, Instant now) {
        try {
            jdbcTemplate.update("INSERT INTO " + table
                    + " (id, merchant_id, " + versionColumn
                    + ", event_type, actor_operator_id, occurred_at, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), context.merchantId(), versionId,
                    event, context.operatorId(), Timestamp.from(now), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Merchant risk lifecycle transition conflicts with durable state", exception);
        }
    }

    private ProfileView requireProfile(UUID merchantId, UUID profileId) {
        ProfileView view = jdbcTemplate.query("""
                SELECT profile.id, profile.version_number, profile.risk_tier, profile.source,
                       profile.reason_code, profile.created_by_operator_id, profile.created_at
                  FROM merchant_risk_profile_versions profile
                 WHERE profile.merchant_id = ? AND profile.id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? profileView(merchantId, resultSet) : null,
                merchantId, profileId);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_PROFILE_NOT_FOUND, "Merchant risk profile was not found");
        }
        return view;
    }

    private PolicyView requirePolicy(UUID merchantId, UUID policyId) {
        return requirePolicy(merchantId, policyId, true);
    }

    private PolicyView requirePolicy(UUID merchantId, UUID policyId, boolean lock) {
        String sql = """
                SELECT policy.id, policy.policy_code, policy.display_name, policy.version_number,
                       policy.created_by_operator_id, policy.created_at
                  FROM merchant_risk_policy_versions policy
                 WHERE policy.merchant_id = ? AND policy.id = ?
                """ + (lock ? " FOR UPDATE" : "");
        PolicyView view = jdbcTemplate.query(sql,
                resultSet -> resultSet.next() ? policyView(merchantId, resultSet) : null,
                merchantId, policyId);
        if (view == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_POLICY_NOT_FOUND, "Merchant risk policy was not found");
        }
        return view;
    }

    private ProfileView profileView(UUID merchantId, ResultSet resultSet) throws SQLException {
        UUID id = resultSet.getObject(1, UUID.class);
        return new ProfileView(id, resultSet.getInt(2), MerchantRiskTier.valueOf(resultSet.getString(3)),
                MerchantRiskProfileSource.valueOf(resultSet.getString(4)),
                MerchantRiskProfileReasonCode.valueOf(resultSet.getString(5)), state(
                        "merchant_risk_profile_events", "profile_version_id", merchantId, id),
                resultSet.getObject(6, UUID.class), approver(
                        "merchant_risk_profile_events", "profile_version_id", merchantId, id),
                resultSet.getTimestamp(7).toInstant());
    }

    private PolicyView policyView(UUID merchantId, ResultSet resultSet) throws SQLException {
        UUID id = resultSet.getObject(1, UUID.class);
        Integer thresholdCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_risk_policy_thresholds
                 WHERE merchant_id = ? AND policy_version_id = ?
                """, Integer.class, merchantId, id);
        return new PolicyView(id, resultSet.getString(2), resultSet.getString(3), resultSet.getInt(4),
                state("merchant_risk_policy_events", "policy_version_id", merchantId, id),
                resultSet.getObject(5, UUID.class), approver(
                        "merchant_risk_policy_events", "policy_version_id", merchantId, id),
                thresholdCount == null ? 0 : thresholdCount, resultSet.getTimestamp(6).toInstant());
    }

    private MerchantRiskLifecycleState state(String table, String versionColumn, UUID merchantId, UUID id) {
        List<String> events = jdbcTemplate.query("SELECT event_type FROM " + table
                + " WHERE merchant_id = ? AND " + versionColumn + " = ? ORDER BY occurred_at, id",
                (resultSet, rowNumber) -> resultSet.getString(1), merchantId, id);
        if (events.contains("RETIRED")) {
            return MerchantRiskLifecycleState.RETIRED;
        }
        if (events.contains("ACTIVATED")) {
            return MerchantRiskLifecycleState.ACTIVE;
        }
        if (events.contains("APPROVED")) {
            return MerchantRiskLifecycleState.APPROVED;
        }
        if (events.contains("SUBMITTED")) {
            return MerchantRiskLifecycleState.PENDING_APPROVAL;
        }
        return MerchantRiskLifecycleState.DRAFT;
    }

    private UUID approver(String table, String versionColumn, UUID merchantId, UUID id) {
        List<UUID> actors = jdbcTemplate.query("SELECT actor_operator_id FROM " + table
                        + " WHERE merchant_id = ? AND " + versionColumn
                        + " = ? AND event_type = 'APPROVED'",
                (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), merchantId, id);
        return actors.isEmpty() ? null : actors.getFirst();
    }

    private int count(String table, UUID merchantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE merchant_id = ?", Integer.class, merchantId);
        return count == null ? 0 : count;
    }

    private int nextVersion(String table, UUID merchantId, String code) {
        String predicate = code == null ? "" : " AND policy_code = ?";
        Integer maximum = code == null
                ? jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version_number), 0) FROM " + table
                        + " WHERE merchant_id = ?" + predicate, Integer.class, merchantId)
                : jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version_number), 0) FROM " + table
                        + " WHERE merchant_id = ?" + predicate, Integer.class, merchantId, code);
        return (maximum == null ? 0 : maximum) + 1;
    }

    private NormalizedOperand normalizeOperand(String featureCode, FeatureValueType type,
            PolicyThresholdOperator operator, Long longValue, Boolean booleanValue, List<String> stringValues) {
        List<String> rawStrings = stringValues == null ? List.of() : stringValues;
        if (operator == PolicyThresholdOperator.GTE) {
            if (type != FeatureValueType.LONG || longValue == null || booleanValue != null || !rawStrings.isEmpty()) {
                throw badRequest("GTE requires exactly one LONG operand");
            }
            validateLongRange(featureCode, longValue);
            return new NormalizedOperand(longValue, null, List.of());
        }
        if (operator == PolicyThresholdOperator.EQ && type == FeatureValueType.LONG) {
            if (longValue == null || booleanValue != null || !rawStrings.isEmpty()) {
                throw badRequest("LONG EQ requires exactly one LONG operand");
            }
            validateLongRange(featureCode, longValue);
            return new NormalizedOperand(longValue, null, List.of());
        }
        if (operator == PolicyThresholdOperator.EQ && type == FeatureValueType.BOOLEAN) {
            if (longValue != null || booleanValue == null || !rawStrings.isEmpty()) {
                throw badRequest("BOOLEAN EQ requires exactly one BOOLEAN operand");
            }
            return new NormalizedOperand(null, booleanValue, List.of());
        }
        if ((operator == PolicyThresholdOperator.EQ || operator == PolicyThresholdOperator.IN)
                && type == FeatureValueType.STRING) {
            int requiredMinimum = 1;
            int requiredMaximum = operator == PolicyThresholdOperator.EQ ? 1 : MAX_IN_VALUES;
            if (longValue != null || booleanValue != null || rawStrings.size() < requiredMinimum
                    || rawStrings.size() > requiredMaximum) {
                throw badRequest("STRING operand count is invalid for operator");
            }
            TreeSet<String> normalized = new TreeSet<>();
            for (String raw : rawStrings) {
                if (raw == null || !raw.matches("[A-Z0-9_]{1,64}")) {
                    throw badRequest("STRING operands must use the closed uppercase token grammar");
                }
                normalized.add(raw);
            }
            if (normalized.size() != rawStrings.size()) {
                throw badRequest("STRING operands must be unique after canonicalization");
            }
            validateStringValues(featureCode, normalized);
            return new NormalizedOperand(null, null, List.copyOf(normalized));
        }
        throw badRequest("Feature type and threshold operator are incompatible");
    }

    private void validateLongRange(String featureCode, long value) {
        long maximum = switch (featureCode) {
            case "payment.amount_minor" -> PaymentIntentController.MAX_PAYMENT_INTENT_AMOUNT;
            case "payment.attempt_number" -> 1_000L;
            case "risk.customer_account_age_days" -> 36_500L;
            case "risk.login_age_minutes" -> 5_256_000L;
            case "risk.bot_score_bps" -> 10_000L;
            default -> featureCode.startsWith("velocity.") ? 1_000_000L : -1L;
        };
        if (maximum < 0 || value < 0 || value > maximum) {
            throw badRequest("LONG operand is outside the governed feature range");
        }
    }

    private void validateStringValues(String featureCode, Set<String> values) {
        Set<String> allowed = switch (featureCode) {
            case "merchant.risk_tier" -> Set.of("STANDARD", "ELEVATED", "HIGH");
            case "payment.currency" -> CURRENCIES;
            case "payment.capture_method" -> Set.of("AUTOMATIC", "MANUAL");
            case "traffic.origin" -> Set.of("MERCHANT_API", "SYNTHETIC_SCENARIO");
            case "card.funding" -> Set.of("CREDIT", "DEBIT", "PREPAID", "UNKNOWN");
            case "risk.checkout_channel" -> Set.of("WEB", "MOBILE_WEB", "IOS", "ANDROID", "API");
            case "risk.captcha_outcome" -> Set.of("NOT_PRESENT", "PASSED", "FAILED", "UNAVAILABLE");
            default -> null;
        };
        if (allowed != null && !allowed.containsAll(values)) {
            throw badRequest("STRING operand is outside the governed feature vocabulary");
        }
        if (allowed == null && (featureCode.equals("card.country")
                || featureCode.equals("risk.ip_country")
                || featureCode.equals("risk.shipping_country"))) {
            for (String value : values) {
                if (!value.matches("[A-Z]{2}")) {
                    throw badRequest("Country operands must be ISO alpha-2 tokens");
                }
            }
        } else if (allowed == null) {
            throw badRequest("STRING feature is not allowlisted for merchant policy");
        }
    }

    private void validateReason(String featureCode, PolicyThresholdReasonCode reason) {
        PolicyThresholdReasonCode expected = featureCode.equals("payment.amount_minor")
                ? PolicyThresholdReasonCode.MERCHANT_AMOUNT_THRESHOLD
                : featureCode.startsWith("velocity.")
                        ? PolicyThresholdReasonCode.MERCHANT_VELOCITY_THRESHOLD
                        : featureCode.equals("merchant.risk_tier")
                                ? PolicyThresholdReasonCode.MERCHANT_RISK_TIER
                                : PolicyThresholdReasonCode.MERCHANT_CONTEXT_THRESHOLD;
        if (reason != expected) {
            throw badRequest("Threshold reason code does not match its feature family");
        }
    }

    private String normalizeCurrency(String currency) {
        if (currency == null) {
            return null;
        }
        if (!CURRENCIES.contains(currency)) {
            throw badRequest("Currency scope is not supported");
        }
        return currency;
    }

    private Object normalizeFixtureValue(MerchantPolicyThreshold threshold, Object value) {
        if (value == null) {
            return null;
        }
        return switch (threshold.valueType()) {
            case LONG -> value instanceof Integer integerValue ? integerValue.longValue()
                    : value instanceof Long ? value : invalidFixture();
            case BOOLEAN -> value instanceof Boolean ? value : invalidFixture();
            case STRING -> value instanceof String stringValue && stringValue.matches("[A-Z0-9_]{1,64}")
                    ? stringValue : invalidFixture();
            case DECIMAL -> invalidFixture();
        };
    }

    private Object invalidFixture() {
        throw badRequest("Fixture value does not match the governed feature type");
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }

    private String json(List<String> strings) {
        try {
            return OBJECT_MAPPER.writeValueAsString(strings);
        } catch (Exception exception) {
            throw new IllegalStateException("Merchant policy operand could not be serialized", exception);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_POLICY_REQUEST_INVALID, message);
    }

    private ResponseStatusException conflict(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_POLICY_CONFLICT, message);
    }

    private ResponseStatusException conflict(String message, RuntimeException cause) {
        return new PublicApiException(PublicApiErrorDescriptor.MERCHANT_RISK_POLICY_CONFLICT, message, cause);
    }

    private record NormalizedOperand(Long longValue, Boolean booleanValue, List<String> stringValues) { }

    public record ProfileView(UUID id, int versionNumber, MerchantRiskTier riskTier,
            MerchantRiskProfileSource source, MerchantRiskProfileReasonCode reasonCode,
            MerchantRiskLifecycleState state, UUID createdByOperatorId, UUID approvedByOperatorId,
            Instant createdAt) { }

    public record PolicyView(UUID id, String policyCode, String displayName, int versionNumber,
            MerchantRiskLifecycleState state, UUID createdByOperatorId, UUID approvedByOperatorId,
            int thresholdCount, Instant createdAt) { }

    public record ThresholdView(UUID id, UUID policyVersionId, String thresholdCode, String featureCode,
            FeatureValueType valueType, PolicyThresholdOperator operator, Long longValue,
            Boolean booleanValue, List<String> stringValues, FraudDecisionAction candidateAction,
            int priority, PolicyThresholdReasonCode reasonCode, String currencyScope, Instant createdAt) { }

    public record PreviewEvaluation(String thresholdCode, String featureCode, boolean matched,
            FraudDecisionAction candidateAction, PolicyThresholdReasonCode reasonCode) { }

    public record PreviewView(UUID policyVersionId, String policyCode, int versionNumber, String previewType,
            FraudDecisionAction strongestCandidate, List<PreviewEvaluation> evaluations) { }
}
