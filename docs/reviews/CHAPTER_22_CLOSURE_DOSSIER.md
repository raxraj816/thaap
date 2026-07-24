# Chapter 22 Closure Dossier

- Chapter: Chapter 22 — Feature Contract And Point-In-Time Lineage
- Chapter number: 22
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_22_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created before implementation so Chapter 22's release argument
could not be reconstructed from whichever tests happened to exist. The frozen
promises now trace through V18/V19, typed materialization, the confirm
transaction, exact contract and PostgreSQL proof, a public-API demo, and the
later hostile pass. Chapter 22 closes the feature-lineage foundation while
deliberately retaining allow-only `rules_stub_v1`; Chapter 23 owns real fraud
actions.

The 2026-07-12 Senior Staff review reopened this dossier before repair work.
ER-0043, ER-0044, and ER-0045 affected frozen type-lineage,
exact-decision-input, and transactional rollback promises. All three are now
resolved at their production boundaries; their expanded families, fresh
traceability pass, and later hostile review completed before merge readiness
was restored.

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

| Family | Partitions | Real-boundary proof |
| --- | --- | --- |
| Type and representation | LONG, STRING, BOOLEAN, DECIMAL; exact/wrong Java type; definition/type cross-product; zero/one/multiple SQL typed columns; integer/decimal JSON | `DecisionFeatureContextTests`, `FeatureValueBoundaryTests`, `FraudJsonConverterTests`, `FeatureLineageDatabaseContractTests` |
| Size and time | Empty, maximum, oversized, Unicode bytes; observed before/at/after decision; observed after computed | Boundary tests and PostgreSQL microsecond partitions |
| Tenant and origin | Correct/cross merchant, attempt, decision, snapshot, four-column definition identity, and traffic origin | Tenant contracts and PostgreSQL composite-FK cases |
| Transaction | Snapshot failure, FeatureValue positions zero through six, real PostgreSQL flush constraint failure, processor boundary, exact retry | Rollback contracts and PostgreSQL pre-processor flush failure proof |
| Concurrency | Same idempotency key and distinct keys while first owner is blocked at FeatureValue SQL | Forced PostgreSQL lock waits and cardinality postconditions |
| Migration/readiness | Fresh V19, V17 upgrade, exact/missing/duplicate/malformed/wrong-type catalogue | PostgreSQL migration and startup validator tests |
| Privacy/API | New, historical, cross-tenant, replay, failure, audit, webhook, and metric surfaces | API/tenant/materializer/metric tests and source scan |

## Enforcement boundaries

- V18 enforces definition ID/code/version/type identity, exact typed-column shape, temporal order,
  per-attempt uniqueness, tenant/origin lineage, and point-in-time indexing.
- V19 makes definitions and decision-time lineage append-only at PostgreSQL and
  H2; application entities do not expose mutation paths.
- `FeatureCatalogueValidator` fails startup unless the exact seven-code V1
  catalogue is present and compatible.
- `FeatureMaterializer` is the sole allowlisted source projection, checks
  decision-time and serialized-size boundaries, and derives snapshot and typed
  rows from the same values.
- `DecisionFeatureContext` is the engine's only input; `MaterializedFeatureSet`
  requires exact context/value agreement before evaluation or persistence.
- The idempotent confirm transaction locks the intent, persists complete
  lineage, flushes it through SQL before processor authorization, then commits
  processor/money/audit/webhook truth and replay state atomically.
- Merchant-scoped lookup plus composite database keys prevent tenant and
  traffic-origin substitution.

## Exact proof

- Backend: 301 tests, zero failures, zero errors, zero skips.
- Repository Python: 74 tests, all passing.
- Focused lineage proof includes seven new Chapter 22 test classes plus expanded
  confirm, rollback, tenant, JSON, and migration suites.
- Deterministic PostgreSQL interleavings prove the actual idempotency,
  PaymentIntent, and FeatureValue lock boundaries rather than simultaneous
  thread starts.
- Every C22 matrix entry maps production enforcement to exact proof and is
  `PASS`.

## Migration evidence

`PostgreSqlMigrationTests` migrates a clean database through V19 and separately
upgrades a realistic V17 schema. It proves seven exact immutable definitions,
the typed columns/constraints/index, historical decision and snapshot byte
preservation, and zero fabricated FeatureValues for old rows. The live demo
also upgraded retained local PostgreSQL state from V13 through V19.

## Demo evidence

`scripts/demo-feature-lineage.py` ran against the local service and
retained PostgreSQL. It created merchant, customer, method, manual-capture
intent, and confirm through public APIs; replayed the identical confirm bytes;
then used a read-only SQL reconciliation to prove one authorized attempt, one
`ALLOW`/`rules_stub_v1` decision, one safe snapshot, seven typed values,
point-in-time validity, exact replay, and prohibited-surface absence. The
script accepts only loopback HTTP, ignores environment proxies, and rejects
redirects.

## Operational signals

Operators receive bounded
`fraud_feature_materialization_seconds{outcome,traffic_origin}`,
`fraud_feature_freshness_total{status,traffic_origin}`, and
`fraud_decision_failures_total{stage,cause,feature_code}` signals. Tests prove
merchant/resource IDs, feature values, card data, PII, and idempotency material
cannot become metric dimensions. Startup validation fails before serving
confirms when required definitions are missing, duplicated, malformed, or
incompatible.

## Known debt

No Chapter 22 debt is accepted. The technical debt register has zero
non-resolved entries. Real deterministic fraud actions are sequenced Chapter
23 product scope, not deferred Chapter 22 debt, because this chapter explicitly
freezes unchanged allow-only behavior.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 22 uses Spring/JPA, Flyway, PostgreSQL constraints,
and Jackson serialization. It introduces no proprietary protocol engine.

## Escaped-review assessment

Chapter 22 has three resolved escaped findings. ER-0043 is an
`IMPLEMENTATION_DEFECT` in definition/type database identity, ER-0044 is an
`IMPLEMENTATION_DEFECT` in the decision-input boundary, and ER-0045 is
`WEAK_PROOF` at the real SQL flush boundary. Their repairs and full family
proof are recorded in the register and matrix; no Chapter 22 escaped finding
remains unresolved.

## Source-of-truth reconciliation

README and the master plan mark Chapter 22 complete and Chapter 23 next. Service
documentation describes V18/V19, the exact V1 catalogue, point-in-time query,
metrics, and allow-only boundary. No source of truth claims Phase 3, `rules_v1`,
`BLOCK`, `REQUEST_3DS`, or `REVIEW` is operational.

## Merge recommendation

`MERGE`. Definition/value type agreement is enforced by PostgreSQL, the engine
consumes only the governed materialized feature context, a real flush failure
proves rollback and coherent retry, every reopened matrix entry is `PASS`, all
three escaped findings are resolved, and the fresh traceability and hostile
reviews are complete.
