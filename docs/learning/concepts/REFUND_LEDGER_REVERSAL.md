# Refund Ledger Reversal

## First-Principles Model

A refund is not a deleted capture. Capture and refund are two append-only
money events:

```text
authorization -> capture -> refund
```

Authorization records permission to charge. Capture records that the merchant
finalized the charge. Refund records a later instruction to reverse that
captured amount. Preserving all three facts lets operations, fraud analytics,
and future labels explain what was known and what happened afterward.

## Chapter 18 Contract

THAAP V1 supports one synchronous full refund per captured PaymentIntent.
Before recording it, the API requires:

- tenant-scoped `CAPTURED` PaymentIntent state
- the approved Authorization that belongs to the same PaymentAttempt and
  PaymentIntent
- exactly two matching capture ledger entries in one balanced transaction group
- refund amount and currency equal to captured truth

Status alone is insufficient. A `CAPTURED` string without authorization and
ledger evidence is not defensible money truth.

## Accounting

Capture posts:

```text
Debit  CUSTOMER_RECEIVABLE
Credit MERCHANT_PAYABLE
```

Refund exactly reverses those accounts:

```text
Debit  MERCHANT_PAYABLE
Credit CUSTOMER_RECEIVABLE
```

Each operation has its own transaction group and must independently balance.
Capture rows retain the PaymentIntent as their source. Refund rows use the
durable Refund ID as their source while also retaining the original
PaymentIntent ID.

## Transaction Boundary

The following facts commit or roll back together:

- Refund row
- two refund ledger entries
- PaymentIntent transition to `REFUNDED`
- `refund.created` webhook delivery records
- safe refund audit log
- completed idempotency response

The PaymentIntent row lock serializes competing refunds. The one-refund
database constraint is a second defense if service locking regresses.

## Simulator Honesty

Refund status is `RECORDED`. It means the local simulator durably recorded the
reversal. It does not claim a card network or acquirer returned funds.
Asynchronous network submission, pending/succeeded/failed processor states,
partial refund accounting, fees, and dispute offsets require later product
boundaries.

## Migration Safety

The earlier thin endpoint could leave `REFUNDED` PaymentIntents without a
durable Refund ID or reversal ledger entries. V8 refuses to invent those facts.
If such a row exists in the current local-only environment, migration fails and
the environment must be reset or explicitly remediated.

This is safer than generating plausible-looking historical accounting during
deployment.

## Deferred Capability Ownership

Chapter 18 has no accepted implementation debt. Its exclusions are named
product slices with entry and proof gates:

- **Partial Money Accounting** (Payments Core) owns a durable Capture object,
  multiple Refunds, cumulative refundable-balance locking, partial refund
  accounting, and fee reversal. It starts when partial amounts or fee postings
  enter the product contract and must prove aggregate amount safety, rounding,
  balanced entries, and PostgreSQL concurrency.
- **Refund Operations And Review Actions** (Phase 4; Risk Operations and
  Payments Core) owns refund reasons and review-triggered approval. It starts
  when ManualReviewCase can initiate payment actions and must prove policy,
  tenant, idempotency, audit, and immutable decision-time lineage.
- **Dispute Offset Accounting** (Phase 4; Payments Core and Risk Operations)
  owns ordered refund/chargeback interaction. It starts with chargeback ledger
  work and must prove no customer over-credit, merchant double-debit, or
  race-dependent accounting.
- **Real PSP Refund Execution** (post-V1; Payments Core with
  Security/Compliance) owns provider submission, asynchronous status,
  callbacks, retries, and reconciliation. It starts only after PSP selection
  and security/compliance approval and must prove that provider success is
  distinct from local `RECORDED` truth.

These are not debt while their triggering product contracts do not exist. If a
trigger is accepted without its owner slice, the unmet prerequisite must be
implemented immediately or registered as debt before dependent work proceeds.

## Explain-Back

1. Why is a refund an append-only fact rather than deletion of capture?
2. Why is `CAPTURED` status insufficient without capture ledger evidence?
3. Why do refund entries use the Refund as source while retaining the
   PaymentIntent ID?
4. Why does `RECORDED` avoid claiming network success?
5. Why must migration fail instead of backfilling unverifiable refund history?
