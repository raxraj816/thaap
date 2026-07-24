# Chapter 31 Invariant Proof Matrix

- Chapter: Chapter 31 — Merchant Risk Policy And Decision Integration
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was frozen on 2026-07-14 after Chapter 30 closure and before any
Chapter 31 production code, migration, policy API, or feature-catalogue change.
The explicit `APPROVED` state was added to reconcile the already-promised
separate approve and activate endpoints before implementation began.

## C31-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Merchant policy supplies only `BLOCK`, `REVIEW`, or `REQUEST_3DS` candidates and complete aggregation can never weaken a stronger base-rule, fraud-list, or velocity candidate.
- Failure sequence: A threshold acts as ALLOW, short-circuits remaining controls, or converts BLOCK/REVIEW to a weaker payment path.
- Implementation: Closed negative-action grammar, complete per-threshold evaluation, and deterministic `BLOCK > REVIEW > REQUEST_3DS > ALLOW` aggregation.
- Proof: All base × list × velocity × policy action/match combinations, all insertion orders, multiple equal-action ties, and exact processor/ledger exclusions.
- Adversarial evidence: Stronger/weaker/equal candidates, corrupt policy, missing policy, duplicate priorities, and all thresholds non-matching.
- Status: PASS

## C31-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Profile/policy evaluation, immutable evidence, final decision, payment transition, and processor exclusion/continuation remain one coherent confirm transaction.
- Failure sequence: Processor truth is created before policy aggregation, evaluation rows survive a rolled-back decision, or retry observes partial policy truth.
- Implementation: Point-in-time evaluation and copied control evidence flush inside the existing confirm/idempotency transaction before any processor boundary.
- Proof: Forced failures before/after profile, threshold, evaluation, decision, review/authentication/processor, audit, webhook, and idempotency persistence.
- Adversarial evidence: Same-key retry, changed-body conflict, database flush failure, and every final action.
- Status: PASS

## C31-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Profiles, policies, thresholds, actors, activation guards, evaluations, APIs, audit, and webhook evidence are bound to one merchant; foreign identifiers are missing-equivalent.
- Failure sequence: One merchant creates, approves, activates, retires, previews, reads, or evaluates another merchant's configuration.
- Implementation: Merchant-qualified reads/writes, copied tenant keys, a tenant-owned guard, and semantic PostgreSQL foreign keys/triggers.
- Proof: API and direct-SQL pairwise merchant/profile/policy/threshold/operator/decision substitutions plus same-code cross-merchant fixtures.
- Adversarial evidence: Foreign credentials, copied UUIDs, same policy code, creator/approver from different merchants, and decision substitution.
- Status: PASS

## C31-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Only active `RISK_MANAGER` operators may mutate profile/policy truth, and authorization is revalidated under the merchant/operator configuration lock.
- Failure sequence: Viewer, analyst, disabled, foreign, or concurrently disabled operator performs a lifecycle transition after an early credential check.
- Implementation: Existing credential boundary plus locked merchant/operator status/role revalidation for every mutation.
- Proof: Role/status/tenant matrix and deterministic disable-first/action-first forced orderings for create, submit, approve, activate, and retire.
- Adversarial evidence: Missing/malformed credential, stale lifecycle epoch, disabled actor replay, and foreign resource ID.
- Status: PASS

## C31-AUTH-002

- Lens: SECURITY_PRIVACY
- Invariant: Creator and approver are different active same-tenant `RISK_MANAGER` operators; approval changes no live decision until a separate activation succeeds.
- Failure sequence: Self-approval, creator credential aliasing, cross-tenant approval, approval activating implicitly, or post-approval threshold mutation.
- Implementation: Immutable creator/approver lineage, explicit `APPROVED` state, under-lock actor comparison, and no threshold mutation after submit.
- Proof: Self/different/foreign/disabled actor matrix, exact status sequence, approval-without-activation confirm, and direct SQL guards.
- Adversarial evidence: Same operator through a second credential, operator disabled between submit/approve, duplicate approval, and threshold insert after submit.
- Status: PASS

## C31-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Policy is constrained data, never executable text; values, errors, logs, metrics, audit, webhook, and preview expose only allowlisted feature/value/action/reason fields and no credentials or identity material.
- Failure sequence: Free-form expression/script, SQL/JSON/control payload, raw identity, HMAC, credential, or idempotency key reaches evaluation or an output surface.
- Implementation: Enums, bounded typed operands, catalogue allowlist, strict JSON, safe DTOs, and closed telemetry.
- Proof: Table-driven hostile strings/unknown fields/oversize collections plus unique canary scans across HTTP, PostgreSQL, logs, metrics, audit, webhook, and idempotency.
- Adversarial evidence: Unicode controls, delimiters, wildcard-looking values, huge numerics, duplicate JSON keys, and exception messages.
- Status: PASS

## C31-API-001

- Lens: API_PRODUCT
- Invariant: Profile and policy APIs enforce the exact `DRAFT → PENDING_APPROVAL → APPROVED → ACTIVE → RETIRED` lifecycle, strict typed grammar, and byte-exact fenced idempotency.
- Failure sequence: Skipped/reversed transition, mutation after submit, unknown type/operator, invalid value/range/currency, missing key, or changed-body replay is accepted.
- Implementation: Strict lifecycle endpoints/DTOs, closed enums, canonical currency/value handling, and the existing byte-fenced idempotency service.
- Proof: Every valid/invalid state edge, method/media/JSON partition, same-key exact replay, changed-body conflict, and terminal replay.
- Adversarial evidence: Empty policy, duplicate threshold code/priority, EQ/GTE/IN type mismatch, unknown feature, invalid currency, and retired reactivation.
- Status: PASS

## C31-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Profile versions, policy versions, thresholds, actors, timestamps, lifecycle events, and historical evaluations are immutable and point-in-time reproducible.
- Failure sequence: Current edits rewrite why an old decision acted, retirement deletes configuration, or historical explanation resolves against a newer threshold.
- Implementation: Additive version/lifecycle/threshold/evaluation tables with append-only triggers and copied immutable evaluation values.
- Proof: Direct update/delete rejection, historical read after retirement/new activation, before/at/after point-in-time evaluation, and migration/entity contracts.
- Adversarial evidence: Backdated/future activation, actor rewrite, threshold replacement, feature-definition mismatch, and decision/config substitution.
- Status: PASS

## C31-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: `merchant.risk_tier` is an immutable typed feature with exact profile/default provenance, while policy scores remain deterministic action severity and are never represented as fraud probability or an ML model output.
- Failure sequence: Missing profile creates a missing feature, risk tier is inferred from unavailable analytics, or policy severity is labeled probability/model score.
- Implementation: Catalogue definition, application-owned STANDARD default lineage, active-profile identity, and existing action-to-severity mapping.
- Proof: Default/STANDARD/ELEVATED/HIGH typed feature fixtures, snapshot/evaluation/decision agreement, and public vocabulary assertions.
- Adversarial evidence: No active profile, retired profile, corrupt multiple-active state, unknown tier, and absent ML model version.
- Status: PASS

## C31-DATA-003

- Lens: DATA_ML_LINEAGE
- Invariant: Every finite active threshold produces one immutable matched/non-matched `RiskControlEvaluation` containing exact version, feature definition/value, operator, typed operand, action, priority, reason, currency scope, and evaluated time.
- Failure sequence: Only matches are stored, missing feature becomes zero/empty, IN order changes truth, or a future explanation lacks the exact operand.
- Implementation: Deterministic evaluator over immutable FeatureValues with copied typed operands and explicit missing/non-match results.
- Proof: Feature type × operator × present/missing/value-boundary × currency matrix and snapshot/evaluation reconciliation.
- Adversarial evidence: Long overflow, boolean coercion, Unicode/case normalization, duplicate IN members, null currency, and malformed durable operand.
- Status: PASS

## C31-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: One merchant configuration guard serializes profile/policy lifecycle so at most one ACTIVE version of each kind exists and activation atomically retires the prior version or leaves it active on rollback.
- Failure sequence: Two activations both commit, prior active retires before new activation fails, or profile and policy locks deadlock in opposite orders.
- Implementation: Merchant-owned guard locked before actor/config rows, database semantic triggers, and append-only activation/retirement events.
- Proof: Deterministic two-activation races, forced failure after prior retirement, direct SQL race, exact lock order, and final-row postconditions on PostgreSQL.
- Adversarial evidence: Same/different config kind, same/different candidates, duplicate activation, deadlock order, and transaction rollback.
- Status: PASS

## C31-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Activation/retirement and confirm share the merchant risk-control ordering boundary so each decision observes exactly one committed profile/policy state and persists it.
- Failure sequence: Confirm mixes old profile with new policy, sees approved-but-not-active bytes, or records a threshold from a rolled-back activation.
- Implementation: Reuse of the Chapter 30 shared merchant confirm lock and exclusive lifecycle mutation lock, with one evaluation timestamp after lock acquisition.
- Proof: Forced activation-first/confirm-first and retirement-first/confirm-first PostgreSQL interleavings with exact feature, evaluation, decision, and processor postconditions.
- Adversarial evidence: Profile and policy changes together, approval without activation, rollback, no active config, and repeated confirm.
- Status: PASS

## C31-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish lifecycle, authorization denial, default/active/corrupt configuration, threshold match/non-match/missing, final candidate, commit/rollback, and preview outcomes using closed low-cardinality dimensions.
- Failure sequence: Rolled-back activation looks successful, missing configuration is invisible, or merchant/operator/config/value identifiers enter metric tags or unsafe logs.
- Implementation: After-completion counters/timers with closed operation/result/state/action/resource/transaction dimensions, bounded logs, and no identifiers in tags.
- Proof: Exact meter/tag/cardinality tests, commit/rollback interleavings, and captured-log canary tests for every operation/result family.
- Adversarial evidence: Foreign denial, self-approval, invalid operand, rollback, corrupt active state, and raw identity canaries.
- Status: PASS

## C31-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: No active merchant profile/policy yields application-owned STANDARD/base behavior; corrupt/unsupported active configuration fails readiness and confirm conservatively rather than silently disabling controls.
- Failure sequence: Absence becomes ALLOW override, unknown feature/operator starts green, multiple active versions choose arbitrarily, or malformed operand is ignored.
- Implementation: Explicit default provider, readiness inventory in the readiness group, strict runtime loader, and conservative REVIEW floor for active-configuration ambiguity.
- Proof: Fresh/no-config, missing/corrupt/multiple/unsupported config, retained config, readiness restart, and current-binary recovery fixtures.
- Adversarial evidence: Partial rollout, retired-only state, unavailable definition, manual SQL corruption, and cached stale configuration.
- Status: PASS

## C31-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Policy size and evaluation cost are bounded by finite profiles, versions, thresholds, operand counts, feature/value sizes, and deterministic preview limits.
- Failure sequence: Unbounded thresholds/IN lists or expensive expressions stall confirm under database locks or exhaust persistence/response memory.
- Implementation: Fixed per-merchant/version limits, allowlisted operations, bounded operand/result sizes, no expression engine, and fixture-only preview.
- Proof: Exact boundary/max+1 tests, deterministic preview timing/result limits, database constraints, and no external dependency calls.
- Adversarial evidence: Huge IN set, duplicate values, maximum strings, many versions, malformed JSON, and concurrent preview/mutation.
- Status: PASS

## C31-OPS-003

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V40 upgrades add policy/profile/evaluation truth without rewriting Chapters 22–30 payment, fraud, review, authentication, context, velocity, list, audit, webhook, or idempotent response bytes.
- Failure sequence: Migration fabricates merchant controls, changes historical decisions/features, weakens immutable triggers, or current binary cannot read retained V40 history.
- Implementation: Forward-only additive V41/V42 migrations and empty-by-default merchant configuration.
- Proof: Fresh V1→current, retained V40→current byte comparisons, Hibernate validation, full suites, gates, and public default/3DS/review/block policy demos.
- Adversarial evidence: Every historical action/context/control cohort, malformed retained seeds, checksum change, and no active config.
- Status: PASS

## C31-PROD-001

- Lens: API_PRODUCT
- Invariant: Fixture preview evaluates a bounded caller-supplied typed fixture against one immutable DRAFT/APPROVED policy without reading historical payments or claiming replay impact.
- Failure sequence: Preview mutates state, calls Redis/processor, reads tenant history, leaks another tenant, or is described as historical simulation/false-positive estimation.
- Implementation: Pure bounded evaluator reuse with explicit `FIXTURE_PREVIEW` language, tenant-qualified non-locking reads, and zero durable decision/payment side effects.
- Proof: Determinism, tenant, no-write/no-external-call, every operator/action, bounded input, and vocabulary contracts.
- Adversarial evidence: Active/retired/foreign policy, missing fixture value, invalid currency, repeat calls, and attempted historical-range fields.
- Status: PASS

## Verified Evidence Index

- `MerchantRiskPolicyContractTests` proves dual control, approval without
  effect, all valid and invalid lifecycle edges, role/tenant/idempotency fences,
  strict threshold and preview grammar, immutable point-in-time evidence, and
  public BLOCK/REVIEW/REQUEST_3DS outcomes with processor exclusion.
- `MerchantRiskPolicyEvaluatorTests` proves LONG/BOOLEAN/STRING × EQ/GTE/IN,
  below/at/above, present/missing/wrong-type, case, set membership, and currency
  partitions. `MerchantRiskPolicyDecisionAggregationTests` proves all base × policy
  candidates plus all insertion permutations and the unavailable REVIEW floor.
- `MerchantRiskPolicyPostgreSqlTests` proves direct immutable/semantic
  enforcement, self-approval and post-submit rejection, deterministic competing
  activation, activation rollback, and activation-first/confirm-first lock
  postconditions on PostgreSQL.
- `PostgreSqlMigrationTests` proves fresh V1→V42 and retained V40→V42 with prior
  review/decision rows unchanged, empty policy/profile/evaluation state, 22 exact
  feature definitions, and production triggers. Hibernate validation passes.
- `MerchantRiskPolicyMetricsTests` and `MerchantRiskPolicyHealthIndicatorTests`
  prove closed tags, exact committed/rolled-back outcomes, no identifier tags,
  safe defaults, dependency-down behavior, and readiness-safe error surfaces.
- Existing confirm rollback, tenant, feature-lineage, list/velocity, review,
  authentication, audit, webhook, and idempotency suites prove the shared
  transaction and protected-action families after the new control is present.
- The full current suite passes 572/572 Java and 97/97 Python tests. The live
  retained-database demo upgraded V40→V42 and proved approval-without-effect,
  activation to REQUEST_3DS/REVIEW/BLOCK, exact replay, fixture-only preview,
  retirement, historical lineage, readiness, and three processor exclusions.
