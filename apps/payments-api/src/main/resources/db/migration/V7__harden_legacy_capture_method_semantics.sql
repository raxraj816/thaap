-- V6 preserved already-authorized AUTOMATIC rows. Complete that compatibility
-- boundary for every other existing non-terminal PaymentIntent that could
-- still progress toward capture. A deployment must not turn a pre-existing
-- confirm, retry, action flow, or recovery into an unrequested money movement.
UPDATE payment_intents
SET capture_method = 'MANUAL',
    updated_at = CURRENT_TIMESTAMP
WHERE capture_method = 'AUTOMATIC'
  AND status IN (
      'REQUIRES_PAYMENT_METHOD',
      'REQUIRES_CONFIRMATION',
      'REQUIRES_FRAUD_CHECK',
      'REQUIRES_ACTION',
      'PROCESSING',
      'AUTHORIZED',
      'REQUIRES_CAPTURE'
  );
