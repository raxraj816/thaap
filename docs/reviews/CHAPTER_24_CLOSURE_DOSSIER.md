# Chapter 24 Closure Dossier

- Chapter: Chapter 24 — Review Reads And Merchant Cancellation
- Chapter number: 24
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_24_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

Merchants can list and inspect their own open/canceled fraud review cases using
bounded stable pagination. The existing idempotent cancel API atomically
cancels review-pending intent, attempt, and case while preserving the original
decision and creating no processor or money truth. No analyst resolution is
claimed.

## Failure inventory

The frozen inventory covers missing/foreign/malformed cursors and IDs, limits,
stable ordering, confirm/cancel and repeated-cancel ordering, rollback,
decision/reason immutability, money leakage, sensitive outputs, bounded signals,
and accidental Phase 4 scope expansion.

## Boundary family evidence

| Family | Partitions and real-boundary evidence |
| --- | --- |
| Reads | Empty/non-empty pages, 0/1/100/101 limits, owned/foreign cursor, detail missing-equivalence |
| Cancellation | Success, exact same-key replay, distinct-key conflict, invalid state, no void/ledger/processor rows |
| Ordering | PostgreSQL forces confirm-first then cancel and cancel-first then confirm at actual case/intent locks |
| Rollback | Case persistence and surrounding confirm/audit/webhook/idempotency failures retain all-or-nothing truth |
| Lineage | Original REVIEW action/reasons/features/results remain; V23 rejects mismatched attachment and V24 rejects rewrite/delete/reopen |
| Privacy/tenant | Dedicated DTOs and merchant-scoped queries exclude customer/method/feature/credential data |

## Enforcement boundaries

The review controller uses status-filtered merchant repositories, limit 1–100,
`(created_at,id)` order, and an owned cursor lookup. Cancellation owns
idempotency, locks PaymentIntent before ManualReviewCase, updates narrow state
methods, and records review audit/webhook/metrics in the same transaction.
Composite V23 keys bind the case to exact tenant/payment/decision/reason/origin;
V24 makes that history append-only with only `OPEN -> CANCELED` permitted.

## Exact proof

`PaymentIntentConfirmContractTests`, `MerchantTenantIsolationTests`, rollback
tests, metric tests, migration constraints, and forced PostgreSQL confirm/cancel
interleavings pass. PostgreSQL also executes the after-cursor query directly.
Integrated totals are 324 backend and 76 repository Python tests. Equal-time
PostgreSQL pagination and every public cursor/limit/method partition are named
assertions rather than inferred coverage.

## Migration evidence

V20 creates the case schema; V23 forward-binds existing cases to exact REVIEW
lineage, and V24 protects identity and terminal history. Fresh and realistic upgrade tests pass on
PostgreSQL and Hibernate validation accepts the entity mapping.

## Demo evidence

The public demo creates the third-attempt review, reads it through the bounded
API, cancels through PaymentIntent, rereads `CANCELED/MERCHANT_CANCELED`, and
reconciles one case with unchanged fraud evidence and zero non-allow processor
work.

## Operational signals

Opened and canceled counters use only traffic origin. Decision and cancellation
rollback counters use closed stage/cause/status/origin dimensions after the
transaction outcome. Audit and webhook records use closed event/resource types
and safe allowlisted state.

## Known debt

No Chapter 24 debt is accepted; analyst assignment/resolution/labels are named
Phase 4 product scope, not an unfinished Phase 3 promise.

## Custom infrastructure decision

`NOT_APPLICABLE`.

## Escaped-review assessment

ER-0049 and ER-0050 escaped the earlier completion claim and are resolved with
transaction-outcome telemetry, a real cancellation/webhook rollback contract,
complete API cursor/limit/method partitions, and equal-timestamp PostgreSQL
keyset proof. Their process corrections now require exact metric and matrix-
partition traceability.

## Source-of-truth reconciliation

All sources describe `/fraud/reviews` as Phase 3 work-item truth and retain
assignment, analyst resolution, notes, labels, and payment actions for Phase 4.

## Merge recommendation

`MERGE`. Tenant-safe reads, stable pagination, lock ordering, cancellation,
historical lineage, zero-money behavior, and public reconciliation all pass.
