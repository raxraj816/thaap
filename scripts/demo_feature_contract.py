"""Exact current feature key set for healthy, no-risk-context demo fixtures."""
BASE_FEATURE_CODES = frozenset({
    "card.country", "card.funding", "merchant.risk_tier", "payment.amount_minor",
    "payment.attempt_number", "payment.capture_method", "payment.currency",
    "risk.context_present", "traffic.origin", "velocity.card_attempts_10m",
    "velocity.customer_attempts_1h", "velocity.merchant_attempts_1m",
})
