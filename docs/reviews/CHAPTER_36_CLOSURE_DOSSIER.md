# Chapter 36 Closure Dossier

- Chapter: Chapter 36 — Fraud Taxonomy And Merchant-Risk Monitoring
- Chapter number: 36
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_36_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

`ER-0064` is resolved. The before/exact/after `linked_at` family passes on H2
and in all 7 inherited PostgreSQL 16.14 monitoring tests; the complete backend
suite passes 759/759 without skips.

This dossier was created after founder promise confirmation and before Chapter
36 production code or migration changes. The original implementation, proof,
live demo, traceability review, and hostile pass are retained as evidence. The
later `ER-0064` recovery re-proved the expanded temporal boundary.

## Observable promises

- Every merchant-risk metric exposes exact numerator, denominator where
  applicable, half-open window, `asOf`, coverage, definition version, and
  traffic-origin policy.
- Monitoring reads durable PostgreSQL facts rather than telemetry or current
  Redis, and cannot mutate payment, fraud, label, ledger, or active profile
  truth.
- Fraud taxonomy resolution is immutable/versioned and never fabricates a
  historical classification.
- Synthetic traffic is excluded by default; explicit inclusion remains visible.
- Profile recommendations are append-only evidence and never auto-activate a
  merchant tier.

## Failure inventory

The frozen matrix covers false zeroes, denominator mismatch, incomplete buckets,
late and contradictory labels, synthetic contamination, cross-tenant joins,
prohibited-value leakage, inconsistent read cohorts, duplicate snapshots,
taxonomy drift, malformed APIs, telemetry ambiguity, migration fabrication,
Redis outage, documentation overclaim, and incident evidence linked only after
the requested point-in-time boundary.

## Boundary family evidence

The Chapter 36 matrix contains the formula traceability table. Tests partition
zero, pending, before/start/end-minus-1µs/end, late evidence before/at/after
`available_at`, incident evidence before/at/after `linked_at`, live/synthetic
origin, equal-time keyset paging, copied/tampered cursors, tenant substitution,
role, interval, page size, taxonomy source, snapshot replay, and
non-activation. Real PostgreSQL executes every aggregate and cohort SQL shape.

## Enforcement boundaries

Enforced boundaries are the exact application-owned V1 taxonomy catalogue,
strict DTOs/enums, risk-operator authentication, merchant-qualified PostgreSQL
queries/composite constraints, point-in-time label resolution, five-second
repeatable-read transactions, the shared `linked_at <= asOf` enumeration
predicate for summary and cohort projections, HMAC-bound cursors, append-only
snapshots and recommendations, forward label-version trigger, and closed
telemetry tags.

## Exact proof

The pre-hostile full Java suite passed 644/644. The post-recovery complete Java
suite passed 759/759 with zero failures, errors, or skips, including all Chapter
36 contracts plus PostgreSQL chargeback, taxonomy, migration, concurrency,
timeout, label, and demo-safety proofs. All 140 repository Python tests pass.
`git diff --check`, executable naming, technical-debt, escaped-review,
invariant, closure, phase-closure, and readiness checks pass.

The focused post-closure contract
`MerchantRiskMonitoringContractTests.lateIncidentEvidenceCannotRewriteEnumerationSummaryOrCohortBeforeLinkedAt`
proves a frozen summary/cohort remains byte-equivalent after later evidence is
linked, then proves exact-boundary and later visibility in both projections.
The focused monitoring contract passed `7/7` on H2 and the inherited
`MerchantRiskMonitoringPostgreSqlTests` passed `7/7` on PostgreSQL 16.14. The
semantic-drift fixture injects privileged test corruption by temporarily
suspending the append-only trigger; the independent migration contract proves
ordinary taxonomy UPDATE/DELETE remains rejected.

## Migration evidence

Fresh PostgreSQL applies and validates V1-to-V51 with Hibernate. Retained
V49-to-V51 preserves historical review/decision/subscription truth, leaves
existing label bytes unclassified rather than backfilling a fabricated
version, and creates zero snapshot/recommendation rows. V50 adds deterministic
catalogue and bounded indexes; V51 protects append-only history,
live-recommendation semantics, and new-label taxonomy binding.

## Demo evidence

The public live PostgreSQL demo passes with live approval `1/2`, explicit
synthetic-inclusive approval `2/3`, bounded safe drill-down `1/2`, 18 exact
taxonomy mappings, byte-exact snapshot replay, one HIGH enumeration
recommendation, zero profile activations, durable SQL reconciliation, and
readiness UP.

## Operational signals

Monitoring reads, requests, and snapshot completion expose only closed metric,
coverage, operation, result, outcome, and transaction tags. Rollback is emitted
after transaction completion. Query timeout maps to bounded `503 query_timeout`
with `Retry-After: 1`. Readiness compares every persisted taxonomy mapping and
the snapshot schema; a same-cardinality semantic drift fixture reports DOWN.
API/cohort/demo canaries prove no credential, PAN, email, fingerprint, or HMAC
identity enters product responses or metric tags.

## Known debt

No debt accepted at plan freeze. Any genuine deferral discovered during
implementation must receive the next `TD-NNNN` before closure.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 36 uses Spring/JPA/JDBC and PostgreSQL; it introduces
no custom protocol, queue, scheduler, cryptography, or concurrency substrate.

## Escaped-review assessment

`ER-0064` escaped the earlier completion claim because label and chargeback
availability tests did not enumerate the separate incident-link availability
clock. The implementation now binds incident evidence through
`linked_at <= asOf`; the summary and cohort boundary family was rerun with
before, exact-boundary, and after partitions. The process correction is to
inventory every durable association timestamp—not only source-event
timestamps—when freezing point-in-time reporting promises.

## Post-closure recovery

The Chapter 36 completion claim was withdrawn while `ER-0064` was open. The
remediation preserved the original frozen formula and changed no historical
event time: it made the append-only association's availability time part of the
query. Focused H2 proof now shows that late incident linkage cannot rewrite a
previously observed enumeration ratio or its exact cohort. The inherited
PostgreSQL suite proves the same before/exact/after semantics and the full
backend suite confirms no adjacent regression.

## Source-of-truth reconciliation

The detailed plan, master plan, README, matrix, dossier, escaped-review register,
and learning artifacts identify `ER-0064` as resolved and Chapter 36 as
`READY_TO_MERGE` with every invariant `PASS`.

## Merge recommendation

`MERGE`. The remediation and complete H2/PostgreSQL boundary family pass,
`ER-0064` is resolved, every matrix row is `PASS`, and no Chapter 36 debt is
accepted.
