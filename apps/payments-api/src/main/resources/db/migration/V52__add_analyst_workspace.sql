CREATE TABLE review_saved_searches (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    owner_operator_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    filter_schema_version VARCHAR(32) NOT NULL,
    canonical_filter_json TEXT NOT NULL,
    sort_code VARCHAR(32) NOT NULL,
    visible_columns_json VARCHAR(512) NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_review_saved_searches PRIMARY KEY (id),
    CONSTRAINT uk_review_saved_searches_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_saved_searches_owner_name UNIQUE
        (merchant_id, owner_operator_id, name),
    CONSTRAINT fk_review_saved_searches_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id),
    CONSTRAINT fk_review_saved_searches_owner FOREIGN KEY (merchant_id, owner_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_saved_searches_name CHECK (
        LENGTH(name) BETWEEN 1 AND 80 AND name = TRIM(name)
    ),
    CONSTRAINT ck_review_saved_searches_visibility CHECK (
        visibility IN ('PRIVATE', 'MERCHANT_SHARED')
    ),
    CONSTRAINT ck_review_saved_searches_schema CHECK (
        LENGTH(filter_schema_version) BETWEEN 1 AND 32
    ),
    CONSTRAINT ck_review_saved_searches_sort CHECK (
        sort_code IN ('CREATED_AT_ASC', 'CREATED_AT_DESC')
    ),
    CONSTRAINT ck_review_saved_searches_digest CHECK (
        LENGTH(definition_digest) = 64
    ),
    CONSTRAINT ck_review_saved_searches_lifecycle CHECK (
        (lifecycle_status = 'ACTIVE' AND disabled_at IS NULL)
        OR (lifecycle_status = 'DISABLED' AND disabled_at IS NOT NULL)
    ),
    CONSTRAINT ck_review_saved_searches_version CHECK (lifecycle_version >= 1),
    CONSTRAINT ck_review_saved_searches_time CHECK (
        created_at <= updated_at
        AND (disabled_at IS NULL OR updated_at = disabled_at)
    )
);

CREATE INDEX idx_review_saved_searches_visible
    ON review_saved_searches (merchant_id, visibility, lifecycle_status, updated_at, id);

CREATE TABLE review_notes (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    author_operator_id UUID NOT NULL,
    author_assignment_version BIGINT NOT NULL,
    supersedes_note_id UUID,
    ciphertext TEXT,
    nonce VARCHAR(24),
    key_version VARCHAR(32),
    plaintext_code_points INTEGER NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    retention_expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    redacted_at TIMESTAMP(6) WITH TIME ZONE,
    redacted_by_operator_id UUID,
    redaction_reason VARCHAR(32),
    CONSTRAINT pk_review_notes PRIMARY KEY (id),
    CONSTRAINT uk_review_notes_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_notes_supersedes UNIQUE (merchant_id, supersedes_note_id),
    CONSTRAINT fk_review_notes_case FOREIGN KEY (merchant_id, manual_review_case_id)
        REFERENCES manual_review_cases (merchant_id, id),
    CONSTRAINT fk_review_notes_author FOREIGN KEY (merchant_id, author_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT fk_review_notes_supersedes FOREIGN KEY (merchant_id, supersedes_note_id)
        REFERENCES review_notes (merchant_id, id),
    CONSTRAINT fk_review_notes_redactor FOREIGN KEY (merchant_id, redacted_by_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_notes_assignment_version CHECK (author_assignment_version >= 0),
    CONSTRAINT ck_review_notes_plaintext_length CHECK (plaintext_code_points BETWEEN 1 AND 2000),
    CONSTRAINT ck_review_notes_retention CHECK (created_at < retention_expires_at),
    CONSTRAINT ck_review_notes_lifecycle CHECK (
        (lifecycle_status = 'ACTIVE'
            AND ciphertext IS NOT NULL AND nonce IS NOT NULL AND key_version IS NOT NULL
            AND redacted_at IS NULL AND redacted_by_operator_id IS NULL AND redaction_reason IS NULL)
        OR (lifecycle_status = 'REDACTED'
            AND ciphertext IS NULL AND nonce IS NULL AND key_version IS NULL
            AND redacted_at IS NOT NULL AND redacted_by_operator_id IS NOT NULL
            AND redaction_reason = 'MANAGER_REDACTION')
        OR (lifecycle_status = 'EXPIRED'
            AND ciphertext IS NULL AND nonce IS NULL AND key_version IS NULL
            AND redacted_at IS NOT NULL AND redacted_by_operator_id IS NULL
            AND redaction_reason = 'RETENTION_EXPIRED')
    ),
    CONSTRAINT ck_review_notes_nonce CHECK (nonce IS NULL OR LENGTH(nonce) = 16),
    CONSTRAINT ck_review_notes_key_version CHECK (
        key_version IS NULL OR LENGTH(key_version) BETWEEN 1 AND 32
    )
);

CREATE INDEX idx_review_notes_case
    ON review_notes (merchant_id, manual_review_case_id, created_at, id);
CREATE INDEX idx_review_notes_retention
    ON review_notes (lifecycle_status, retention_expires_at, id);

CREATE TABLE review_note_redaction_events (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    review_note_id UUID NOT NULL,
    manual_review_case_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_operator_id UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_note_redaction_events PRIMARY KEY (id),
    CONSTRAINT uk_review_note_redaction_events_merchant_id_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_review_note_redaction_events_note UNIQUE (merchant_id, review_note_id),
    CONSTRAINT fk_review_note_redaction_events_note FOREIGN KEY (merchant_id, review_note_id)
        REFERENCES review_notes (merchant_id, id),
    CONSTRAINT fk_review_note_redaction_events_case FOREIGN KEY (merchant_id, manual_review_case_id)
        REFERENCES manual_review_cases (merchant_id, id),
    CONSTRAINT fk_review_note_redaction_events_actor FOREIGN KEY (merchant_id, actor_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_review_note_redaction_events_shape CHECK (
        (event_type = 'MANAGER_REDACTION' AND actor_operator_id IS NOT NULL)
        OR (event_type = 'RETENTION_EXPIRED' AND actor_operator_id IS NULL)
    )
);

CREATE INDEX idx_review_note_redaction_events_case
    ON review_note_redaction_events (merchant_id, manual_review_case_id, created_at, id);
