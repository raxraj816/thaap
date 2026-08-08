package com.thaap.payments.fraud;

import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rule_execution_results", uniqueConstraints = {
        @UniqueConstraint(name = "uk_rule_results_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_rule_results_decision_rule",
                columnNames = {"merchant_id", "fraud_decision_id", "rule_code", "rule_version"})
})
public class RuleExecutionResult {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "fraud_decision_id", nullable = false)
    private UUID fraudDecisionId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "rule_code", nullable = false, length = 128)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean matched;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FraudDecisionAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    private FraudDecisionReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_outcome", nullable = false, length = 16)
    private RuleEvaluationOutcome evaluationOutcome;

    @Column(name = "latency_us", nullable = false)
    private long latencyUs;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32)
    private TrafficOrigin trafficOrigin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RuleExecutionResult() {
    }

    public RuleExecutionResult(UUID merchantId, UUID fraudDecisionId, RuleEvaluation evaluation,
            TrafficOrigin trafficOrigin) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.fraudDecisionId = fraudDecisionId;
        this.ruleId = evaluation.ruleId();
        this.ruleCode = evaluation.ruleCode();
        this.ruleVersion = evaluation.ruleVersion();
        this.priority = evaluation.priority();
        this.matched = evaluation.matched();
        this.action = evaluation.action();
        this.reasonCode = evaluation.reasonCode();
        this.evaluationOutcome = evaluation.outcome();
        this.latencyUs = evaluation.latencyUs();
        this.trafficOrigin = trafficOrigin;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public UUID getRuleId() { return ruleId; }
    public String getRuleCode() { return ruleCode; }
    public String getRuleVersion() { return ruleVersion; }
    public int getPriority() { return priority; }
    public boolean isMatched() { return matched; }
    public FraudDecisionAction getAction() { return action; }
    public FraudDecisionReasonCode getReasonCode() { return reasonCode; }
    public RuleEvaluationOutcome getEvaluationOutcome() { return evaluationOutcome; }
    public long getLatencyUs() { return latencyUs; }
    public TrafficOrigin getTrafficOrigin() { return trafficOrigin; }
    public Instant getCreatedAt() { return createdAt; }
}
