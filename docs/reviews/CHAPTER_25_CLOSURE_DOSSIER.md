# Chapter 25 Closure Dossier

- Chapter: Chapter 25 — Integrated Fraud Decision MVP Closure
- Chapter number: 25
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_25_INVARIANT_PROOF_MATRIX.md
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

Phase 3 exposes all four deterministic fraud actions through public APIs,
retains exact typed feature and rule evidence, permits processor work only for
ALLOW, and makes review intake readable and coherently cancelable without
claiming analyst resolution.

## Failure inventory

The integrated matrix covers action leakage, partial writes, replay and lock
order, tenant/origin substitution, copied-semantic drift, sensitive surfaces,
migration compatibility, subscription intent, demo safety, telemetry, and
source-of-truth overclaim.

## Boundary family evidence

| Family | Integrated disposition |
| --- | --- |
| Four actions | Public contracts and demo reconcile payment, processor, decision, rule, review, audit, webhook, and idempotency truth |
| Precedence | Every three-rule match vector/permutation and numeric edge has stable action/score/reasons |
| Transaction | Feature/result positions, review persistence, audit/webhook/idempotency failures, and processor boundary are proven |
| Concurrency | Forced PostgreSQL ALLOW/BLOCK confirm and both REVIEW confirm/cancel orderings pass |
| Tenant/privacy | API substitution, cursor ownership, composite SQL identity, DTO, metric, audit, webhook, and source scans pass |
| Migration | Fresh V25, realistic Chapter 21/V17 upgrade, retained V22→V24, applied checksum preservation, and legacy subscription-consent repair pass |
| Operations | Exact catalogue readiness, bounded metrics, secure loopback demo, registers, learning, and gates reconcile |

## Enforcement boundaries

Chapters 22–24 form one chain: immutable decision-time features, immutable
complete rule evaluation, action-before-processor payment application, and
tenant-bound review lifecycle. V18–V25, startup validators, idempotency fencing,
pessimistic locks, dedicated DTOs, audit/webhook records, and after-commit
metrics enforce the product promise at the appropriate boundaries.

## Exact proof

The final backend suite passes 324 tests with zero failures/errors/skips. The
repository Python suite passes 76 tests. Named unit, contract, rollback,
tenant/privacy, migration, PostgreSQL, and demo evidence maps to every C23–C25
matrix row; aggregate totals are regression context rather than substitutes.

## Migration evidence

Fresh H2 and PostgreSQL reach V25. A realistic V17/Chapter 21 state preserves
historical decision/snapshot bytes and the shipped V18 checksum while forward
adding typed identity, catalogues, results, review, and semantic keys. A retained
local V22 database advanced through V24 without Flyway repair/reset. V25 proves
partial and exact-full legacy endpoints are not expanded while new nine-event
endpoints retain explicit subscriptions. Its subsequent
startup correctly remained fail-closed without the historical webhook key.

## Demo evidence

`demo-fraud-decision-loop.py` accepts only loopback HTTP, disables
environment proxies, rejects redirects, uses public writes, and reconciles with
read-only PostgreSQL. It passed with actions ALLOW/REQUEST_3DS/BLOCK/REVIEW,
6 decisions, 42 values, 18 results, 3 authorizations, 1 canceled review, exact
replay, and zero non-allow processor calls.

## Operational signals

Operators can distinguish feature materialization/freshness, decision action/
risk/latency, rule match outcome, bounded failure stage/cause, review open/
cancel, and traffic origin. Startup validates the exact seven-feature and
three-rule catalogues before traffic.

## Known debt

No Phase 3 debt is accepted. `docs/TECHNICAL_DEBT_REGISTER.md` has zero
non-resolved entries. Phase 4 boundaries are deliberately rejected from this
phase with exact unblock evidence in the detailed plan.

## Custom infrastructure decision

`NOT_APPLICABLE`. The implementation uses mature framework/database boundaries
and a small typed predicate engine, not a proprietary protocol or general DSL.

## Escaped-review assessment

ER-0046–ER-0050 are resolved with product and process evidence. No unresolved P0/P1,
frozen-promise P2, or Phase 3 escaped finding remains.

## Source-of-truth reconciliation

README, service README, master/detailed plans, architecture, concept note,
daily/weekly learning, matrices, dossiers, debt register, and escaped register
all identify Phase 3 as complete and Phase 4 as the next owner.

## Merge recommendation

`MERGE`. All frozen Phase 3 promises pass at their real boundaries, the later
hostile review is closed, the public loop is demoable, and no debt or finding
blocks the decision MVP.
