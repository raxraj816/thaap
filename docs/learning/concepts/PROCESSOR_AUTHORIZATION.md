# Processor Authorization From First Principles

This note explains processor authorization as we learn it while building THAAP.
It is a learning note, not the implementation source of truth.

## The Core Idea

Authorization is the processor or issuer saying whether a payment may reserve
funds. It is not settlement. In plain language: authorization answers "can this
card payment proceed right now?"

## The Problem It Solves

Without a durable authorization record, later capture, refund, dispute, fraud
label, and analyst workflows would have to guess what the processor said. A
string on a payment attempt is not enough because the platform also needs the
response code, verification checks, requested amount, authorized amount,
currency, processor reference, and timestamp.

## Why It Matters In Payments And Fraud

Payment systems separate authorization from capture so money movement can be
controlled. Fraud systems need authorization truth because issuer declines,
suspected-fraud declines, CVC failures, AVS failures, and 3DS requirements all
become signals for future reviews, card-testing detection, disputes, and ML
training.

## How THAAP Uses It

Chapter 11 adds a deterministic `ProcessorAdapter` and a durable
`authorizations` table. Chapter 12 now makes confirm persist honest fraud
lineage before the processor call. Confirm creates a PaymentAttempt, records the
stub `ALLOW` decision and `features_v1` snapshot, calls the simulator, persists
an authorization row, and maps the result to PaymentAttempt and PaymentIntent
state.

Approved authorizations move the PaymentAttempt to `AUTHORIZED`. An approved
`MANUAL` PaymentIntent moves to `REQUIRES_CAPTURE`; an approved `AUTOMATIC`
PaymentIntent atomically moves to `CAPTURED` with balanced ledger, audit, and
webhook truth. Retryable declines move the PaymentAttempt to `DECLINED` and the
PaymentIntent to `REQUIRES_PAYMENT_METHOD`. Processor 3DS-required results move
both objects to action-required states.

`requested_amount` is what THAAP asked the processor to authorize.
`authorized_amount` is what the processor actually allowed to be reserved.
For Chapter 11, approved simulator outcomes authorize the requested amount;
declined and 3DS-required outcomes authorize `0`.

## Design Tradeoffs

THAAP uses a simulator before a real PSP because V1 is about learning and
controlling payment-risk mechanics without real money movement or compliance
scope. The simulator is behind an adapter so a real PSP can be introduced later
without rewriting confirm orchestration.

The simulator uses safe stored card metadata, not raw PAN. That preserves the
tokenized boundary created during PaymentMethod creation. Its in-memory catalog
uses an explicit `card_bin:card_last4` key; that makes the test simulator
deterministic without teaching the production system to depend on raw card
numbers.

## Chapter 20 Simulator Fixture Contract

These values are simulator-only test fixtures. Never submit a real card number
to THAAP. PaymentMethod creation accepts each value transiently, stores only
safe tokenized metadata, and the processor catalog resolves the stored
`card_bin:card_last4` pair.

| Test card | Fixture | Processor response | Verification / 3DS result | PaymentAttempt | Manual PaymentIntent |
| --- | --- | --- | --- | --- | --- |
| `4999990000000001` | `approved` | `APPROVED` | CVC/AVS pass; 3DS not required | `AUTHORIZED` | `REQUIRES_CAPTURE` |
| `4999990000000002` | `issuer_decline` | `ISSUER_DECLINE` | CVC/AVS pass | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000003` | `insufficient_funds` | `INSUFFICIENT_FUNDS` | CVC/AVS pass | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000004` | `suspected_fraud` | `SUSPECTED_FRAUD` | CVC/AVS pass | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000005` | `cvc_failure` | `CVC_FAILURE` | CVC fails | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000006` | `avs_line1_failure` | `AVS_LINE1_FAILURE` | AVS line1 fails | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000007` | `avs_postal_failure` | `AVS_POSTAL_FAILURE` | AVS postal fails | `DECLINED` | `REQUIRES_PAYMENT_METHOD` |
| `4999990000000008` | `three_ds_required` | `THREE_DS_REQUIRED` | 3DS required | `REQUIRES_ACTION` | `REQUIRES_ACTION` |
| Any other valid simulator card, including `4242424242424242` | `unknown_default_approval` | `APPROVED` | CVC/AVS pass; 3DS not required | `AUTHORIZED` | `REQUIRES_CAPTURE` |

The `SUSPECTED_FRAUD` response is issuer/processor truth, not a THAAP fraud
decision. Until Phase 3 activates real rules, the independently persisted
FraudDecision remains `ALLOW` with `RULES_NOT_ENABLED` for every fixture.

## Failure Modes

- Retrying confirm could create duplicate authorizations if idempotency fails.
- Treating authorization as capture could move money too early.
- Reusing raw card data downstream would expand PCI-style risk.
- Losing processor response codes would weaken dispute and fraud analysis.
- Letting a declined payment stay terminal would block legitimate retry flows.
- Persisting a non-zero authorized amount for a declined or action-required
  outcome would create false money truth for capture, fraud analysis, and ML
  labels.
- Calling the processor after a fraud `BLOCK` or `REVIEW` decision would create
  network side effects for a payment THAAP already decided should not proceed.
- Leaving a PaymentIntent in `PROCESSING` after processor failure would strand
  a merchant-facing payment in a non-actionable state.

## Tests And Proof

Chapter 11 tests prove approved, declined, verification-failure, and
3DS-required outcomes. They also prove idempotent replay does not create another
authorization, post-approval confirm is rejected, retryable decline permits a
second attempt, tenant boundaries hold, non-`ALLOW` fraud decisions do not call
the processor, processor exceptions roll back the confirm transaction, unknown
simulator cards default to approval. Chapter 12 extends that proof by requiring
one FraudDecision and one FeatureSnapshot per successful confirm attempt.

## What Remains To Build

The canonical Phase 2 processor, capture, cancel/void, full-refund, ledger,
audit, and webhook boundaries are complete. Real fraud rules, 3DS challenge
completion, disputes, partial money accounting, real PSP integration, and
model scoring remain owned by later phases.
