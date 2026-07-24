CREATE TABLE card_testing_incidents (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    open_slot BOOLEAN,
    trigger_code VARCHAR(64) NOT NULL,
    source_attestation VARCHAR(64) NOT NULL,
    detection_window_started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    detection_window_ended_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL,
    decline_count INTEGER NOT NULL,
    distinct_card_count INTEGER NOT NULL,
    distinct_bin_count INTEGER NOT NULL,
    distinct_ip_count INTEGER NOT NULL,
    first_evidence_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_evidence_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_card_testing_incidents PRIMARY KEY (id),
    CONSTRAINT uk_card_testing_incidents_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_card_testing_incidents_open_slot UNIQUE (merchant_id, open_slot),
    CONSTRAINT fk_card_testing_incidents_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ck_card_testing_incidents_status CHECK (
        (status = 'OPEN' AND open_slot = TRUE AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND open_slot IS NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT ck_card_testing_incidents_trigger CHECK (
        trigger_code = 'PERSISTED_CARD_TESTING_PATTERN'
        AND source_attestation = 'THAAP_PERSISTED_CONTROL_EVIDENCE'
    ),
    CONSTRAINT ck_card_testing_incidents_counts CHECK (
        attempt_count >= 1 AND decline_count >= 0 AND decline_count <= attempt_count
        AND distinct_card_count >= 1 AND distinct_card_count <= attempt_count
        AND distinct_bin_count >= 1 AND distinct_bin_count <= distinct_card_count
        AND distinct_ip_count >= 0 AND distinct_ip_count <= attempt_count
    ),
    CONSTRAINT ck_card_testing_incidents_time CHECK (
        detection_window_started_at < detection_window_ended_at
        AND detection_window_started_at <= first_evidence_at
        AND first_evidence_at <= last_evidence_at
        AND last_evidence_at <= updated_at
        AND created_at <= updated_at
    )
);

CREATE TABLE incident_evidence_links (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    payment_attempt_id UUID NOT NULL,
    fraud_decision_id UUID NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    linked_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_incident_evidence_links PRIMARY KEY (id),
    CONSTRAINT uk_incident_evidence_links_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_incident_evidence_links_attempt UNIQUE (merchant_id, payment_attempt_id),
    CONSTRAINT fk_incident_evidence_links_incident FOREIGN KEY (merchant_id, incident_id)
        REFERENCES card_testing_incidents (merchant_id, id),
    CONSTRAINT fk_incident_evidence_links_attempt FOREIGN KEY (merchant_id, payment_attempt_id)
        REFERENCES payment_attempts (merchant_id, id),
    CONSTRAINT fk_incident_evidence_links_decision FOREIGN KEY (merchant_id, fraud_decision_id)
        REFERENCES fraud_decisions (merchant_id, id),
    CONSTRAINT ck_incident_evidence_links_type CHECK (
        evidence_type IN ('PROCESSOR_DECLINE', 'RISK_BLOCK', 'DISTINCT_CARD_PATTERN')
    ),
    CONSTRAINT ck_incident_evidence_links_time CHECK (observed_at <= linked_at)
);

CREATE TABLE card_testing_incident_resolutions (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    actor_operator_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    mitigation_code VARCHAR(64) NOT NULL,
    before_status VARCHAR(16) NOT NULL,
    after_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_card_testing_incident_resolutions PRIMARY KEY (id),
    CONSTRAINT uk_incident_resolutions_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_incident_resolutions_incident UNIQUE (merchant_id, incident_id),
    CONSTRAINT fk_incident_resolutions_incident FOREIGN KEY (merchant_id, incident_id)
        REFERENCES card_testing_incidents (merchant_id, id),
    CONSTRAINT fk_incident_resolutions_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_incident_resolutions_role CHECK (actor_role = 'RISK_MANAGER'),
    CONSTRAINT ck_incident_resolutions_mitigation CHECK (mitigation_code IN (
        'MONITORING_CONFIRMED', 'CREDENTIAL_ROTATED', 'UPSTREAM_BLOCK_APPLIED',
        'FALSE_POSITIVE_DISMISSED', 'MERCHANT_CONTACTED'
    )),
    CONSTRAINT ck_incident_resolutions_transition CHECK (
        before_status = 'OPEN' AND after_status = 'RESOLVED'
    )
);

CREATE INDEX idx_card_testing_incidents_merchant_created
    ON card_testing_incidents (merchant_id, created_at, id);
CREATE INDEX idx_incident_evidence_links_incident
    ON incident_evidence_links (merchant_id, incident_id, linked_at, id);

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_event_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_event_type CHECK (event_type IN (
    'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
    'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
    'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
    'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
    'authentication_challenge.created', 'authentication_challenge.completed',
    'chargeback.created', 'dispute_evidence_packet.created', 'post_authorization_signal.created',
    'card_testing_incident.opened', 'card_testing_incident.resolved'
));
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_resource_type;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_resource_type CHECK (
    resource_type IN ('PAYMENT_INTENT', 'PAYMENT_ATTEMPT', 'MANUAL_REVIEW_CASE', 'REFUND',
        'FRAUD_LABEL', 'AUTHENTICATION_CHALLENGE', 'CHARGEBACK', 'DISPUTE_EVIDENCE_PACKET',
        'POST_AUTHORIZATION_SIGNAL', 'CARD_TESTING_INCIDENT')
);
ALTER TABLE webhook_endpoint_event_subscriptions
    DROP CONSTRAINT ck_webhook_endpoint_event_subscriptions_type;
ALTER TABLE webhook_endpoint_event_subscriptions
    ADD CONSTRAINT ck_webhook_endpoint_event_subscriptions_type CHECK (event_type IN (
        'payment_intent.created', 'payment_attempt.created', 'payment_intent.captured',
        'payment_intent.canceled', 'payment_intent.blocked', 'payment_intent.requires_action',
        'fraud_review.opened', 'fraud_review.canceled', 'refund.created',
        'review.assigned', 'review.released', 'review.resolved', 'fraud_label.created',
        'authentication_challenge.created', 'authentication_challenge.completed',
        'chargeback.created', 'dispute_evidence_packet.created', 'post_authorization_signal.created',
        'card_testing_incident.opened', 'card_testing_incident.resolved'
    ));
