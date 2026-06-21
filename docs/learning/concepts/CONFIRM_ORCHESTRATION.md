# Confirm Orchestration

Confirm is the first real-time payment-risk orchestration point in THAAP.

Before confirm, a PaymentIntent only says the merchant wants to collect money.
After confirm, THAAP has a concrete PaymentAttempt that can be routed through
fraud decisioning and processor authorization.

## Current Chapter 12 Behavior

`POST /payment_intents/{id}/confirm`:

- requires merchant tenant context from `X-Api-Key`
- requires `Idempotency-Key`
- loads PaymentIntent and PaymentMethod through merchant scope
- verifies the PaymentMethod belongs to the PaymentIntent customer
- creates one durable PaymentAttempt
- calls an in-memory decision-engine interface that returns `ALLOW`, score
  `0.00`, risk level `LOW`, ruleset version `rules_stub_v1`, and
  `RULES_NOT_ENABLED`
- persists one `FraudDecision`
- persists one immutable `FeatureSnapshot`
- attaches the fraud decision to the PaymentAttempt
- moves PaymentAttempt to `READY_FOR_PROCESSOR`
- moves PaymentIntent to `PROCESSING`

The confirm path now persists fraud lineage and processor authorization. It
still does not persist capture, ledger, processor-event, or audit-log side
effects.

## Why This Boundary Matters

The system needs a real-time shape that is fast enough for payment confirm and
honest enough for later fraud review. Chapter 12 keeps the decision stub simple
but no longer thin: it records score, risk level, reason codes, latency, feature
snapshot, and freshness proof.

## Invariants

- No confirm without tenant-scoped PaymentIntent ownership.
- No confirm without tenant-scoped PaymentMethod ownership.
- No confirm with a PaymentMethod for a different customer.
- No duplicate attempt for an idempotent replay.
- No second successful confirm while the PaymentIntent is `PROCESSING`.
- Exactly one durable fraud decision and one feature snapshot per successful
  confirm attempt.
- No durable fraud lineage when confirm fails before attempt creation or the
  confirm transaction rolls back.
- No ledger side effects during confirm.

## Explain-Back Prompt

Explain why Chapter 12 persists honest stub lineage instead of fake rules or
fake model lineage.
