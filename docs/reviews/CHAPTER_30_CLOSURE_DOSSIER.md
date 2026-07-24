# Chapter 30 Closure Dossier

- Chapter: Chapter 30 — Redis Velocity And Typed Fraud Lists
- Chapter number: 30
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_30_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created after Chapter 29 closure and before Chapter 30
production code, migrations, or Redis scripts. ADR-0010 is accepted and contains
the required custom-infrastructure boundary table. The later traceability and
temporally separate hostile reviews reconciled every frozen promise to its real
Redis, PostgreSQL, HTTP, migration, telemetry, and live-demo enforcement boundary.

## Observable promises

- Velocity excludes the current stable logical attempt on first execution and
  every retry while an abandoned member can only conservatively raise risk.
- Redis ambiguity/unavailability never becomes zero or ALLOW; it creates
  unavailable lineage and a REVIEW floor.
- Typed negative lists are immutable, versioned, manager-authorized,
  tenant-bound, effective-time aware, and can never express ALLOW.
- Complete aggregation preserves stronger base decisions and persists exact
  control evidence without exposing raw identities.

## Failure inventory

The matrix freezes exact script bytes/version/hash/return grammar, current-member
exclusion, windows/TTL/limits, timeout-before/after-apply ambiguity,
rollback/compensation, restart/eviction/SCRIPT-FLUSH, HMAC privacy/rotation,
every list type and normalization/time partition, operator authorization,
activation-confirm races, complete aggregation, migration, telemetry, and
premature-closure failures.

## Boundary family evidence

- Real Redis fixtures prove exact script bytes/SHA, atomic prune/exclude/count/
  add/expire behavior, concurrent clients, retry exclusion, TTL, compensation,
  SCRIPT FLUSH reload, timeout, and outage recovery.
- Real PostgreSQL tests prove append-only and semantic triggers, composite tenant
  keys, forced list/confirm and operator/mutation orders, and exactly one winner
  in a concurrent V40 activation race.
- Strict public contracts cover every identity family and invalid grammar/time/
  role/idempotency partition; canary scans cover HTTP, logs, metrics, Redis, and
  all relevant durable surfaces.

## Enforcement boundaries

Strict DTO/type canonicalization, under-lock risk-manager authorization,
purpose-separated HMAC keyrings, the exact-byte `velocity_v1.lua` observation
and `velocity_compensation_v1.lua` token-guarded cleanup EVALSHA operations,
bounded Redis timeouts/TTL/dimensions, PostgreSQL V35–V40 immutable
lineage and lifecycle triggers, complete action aggregation, shared/exclusive
merchant risk-control locks, and transaction-outcome cleanup enforce the frozen
promises. SHA-1 `10bde6dc25904bcbce276ecc99f44e6bbaeeb53b`
and `f333eb65957b7b3beb279a3c79699ac0de758fab` bind the production Lua bytes.

## Exact proof

- The complete Java suite passes 541/541 with zero failures, errors, or skips.
- The complete Python structural/demo suite passes 94/94.
- `VelocityDecisionAggregationTests` covers all 36 base × velocity × list
  candidates and all six list insertion permutations.
- Eight real-PostgreSQL fraud-list tests prove immutable lineage, tenant SQL
  substitutions, lifecycle races, operator races, control-lock order, and the
  V40 one-active-version boundary.
- Real Redis, public API, readiness, key-retention, exact grammar, rollback,
  metric, and privacy suites pass without a mock standing in for their claim.

## Migration evidence

Fresh V1→V40 applies all 40 migrations and validates Hibernate mappings. The
retained migration cohort preserves Chapter 22–29 decisions, contexts, reviews,
authentication, labels, and response bytes while advancing through the Phase 4
migrations. The live local database was separately observed at V39, upgraded by
the current binary using only V40, and started successfully at V40. No migration
fabricates active fraud-list controls.

## Demo evidence

`scripts/demo-velocity-fraud-lists.py` passed against the V40 retained
database and real local Redis. It proved a typed CARD_FINGERPRINT list produces
BLOCK with no processor authorization, deactivation changes future but not
historical truth, the fourth same-card attempt produces REVIEW, paused Redis
produces `VELOCITY_UNAVAILABLE` REVIEW and degraded readiness, unpause restores
readiness and normal decisions, replays are byte-exact, lineage reconciles, and
raw card/email canaries have zero Redis/durable/public matches. Public contracts
separately prove all eight supported list identity families.

## Operational signals

Closed Redis and list operation/result/cause/transaction/version dimensions,
committed-versus-rolled-back metrics, script/key readiness, cleanup success and
failure, lifecycle/evaluation outcomes, TTL/cardinality bounds, and captured safe
logs pass. Dependency ambiguity is visible and cannot silently become fresh zero.

## Known debt

None accepted. Any real deferral requires the next immutable `TD-NNNN` with a
named owner slice, DRI, trigger/deadline, and done evidence. P0 work cannot be
deferred.

## Custom infrastructure decision

ADR-0010 is `Accepted` and its boundary table governs identity, authoritative
truth, time, atomicity, retry, ambiguity, rollback, limits, privacy, and
operations. The repository-owned Lua surface is limited to velocity observation,
velocity compensation, and rate limiting; expansion or excessive operational complexity triggers
replacement with a mature managed coordination component.

## Escaped-review assessment

PASS. The hostile pass found two pre-closure gaps: lifecycle/confirm did not yet
share an explicit merchant control lock, and list metrics were emitted before
transaction outcome was known. Both were fixed before the first completion
claim. The 2026-07-25 post-claim review later registered ER-0052 and ER-0053;
retained-window rotation and token-guarded compensation are now resolved with
real Redis evidence and permanent failure-inventory corrections.

## Source-of-truth reconciliation

Chapters 26–30 are verified complete. The Phase 4 detailed plan, master plan,
READMEs, daily note, weekly review, matrix, and this dossier advance the active
implementation frontier to Chapter 31 while Phase 4 remains open through
Chapters 31–35.

## Merge recommendation

`MERGE`. Every matrix row is `PASS`; real Redis/PostgreSQL, fresh/retained
migration, public live demo, traceability, hostile review, and repository gates
are the required final evidence. There is no blocking debt or escaped finding.
