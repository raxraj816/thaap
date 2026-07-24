# Review Resolution And Label Lineage

## Mental Model

A review case is a work item. A resolution is an authorized human judgment. A
payment action is a state or money mutation. A fraud label is evidence for
future analysis or ML. They are related, but they are not the same fact:

```text
review evidence
  -> authorized resolution
       -> optional payment action
       -> optional fraud label
```

Collapsing these facts into one mutable `status` creates false truth. A refund
may be customer service rather than fraud. An analyst may cancel an uncertain
payment without enough evidence to label it. An approved review must preserve
that the original engine chose REVIEW rather than pretending it chose ALLOW.

## Why Actor Identity Comes First

The database cannot defend an analyst action if “analyst” is only a request
header supplied by the caller. THAAP therefore needs a merchant-owned
RiskOperator identity with a credential, role, active/disabled lifecycle, and
immutable audit reference.

Authentication answers “which principal presented a valid secret?”
Authorization answers “may this active principal perform this action on this
merchant-owned case in its current state?” Both checks matter. Authorization
must be revalidated inside the mutation transaction because the operator can be
disabled or the case can be reassigned after an early web-filter check.

## Assignment Is Not Resolution

Assignment establishes current work ownership. It does not authorize every
action forever and it is not historical evidence by itself. Phase 4 uses:

- an append-only assignment event for each assign/release/reassign action
- a current assignment projection on ManualReviewCase for efficient reads
- a version checked under row lock so stale release cannot clear a newer claim
- one immutable resolution row for the terminal judgment

This makes queue state fast to query while retaining exact history.

## Outcome Is Not Label

Pre-authorization outcomes can approve or cancel a payment. Post-authorization
outcomes can take no action or request a refund. Labels answer a different
question: was this payment later confirmed fraudulent or legitimate?

Phase 4 therefore carries a separate explicit classification and creates labels
only when that classification is confirmed:

- analyst approval or no-action plus CONFIRMED_LEGITIMATE -> LEGITIMATE
- cancel-and-report-fraud -> FRAUD with required fraud type
- refund-and-report-fraud -> FRAUD with required fraud type
- fraud-coded chargeback -> FRAUD with chargeback source

INCONCLUSIVE, plain cancel, and plain refund do not manufacture labels.

## Two Times Prevent ML Leakage

A label needs at least two timestamps:

- `evidence_occurred_at`: when the underlying event happened
- `available_at`: when THAAP could first have known the label

A chargeback may refer to a transaction from weeks earlier, but a model trained
as if that fraud label was known at authorization time sees the future. Dataset
builders must use `available_at` for time-safe joins.

## Append-Only Correction

Human and dispute evidence can conflict. Deleting or updating the older label
would erase what the system knew at the time. A correction appends a new label
with same-payment supersession lineage. Dataset policy may select the latest
eligible label, while audit and replay retain the complete history.

## Payment Invariants

- Analyst approval preserves the original REVIEW FraudDecision.
- Cancel outcomes create no processor, capture, refund, or ledger truth.
- Refund outcomes are impossible before capture.
- Merchant cancellation and analyst resolution have one lock-serialized winner.
- Duplicate retries cannot create a second resolution, processor call, refund,
  label, audit, or webhook.
- A label never rewrites payment, processor, feature, rule, or decision history.

## Security And Privacy Invariants

- Operator credentials are distinct from merchant credentials and stored only
  as protected secret material.
- Cross-merchant identifiers remain missing-equivalent.
- Phase 4 annotations use closed codes; free-form notes are deferred until
  encryption, retention, redaction, export, and access-audit policy exists.
- Metrics use role/outcome/cause codes, never operator or resource IDs.
- Actor authorization is checked under the same locked state that is mutated.

## Tests As Proof

Unit tests can prove the role/outcome table, but they cannot prove concurrent
ownership. Real proof also needs:

- PostgreSQL forced waits for analyst/analyst and analyst/merchant races
- actual constraint failures at resolution and label flush boundaries
- exact rollback cardinalities across payment, audit, webhook, and idempotency
- direct-SQL tenant and copied-semantic substitution rejection
- fresh and retained migration evidence
- a public API demo reconciled with read-only durable state

## Design-Review Explanation

The Staff-level claim is not “analysts can click approve.” It is that a
tenant-owned, role-authorized actor resolves one locked case exactly once; the
resolution, optional payment action, optional label, audit, webhook, and retry
response agree; and every historical decision and label remains immutable and
time-safe for future training.
