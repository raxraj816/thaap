# Idempotency From First Principles

This note explains idempotency in THAAP.

## The Core Idea

Idempotency means a client can safely retry the same operation without duplicating side effects.

In plain language:

```text
If the same request is sent twice, do the work once and return the same answer.
```

## The Problem It Solves

Networks are unreliable.

A merchant might send:

```text
POST /payment_intents
```

THAAP might create the payment successfully, but the response could be lost before the merchant receives it.

Without idempotency, the merchant retries and accidentally creates a second payment.

With idempotency, the retry returns the original result.

## Why It Matters In Payments And Fraud

Duplicate side effects are dangerous in payments.

Bad outcomes include:

- duplicate PaymentIntents
- duplicate captures
- duplicate refunds
- repeated webhook records
- inconsistent fraud audit trails
- incorrect ledger entries

Idempotency is one of the core safety mechanisms that turns unreliable networks into safe payment APIs.

## How THAAP Uses It Today

THAAP requires `Idempotency-Key` for mutating payment APIs.

The current idempotency model stores:

- merchant ID
- idempotency key
- operation name
- request hash
- status
- response status
- response body
- expiration time

Rules:

- same merchant + same key + same request returns the stored response
- same merchant + same key + different operation or request returns conflict
- same merchant + same key + in-progress request returns conflict before the
  duplicate mutation runs
- different merchants can reuse the same key safely

## Design Tradeoffs

Idempotency keys are scoped by merchant because THAAP is multi-tenant.

Global idempotency keys would create unnecessary collisions across merchants.

Request hashing protects against accidental key reuse with a different body.
Operation binding protects same-body mutations such as empty capture and cancel
requests from accidentally replaying the wrong response.

THAAP now reserves an `IN_PROGRESS` row before running the mutation, completes
the row only after the mutation response is durable, and removes the reservation
when the mutation fails. That makes retry storms deterministic without caching
retryable failures as successful work.

## Failure Modes

Idempotency can fail if:

- a mutation endpoint forgets to require a key
- the request hash is unstable
- two identical requests arrive concurrently
- the response is stored before the transaction is truly safe
- old keys never expire
- keys are not tenant-scoped
- an `IN_PROGRESS` record is abandoned after a process crash

## Tests And Proof

Current tenant isolation tests verify:

- same key and same request replays within a merchant
- same key and different request conflicts within a merchant
- different merchants can use the same key independently

Current idempotency service tests verify:

- completed reservations replay the original response
- changed operation conflicts even when the body matches
- in-progress duplicates are rejected before running the mutation
- failed mutations release the reservation and can be retried
- in-progress records store operation, request hash, status, and expiration
  without fake response payloads

## What Remains To Build

- Cleanup or takeover policy for expired in-progress keys after process crash.
- Idempotency tests for capture, cancel, refund, and payment method creation.
