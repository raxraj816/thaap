# Chapter 22 Closure Dossier

- Chapter: Chapter 22 — Feature Contract And Point-In-Time Lineage
- Chapter number: 22
- Status: IN_PROGRESS
- Invariant matrix: docs/reviews/CHAPTER_22_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: DO_NOT_MERGE

This dossier is created before implementation so Chapter 22's eventual release
argument cannot be reconstructed from whichever tests happen to exist. It must
remain `DO_NOT_MERGE` until every planned proof is implemented and verified.

## Observable promises

- Every new attempt has complete typed point-in-time feature lineage.
- Chapter 22 preserves the existing allow-only payment and processor behavior.
- Snapshot, FeatureValues, definitions, decision, attempt, merchant, timestamps,
  and traffic origin reconcile exactly.
- Historical stub decisions remain readable and unchanged.
- Feature surfaces exclude sensitive and merchant-controlled unsafe data.

## Failure inventory

The frozen inventory covers partial persistence, replay and concurrent confirm,
tenant substitution, type/serialization ambiguity, future-time leakage,
definition/configuration corruption, legacy migration, sensitive-data capture,
and premature product-status claims. Exact sequences are recorded in the
Chapter 22 matrix and Phase 3 detailed plan.

## Boundary family evidence

PENDING. Chapter 22 must enumerate and prove:

- every supported/unsupported feature type and typed-column combination
- before/at/after decision timestamp partitions at database precision
- empty/min/max/over-max and Unicode UTF-8 storage boundaries
- same-key and distinct-key confirm interleavings
- fresh, Chapter 21, missing-definition, duplicate-definition, and wrong-type
  migration/startup states

## Enforcement boundaries

PENDING. The final dossier will map each promise to exact schema constraints,
materializer code, transaction scope, tenant authorization, serialization
guard, startup validation, and operational signal.

## Exact proof

PENDING. Planned tests and commands are named in the matrix; none are claimed
to exist yet.

## Migration evidence

PENDING. Required evidence is a fresh PostgreSQL schema and a realistic
Chapter 21-to-Chapter 22 forward migration that preserves historical decisions.

## Demo evidence

PENDING. The Chapter 22 demo must use public APIs and reconcile typed values,
snapshot, decision, attempt, processor outcome, and idempotent replay without
direct mutation of product truth.

## Operational signals

PENDING. Bounded materialization/freshness/failure metrics, safe structured
logs, and feature-catalogue readiness will be reconciled before closure.

## Known debt

No debt is accepted by this planning artifact. Any implementation deferral of
a frozen promise requires a new valid `TD-NNNN` before Chapter 22 closure.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 22 uses Spring/JPA, Flyway, PostgreSQL constraints,
and Jackson serialization. It introduces no proprietary protocol engine.

## Escaped-review assessment

No Chapter 22 escaped finding exists before implementation. Any later escaped
finding must be registered before its fix and must reopen the complete affected
boundary family.

## Source-of-truth reconciliation

The Phase 3 detailed plan and master plan identify Chapter 22 as approved and
ready to implement. README states that the Phase 3 entry gate and promise freeze
are satisfied while the fraud MVP remains unimplemented.

## Merge recommendation

`DO_NOT_MERGE` as a Chapter 22 completion claim. Implementation, focused proof,
traceability review, later hostile review, migration/demo evidence, and every
matrix `PASS` remain outstanding.
