# Chapter 38 Closure Dossier

- Chapter: Chapter 38 — Historical Risk Policy Simulation
- Chapter number: 38
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_38_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was frozen before Chapter 38 production work and is now reconciled
to V54/V55, the Spring Batch implementation, real PostgreSQL restart and
terminal-state proof, public API/UI behavior, and a later live hostile pass.
The earlier closure claim was reopened on 2026-07-27 for `ER-0065`. Remediation,
independent semantic review, the 8/8 inherited PostgreSQL 16.14 suite, and the
759/759 full backend regression now pass, so the expanded boundary is closed.

## Observable promises

- A manager creates a bounded durable run whose tenant, candidate bytes/digest,
  cohort, `asOf`, label cutoff, origin policy, and evaluator/catalogue versions
  cannot drift.
- Historical evaluation uses stored decision-time feature/control evidence and
  cutoff-visible labels; it neither consults current Redis/RiskContext nor
  mutates payment, fraud, label, ledger, review, dispute, or webhook truth.
- Spring Batch JDBC metadata and THAAP product rows restart together without
  duplicate/skipped results or false progress at result/progress/finalizer
  commit boundaries.
- Cancellation and success compete on one fenced run; the committed terminal
  transition wins and PostgreSQL verifies its one matching event/report.
- One immutable report reconciles transitions, exclusions, label coverage,
  origin, descriptive money, protected suppression, data quality, digest, and
  expiry; a reusable evidence-only gate never performs activation.
- The cockpit keeps the credential in memory and exposes queue, durable
  progress, and successful report truth without rendering partial results.

## Failure inventory

The implemented boundary family covers wrong role/tenant, copied run identity,
candidate digest drift, unknown candidate/schema, invalid time bounds, disabled
worker mode, result/progress rollback, finalizer failure, delayed worker after
cancel, cancel while finalization is paused, duplicate restart, report
reconciliation, public representation, low-cardinality telemetry, incomplete
Batch schema, and retained migration. The later live review found one
representation defect: Boot 4's HTTP mapper serialized the older Jackson
`JsonNode` as introspection flags. The response is now a neutral ordered map,
and both MockMvc and live SHA-256 reproduction prove the real wire boundary.

## Boundary family evidence

Wrong role and tenant, copied run identity, candidate/digest drift, malformed
time bounds, disabled workers, every rollback/finalization ordering, terminal
competition, incomplete Batch metadata, wire representation, and retained
migration are each tied to their real API, transaction, PostgreSQL, Batch, or
live HTTP enforcement boundary. The complete partition family is `PASS`.

## Enforcement boundaries

Strict DTO parsing and actor-scoped idempotency guard the API. V54 composite
keys and checks bind tenant, lifecycle, counts, time, candidate, results, and
reports. V55 protects immutable run identity, append-only evidence, monotonic
progress, legal lifecycle transitions, and deferred terminal semantics.
Spring Batch's JDBC repository owns job/step restart state; the chunk transaction
owns result rows, `(created_at,id)` checkpoint, counts, and execution context.
The finalizer locks the run and atomically writes report, terminal event, and
success projection.

## Exact proof

- Backend: the full regression is green with the Chapter 38 H2 and inherited
  PostgreSQL suites; focused tests force rollback after result writes, restart
  after finalizer failure, delayed-start cancellation, paused-finalizer
  cancellation, independent approval, digest drift, public role/tenant access,
  readiness, metrics, canonical candidate bytes, and mixed-control replacement.
  `HistoricalRiskSimulationPostgreSqlTests` passes 8/8 and the complete backend
  suite passes 759/759 with zero failures, errors, or skips.
- Repository: 140 Python tests pass, including three demo safety tests.
- Cockpit: 20 Vitest tests, TypeScript validation, accessibility entry proof,
  credential non-rendering, queued-to-success report behavior, and the Vite
  production build pass.
- Migration: fresh V1–V55 and retained V53–V55 PostgreSQL upgrades, schema,
  sequence, index, constraint, trigger, empty-state, and historical-row
  preservation checks pass.

## Migration evidence

Fresh V1–V55 and retained V53–V55 PostgreSQL upgrades, schema, sequence, index,
constraint, trigger, empty-state, and historical-row preservation checks pass.

## Demo evidence

`scripts/demo-historical-risk-simulation.py` passes against the retained local
PostgreSQL database and current binary. Public creation replay is byte-exact;
the worker advances `RUNNING -> SUCCEEDED`; one stored amount feature yields the
hand-computed `ALLOW->REVIEW` result; population is `1 processed / 1 eligible /
0 excluded`; returned report bytes reproduce the persisted SHA-256; durable
reconciliation is `1|1|1|4|1` for run/result/report/events/Batch parameter;
and payment-attempt, decision, ledger, and label counts remain unchanged.

## Operational signals

Creation can be enabled only with a worker. Readiness verifies product and
Batch metadata tables and exposes only closed cause codes. The executor is
bounded, automatic startup is disabled, chunk size is configured, rollback
cannot commit progress, and metrics contain only closed `phase`/`result` tags.
No signal contains tenant, actor, run, candidate, payment, digest, checkpoint,
feature value, credential, or note content.

## Known debt

No Chapter 38 debt was accepted or deferred. Authored-rule and safety-exception
candidate implementations remain Chapter 39 scope, but the Chapter 38
candidate type and evidence-only gate are ready for that typed integration.
The canonical debt register continues to contain zero open entries.

## Custom infrastructure decision

`NOT_APPLICABLE`. The slice uses Spring Batch 6, its JDBC repository, Spring
transactions, PostgreSQL, and existing deterministic evaluation primitives. It
adds no custom queue, broker, distributed lease protocol, or replay engine.

## Escaped-review assessment

`ER-0065` escaped the prior Chapter 38 completion claim because an isolated
candidate fixture did not prove reconstruction of the complete historical
control stack. The implementation now preserves legacy, policy, and unrelated
authored evidence, replaces an authored candidate's `rule_code` family, and
allows a safety exception to suppress only its exact historically evaluated
source version. The expanded H2 partitions pass; inherited PostgreSQL proof is
also 8/8 green, including restart and rollback boundaries.

## Source-of-truth reconciliation

The Phase 4.5 plan, master plan, README, learning trail, this dossier, its
matrix, and the escaped-review register identify `ER-0065` as resolved and
Chapter 38 as `READY_TO_MERGE` with every invariant `PASS`.

## Merge recommendation

`MERGE`. The mixed-control counterfactual implementation passes independent
semantic review, H2 proof, and all 8 inherited PostgreSQL tests; `ER-0065` is
resolved and no Chapter 38 debt is open.
