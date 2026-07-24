# Chapter 31 Closure Dossier

- Chapter: Chapter 31 — Merchant Risk Policy And Decision Integration
- Chapter number: 31
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_31_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created after Chapter 30 closure and before Chapter 31
production code or migrations. The later traceability and temporally separate
hostile reviews reconciled every frozen promise to its real HTTP, PostgreSQL,
confirm-transaction, migration, telemetry, readiness, and live-demo boundary.

## Observable promises

- Merchant risk profile and policy are separate immutable tenant-owned version
  aggregates with explicit submit, approve, activate, and retire transitions.
- Creator and approver are distinct active RISK_MANAGER operators; approval is
  not activation and configuration bytes cannot change after submission.
- Safe absence yields application-owned STANDARD/base behavior; active-state
  ambiguity is explicit and conservative.
- Every finite threshold is evaluated and persisted point-in-time, and complete
  aggregation can never suppress stronger protected controls.
- Fixture preview is deterministic bounded evaluation, not historical replay or
  a false-positive/authorization-rate simulation claim.

## Failure inventory

The matrix freezes lifecycle/state grammar, role/status/tenant authorization,
self-approval, operator disable races, feature/operator/operand/currency types,
no/one/multiple active versions, activation rollback, activation-confirm
ordering, complete action aggregation, missing/corrupt configuration, immutable
history, risk-tier lineage, preview purity/language, migration, telemetry,
idempotency, privacy, and premature-closure failures.

## Boundary family evidence

- Strict public contracts prove the complete profile/policy lifecycle, distinct
  creator/approver authorization, typed threshold grammar, exact replay,
  tenant fences, fixture-preview purity, and every protected final action.
- Real PostgreSQL proves append-only and semantic enforcement, direct-SQL
  self-approval/post-submit rejection, one-active serialization, rollback to the
  prior active version, and activation-first/confirm-first lock postconditions.
- Table-driven evaluator and aggregation suites cover LONG/BOOLEAN/STRING across
  EQ/GTE/IN, all base/policy action pairs, and every insertion permutation.

## Enforcement boundaries

Strict DTOs/enums, catalogue-bound typed operands, existing risk-operator
credentials with under-lock revalidation, a merchant configuration guard,
shared/exclusive confirm/lifecycle ordering, V41/V42 append-only profile/policy/
threshold/event/evaluation lineage, complete action aggregation, and
after-completion telemetry enforce the frozen promises. Preview deliberately
uses a tenant-qualified non-locking read because it is pure, bounded, and does
not claim to serialize with activation or historical payments.

## Exact proof

- The complete Java suite passes 572/572 with zero failures, errors, or skips.
- The complete Python structural/demo suite passes 97/97.
- `MerchantRiskPolicyContractTests` proves dual control, lifecycle,
  tenant/role/idempotency fences, strict policy/preview grammar, historical
  lineage, public actions, and processor exclusion.
- `MerchantRiskPolicyEvaluatorTests`, `MerchantRiskPolicyDecisionAggregationTests`,
  `MerchantRiskPolicyPostgreSqlTests`, metrics, and health suites prove
  every typed, ordering, rollback, telemetry, and readiness family at its real
  boundary.

## Migration evidence

Fresh V1→V42 applies all 42 migrations and validates Hibernate mappings. The
retained V40→V42 cohort preserves prior review, decision, context, velocity,
fraud-list, authentication, and response truth byte-for-byte while creating no
profile, policy, threshold, or evaluation rows. The current binary separately
upgraded the live retained local database from V40 to V42 and started with
readiness `UP`.

## Demo evidence

`scripts/demo-merchant-risk-policy.py` passed against the retained
V42 database. It proved distinct creator/approver dual control, approval without
decision effect, activation changing only future decisions to REQUEST_3DS,
REVIEW, and BLOCK, byte-exact replay, fixture-only preview, immutable historical
lineage, retirement behavior, three processor exclusions, and readiness `UP`.
Public contract and aggregation suites separately prove application-owned
STANDARD/base behavior, tenant isolation, and protected-action precedence.

## Operational signals

Closed operation/result/state/action/resource/transaction metrics distinguish
committed and rolled-back lifecycle/evaluation outcomes without merchant,
operator, configuration, feature-value, credential, or idempotency identifiers.
Readiness treats absent configuration as safe `UP` and incompatible active
inventory as `DOWN`; confirm converts active-configuration ambiguity into a
conservative REVIEW floor rather than silent ALLOW.

## Known debt

None accepted. Any real deferral requires the next immutable `TD-NNNN` with a
named owner slice, DRI, trigger/deadline, and done evidence. P0 work cannot be
deferred.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 31 must use strict Jackson/Bean Validation, Spring
transactions, PostgreSQL, the existing feature lineage and action aggregator,
and bounded typed evaluation. It must not introduce a policy language, parser,
expression engine, queue, cache, or proprietary protocol.

## Escaped-review assessment

PASS. The live pre-closure demo found that fixture preview attempted a
`SELECT ... FOR UPDATE` inside a read-only PostgreSQL transaction. Preview now
uses a tenant-qualified non-locking read while lifecycle mutations retain their
locking path, and the live demo plus regression suite pass. This occurred before
the first Chapter 31 completion claim. The later post-claim nested-HMAC review
registered ER-0054; V49 retained inner aliases and IP/device/session rolling-key
match tests resolve it.

## Source-of-truth reconciliation

Chapters 26–31 are verified complete. The Phase 4 detailed plan, master plan,
READMEs, daily note, weekly review, matrix, and this dossier advance the active
implementation frontier to Chapter 32 while Phase 4 remains open through
Chapters 32–35.

## Merge recommendation

`MERGE`. Every matrix row is `PASS`; real PostgreSQL, fresh/retained migration,
public live demo, complete regression, traceability, hostile review, and
repository gates are the required final evidence. There is no blocking debt or
escaped finding.
