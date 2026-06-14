# PaymentAttempt From First Principles

This note explains `PaymentAttempt` in THAAP.

## The Core Idea

A `PaymentIntent` is the payment container. A `PaymentAttempt` is one concrete
try to confirm that container with a specific payment method.

In plain language:

```text
PaymentIntent = what the merchant wants to collect.
PaymentAttempt = this specific try to authorize or route that payment.
```

## Why It Matters In Payments And Fraud

Fraud systems need a stable event to score, explain, review, and later label.
That event is not the broad PaymentIntent. It is the attempt.

A single PaymentIntent can have more than one attempt:

- first card fails
- customer tries another card
- 3DS is requested and retried
- processor declines and the merchant retries later

If fraud decisions were attached only to PaymentIntent, the platform would lose
which payment method, retry number, processor response, or 3DS state produced a
decision. That would corrupt model training data and analyst explanations.

## Current THAAP Behavior

`POST /payment_intents/{id}/confirm` creates a `PaymentAttempt` when:

- the PaymentIntent belongs to the authenticated merchant
- the PaymentIntent is in `REQUIRES_CONFIRMATION`
- the PaymentMethod belongs to the same merchant
- the PaymentMethod belongs to the same customer as the PaymentIntent
- the request has an `Idempotency-Key`

The attempt stores:

- merchant ID
- PaymentIntent ID
- PaymentMethod ID
- idempotency key
- status
- attempt number
- nullable fraud decision ID
- nullable processor authorization ID
- nullable 3DS status
- timestamps

Chapter 9 intentionally leaves fraud, processor, 3DS, and final status mapping
empty. The point is to create lineage before creating outcomes.

## Invariants

- No PaymentAttempt without tenant-scoped PaymentIntent ownership.
- No PaymentAttempt without tenant-scoped PaymentMethod ownership.
- No PaymentAttempt when the PaymentMethod belongs to a different customer.
- No PaymentAttempt when the PaymentIntent is not confirmable.
- Attempt numbers increase monotonically per PaymentIntent.
- Idempotent confirm replay returns the original attempt and does not create a
  duplicate.

## Tests And Proof

`PaymentIntentConfirmContractTests` prove:

- confirm creates a durable attempt with nullable fraud/processor/3DS fields
- the attempt can be fetched through `GET /payment_attempts/{id}`
- idempotent replay does not create duplicate attempts or webhook deliveries
- separate confirm keys create attempt numbers `1`, then `2`
- concurrent confirm keys create distinct attempt numbers because confirm locks
  the PaymentIntent row before numbering
- invalid status, cross-merchant PaymentIntent, cross-merchant PaymentMethod,
  and wrong-customer PaymentMethod requests create no attempt

## What Remains To Build

- FraudDecision and FeatureSnapshot creation for every attempt.
- Processor simulator authorization records linked to attempts.
- PaymentIntent and PaymentAttempt final status transitions after processor and
  fraud outcomes.
- Capture/refund/ledger behavior after authorization exists.
