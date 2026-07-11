ALTER TABLE webhook_delivery_attempts
    ADD COLUMN IF NOT EXISTS dispatch_started_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE webhook_delivery_attempts
    ALTER COLUMN http_request_sent DROP NOT NULL;

UPDATE webhook_delivery_attempts
SET dispatch_started_at = started_at
WHERE dispatch_started_at IS NULL
  AND http_request_sent IS TRUE;

ALTER TABLE webhook_delivery_attempts
    DROP CONSTRAINT IF EXISTS ck_webhook_delivery_attempts_dispatch_truth;

ALTER TABLE webhook_delivery_attempts
    ADD CONSTRAINT ck_webhook_delivery_attempts_dispatch_truth CHECK (
        (
            (
                outcome = 'IN_PROGRESS'
                AND (
                    (
                        dispatch_started_at IS NULL
                        AND http_request_sent IS FALSE
                    )
                    OR
                    (
                        dispatch_started_at IS NOT NULL
                        AND http_request_sent IS NULL
                    )
                )
            )
            OR
            (
                outcome = 'OUTCOME_UNKNOWN'
                AND dispatch_started_at IS NOT NULL
                AND http_request_sent IS NULL
            )
            OR
            (
                outcome NOT IN ('IN_PROGRESS', 'OUTCOME_UNKNOWN')
                AND http_request_sent IS NOT NULL
            )
        )
        AND (
            http_request_sent IS NULL
            OR http_request_sent IS FALSE
            OR dispatch_started_at IS NOT NULL
        )
    );
