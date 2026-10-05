# Chapter 55 Closure Dossier

- Chapter: Chapter 55 — Investigation Saved Views And Bounded Bulk Review Assignment
- Chapter number: 55
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_55_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 55 implementation. Chapters
53–54 are merge-ready, so the dependency gate is satisfied, and all thirteen
matrix rows now hold on executed evidence.

The two rows that were last to close were the ones that mattered operationally.
`C55-OBS-001` needed a stranded run — claimed, then its lease expired, exactly
the state a dead worker leaves — because nothing throws, no item fails, and no
error is logged when a worker simply stops; the backlog and oldest-age gauges are
the entire detection surface for that failure, and they are now asserted to rise,
to keep readiness `UP`, and to fall back to zero once recovery finishes the run.
`C55-SCP-001` needed the public demo plus the reciprocal insight-absence scan
that Chapter 56 route ownership made possible.

The full backend suite is green at 1208 tests with zero failures, the browser
suite at 67 tests across 10 files, and the demo safety suite at 10 tests.

## Observable promises

Analysts/managers can persist typed investigation views and safely distribute
at most 25 review cases through assignment/release only. Saved definitions are
version/digest/visibility safe; bulk work has durable item truth, live actor and
source rechecks, exact idempotency, restart/fencing, and explicit partial
results without bulk fraud classification or money/control action.

## Failure inventory

The matrix freezes saved grammar/canonicalization/upgrade/visibility, 0/1/25/26
and duplicate items, changed replay, actor/target disable, assignment/release/
resolution races, write-boundary rollback, crash/takeover/stale finalizer,
tenant substitution, batch privacy, migration history, UI ambiguity, and scope
families.

## Boundary family evidence

Executed for every family except the two named residual gaps. Saved definitions
have canonicalization order-independence, exact round trip, and fail-closed
range/amount/currency/set-size boundaries. Bulk requests have the full
0/25/26/duplicate size table, the closed action and role matrix, and principal-
bound idempotency replay and conflict. Concurrency uses forced PostgreSQL
orderings verified through `pg_stat_activity` lock waits rather than timing, with
both deterministic losing orderings. Migration exercises every malformed legacy
request and item state individually on real PostgreSQL. Browser proof covers the
mixed partial-success combination, role gating, stale-version conflict, and an
operator switch.

Every family is now closed, including the stuck-lease backlog gauge and the public demo.

## Enforcement boundaries

Planned enforcement is V88 typed saved views, the closed filter codec, live
risk-operator/visibility checks, V89/V90 request/item constraints and immutable
terminal guards, principal idempotency, Spring Batch restart mechanics, lease
epoch/token fencing, inherited actor-first assignment locks, and atomic domain
item execution.

## Exact proof

Executed proof now exists for the grammar, authorization, and scope families:

- `PaymentInvestigationFilterCodecTests` — canonicalization order-independence,
  exact round trip, and fail-closed range/amount/currency/set-size boundaries.
- `PaymentInvestigationSavedViewContractTests` — full saved-view lifecycle,
  owner/manager-shared authorization, stale-version conflict, idempotency,
  tenant non-disclosure, schema-drift and digest-tamper upgrade refusal,
  disabled-owner revocation, projection-generation swap, and non-interference
  with the existing review saved-search table and routes.
- `ReviewBulkActionScopeTests` — closed action vocabulary, role gates, the exact
  0/25/26 item boundary, duplicate and malformed item partitions, order-permutation
  identity, principal-bound idempotency, and a source-table fingerprint proving a
  queued request touches no resolution, label, chargeback, ledger, rule, or list row.
- `ReviewBulkActionScopeBoundaryTests` — package-wide import and reflective-dispatch
  scans plus the pinned three-operation vocabulary.
- `ReviewBulkActionAtomicityPostgreSqlTests` — real-PostgreSQL rollback of the
  assignment, assignment event, and audit row when the fence is lost at the terminal
  item write; atomic success; stale-version refusal; and the `RELEASE` arm.
- `ReviewBulkActionConcurrencyPostgreSqlTests` — forced `manual_review_cases` lock
  contention verified through `pg_stat_activity`, exactly-one-winner convergence,
  both deterministic losing orderings, and live actor/target disable rechecks driven
  through the production disable route.
- `InvestigationWorkflowTenantIsolationTests` — two equal-shaped merchants sharing
  idempotency keys and view names; foreign-versus-absent proven on scrubbed response
  bytes; cross-tenant item attempt leaves the neighbour untouched; cursors are
  tenant-bound.
- `InvestigationWorkflowPrivacyTests` — planted card, contact, credential, and raw
  idempotency-key canaries scanned across every response, every text column of both
  bulk tables (enumerated from `information_schema`), and captured logs; stored
  filter bytes asserted against the frozen key set.
- `InvestigationWorkflowObservabilityTests` — bulk-item meter vocabulary pinned to
  the frozen terminal-state table and every decision-intelligence tag key asserted
  closed, so no identifier or exception text can become a dimension.

Additional executed proof now closes the families that were outstanding:

- `ReviewBulkActionRestartPostgreSqlTests` — mid-run worker death resuming from
  durable item truth without reapplying a completed assignment, poison-item
  continuation producing an honest `PARTIAL`, terminal-request closure, refusal to
  finalize with items still pending, and recovery-eligibility of an expired run.
- `ReviewBulkAssignmentMigrationPostgreSqlTests` — fresh install of the substrate,
  guards and indexes; retained V87 saved searches preserved byte-identically across
  V88–V91 with repeated-startup convergence; every malformed request and item shape
  refused at the database; terminal history immutable; and terminal status reconciled
  against item truth by the deferred constraint trigger.
- `InvestigationWorkflowLineageTests` — the saved-view column set asserted whole so
  no cursor or captured result can appear, execution bound to the currently active
  generation, per-item requested/observed/final version lineage, and zero label,
  decision, feature, or projection mutation from a bulk run.
- `savedViewsAndBulkReview.test.tsx` — saved-view and bulk browser proof including
  mixed-result partial truth, role gating, stale-version conflict surfacing, and an
  operator switch forcing a fresh server answer.
- `ReviewBulkActionAtomicityPostgreSqlTests` additionally proves the no-op
  self-assignment path and that a rolled-back item increments no terminal meter.

Both formerly residual gaps are now closed: `ReviewBulkBacklogReadinessTests`
covers the stuck-lease backlog gauge (`C55-OBS-001`), and the public demo plus
the reciprocal insight-absence scan close `C55-SCP-001`.

## Migration evidence

`ReviewBulkAssignmentMigrationPostgreSqlTests` drives Flyway directly against
isolated schemas on real PostgreSQL. Fresh migration installs both bulk tables,
the saved-view table, all three V90 guards, and both bounded work indexes. A
retained V87 schema carrying a real legacy `review_saved_searches` row migrates
forward with that row byte-identical, the new saved-view table empty, no bulk
guard attached to the legacy table, and a repeated startup converging at V91
without changing it. Every malformed request and item shape is refused by the
database rather than by application code alone, terminal history cannot be
rewritten or deleted, a fence cannot move backwards by update or insert, and a
terminal request status that disagrees with its item truth is refused.

The item table's deliberate absence of a foreign key to `manual_review_cases` is
proven rather than assumed: resolving a review at admission would disclose
cross-tenant existence, so the item is tenant-bound through its owning request
and an unknown or foreign review terminates as a non-disclosing per-item
`NOT_FOUND`.

## Demo evidence

`scripts/demo-saved-views-and-bulk-review.py` saves a merchant-shared view,
proves the saved-view table stores no cursor or result column, executes the view
and requires live projection freshness, and confirms a second operator can read
the shared definition. It then submits one bounded three-item assignment
containing an eligible item, a stale-version item, and an absent review, so the
run ends `PARTIAL` with `SUCCEEDED`/`STALE`/`NOT_FOUND` per item — a bulk demo
that only ever shows success has not demonstrated the per-item truth the durable
item table exists for. It proves exact idempotent replay and a changed-body
conflict, reconciles that exactly one assignment event exists for the one
successful item, and asserts the prohibited-effect fingerprint over resolutions,
labels, chargebacks, ledger entries, rules, and lists is unchanged.

`scripts/saved_views_and_bulk_review_demo_tests.py` holds the demo itself to the
product's boundaries: loopback-only origins, mutating reconciliation refused
before any subprocess starts, a single read-only reconciliation seam, no route
outside the chapter's surface, and no hard-coded credential.

## Operational signals

`InvestigationWorkflowObservabilityTests` pins the bulk-item meter vocabulary to
the frozen terminal-state table and asserts the decision-intelligence tag-key set
is closed, so no identifier, cursor, credential, or exception text can become a
dimension. `ReviewBulkActionAtomicityPostgreSqlTests` proves a rolled-back item
increments no terminal meter, so counters cannot drift from durable rows. The
lease vocabulary keeps a first claim, a recovery, a non-claimable attempt, a
launch failure, and a terminal run distinct.

`ReviewBulkBacklogReadinessTests` closes the remaining gap: a stranded run raises
the backlog and oldest-age gauges, readiness stays `UP` so one stuck bulk run
cannot take payment mutation down, the backlog returns to zero once recovery
finishes the run, and both gauges are asserted tag-free so they stay one bounded
series rather than one per tenant.

## Known debt

No debt is accepted at freeze. High-risk bulk actions are rejected scope;
insight calculations remain Chapter 56. Spring Batch is the mature restart
mechanism rather than deferred custom infrastructure.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 55 uses Spring Batch, PostgreSQL transactions/locks,
existing principal idempotency, existing assignment services/events/audit/
webhook intent, typed Spring APIs, and the existing React client.

## Escaped-review assessment

No Chapter 55 escaped finding exists at freeze. Any escape receives an
`ER-NNNN`, reopens its full grammar/concurrency/dependency/migration/
authorization family, and requires product evidence plus process correction.

## Source-of-truth reconciliation

The detailed and master plans, the matrix, the learning trail, and this dossier
agree that Chapter 55 is implemented and proven, with all thirteen rows holding
on executed evidence and the matrix `VERIFIED`. Chapter 55 does not claim any
Chapter 56–59 capability: insights, the developer contract, the SDK, and
integrated phase closure are proven by their own dossiers.

## Merge recommendation

`MERGE`. The dependency gate is satisfied, V88–V90 are proven on real PostgreSQL
including retained-history preservation, every one of the thirteen matrix rows
holds on executed evidence, the public demo runs through published routes only
and shows a mixed per-item result, operational signals are bounded and a stuck
run is visible, and source reconciliation is complete.

Phase 6 integrated closure is complete in the Chapter 59 dossier. This dossier
certifies only Chapter 55's assignment and saved-view boundaries.
