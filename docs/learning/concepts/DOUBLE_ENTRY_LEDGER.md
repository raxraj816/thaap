# Double-Entry Ledger

## Mental Model

A ledger is the system's accounting memory. A double-entry ledger records every
money-moving event as at least one debit and one credit in the same transaction
group.

The rule is simple:

```text
sum(debits) == sum(credits)
```

If the group does not balance, the system is inventing or losing money on
paper.

## Why It Matters In Payments

Authorization means a processor says funds may be captured. Capture means the
platform now needs accounting truth. Without a ledger, `CAPTURED` is only a
status string. With a balanced ledger group, the platform can later explain
merchant balances, refunds, disputes, fees, payout settlement, and analyst
timelines from durable accounting records.

## THAAP V1 Capture Ledger

Chapter 15 records a full capture with two entries:

- debit `CUSTOMER_RECEIVABLE`
- credit `MERCHANT_PAYABLE`

Both entries share:

- `merchant_id`
- `transaction_group_id`
- source type `PAYMENT_INTENT_CAPTURE`
- source ID equal to the captured PaymentIntent ID
- amount and currency

The database also protects this chapter's source truth:

- capture ledger rows must reference a same-merchant PaymentIntent
- only the Chapter 15 capture account/direction pairs are valid
- the same capture source cannot post the same account row twice

## Invariants

- No capture ledger write is valid unless debits equal credits.
- Ledger entries are append-only; payment status changes do not edit old ledger
  rows.
- Ledger rows carry `merchant_id` and are only exposed through tenant scope.
- Ledger reads must be bounded; tenant isolation controls whose data is visible,
  while pagination controls how much history one request can pull.
- Capture, ledger entries, webhook delivery records, audit log, and idempotency
  completion commit or roll back together.

## Deferred

- Refund ledger groups.
- Fee revenue entries.
- Dispute and chargeback loss accounting.
- Payout settlement and reserves.
- Partial capture and partial refund accounting.
