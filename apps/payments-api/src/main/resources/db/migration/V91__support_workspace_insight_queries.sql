CREATE INDEX idx_payment_risk_projections_workspace_filters
    ON payment_risk_projections
        (merchant_id, projection_generation_id, traffic_origin, fraud_action,
         risk_level, review_status, label_value, last_event_recorded_at DESC,
         payment_intent_id);

CREATE INDEX idx_manual_reviews_workspace_operations
    ON manual_review_cases
        (merchant_id, status, assigned_operator_id, review_stage, created_at, id);

CREATE INDEX idx_review_assignment_events_workspace_range
    ON review_assignment_events
        (merchant_id, created_at, event_type, actor_operator_id, manual_review_case_id);
