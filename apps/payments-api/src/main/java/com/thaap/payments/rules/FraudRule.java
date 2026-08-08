package com.thaap.payments.rules;

import com.thaap.payments.time.DatabaseInstant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.RuleConditionType;

@Entity
@Table(name = "fraud_rules", uniqueConstraints = @UniqueConstraint(name = "uk_fraud_rules_code_version",
        columnNames = {"rule_code", "ruleset_version"}))
public class FraudRule {

    @Id
    private UUID id;

    @Column(name = "rule_code", nullable = false, length = 128)
    private String ruleCode;

    @Column(name = "ruleset_version", nullable = false, length = 32)
    private String rulesetVersion;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FraudDecisionAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    private FraudDecisionReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 32)
    private RuleConditionType conditionType;

    @Column(name = "feature_code", nullable = false, length = 128)
    private String featureCode;

    @Column(name = "currency_feature_code", length = 128)
    private String currencyFeatureCode;

    @Column(name = "currency_value", length = 3)
    private String currencyValue;

    @Column(name = "long_threshold", nullable = false)
    private long longThreshold;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    protected FraudRule() {
    }

    public FraudRule(UUID id, String ruleCode, String rulesetVersion, int priority, FraudDecisionAction action,
            FraudDecisionReasonCode reasonCode, RuleConditionType conditionType, String featureCode,
            String currencyFeatureCode, String currencyValue, long longThreshold, String status) {
        this.id = id;
        this.ruleCode = ruleCode;
        this.rulesetVersion = rulesetVersion;
        this.priority = priority;
        this.action = action;
        this.reasonCode = reasonCode;
        this.conditionType = conditionType;
        this.featureCode = featureCode;
        this.currencyFeatureCode = currencyFeatureCode;
        this.currencyValue = currencyValue;
        this.longThreshold = longThreshold;
        this.status = status;
        this.createdAt = DatabaseInstant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public int getPriority() {
        return priority;
    }

    public FraudDecisionAction getAction() {
        return action;
    }

    public FraudDecisionReasonCode getReasonCode() {
        return reasonCode;
    }

    public RuleConditionType getConditionType() {
        return conditionType;
    }

    public String getFeatureCode() {
        return featureCode;
    }

    public String getCurrencyFeatureCode() {
        return currencyFeatureCode;
    }

    public String getCurrencyValue() {
        return currencyValue;
    }

    public long getLongThreshold() {
        return longThreshold;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
