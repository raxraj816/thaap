ALTER TABLE fraud_rules ADD CONSTRAINT uk_fraud_rules_execution_semantics
    UNIQUE (id, rule_code, ruleset_version, priority, action, reason_code);

ALTER TABLE rule_execution_results DROP CONSTRAINT fk_rule_results_rule;
ALTER TABLE rule_execution_results ADD CONSTRAINT fk_rule_results_rule_semantics
    FOREIGN KEY (rule_id, rule_code, rule_version, priority, action, reason_code)
    REFERENCES fraud_rules (id, rule_code, ruleset_version, priority, action, reason_code);

ALTER TABLE payment_intents ADD CONSTRAINT uk_payment_intents_review_origin
    UNIQUE (merchant_id, id, traffic_origin);
ALTER TABLE payment_attempts ADD CONSTRAINT uk_payment_attempts_review_lineage
    UNIQUE (merchant_id, id, payment_intent_id, traffic_origin);
ALTER TABLE fraud_decisions ADD CONSTRAINT uk_fraud_decisions_review_lineage
    UNIQUE (merchant_id, id, payment_attempt_id, action, reason_codes, traffic_origin);

ALTER TABLE manual_review_cases ADD COLUMN decision_action VARCHAR(255) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE manual_review_cases ALTER COLUMN decision_action DROP DEFAULT;
ALTER TABLE manual_review_cases ADD CONSTRAINT ck_manual_reviews_decision_action
    CHECK (decision_action = 'REVIEW');

ALTER TABLE manual_review_cases DROP CONSTRAINT fk_manual_reviews_intent;
ALTER TABLE manual_review_cases DROP CONSTRAINT fk_manual_reviews_attempt;
ALTER TABLE manual_review_cases DROP CONSTRAINT fk_manual_reviews_decision;

ALTER TABLE manual_review_cases ADD CONSTRAINT fk_manual_reviews_intent_origin
    FOREIGN KEY (merchant_id, payment_intent_id, traffic_origin)
    REFERENCES payment_intents (merchant_id, id, traffic_origin);
ALTER TABLE manual_review_cases ADD CONSTRAINT fk_manual_reviews_attempt_lineage
    FOREIGN KEY (merchant_id, payment_attempt_id, payment_intent_id, traffic_origin)
    REFERENCES payment_attempts (merchant_id, id, payment_intent_id, traffic_origin);
ALTER TABLE manual_review_cases ADD CONSTRAINT fk_manual_reviews_decision_lineage
    FOREIGN KEY (
        merchant_id, fraud_decision_id, payment_attempt_id,
        decision_action, reason_codes, traffic_origin
    )
    REFERENCES fraud_decisions (
        merchant_id, id, payment_attempt_id, action, reason_codes, traffic_origin
    );

DELETE FROM webhook_endpoint_event_subscriptions subscription
WHERE subscription.event_type IN (
        'payment_intent.blocked',
        'payment_intent.requires_action',
        'fraud_review.opened',
        'fraud_review.canceled'
    )
  AND (
      SELECT COUNT(*)
      FROM webhook_endpoint_event_subscriptions old_subscription
      WHERE old_subscription.merchant_id = subscription.merchant_id
        AND old_subscription.webhook_endpoint_id = subscription.webhook_endpoint_id
        AND old_subscription.event_type IN (
            'payment_intent.created',
            'payment_attempt.created',
            'payment_intent.captured',
            'payment_intent.canceled',
            'refund.created'
        )
  ) < 5;
