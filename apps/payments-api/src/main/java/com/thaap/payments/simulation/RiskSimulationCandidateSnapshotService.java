package com.thaap.payments.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.controls.AuthoredRuleCodec;
import com.thaap.payments.controls.AuthoredRuleSimulationSnapshot;
import com.thaap.payments.controls.SafetyExceptionSnapshot;
import com.thaap.payments.riskpolicy.PolicyThresholdOperator;
import com.thaap.payments.security.SecretHasher;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskSimulationCandidateSnapshotService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuthoredRuleCodec authoredRuleCodec;

    @Autowired
    public RiskSimulationCandidateSnapshotService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            AuthoredRuleCodec authoredRuleCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.authoredRuleCodec = authoredRuleCodec;
    }

    RiskSimulationCandidateSnapshotService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, new AuthoredRuleCodec(objectMapper));
    }

    public FrozenCandidate freeze(UUID merchantId, RiskSimulationCandidateType candidateType,
            UUID candidateVersionId) {
        if (candidateType == RiskSimulationCandidateType.AUTHORED_RULE) {
            return freezeAuthoredRule(merchantId, candidateVersionId);
        }
        if (candidateType == RiskSimulationCandidateType.SAFETY_EXCEPTION) {
            return freezeSafetyException(merchantId, candidateVersionId);
        }
        PolicyIdentity policy = jdbcTemplate.query("""
                SELECT policy_code, version_number
                  FROM merchant_risk_policy_versions
                 WHERE merchant_id = ? AND id = ?
                 FOR UPDATE
                """, resultSet -> resultSet.next()
                        ? new PolicyIdentity(resultSet.getString(1), resultSet.getInt(2)) : null,
                merchantId, candidateVersionId);
        if (policy == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation candidate was not found");
        }
        List<RiskSimulationCandidateSnapshot.Threshold> thresholds = jdbcTemplate.query("""
                SELECT threshold_code, feature_code, feature_version, value_type, operator,
                       long_operand, boolean_operand, string_operands_json,
                       candidate_action, priority, currency_scope
                  FROM merchant_risk_policy_thresholds
                 WHERE merchant_id = ? AND policy_version_id = ?
                 ORDER BY priority, threshold_code, id
                """, (resultSet, rowNumber) -> new RiskSimulationCandidateSnapshot.Threshold(
                        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
                        FeatureValueType.valueOf(resultSet.getString(4)),
                        PolicyThresholdOperator.valueOf(resultSet.getString(5)),
                        resultSet.getObject(6, Long.class), resultSet.getObject(7, Boolean.class),
                        strings(resultSet.getString(8)),
                        FraudDecisionAction.valueOf(resultSet.getString(9)),
                        resultSet.getInt(10), resultSet.getString(11)),
                merchantId, candidateVersionId);
        if (thresholds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Simulation candidate must contain at least one threshold");
        }
        RiskSimulationCandidateSnapshot snapshot = new RiskSimulationCandidateSnapshot(
                RiskSimulationCandidateSnapshot.SCHEMA_VERSION, candidateType, candidateVersionId,
                policy.policyCode(), policy.versionNumber(), thresholds);
        String canonical = write(snapshot);
        return new FrozenCandidate(snapshot, RiskSimulationCandidateSnapshot.SCHEMA_VERSION,
                canonical, SecretHasher.sha256(canonical));
    }

    private FrozenCandidate freezeAuthoredRule(UUID merchantId, UUID candidateVersionId) {
        StoredRule rule = jdbcTemplate.query("""
                SELECT canonical_rule_json, rule_digest, candidate_action
                  FROM authored_rule_versions
                 WHERE merchant_id = ? AND id = ?
                 FOR UPDATE
                """, resultSet -> resultSet.next()
                        ? new StoredRule(resultSet.getString(1), resultSet.getString(2),
                                FraudDecisionAction.valueOf(resultSet.getString(3))) : null,
                merchantId, candidateVersionId);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation candidate was not found");
        }
        authoredRuleCodec.parseStored(rule.json(), rule.digest());
        AuthoredRuleSimulationSnapshot snapshot = new AuthoredRuleSimulationSnapshot(
                AuthoredRuleSimulationSnapshot.SCHEMA_VERSION, candidateVersionId,
                rule.json(), rule.digest(), rule.candidateAction());
        String canonical = write(snapshot);
        return new FrozenCandidate(snapshot, AuthoredRuleSimulationSnapshot.SCHEMA_VERSION,
                canonical, SecretHasher.sha256(canonical));
    }

    private FrozenCandidate freezeSafetyException(UUID merchantId, UUID candidateVersionId) {
        SafetyExceptionSnapshot snapshot = jdbcTemplate.query("""
                SELECT exception.id, exception.source_rule_version_id,
                       rule.canonical_rule_json, rule.rule_digest, rule.candidate_action,
                       exception.subject_type, exception.subject_identity_hmac,
                       exception.subject_hmac_key_version, exception.reason_code,
                       exception.valid_from, exception.expires_at
                  FROM risk_safety_exception_versions exception
                  JOIN authored_rule_versions rule
                    ON rule.merchant_id = exception.merchant_id
                   AND rule.id = exception.source_rule_version_id
                 WHERE exception.merchant_id = ? AND exception.id = ?
                 FOR UPDATE OF exception
                """, resultSet -> resultSet.next() ? new SafetyExceptionSnapshot(
                        SafetyExceptionSnapshot.SCHEMA_VERSION,
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getString(3), resultSet.getString(4),
                        FraudDecisionAction.valueOf(resultSet.getString(5)), resultSet.getString(6),
                        resultSet.getString(7), resultSet.getString(8), resultSet.getString(9),
                        resultSet.getTimestamp(10).toInstant(), resultSet.getTimestamp(11).toInstant()) : null,
                merchantId, candidateVersionId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation candidate was not found");
        }
        authoredRuleCodec.parseStored(snapshot.sourceRuleJson(), snapshot.sourceRuleDigest());
        String canonical = write(snapshot);
        return new FrozenCandidate(snapshot, SafetyExceptionSnapshot.SCHEMA_VERSION,
                canonical, SecretHasher.sha256(canonical));
    }

    public RiskSimulationCandidateSnapshot parseAndVerify(String canonicalJson, String digest) {
        if (!SecretHasher.sha256(canonicalJson).equals(digest)) {
            throw new IllegalStateException("Simulation candidate digest does not match frozen bytes");
        }
        try {
            RiskSimulationCandidateSnapshot snapshot = objectMapper.readValue(
                    canonicalJson, RiskSimulationCandidateSnapshot.class);
            if (!RiskSimulationCandidateSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())
                    || snapshot.candidateType() != RiskSimulationCandidateType.MERCHANT_RISK_POLICY
                    || snapshot.thresholds().isEmpty()) {
                throw new IllegalStateException("Simulation candidate schema is unsupported");
            }
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Simulation candidate bytes are unreadable", exception);
        }
    }

    public AuthoredRuleSimulationSnapshot parseAndVerifyAuthoredRule(String canonicalJson, String digest) {
        if (!SecretHasher.sha256(canonicalJson).equals(digest)) {
            throw new IllegalStateException("Authored rule snapshot digest does not match frozen bytes");
        }
        try {
            AuthoredRuleSimulationSnapshot snapshot = objectMapper.readValue(
                    canonicalJson, AuthoredRuleSimulationSnapshot.class);
            if (!AuthoredRuleSimulationSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())
                    || snapshot.ruleVersionId() == null || snapshot.candidateAction() == null) {
                throw new IllegalStateException("Authored rule snapshot schema is unsupported");
            }
            authoredRuleCodec.parseStored(snapshot.ruleJson(), snapshot.ruleDigest());
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Authored rule snapshot bytes are unreadable", exception);
        }
    }

    private List<String> strings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored policy threshold strings are unreadable", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Simulation candidate cannot be canonicalized", exception);
        }
    }

    public record FrozenCandidate(Object snapshot, String schemaVersion,
            String canonicalJson, String digest) { }

    private record PolicyIdentity(String policyCode, int versionNumber) { }

    private record StoredRule(String json, String digest, FraudDecisionAction candidateAction) { }
}
