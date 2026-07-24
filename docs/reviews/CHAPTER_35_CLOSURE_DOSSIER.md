# Chapter 35 Closure Dossier

- Chapter: Chapter 35 — Integrated Phase 4 Closure
- Chapter number: 35
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_35_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created before integrated closure work and was reopened on
2026-07-25 for ER-0052 through ER-0057. V49 plus exact real-boundary regression
proof resolves every finding, so the dossier again recommends `MERGE`.

## Observable promises

- The complete loop is demoable: merchant payment → safe risk context →
  immutable decision → authentication/review → analyst action → label →
  post-authorization/dispute feedback → suspected-abuse operations.
- Payment, fraud, tenant, security, ledger, ML-lineage, replay, and operational
  claims compose across chapters without rewriting earlier truth.
- Redis remains ephemeral coordination; PostgreSQL remains authoritative for
  payment, decision, review, label, dispute, signal, incident, and audit truth.
- Phase 4 closes with zero blocking debt/finding and no contradictory status.

## Failure inventory

The pre-implementation matrix freezes integrated money consistency, tenant and
privacy boundaries, concurrent winners, point-in-time lineage, public language,
telemetry, dependency ambiguity, fresh/retained startup, all-demo compatibility,
learning trail, debt/findings, and repository source reconciliation.

## Boundary family evidence

The full suite covers every chapter contract plus forced PostgreSQL ordering and
real Redis fixtures. Sequential latest-binary demos cover all nine public
workflows. Hostile integration reopened feature catalogue counts, Redis outage
precedence, HMAC rotation, timestamp binding, concurrent signals/detectors,
retained migration, demo safety, and bounded error logging; each family has
executable regression or live reconciliation.

## Enforcement boundaries

Strict DTOs/enums and merchant/operator credentials own API grammar and
authorization. Fenced idempotency owns replay. PostgreSQL composite keys,
transactions, locks, migrations, and append-only/semantic triggers own durable
truth. Shared payment/refund/ledger components own money. Exact Redis Lua and
domain-separated HMACs own ephemeral coordination. Local audit/webhook
transactions and after-completion metrics own operational truth.

## Exact proof

The latest clean Java suite passes 629/629 with zero failures, errors, or skips.
Repository Python discovery passes 114/114. Chapter 33 adds 13 PostgreSQL
contracts; Chapter 34 adds 12 PostgreSQL/Redis/API/script/service contracts.
Every Chapter 26–35 invariant matrix is `FROZEN`/`VERIFIED` with all rows PASS.

## Migration evidence

`PostgreSqlMigrationTests` passes 14/14. Fresh V1-to-V49 applies 49 production
migrations and validates current entities. Retained staged upgrades preserve
review/decision rows and explicit webhook consent, classify legacy refund
reasons, create no fabricated signal/incident truth, and install V45–V48
history/semantic protections. The current binary started and ran all demos on
retained V48-to-V49 upgrade proof adds empty risk-identity aliases and
backfilled merchant principal ownership without rewriting historical responses.
The real retained local volume also advanced from V48 to V49 without reset; its
missing historical webhook KEK was rejected after migration. A separate fresh
V49 runtime with valid ephemeral encryption configuration started successfully,
reported health/readiness `UP`, and shut down cleanly.

## Demo evidence

All Chapter 26–34 public demos pass sequentially on the latest binary. They
prove review/label, review approval, two authentication sources, RiskContext
all-action parity, list/velocity and 503 recovery, dual-control policy,
chargeback/refund offset, post-authorization manager refund/label, durable
suspected incident resolution, definite 429, HMAC-only Redis state, exact
replay, tenant/privacy boundaries, read-only SQL reconciliation, and readiness.

## Operational signals

Metrics use closed low-cardinality outcomes and transaction completion where
durable truth is claimed. Logs retain request ID, route, and exception type but
not raw database values or identities. 429 is a definite abuse-control result;
503 is dependency ambiguity; velocity UNAVAILABLE is later fraud evidence and
not interchangeable with either. Live readiness recovered after Redis return.

## Known debt

None accepted. `scripts/technical-debt-check.py` passes with five immutable
resolved entries and zero open entries. No Phase 4 work exists only in chat or
an unnamed future slice.

## Custom infrastructure decision

`PASS` under accepted ADR-0010. Phase 4 owns three narrow exact-byte Redis Lua
scripts for velocity observation, token-guarded compensation, and fixed-window
limiting. Their boundary table
covers identity, authority, time, atomicity, retry, ambiguity, rollback,
limits/TTL/cardinality, privacy, metrics, version/hash, reload, and replacement
trigger. No Chapter 35 infrastructure was added.

## Escaped-review assessment

`COMPLETE`. The escaped-review register check passes for 57 immutable findings.
ER-0052 through ER-0057 record the post-claim defects and are resolved with
real Redis, API, migration, identity-rotation, response-lineage, and exact-suite
evidence plus permanent process corrections.

## Source-of-truth reconciliation

Phase 4 and Chapters 26–35 are complete. README, service/docs/scripts READMEs,
implementation master plan, detailed Phase 4 plan, architecture/ADR references,
daily/deep-dive/weekly learning notes, matrices, dossiers, debt register, and
escaped-review register reconcile. The next product frontier is Phase 4.5
product-grade risk operations, not unfinished Phase 4 work.

## Merge recommendation

`MERGE`. Every integrated promise has real-boundary proof, full suites and all
public demos pass, migrations preserve truth, and no blocking debt/finding or
source contradiction remains.
