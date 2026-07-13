DELETE FROM webhook_endpoint_event_subscriptions new_subscription
WHERE new_subscription.event_type IN (
        'payment_intent.blocked',
        'payment_intent.requires_action',
        'fraud_review.opened',
        'fraud_review.canceled'
    )
  AND EXISTS (
      SELECT 1
      FROM webhook_endpoint_event_subscriptions legacy_subscription
      WHERE legacy_subscription.merchant_id = new_subscription.merchant_id
        AND legacy_subscription.webhook_endpoint_id = new_subscription.webhook_endpoint_id
        AND legacy_subscription.event_type IN (
            'payment_intent.created',
            'payment_attempt.created',
            'payment_intent.captured',
            'payment_intent.canceled',
            'refund.created'
        )
        AND legacy_subscription.created_at <> new_subscription.created_at
  );
