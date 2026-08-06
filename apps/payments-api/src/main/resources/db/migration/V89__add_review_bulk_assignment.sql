CREATE TABLE review_bulk_action_requests (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    initiating_operator_id UUID NOT NULL,
    action_code VARCHAR(32) NOT NULL,
    target_operator_id UUID,
    idempotency_identity_hmac VARCHAR(64) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    item_count INTEGER NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    succeeded_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    lease_token UUID,
    lease_epoch BIGINT NOT NULL,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_bulk_action_requests PRIMARY KEY (id),
    CONSTRAINT uk_review_bulk_action_requests_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_bulk_action_requests_idempotency UNIQUE (
        merchant_id, initiating_operator_id, idempotency_identity_hmac),
    CONSTRAINT fk_review_bulk_action_requests_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_review_bulk_action_requests_actor
        FOREIGN KEY (merchant_id, initiating_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_review_bulk_action_requests_target
        FOREIGN KEY (merchant_id, target_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_bulk_action_requests_action CHECK (
        action_code IN ('ASSIGN_TO_SELF', 'ASSIGN_TO_OPERATOR', 'RELEASE')),
    CONSTRAINT ck_review_bulk_action_requests_target CHECK (
        (action_code = 'ASSIGN_TO_OPERATOR' AND target_operator_id IS NOT NULL)
        OR (action_code <> 'ASSIGN_TO_OPERATOR' AND target_operator_id IS NULL)),
    CONSTRAINT ck_review_bulk_action_requests_digest CHECK (
        CHAR_LENGTH(idempotency_identity_hmac) = 64 AND CHAR_LENGTH(request_digest) = 64),
    CONSTRAINT ck_review_bulk_action_requests_count CHECK (
        item_count BETWEEN 1 AND 25 AND succeeded_count BETWEEN 0 AND item_count
        AND failed_count BETWEEN 0 AND item_count
        AND succeeded_count + failed_count <= item_count),
    CONSTRAINT ck_review_bulk_action_requests_status CHECK (
        lifecycle_status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_review_bulk_action_requests_lease CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_review_bulk_action_requests_epoch CHECK (lease_epoch >= 0),
    CONSTRAINT ck_review_bulk_action_requests_time CHECK (
        created_at <= updated_at
        AND (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= COALESCE(started_at, created_at)))
);

CREATE TABLE review_bulk_action_items (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    bulk_action_request_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    expected_assignment_version BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(32),
    observed_assignment_version BIGINT,
    final_assignment_version BIGINT,
    claim_lease_token UUID,
    claim_lease_epoch BIGINT,
    claimed_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_bulk_action_items PRIMARY KEY (id),
    CONSTRAINT uk_review_bulk_action_items_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_bulk_action_items_ordinal
        UNIQUE (merchant_id, bulk_action_request_id, ordinal),
    CONSTRAINT uk_review_bulk_action_items_review
        UNIQUE (merchant_id, bulk_action_request_id, manual_review_case_id),
    CONSTRAINT fk_review_bulk_action_items_request
        FOREIGN KEY (merchant_id, bulk_action_request_id)
        REFERENCES review_bulk_action_requests (merchant_id, id),
    CONSTRAINT ck_review_bulk_action_items_version CHECK (
        expected_assignment_version >= 0
        AND (observed_assignment_version IS NULL OR observed_assignment_version >= 0)
        AND (final_assignment_version IS NULL OR final_assignment_version >= 0)),
    CONSTRAINT ck_review_bulk_action_items_ordinal CHECK (ordinal BETWEEN 1 AND 25),
    CONSTRAINT ck_review_bulk_action_items_status CHECK (
        lifecycle_status IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'STALE',
            'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED')),
    CONSTRAINT ck_review_bulk_action_items_failure CHECK (
        (lifecycle_status = 'SUCCEEDED' AND failure_code IS NULL
            AND final_assignment_version IS NOT NULL AND completed_at IS NOT NULL)
        OR (lifecycle_status IN ('STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED')
            AND failure_code IS NOT NULL AND completed_at IS NOT NULL)
        OR (lifecycle_status IN ('PENDING', 'CLAIMED')
            AND failure_code IS NULL AND final_assignment_version IS NULL
            AND completed_at IS NULL)),
    CONSTRAINT ck_review_bulk_action_items_claim CHECK (
        (lifecycle_status = 'CLAIMED' AND claim_lease_token IS NOT NULL
            AND claim_lease_epoch IS NOT NULL AND claimed_at IS NOT NULL)
        OR lifecycle_status <> 'CLAIMED')
);

CREATE INDEX idx_review_bulk_action_requests_work
    ON review_bulk_action_requests (lifecycle_status, lease_expires_at, created_at, id);

CREATE INDEX idx_review_bulk_action_items_work
    ON review_bulk_action_items
        (merchant_id, bulk_action_request_id, lifecycle_status, ordinal, id);
