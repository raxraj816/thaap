# Confirm Orchestration

Confirm is the first real-time payment-risk orchestration point in THAAP.

Before confirm, a PaymentIntent only says the merchant wants to collect money.
After confirm, THAAP has a concrete PaymentAttempt that can be routed through
fraud decisioning and processor authorization.

## Current Chapter 10 Behavior

`POST /payment_intents/{id}/confirm`:

- requires merchant tenant context from `X-Api-Key`
- requires `Idempotency-Key`
- loads PaymentIntent and PaymentMethod through merchant scope
- verifies the PaymentMethod belongs to the PaymentIntent customer
- creates one durable PaymentAttempt
- calls an in-memory decision-engine interface that returns `ALLOW` with
  `RULES_NOT_ENABLED`
- moves PaymentAttempt to `READY_FOR_PROCESSOR`
- moves PaymentIntent to `PROCESSING`

The confirm path does not persist `FraudDecision`, `FeatureSnapshot`, processor
authorization, or ledger entries yet.

## Why This Boundary Matters

The system needs a real-time shape before it has real rules, models, or
processor outcomes. The interface boundary lets Chapter 12 replace the stub
with durable fraud lineage without changing the merchant-facing confirm API.

Persisting a thin fraud row in Chapter 10 would be misleading because it would
lack score, risk level, reason codes, latency, feature snapshot, and freshness
proof.

## Invariants

- No confirm without tenant-scoped PaymentIntent ownership.
- No confirm without tenant-scoped PaymentMethod ownership.
- No confirm with a PaymentMethod for a different customer.
- No duplicate attempt for an idempotent replay.
- No second successful confirm while the PaymentIntent is `PROCESSING`.
- No durable fraud, processor, or ledger side effects in Chapter 10.

## Explain-Back Prompt

Explain why Chapter 10 adds a fraud decision interface but does not persist a
FraudDecision row.
