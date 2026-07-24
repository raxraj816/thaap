# Chapter 32 Closure Dossier

- Chapter: Chapter 32 — Chargebacks, Dispute Offsets, And Evidence Packets
- Chapter number: 32
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_32_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created after Chapter 31 closure and before Chapter 32
production code or migrations. It now recommends `MERGE` because every frozen
amount, offset, ledger, label, tenant, idempotency, evidence, deadline,
concurrency, migration, privacy, telemetry, and product-language promise passed
at its real boundary.

## Observable promises

- V1 chargebacks are tenant-scoped merchant-ingested simulator records, never
  represented as independently verified acquirer or card-network truth.
- Liability is capped by remaining captured value. Full-refund-first produces a
  visible zero offset; chargeback-first owns the remaining value and blocks a
  later full refund.
- Fraud and non-fraud reasons are distinct; only closed fraud reasons create an
  immutable chargeback-sourced FRAUD label with honest availability time.
- Every non-zero allocation has one balanced, fully linked ledger group; every
  zero allocation has none.
- Evidence packets are bounded allowlisted structured DRAFT artifacts created by
  the due-time boundary, not uploaded documents or a representment claim.

## Failure inventory

The matrix freezes payment state/amount/currency ceilings, reason taxonomy,
source attestation, duplicate and out-of-order external events, tenant and
credential substitutions, refund/chargeback lock ordering, zero/non-zero ledger
groups, label occurrence/availability time, evidence grammar/privacy/deadline,
atomic rollback, audit/webhook/idempotency, migration, telemetry, and premature
product-language claims.

## Boundary family evidence

`ChargebackAndDisputeContractTests` runs on PostgreSQL and covers all eight
reasons, captured/refunded/disputed cohorts, one/ceiling/over/zero amounts,
currency, automatic-capture eligibility, strict JSON and machine keys, replay,
external dedup, foreign reads, append-only/semantic SQL, telemetry tags, and
injected rollback. Fixed-clock deadline proof covers at/after while the public
contract covers before/after. Deterministic uncommitted lock holders prove both
refund/chargeback orders and identical external-event convergence.

## Enforcement boundaries

The merchant credential interceptor and strict DTO/enums own API authorization
and grammar. Composite keys, V44 semantic triggers, append-only triggers, and
tenant-scoped external identity own durable lineage. PaymentIntent locking owns
money serialization; merchant locking owns source-event serialization. The
existing balanced `LedgerService`, chargeback-qualified FraudLabel constraint,
local audit/webhook transaction, fenced idempotency, and after-completion
metrics own their respective commit claims.

## Exact proof

The post-implementation regression gate passes 587 Java tests with zero
failures/errors/skips and 100 Python tests with zero failures. Chapter 32 adds
PostgreSQL reason, amount, state, tenant, grammar, semantic-trigger, rollback,
refund race, duplicate race, and fixed-clock deadline proof without weakening
the Chapters 1-31 suites.

## Migration evidence

Fresh V1-to-V44 plus Hibernate validation passes. The retained V42-to-V44
cohort preserves review/decision history and the sole pre-existing webhook
subscription, creates empty chargeback/allocation/evidence tables, installs six
new semantic/history triggers, and creates no label or subscription truth.
The current binary also started against the real retained local V42 volume and
migrated it to V44 before the live demo.

## Demo evidence

`scripts/demo-chargeback-lifecycle.py` passed against the retained local
PostgreSQL volume. Public APIs and read-only SQL reconciled captured fraud as
`DISPUTED|2 chargeback ledger rows|1 label` and refund-first non-fraud as
`REFUNDED|0 chargeback ledger rows|0 labels`. It also proved byte-exact replay,
independent external dedup, `SIMULATOR_MERCHANT_INGESTED`, and a bounded
pre-deadline DRAFT packet.

## Operational signals

`thaap.dispute.chargeback.ingestions` exposes only reason class, allocation,
result, and transaction. `thaap.dispute.evidence.packets` exposes only result
and transaction. Tests enumerate exact tag keys and prove deadline-expired
rollback visibility. API failure logs remain the existing bounded request ID,
path, and exception type; identifiers, evidence values, credentials, and keys
are excluded from metric tags.

## Known debt

None accepted. Any real deferral requires the next immutable `TD-NNNN` with a
named owner slice, DRI, trigger/deadline, and done evidence. P0 work cannot be
deferred.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 32 must reuse strict Jackson/Bean Validation, Spring
transactions, PostgreSQL locking/constraints, the existing `LedgerService`,
FraudLabel, audit, webhook, and idempotency components. It must not introduce an
external dispute connector, document store, queue, custom protocol, or general
evidence JSON engine.

## Escaped-review assessment

`COMPLETE`. No prior Chapter 32 completion claim existed. Hostile review found
and fixed machine-key grammar, unsupported ledger enum drift,
automatic-capture eligibility, exact status/allocation coupling, one allocated
dispute, reason-to-type mapping, authorization-derived evidence enforcement,
and missing retained/concurrency/rollback proof before closure. No `ER-NNNN`
entry is required.

## Source-of-truth reconciliation

Chapters 26–32 are verified complete. Roadmap, plan, READMEs, learning notes,
matrix, and dossier advance Chapter 33 as the active slice. Phase 4 remains in
progress until Chapters 33-35 close.

## Merge recommendation

`MERGE`. Every matrix row is `PASS`; real PostgreSQL forced ordering, amount/
reason/tenant/privacy/deadline partitions, balanced ledger and injected
rollback, fresh/retained migration, public demo, traceability, and separate
hostile review pass with no blocking debt or escaped finding.
