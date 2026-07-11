# Phase 3 Detailed Implementation Plan

- Phase: Phase 3 — Fraud Decision MVP
- Status: APPROVED_READY_FOR_CHAPTER_22_IMPLEMENTATION
- Entry gate: SATISFIED by Chapter 21 and the Phase 2.5 zero-debt closure
- Primary deployable: `apps/payments-api`
- Product DRI: Fraud/Risk Product
- Technical DRI: Payments Core
- Data/ML DRI: ML Architecture
- First implementation chapter: Chapter 22
- First proof artifact:
  `docs/reviews/CHAPTER_22_INVARIANT_PROOF_MATRIX.md`
- Concept foundation:
  `docs/learning/concepts/DETERMINISTIC_RULE_DECISIONING.md`

## 1. Outcome

Phase 3 turns the existing allow-only fraud lineage into the first truthful,
explainable fraud decision product. A merchant confirmation will produce one
immutable decision-time feature record, evaluate a versioned deterministic
ruleset, preserve each rule result, choose exactly one action, and apply that
action before any processor authorization:

```text
confirm
  -> create PaymentAttempt
  -> materialize point-in-time features
  -> evaluate versioned rules
  -> persist FraudDecision + FeatureSnapshot + RuleExecutionResults
  -> ALLOW       -> processor authorization
  -> BLOCK       -> no processor call; terminal blocked attempt
  -> REQUEST_3DS -> no processor call; action-required attempt
  -> REVIEW      -> no processor call; immutable open review case
```

The Phase 3 wedge is explainability and replayable decision truth, not rule
volume. A merchant or future analyst must be able to answer:

1. What action did THAAP take?
2. Which exact feature values existed at decision time?
3. Which versioned rules were evaluated and which matched?
4. Why did the winning action outrank the alternatives?
5. Was the processor called, and if not, why not?

## 2. Current Substrate

Phase 3 extends, rather than replaces, these proven boundaries:

- tenant-scoped `PaymentIntent` and `PaymentAttempt`
- idempotent confirm with fenced reservation ownership
- one `FraudDecision` and one `FeatureSnapshot` per successful attempt
- `ConfirmDecisionEngine` as the decision boundary
- processor invocation only after `ALLOW`
- safe audit and immutable webhook event envelopes
- server-assigned `traffic_origin` lineage
- H2 service proof, PostgreSQL migration/concurrency proof, and public-API demos

The current `StubConfirmDecisionEngine` always returns `ALLOW` with
`RULES_NOT_ENABLED`. Phase 3 removes that placeholder from new decisions; old
rows retain their historical ruleset and reason code.

## 3. Frozen Phase Promises

These founder-confirmed promises define Phase 3 scope. A chapter may refine its
internal design but may not weaken these observable outcomes without reopening
the current chapter matrix before code changes.

### 3.1 Merchant promises

- Every committed confirm creates exactly one PaymentAttempt and exactly one
  FraudDecision; idempotent replay returns the original attempt and creates no
  new feature, rule, review, processor, audit, or webhook truth.
- The confirm response remains a `PaymentAttempt` response. Its status exposes
  the chosen branch; decision explanation remains available through the
  tenant-scoped fraud-decision read API.
- `BLOCK`, `REQUEST_3DS`, and `REVIEW` never call the processor.
- `ALLOW` preserves the existing deterministic processor and automatic/manual
  capture behavior.
- A cross-merchant identifier is indistinguishable from a missing object and
  creates no decision or review side effect.

### 3.2 Fraud and analyst promises

- Every evaluated rule produces an immutable `RuleExecutionResult`, including
  non-matches, so absence cannot be confused with non-execution.
- Rule precedence is deterministic: `BLOCK > REVIEW > REQUEST_3DS > ALLOW`.
- Within an action, the lowest numeric priority wins; ties are ordered by
  immutable rule code. All matched reasons remain visible even when a stronger
  action wins.
- `REVIEW` creates exactly one tenant-scoped `OPEN` ManualReviewCase linked to
  the attempt and decision.
- Phase 3 review cases support only `OPEN -> CANCELED` through the existing
  merchant PaymentIntent cancellation API. Assignment, analyst notes,
  approve/reject/refund actions, and analyst closure belong to Phase 4.
- Fraud decisions never copy issuer/network decline truth from Authorization.

### 3.3 Data and ML promises

- `FeatureSnapshot` is immutable decision-time truth and is never rebuilt from
  current entity state.
- Every present decision feature has a versioned `FeatureDefinition` and a typed,
  point-in-time `FeatureValue` whose `observed_at <= decision_started_at`.
- Snapshot, feature values, decision, rule results, review case, attempt, and
  payment share the same merchant and `traffic_origin` lineage where the table
  carries origin.
- Offline joins select only feature values observed at or before the attempt's
  decision time. Later mutations cannot change a historical training row.
- Phase 3 has no model score. `model_version` remains null, and the risk score
  is an explicitly documented deterministic rules score.

### 3.4 Operations promises

- A decision and all of its local consequences commit atomically or roll back
  together with idempotency completion.
- Operators can distinguish allow, block, step-up, review, evaluation failure,
  missing feature, and stale feature without raw card/PII or unbounded IDs in
  metrics.
- A fresh database and the supported Chapter 21 schema upgrade through the
  Phase 3 migrations both validate before traffic is accepted.

## 4. Explicit Scope And Deferrals

### 4.1 In scope

- V1 feature catalogue and typed point-in-time persistence
- deterministic in-process rules engine
- immutable rule execution evidence
- action aggregation and deterministic score/risk mapping
- `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW` confirm branches
- review intake and atomic merchant cancellation
- tenant-scoped decision, rule-result, and review reads
- audit, webhook, metrics, migrations, tests, and public demo

### 4.2 Out of scope without debt

The following are rejected from Phase 3 rather than accepted as unfinished
Phase 3 promises. Each boundary has a named owner and concrete unblock proof:

| Excluded capability | Exact reason | Owner slice | Evidence that unblocks it |
|---|---|---|---|
| Redis velocity and RiskContext | Phase 3 first needs immutable feature/rule lineage before adding mutable dependency state | Phase 4 / Real-Time Features And Velocity | RiskContext privacy contract, Redis failure policy, current-attempt exclusion, post-decision increment ordering, and forced failure proof |
| Mutable merchant policy and rule authoring | Activation adds authorization, approval, validation, and rollback blast radius | Phase 4 / Merchant Risk Policy MVP | Versioned draft/approve/activate state machine, policy simulation, tenant authorization matrix, and rollback target |
| Review assignment, analyst resolution, and review-triggered payment actions | Review-to-money mutations need actor authorization and ordered lifecycle/accounting semantics | Phase 4 / Manual Review Lifecycle | role matrix, assignment fencing, idempotent resolution, approve/reject/refund proof, audit, and concurrent action postconditions |
| Chargebacks, labels, and fraud lists | These are post-authorization evidence and feedback truth, not decision execution mechanics | Phase 4 / Chargeback And Fraud Label Feedback | immutable label provenance, dispute/refund ordering, list authorization, and decision non-rewrite proof |
| 3DS challenge completion | `REQUEST_3DS` can truthfully request action, but completion needs a challenge object and authenticated result boundary | Phase 4 / Payment Authentication Completion | challenge lifecycle/schema, idempotent result API, expiry/replay semantics, processor/fraud source distinction, and concurrency proof |
| Kafka/outbox publishing and replay | Phase 3 can commit local truth without claiming asynchronous publication | Phase 5 / Durable Outbox And Replay | atomic domain/outbox write, retryable publisher, consumer idempotency, replay reconciliation, and lag signals |
| Analyst dashboard | A UI before stable reads and review truth would be a mock product surface | Phase 6 / Dashboard MVP | stable transaction/decision/review APIs, pagination contracts, authorization, loading/error states, and end-to-end analyst demo |
| Model training/serving and fallback | A rules score is not a trained probability; ML needs leakage-safe labelled data | Phase 7 / Leakage-Safe ML Baseline | dataset/model cards, time split, calibration and segment reports, shadow evidence, timeout fallback, and rollback version |
| Graph and cross-merchant features | Entity linking expands privacy and tenant boundaries | Phase 8 / Graph Investigation | privacy-safe identity design, authorization boundary, synthetic ring proof, and cross-merchant non-disclosure tests |

If implementation discovers a real Phase 3 invariant that cannot be completed,
it must receive a new `TD-NNNN`; chapter names alone are not deferral evidence.

## 5. V1 Domain Contract

### 5.1 State transitions

| Decision | PaymentAttempt | PaymentIntent | Processor | Additional durable truth |
|---|---|---|---|---|
| `ALLOW` | `READY_FOR_PROCESSOR`, then existing processor outcome | Existing processor lifecycle | Called once | Authorization and existing money truth |
| `BLOCK` | `BLOCKED` | `BLOCKED` | Never called | Fraud audit and webhook event |
| `REQUEST_3DS` | `REQUIRES_ACTION` | `REQUIRES_ACTION` | Never called | `three_ds_status=REQUESTED`, fraud audit and webhook event |
| `REVIEW` | `IN_REVIEW` | `REQUIRES_REVIEW` | Never called | One `OPEN` ManualReviewCase, audit and webhook event |

`REQUEST_3DS` produced by THAAP is fraud-engine step-up truth. It must not
create an Authorization row or pretend that a processor requested 3DS.

`BLOCK` uses the explicit terminal `PaymentIntent.BLOCKED` state. It must not be
collapsed into technical/processor `FAILED` truth. A new payment requires a new
PaymentIntent, preventing retry of the same risk-blocked intent until a future
policy explicitly defines safe reconsideration.

Merchant cancellation from review follows one atomic transition:

```text
PaymentIntent.REQUIRES_REVIEW + PaymentAttempt.IN_REVIEW + ReviewCase.OPEN
  -> PaymentIntent.CANCELED + PaymentAttempt.CANCELED + ReviewCase.CANCELED
```

The existing idempotent PaymentIntent cancel API owns this transition. It locks
PaymentIntent before ManualReviewCase, records `MERCHANT_CANCELED`, emits safe
payment/review audit and webhook truth, and never rewrites the original
`FraudDecision.REVIEW`. Confirm, capture, and payment-method replacement remain
invalid while review is open.

### 5.2 Action aggregation

Rules are evaluated as a complete frozen ruleset. The engine does not stop at
the first match. It persists one result for every rule, then aggregates:

1. no matches -> `ALLOW`
2. otherwise choose the strongest matched action using
   `BLOCK > REVIEW > REQUEST_3DS`
3. choose the primary reason by numeric priority, then immutable rule code
4. preserve every matched reason code in stable precedence/priority/code order

Rule exceptions fail the confirm transaction. Phase 3 does not silently allow
when the only active decision engine cannot produce complete rule evidence.

### 5.3 Deterministic score contract

The rules score is not a probability. It is a stable severity projection:

| Final action | `risk_score` | `risk_level` |
|---|---:|---|
| `ALLOW` | `0.0500` | `LOW` |
| `REQUEST_3DS` | `0.5500` | `MEDIUM` |
| `REVIEW` | `0.7500` | `HIGH` |
| `BLOCK` | `0.9500` | `HIGH` |

The API and concept documentation must call this a deterministic rules score,
not a calibrated fraud probability.

## 6. V1 Feature Catalogue

Phase 3 deliberately uses only durable values already available inside the
locked confirm transaction. Raw PAN, CVV, card fingerprint, API key,
idempotency hash, customer PII, free-form metadata, and description are not
features.

| Feature code | Type | Entity | Source | Observed time | Missing policy |
|---|---|---|---|---|---|
| `payment.amount_minor` | `LONG` | `PAYMENT_ATTEMPT` | locked PaymentIntent amount | PaymentIntent `created_at` | fail decision |
| `payment.currency` | `STRING` | `PAYMENT_ATTEMPT` | locked PaymentIntent currency | PaymentIntent `created_at` | fail decision |
| `payment.capture_method` | `STRING` | `PAYMENT_ATTEMPT` | locked PaymentIntent capture method | PaymentIntent `created_at` | fail decision |
| `payment.attempt_number` | `LONG` | `PAYMENT_ATTEMPT` | new PaymentAttempt | attempt `created_at` | fail decision |
| `card.country` | `STRING` | `PAYMENT_ATTEMPT` | owned tokenized PaymentMethod | PaymentMethod `created_at` | fail decision |
| `card.funding` | `STRING` | `PAYMENT_ATTEMPT` | owned tokenized PaymentMethod | PaymentMethod `created_at` | fail decision |
| `traffic.origin` | `STRING` | `PAYMENT_ATTEMPT` | server-assigned attempt lineage | attempt `created_at` | fail decision |

For Phase 3, a value is `STALE` only if a definition supplies a positive
maximum age and the value exceeds it. The initial durable fields have no expiry
and resolve to `FRESH`. An observed time after decision start is future leakage,
not staleness, and is rejected. All V1 features are required; a missing feature
aborts the decision rather than manufacturing an allow or incomplete evidence.

Snapshot JSON uses exactly the feature codes above in lexical key order. IDs,
BIN, last4, and resource identifiers remain lineage columns, not model features.

## 7. V1 Ruleset

Rules are immutable application-owned definitions seeded by migration. Phase 3
does not expose rule mutation APIs or an arbitrary expression language. This is
intentional: it proves the decision lifecycle before introducing a policy
authoring security boundary.

Ruleset version: `rules_v1`.

All amounts are minor currency units. The rules are simulator policies, not
claims about real-world fraud performance.

| Priority | Rule code | Condition | Action | Reason code |
|---:|---|---|---|---|
| 100 | `BLOCK_USD_EXTREME_AMOUNT_V1` | `payment.currency == USD && payment.amount_minor >= 5_000_000` | `BLOCK` | `EXTREME_AMOUNT` |
| 200 | `REVIEW_REPEATED_ATTEMPT_V1` | `payment.attempt_number >= 3` | `REVIEW` | `REPEATED_PAYMENT_ATTEMPT` |
| 300 | `REQUEST_3DS_USD_ELEVATED_AMOUNT_V1` | `payment.currency == USD && payment.amount_minor >= 100_000` | `REQUEST_3DS` | `ELEVATED_AMOUNT` |

Amount rules are intentionally USD-scoped. Applying one minor-unit threshold to
USD, EUR, GBP, and INR would create false cross-currency risk semantics. Phase
4 merchant risk policy owns additional currency-specific thresholds.

Boundary examples:

- USD `99_999` on attempt 1 -> `ALLOW`
- USD `100_000` on attempt 1 -> `REQUEST_3DS`
- USD `5_000_000` on any attempt -> `BLOCK`
- non-USD `5_000_000` on attempt 1 -> `ALLOW`
- attempt 3 below `100_000` -> `REVIEW`
- attempt 3 at `100_000` -> `REVIEW` because it outranks `REQUEST_3DS`
- attempt 3 at `5_000_000` -> `BLOCK`; both lower matches remain recorded

## 8. Persistence Design

All new tables carry UUID primary keys and tenant-safe composite identities.
Foreign keys include `merchant_id` wherever tenant lineage crosses tables.

### 8.1 `feature_definitions`

- `id`
- `feature_code`
- `entity_type` (`PAYMENT_ATTEMPT` in V1)
- `value_type` (`LONG`, `STRING`, `BOOLEAN`, `DECIMAL`)
- `owner`
- `feature_version`
- `description`
- `max_age_ms`, nullable
- `created_at`
- unique `(feature_code, feature_version)`
- unique `(id, feature_code, feature_version)` for composite lineage

Definitions are global, application-owned, immutable reference data. They
contain no merchant data.

### 8.2 `feature_values`

- `id`, `merchant_id`
- `payment_attempt_id`
- `feature_definition_id`, `feature_code`, `feature_version`
- `value_type`
- exactly one of `long_value`, `string_value`, `boolean_value`, `decimal_value`
- `observed_at`, `computed_at`, `created_at`
- `traffic_origin`
- unique `(merchant_id, payment_attempt_id, feature_code, feature_version)`
- same-tenant foreign key to PaymentAttempt
- check constraint matching `value_type` to exactly one populated value column
- check `observed_at <= computed_at`

The application also enforces `observed_at <= decision_started_at`; migration
and service tests prove the boundary because that timestamp lives on another
table.

### 8.3 `fraud_rules`

- `id`, `rule_code`, `ruleset_version`
- `priority`, `action`, `reason_code`
- constrained `condition_type` (`LONG_GTE` or `CURRENCY_AMOUNT_GTE`)
- `feature_code`, optional `currency_feature_code`, optional `currency_value`
- one typed long threshold value
- `status=ACTIVE`
- `created_at`
- unique `(rule_code, ruleset_version)`

Only the two application-owned predicate shapes required by `rules_v1` ship in
Phase 3. Adding a general JSON DSL requires a later promise freeze and hostile
parser review.

### 8.4 `rule_execution_results`

- `id`, `merchant_id`
- `fraud_decision_id`
- `rule_id`, `rule_code`, `rule_version`
- `priority`, `matched`, `action`, `reason_code`
- `evaluation_outcome` (`MATCH`, `NO_MATCH`)
- `latency_us`, `created_at`
- unique `(merchant_id, fraud_decision_id, rule_code, rule_version)`
- same-tenant foreign key to FraudDecision

Rule code, version, priority, action, and reason are copied intentionally. This
makes historical evidence independent of later reference-data changes. Exact
evaluated inputs remain in the immutable FeatureSnapshot rather than being
duplicated into a second representation.

### 8.5 `manual_review_cases`

- `id`, `merchant_id`
- `payment_intent_id`, `payment_attempt_id`, `fraud_decision_id`
- `status` (`OPEN`, `CANCELED`)
- `reason_codes`
- `traffic_origin`
- `cancellation_reason`, nullable (`MERCHANT_CANCELED` in Phase 3)
- `created_at`, `updated_at`, `canceled_at`, nullable
- unique `(merchant_id, payment_attempt_id)`
- unique `(merchant_id, fraud_decision_id)`
- same-tenant foreign keys across payment, attempt, and decision lineage

Only the existing PaymentIntent cancel API may move `OPEN -> CANCELED` in Phase
3. Phase 3 does not add assignee, analyst notes, analyst resolution, or
review-triggered payment-action fields. Those belong to the Phase 4 lifecycle
migration.

### 8.6 Existing table changes

- add `BLOCKED`, `IN_REVIEW`, and `CANCELED` to `payment_attempts.status`
- add terminal `BLOCKED` and non-terminal `REQUIRES_REVIEW` to
  `payment_intents.status`
- retain existing FraudDecision/FeatureSnapshot rows and constraints
- add database checks for the expanded action/status lineage where enforceable
- never rewrite historical `rules_stub_v1` decisions during migration

## 9. API Contract

### 9.1 Existing APIs retained

- `POST /payment_intents/{id}/confirm`
- `GET /payment_attempts/{id}`
- `GET /fraud/decisions`
- `GET /fraud/decisions/{id}`

Fraud-decision detail expands additively with `ruleExecutionResults`. Results
are ordered by priority then rule code. The existing snapshot remains included.

### 9.2 New review read APIs

- `GET /fraud/reviews?status=OPEN&limit=...&starting_after=...`
- `GET /fraud/reviews/{id}`

Both are merchant-scoped, bounded, cursor-based, and expose no direct review
mutation. Merchant cancellation continues through the existing idempotent
PaymentIntent cancel API. Cross-tenant IDs return the same `404` contract as
missing IDs.

### 9.3 Decision branch response behavior

Confirm remains `201 Created` for a newly committed attempt and returns the
existing `PaymentAttemptResponse` shape:

- `ALLOW`: existing processor-derived terminal/intermediate attempt status
- `BLOCK`: `BLOCKED`
- `REQUEST_3DS`: `REQUIRES_ACTION`, `threeDsStatus=REQUESTED`
- `REVIEW`: `IN_REVIEW`

Idempotent replay preserves the original HTTP status and exact response body.
No branch uses `409` to represent a valid fraud decision.

## 10. Transaction And Locking Model

Confirm keeps the existing idempotency reservation and one mutation
transaction. The required lock order is:

```text
idempotency record ownership
  -> PaymentIntent FOR UPDATE
  -> owned PaymentMethod read
  -> create PaymentAttempt
  -> materialize features
  -> persist decision/snapshot/values/rule results
  -> apply fraud action
  -> optional review case OR optional processor/authorization/money truth
  -> audit and webhook records
  -> complete idempotency response
```

Rules and feature definitions are immutable reference reads and require no
write locks. Review case uniqueness and same-tenant foreign keys are defense in
depth, not substitutes for the PaymentIntent lock.

Forced PostgreSQL proof must cover:

- two different idempotency keys confirming the same eligible PaymentIntent
- same-key replay while the winning decision is blocked inside feature/rule
  persistence
- cancel competing with `REQUEST_3DS` or `REVIEW`
- a rollback after each new persistence boundary

Exactly one coherent transition may commit. No losing transaction may leave a
decision, feature value, rule result, review, processor, ledger, audit, webhook,
or completed-idempotency artifact.

## 11. Failure Inventory

| Family | Hostile sequence | Required outcome |
|---|---|---|
| Rollback | Rule result N persists, result N+1 fails | Entire confirm remains retryable with zero partial lineage |
| Duplicate | Same idempotency key arrives during evaluation | One owner evaluates; replay returns identical committed attempt |
| Concurrent mutation | Distinct keys confirm one intent | PaymentIntent lock permits one coherent attempt |
| Tenant substitution | Merchant A supplies Merchant B intent/method/decision/review ID | Not found; no existence leak or side effect |
| Rule conflict | Block, review, and step-up rules all match | Block wins; every result remains visible in stable order |
| Missing feature | Required amount or origin is absent/corrupt | Fail transaction; never default allow |
| Time lineage | Observed feature timestamp is after decision start | Reject false point-in-time truth |
| Processor separation | Fraud step-up is chosen | No Authorization or processor call is created |
| Review duplication | Replay or race reaches review creation twice | One review case linked to original decision |
| Review cancellation | Merchant cancel races confirm/replay or repeats after case closure | One atomic canceled payment/attempt/case result; original REVIEW decision remains immutable |
| Migration | Legacy stub rows exist during upgrade | Preserved unchanged; new reference data seeds exactly once |
| Serialization | Feature key/type differs across DB JSON and API | Reject before commit; exact typed values reconcile |
| Privacy | Feature/audit/webhook/result captures BIN, last4, fingerprint, PII, or secrets | Tests fail; prohibited data never commits or emits |
| Dependency/config | Active ruleset is missing, duplicated, malformed, or unsupported | Startup/readiness fails before decision traffic |
| Operator error | Rules seed is partly applied or version config names unknown ruleset | Migration/startup fails closed with bounded diagnostic |

## 12. Observability And Operations

Bounded metrics:

- `fraud_decisions_total{action,risk_level,traffic_origin}`
- `fraud_rule_evaluations_total{rule_code,outcome}`
- `fraud_decision_latency_seconds{action}`
- `fraud_feature_freshness_total{status}`
- `fraud_decision_failures_total{stage,cause}`
- `fraud_review_cases_opened_total{traffic_origin}`

`merchant_id`, payment IDs, card metadata, customer identifiers, reason text,
and idempotency keys are forbidden metric dimensions. Structured logs may carry
merchant/payment/attempt/decision IDs for authorized operational correlation,
but never raw payment input, fingerprint, PII, secret, feature values, or
idempotency material.

Readiness validates:

- the configured `rules_v1` exists exactly once
- every active rule references an existing definition of the expected type
- priorities, actions, operators, and typed thresholds are valid
- seeded rules match the application-supported engine contract

## 13. Chapter Execution Plan

### Chapter 22 — Feature Contract And Point-In-Time Lineage

Goal: replace ad hoc snapshot construction with a typed, versioned feature
materialization boundary while preserving allow-only behavior.

Deliver:

- freeze `CHAPTER_22_INVARIANT_PROOF_MATRIX.md`
- V1 `FeatureDefinition` and `FeatureValue` schema/entities/repositories
- seeded feature catalogue
- typed materializer producing both FeatureValues and FeatureSnapshot
- lexical snapshot serialization and strict storage/type guards
- freshness and point-in-time validation
- decision detail remains backward compatible
- migration proof from the real Chapter 21 schema and fresh schema

Proof focus:

- exact typed values and definition linkage
- immutable snapshot/value agreement
- missing/stale/future timestamp partitions
- tenant and traffic-origin lineage
- injected rollback at each feature persistence boundary
- no change to current allow/processor behavior

Done when:

- every new decision has the complete V1 definition/value/snapshot lineage
- historical stub decisions remain readable
- matrix is verified, dossier recommends merge, and demo reconciles feature
  rows against the decision-time public objects

### Chapter 23 — Deterministic Rules, Block, And Fraud Step-Up

Goal: evaluate the complete immutable `rules_v1` catalogue, persist one result
per rule, and make `ALLOW`, `BLOCK`, and `REQUEST_3DS` coherent confirm outcomes.

Deliver:

- pre-implementation Chapter 23 matrix and failure inventory
- seeded `fraud_rules` and `rule_execution_results`
- typed `LONG_GTE` and `CURRENCY_AMOUNT_GTE` evaluators; no arbitrary
  expression parser
- complete-result evaluation and deterministic aggregation
- rules score/risk mapping
- decision-detail rule results
- readiness validation and bounded rule metrics
- PaymentAttempt and PaymentIntent block transitions to explicit terminal
  `BLOCKED`
- fraud `REQUEST_3DS` transition to existing action-required statuses
- explicit distinction between fraud step-up and processor 3DS evidence
- branch-specific audit and webhook truth
- processor-call and automatic-capture exclusion for non-allow branches

Proof focus:

- every below/at/above numeric boundary
- multi-match precedence and tie ordering
- persistence of non-matches
- unsupported type/operator/config startup failure
- rollback and idempotent replay
- amount boundaries and multi-match precedence
- zero processor/Authorization/ledger/capture rows on non-allow branches
- rollback, replay, cancel conflicts, and forced PostgreSQL orderings
- safe API/audit/webhook representations

Done when:

- every new decision uses `rules_v1` and has exactly three ordered results
- allow, block, and step-up are public, idempotent, tenant-safe, and fully
  reconciled; no computed non-allow action is left unapplied

### Chapter 24 — Manual Review Intake And Merchant Cancellation

Goal: make `REVIEW` produce a durable analyst work item with a safe merchant
exit, without prematurely claiming analyst resolution operations.

Deliver:

- pre-implementation Chapter 24 matrix
- PaymentAttempt `IN_REVIEW`; PaymentIntent `REQUIRES_REVIEW`
- `manual_review_cases` with `OPEN -> CANCELED` merchant lifecycle
- bounded tenant-scoped list/detail APIs
- review-opened audit/webhook truth and metrics
- atomic integration with the existing idempotent PaymentIntent cancel API
- explicit rejection of assignment and analyst-resolution mutations

Proof focus:

- attempt 2/3 boundary and conflicts with amount rules
- exactly one case under replay and forced concurrency
- confirm/replay/cancel forced orderings and repeated cancellation
- tenant substitution, cursor bounds, privacy, and rollback
- no processor or money truth on review

Done when:

- the first analyst queue truth exists, can be inspected through public APIs,
  and can leave the queue through coherent merchant cancellation

### Chapter 25 — Integrated Fraud Decision MVP Closure

Goal: prove the complete merchant-to-decision loop across all four actions.

Deliver:

- pre-implementation Chapter 25 integrated matrix and closure dossier
- public demo driving allow, block, step-up, and review through API boundaries
- durable reconciliation of attempts, decisions, feature values/snapshots, rule
  results, review, authorization, ledger, audit, webhook, and idempotency truth
- clean-schema and Chapter 21 upgrade evidence
- operator signal verification
- Phase 3 documentation and source-of-truth reconciliation

Proof focus:

- plan-to-implementation traceability
- temporally separate hostile failure-sequence review
- all precedence partitions and both dangerous concurrent orderings
- no open previous-phase or Phase 3 debt

Done when:

- every Phase 3 promise is `PASS`, the dossier recommends `MERGE`, and a fresh
  public-API demo proves the decision loop without direct database mutation

## 14. Dependency Graph

```text
Chapter 21 zero-debt substrate
  -> Chapter 22 typed point-in-time features
  -> Chapter 23 deterministic rules, block, and step-up
  -> Chapter 24 review intake and merchant cancellation
  -> Chapter 25 integrated closure
```

The sequence remains serial because every chapter changes confirm lineage or
lifecycle truth. This avoids inactive rule code, computed-but-unapplied
actions, and parallel migrations that need semantic reconciliation later.

## 15. Phase Completion Gate

Phase 3 is complete only when:

- Chapters 22–25 each have a pre-implementation matrix and closure dossier
- every matrix lens is `PASS`
- all migrations pass fresh and supported-prior-state PostgreSQL proof
- forced interleavings prove decision/money/review exclusivity
- the public demo reconciles all four action branches
- full backend and repository Python suites pass as regression context
- traceability and a later hostile pass are recorded separately
- technical debt and escaped-review registers are current
- README, master plan, architecture, learning trail, matrices, and dossiers
  agree that Phase 3 is complete

Until then, README must describe Phase 3 as in progress and must not claim a
working fraud MVP from schemas or unit tests alone.

## 16. Design-Review Narrative

In a Stripe/Mastercard-style review, defend Phase 3 this way:

- PaymentAttempt remains the immutable unit of decision and processor lineage.
- The fraud action is completed before processor invocation, so a block cannot
  race into authorization truth.
- Every rule, including non-matches, is retained; explanations are evidence,
  not reconstructed prose.
- Point-in-time FeatureValues make future offline joins possible without
  reading today's mutable customer/payment state.
- Rule precedence and scoring are deterministic and explicitly not presented
  as model probability.
- Tenant keys and database constraints prevent cross-merchant lineage even if
  an application lookup is accidentally weakened.
- Phase 3 creates review intake but does not falsely claim an analyst workflow
  before authorization, resolution, and money-action policies exist.
- The narrow ruleset is a deliberate product slice: it proves trustworthy
  decision mechanics before velocity, mutable policies, and ML increase the
  blast radius.
