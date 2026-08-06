CREATE TABLE payment_investigation_saved_views (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    owner_operator_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    filter_schema_version VARCHAR(32) NOT NULL,
    canonical_filter_json TEXT NOT NULL,
    sort_code VARCHAR(32) NOT NULL,
    visible_columns_json VARCHAR(1024) NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_payment_investigation_saved_views PRIMARY KEY (id),
    CONSTRAINT uk_payment_investigation_saved_views_id UNIQUE (merchant_id, id),
    CONSTRAINT uk_payment_investigation_saved_views_owner_name
        UNIQUE (merchant_id, owner_operator_id, name),
    CONSTRAINT fk_payment_investigation_saved_views_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_payment_investigation_saved_views_owner
        FOREIGN KEY (merchant_id, owner_operator_id)
        REFERENCES risk_operators (merchant_id, id),
    CONSTRAINT ck_payment_investigation_saved_views_name CHECK (
        CHAR_LENGTH(name) BETWEEN 1 AND 80 AND name = TRIM(name)),
    CONSTRAINT ck_payment_investigation_saved_views_visibility CHECK (
        visibility IN ('PRIVATE', 'MERCHANT_SHARED')),
    CONSTRAINT ck_payment_investigation_saved_views_schema CHECK (
        CHAR_LENGTH(filter_schema_version) BETWEEN 1 AND 32),
    CONSTRAINT ck_payment_investigation_saved_views_sort CHECK (
        sort_code IN ('CREATED_AT_ASC', 'CREATED_AT_DESC')),
    CONSTRAINT ck_payment_investigation_saved_views_digest CHECK (
        CHAR_LENGTH(definition_digest) = 64),
    CONSTRAINT ck_payment_investigation_saved_views_version CHECK (lifecycle_version >= 1),
    CONSTRAINT ck_payment_investigation_saved_views_lifecycle CHECK (
        (lifecycle_status = 'ACTIVE' AND disabled_at IS NULL)
        OR (lifecycle_status = 'DISABLED' AND disabled_at IS NOT NULL)),
    CONSTRAINT ck_payment_investigation_saved_views_time CHECK (
        created_at <= updated_at AND (disabled_at IS NULL OR disabled_at = updated_at))
);

CREATE INDEX idx_payment_investigation_saved_views_visible
    ON payment_investigation_saved_views
        (merchant_id, visibility, lifecycle_status, updated_at DESC, id);

