package com.thaap.payments.controls;

import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.MaterializedFeatureSet;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.security.SensitiveValueHasher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActiveAuthoredRuleEvaluator {

    private static final String HMAC_DOMAIN = "risk_exception.card_fingerprint";

    private final JdbcTemplate jdbcTemplate;
    private final AuthoredRuleCodec codec;
    private final AuthoredRuleEvaluator evaluator;
    private final SensitiveValueHasher hasher;
    private final GovernedControlMetrics metrics;

    public ActiveAuthoredRuleEvaluator(JdbcTemplate jdbcTemplate, AuthoredRuleCodec codec,
            AuthoredRuleEvaluator evaluator, SensitiveValueHasher hasher, GovernedControlMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
        this.evaluator = evaluator;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    public AuthoredRuleAssessment evaluate(UUID merchantId, PaymentMethod paymentMethod,
            MaterializedFeatureSet features, Instant decisionTime) {
        List<ActiveRule> rules = jdbcTemplate.query("""
                SELECT id, canonical_rule_json, rule_digest, candidate_action
                  FROM authored_rule_versions
                 WHERE merchant_id = ? AND lifecycle_status = 'ACTIVE'
                 ORDER BY rule_code, version_number, id
                """, (resultSet, rowNumber) -> new ActiveRule(resultSet.getObject(1, UUID.class),
                        resultSet.getString(2), resultSet.getString(3),
                        FraudDecisionAction.valueOf(resultSet.getString(4))), merchantId);
        List<AuthoredRuleAssessment.Evaluation> results = new ArrayList<>();
        FraudDecisionAction strongest = null;
        for (ActiveRule rule : rules) {
            boolean matched = evaluator.matches(codec.parseStored(rule.json(), rule.digest()).node(),
                    features.decisionFeatureContext().features());
            ExceptionMatch exception = matched ? exception(merchantId, rule.id(), paymentMethod, decisionTime) : null;
            String disposition = !matched ? "NOT_APPLICABLE"
                    : exception == null ? "NONE" : exception.disposition();
            UUID exceptionId = exception != null && "APPLIED".equals(disposition) ? exception.id() : null;
            FraudDecisionAction effective = matched && !"APPLIED".equals(disposition) ? rule.action() : null;
            results.add(new AuthoredRuleAssessment.Evaluation(rule.id(), rule.digest(), matched, rule.action(),
                    disposition, exceptionId, effective));
            metrics.recordEvaluation(matched, rule.action(), disposition);
            if (effective != null && (strongest == null || strength(effective) > strength(strongest))) {
                strongest = effective;
            }
        }
        return new AuthoredRuleAssessment(results, strongest);
    }

    public void persist(AuthoredRuleAssessment assessment, PaymentAttempt attempt, UUID fraudDecisionId,
            Instant evaluatedAt) {
        for (AuthoredRuleAssessment.Evaluation evaluation : assessment.evaluations()) {
            jdbcTemplate.update("""
                    INSERT INTO authored_rule_evaluations
                        (id, merchant_id, payment_attempt_id, fraud_decision_id, rule_version_id,
                         rule_digest, matched, candidate_action, exception_disposition,
                         applied_exception_version_id, effective_action, evaluated_at, traffic_origin)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), attempt.getMerchantId(), attempt.getId(), fraudDecisionId,
                    evaluation.ruleVersionId(), evaluation.ruleDigest(), evaluation.matched(),
                    evaluation.candidateAction().name(), evaluation.exceptionDisposition(),
                    evaluation.appliedExceptionVersionId(),
                    evaluation.effectiveAction() == null ? null : evaluation.effectiveAction().name(),
                    Timestamp.from(evaluatedAt), attempt.getTrafficOrigin().name());
        }
    }

    private ExceptionMatch exception(UUID merchantId, UUID ruleId, PaymentMethod paymentMethod,
            Instant decisionTime) {
        List<StoredException> exceptions = jdbcTemplate.query("""
                SELECT id, subject_identity_hmac, subject_hmac_key_version, valid_from, expires_at
                  FROM risk_safety_exception_versions
                 WHERE merchant_id = ? AND source_rule_version_id = ? AND lifecycle_status = 'ACTIVE'
                 ORDER BY activated_at DESC, id
                """, (resultSet, rowNumber) -> new StoredException(resultSet.getObject(1, UUID.class),
                        resultSet.getString(2), resultSet.getString(3),
                        resultSet.getTimestamp(4).toInstant(), resultSet.getTimestamp(5).toInstant()),
                merchantId, ruleId);
        ExceptionMatch expired = null;
        for (StoredException exception : exceptions) {
            if (decisionTime.isBefore(exception.validFrom()) || !decisionTime.isBefore(exception.expiresAt())) {
                expired = new ExceptionMatch(exception.id(), "EXPIRED");
                continue;
            }
            try {
                String actual = hasher.hmacSha256(HMAC_DOMAIN, paymentMethod.getCardFingerprint(),
                        exception.keyVersion());
                if (actual.equals(exception.subjectHmac())) {
                    return new ExceptionMatch(exception.id(), "APPLIED");
                }
            } catch (IllegalStateException unavailableKey) {
                return new ExceptionMatch(exception.id(), "INVALID_EVIDENCE");
            }
        }
        return expired;
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }

    private record ActiveRule(UUID id, String json, String digest, FraudDecisionAction action) { }

    private record StoredException(UUID id, String subjectHmac, String keyVersion,
            Instant validFrom, Instant expiresAt) { }

    private record ExceptionMatch(UUID id, String disposition) { }
}
