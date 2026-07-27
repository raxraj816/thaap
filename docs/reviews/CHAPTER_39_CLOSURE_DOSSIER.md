# Chapter 39 Closure Dossier

- Chapter: Chapter 39 — Governed Rule Authoring And Safety Exceptions
- Chapter number: 39
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_39_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created after Chapter 38 closure and before Chapter 39 schema,
production code, tests, configuration, or UI work. It is now reconciled to
V56–V58, the governed-control implementation, PostgreSQL enforcement, cockpit,
historical simulation integration, public API demo, and later hostile pass.

## Observable promises

- Managers author only bounded deterministic typed JSON rules; no script,
  regex, dynamic field, clock, network, or database capability exists.
- Submission freezes canonical bytes/digest/catalogue meaning; approval uses
  dual control; activation and rollback require exact fresh simulation evidence
  inside the merchant configuration transaction.
- Confirm observes a complete configuration state, persists every match and
  exception disposition, and keeps strongest-action precedence.
- Exceptions are exact purpose-HMACed card subjects tied to one authored source
  version, last no more than 30 days, and never suppress protected sources or
  emit ALLOW.
- Historical decision explanation remains stable after activation, retirement,
  rollback, exception expiry, catalogue change, or HMAC rotation.

## Failure inventory

The frozen grammar and lifecycle/exception tables map to codec boundary tests,
public API contracts, inherited forced PostgreSQL configuration-lock
interleavings, V57 database postconditions, fresh/retained migrations, UI proof,
and the live candidate-to-simulation-to-activation/exception/rollback demo.
Malformed/unknown JSON, numeric coercion/ranges, Unicode, depth/node/set/byte
cost, wrong role/tenant, self approval, stale simulation, digest/action drift,
raw identity, HMAC availability, exact expiry, history mutation, and rollback
representation are all closed. Every matrix row is `PASS`.

## Boundary family evidence

The closed grammar, numeric, Unicode, authorization, tenant, dual-control,
simulation-lineage, HMAC availability/rotation, expiry, history, rollback, and
retained-migration partitions are proven at their codec, API, transaction,
PostgreSQL, client, or live-demo boundary. Every family is `PASS`.

## Enforcement boundaries

`AuthoredRuleCodec` parses a closed typed immutable AST, enforces cost and
feature-domain bounds, sorts order-insensitive groups/sets, and binds exact
bytes with SHA-256. V56 uses tenant composite keys and immutable evidence rows;
V57 protects version transitions/history and one-active identity; mutations
lock the live manager, configuration guard, and exact version before Chapter
38 gate revalidation. Exception identity is a purpose/version HMAC derived from
a tenant-owned payment method. Decision evidence retains the source match and
records exception disposition separately.

## Exact proof

- Backend: the full regression is green after the rule-action snapshot fix,
  governed readiness validation, rollback contract, and V58 forward repair.
  Focused codec, API, simulation, and PostgreSQL suites pass.
- Repository: the new governed-control demo safety tests pass with the existing
  Python repository suite.
- Cockpit: 4 Vitest tests, TypeScript validation, and the Vite production build
  pass with memory-only credentials and governed lifecycle actions.
- Simulation: an authored snapshot binds version ID, canonical rule bytes,
  inner digest, and candidate action under one outer digest; outer action drift
  and inner rule drift fail closed.

## Migration evidence

Fresh V1–V58 and retained V55–V58 PostgreSQL upgrades pass. V56 adds empty
governed rule/exception/evaluation state; V57 installs five version/history
triggers and active-identity indexes; V58 expands the rollback event field to
the exact closed value width. Direct definition mutation and event UPDATE/
DELETE fail, while retained payment/fraud rows and prior controls remain
unchanged.

## Demo evidence

`scripts/demo-governed-controls.py` passes against the retained local
PostgreSQL database and current binary. It proves byte-exact creation/simulation
replays, self-approval denial, independent approval, exact simulation gate,
real BLOCK with no authorization, exact-card purpose-HMAC exception with match
evidence retained, an allowed processor path, append-only retire/rollback, one
active rule identity, and readiness `UP` using read-only reconciliation. The
one-row demo uses the documented local-only `1`/`0` evidence threshold; the
runtime default remains 100 eligible rows and 50% label coverage, and every
other exact activation check remains enabled.

## Operational signals

Closed lifecycle/evaluation metrics expose no tenant, actor, rule, exception,
payment, digest, operand, or subject identity tags. Safe audits record lifecycle
metadata only. Readiness validates schema, active canonical bytes/digests, and
all active exception key versions; the public response canary confirms no raw
card, HMAC, or key version escapes.

## Known debt

No Chapter 39 debt was accepted or deferred. Broad exception identity,
merchant scripting, regex/string-contains, automatic activation, and unbounded
control languages are rejected product scope, not deferred implementation debt.

## Custom infrastructure decision

`NOT_APPLICABLE`. The implementation uses Jackson, typed Java ASTs, Spring
transactions, PostgreSQL, the existing HMAC service, and Chapter 38's Spring
Batch evidence gate. It adds no scripting engine, rules service, broker,
distributed lease, or proprietary cryptographic primitive.

## Escaped-review assessment

The original hostile pass found that `ROLLBACK_ACTIVATED` exceeded V56's
16-character event column before closure; V58 repaired that retained schema
forward and the complete rollback family passed. Chapter 43 later found
`ER-0058`: the one-row public demo's reduced activation evidence threshold had
not been made durable in user-facing runtime instructions. The product-safe
100-row/50%-coverage defaults remain unchanged; explicit local-only variables,
warnings, API documentation, and a clean retained-runtime rerun resolve the
documentation overclaim and correct the demo-review process.

## Source-of-truth reconciliation

The Phase 4.5 plan, master plan, README, learning trail, this dossier, and its
matrix identify Chapters 36–39 complete and Chapter 40 as the next slice. Phase
4.5 is not complete.

## Merge recommendation

`MERGE`. V56–V58, bounded canonical rules, exact immutable simulation snapshots,
dual-controlled lifecycle, decision evidence, narrow safety exceptions,
PostgreSQL history enforcement, cockpit operations, and the public live demo
pass with no blocking finding or undocumented debt.
