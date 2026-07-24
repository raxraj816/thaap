# Phase 4 Detailed Implementation Plan

- Phase: Phase 4 — Operational Risk Feedback And Adaptive Controls
- Status: COMPLETE
- Entry gate: SATISFIED by Phase 3 / Chapter 25 closure
- Promise freeze: CONFIRMED
- Primary deployable: `apps/payments-api`
- Product DRI: Fraud/Risk Product
- Technical DRIs: Payments Core and Risk Platform
- Data/ML DRI: ML Architecture
- Risk-operations DRI: Risk Operations
- Current implementation chapter: COMPLETE THROUGH CHAPTER 35
- First proof artifact:
  `docs/reviews/CHAPTER_26_INVARIANT_PROOF_MATRIX.md`
- First closure artifact:
  `docs/reviews/CHAPTER_26_CLOSURE_DOSSIER.md`
- Concept foundation:
  `docs/learning/concepts/REVIEW_RESOLUTION_AND_LABEL_LINEAGE.md`

Founder/domain review confirmed the eight product decisions in Section 20 on
2026-07-13 before Chapter 26 production code or migration changes. Planned
classes, tables, APIs, migrations, tests, and metrics below remain proof
obligations until implemented and verified. Chapter 26 began after that freeze
and is now verified complete. Chapters 27–34 are also verified; Chapter 35
remains open and does not inherit a completion claim from those implementations.

## 1. Outcome

Phase 4 closes THAAP's first operational fraud-learning loop and then adds the
smallest adaptive controls needed to improve decisions without sacrificing
payment truth:

```text
merchant payment
  -> safe RiskContext
  -> point-in-time features + velocity + lists + merchant policy
  -> immutable FraudDecision
  -> ALLOW | BLOCK | REQUEST_3DS | REVIEW
  -> authenticated completion or analyst action
  -> processor/refund/dispute truth when authorized
  -> immutable FraudLabel
  -> replayable rule/model feedback lineage
```

The Phase 4 challenger wedge is not maximum rule count. It is a transparent
operator loop in which every action answers five questions:

1. Who was authorized to act, for which merchant, and in which role?
2. What payment and fraud state existed immediately before the action?
3. Which immutable evidence justified the action and label?
4. Which processor, ledger, audit, webhook, and operational side effects were
   allowed or forbidden?
5. Can a later dataset reproduce when the label became available without
   leaking future information?

## 2. Current Substrate And Entry Truth

Phase 4 extends these completed Phase 3 boundaries:

- tenant-scoped merchant API authentication and versioned secret handling
- fenced idempotency reservations and PaymentIntent-first locking
- deterministic `rules_v1` evaluation with complete match/non-match evidence
- immutable FraudDecision, FeatureSnapshot, FeatureValue, and rule lineage
- coherent ALLOW, BLOCK, REQUEST_3DS, and REVIEW payment states
- one open pre-authorization ManualReviewCase for REVIEW
- merchant review reads and atomic merchant cancellation
- deterministic processor, capture, void, full refund, ledger, audit, and
  signed webhook paths
- server-assigned traffic origin and retained migration history through V25

The exact starting boundary is:

- an open review has no analyst identity, assignment, annotation, or resolution
- a fraud REQUEST_3DS has required-action truth but no completion lifecycle
- the seven Phase 3 features contain no device, session, IP, bot, or velocity
  context
- policy is global immutable `rules_v1`; merchants cannot tune it
- no chargeback, post-authorization signal, or label exists
- the deterministic score is severity, not calibrated probability

## 3. Frozen Phase Promises

These founder-confirmed promises define immutable Phase 4 scope. An
implementation chapter may refine internals but may not weaken an observable
promise without reopening its matrix before code changes.

### 3.1 Merchant promises

- Existing merchant payment, review-read, cancellation, refund, webhook, and
  idempotency contracts remain backward compatible.
- Merchant API credentials may create and disable merchant-owned risk operators
  but may not impersonate an operator in analyst audit history.
- A merchant can attach constrained RiskContext to confirm. Raw IP, user agent,
  device/session identifiers, email, phone, or address never persist, appear in
  logs, public responses, metrics, audit payloads, or Redis keys.
- Merchant-specific policy is versioned and tenant-scoped. Activation never
  rewrites an earlier decision or historical explanation.
- A cross-merchant identifier or credential is missing-equivalent and creates
  no review, label, policy, processor, refund, dispute, or list side effect.

### 3.2 Analyst promises

- Every analyst mutation is authenticated as one active merchant-owned
  `RiskOperator` with one explicit role: `VIEWER`, `ANALYST`, or
  `RISK_MANAGER`.
- `VIEWER` is read-only. `ANALYST` may self-assign, release, annotate, and
  execute permitted pre-authorization resolutions. `RISK_MANAGER` may
  reassign, perform money-affecting review actions, manage lists/policies, and
  resolve operational incidents.
- Review assignment, release, annotation, and resolution are separate
  append-only facts. Free-form notes are rejected in Phase 4; annotations use
  closed codes so the product cannot become an uncontrolled PII store.
- A review resolution is idempotent, terminal, immutable, actor-bound, and
  compatible with the review's payment stage. A stale or unauthorized actor
  cannot create processor or money truth.
- Merchant cancellation and analyst resolution have exactly one serialized
  winner. The loser observes committed terminal truth and creates no side
  effect.

### 3.3 Payment and authentication promises

- Pre-authorization review outcomes are `APPROVE`, `CANCEL`, and
  `CANCEL_AND_REPORT_FRAUD`. Refund outcomes are invalid before capture.
- `APPROVE` preserves the original REVIEW FraudDecision and records a separate
  analyst override before one processor continuation. It never converts the
  original decision to ALLOW.
- `CANCEL` and `CANCEL_AND_REPORT_FRAUD` create no Authorization, Capture,
  Refund, or ledger rows.
- Post-authorization review outcomes are `NO_ACTION`, `REFUND`, and
  `REFUND_AND_REPORT_FRAUD`. They are invalid unless the payment and refundable
  balance satisfy the existing refund boundary.
- A fraud or processor authentication challenge authorizes no funds before one
  valid, unexpired, tenant-bound, idempotent simulator completion succeeds.
- Fraud-requested and processor-requested authentication retain different
  source lineage even when they share a completion endpoint.

### 3.4 Fraud and policy promises

- Existing `rules_v1` definitions and results remain immutable and readable.
- Phase 4 controls may add only `BLOCK`, `REVIEW`, or `REQUEST_3DS` candidates.
  No allow-list or merchant policy can suppress a stronger matched safety
  action in Phase 4.
- Final precedence remains `BLOCK > REVIEW > REQUEST_3DS > ALLOW`. Every
  contributing rules, list, velocity, and policy result remains explainable.
- Redis velocity excludes the current logical attempt. A dependency timeout or
  ambiguous result can never silently become zero or ALLOW; affected decisions
  receive explicit unavailable/stale lineage and a minimum REVIEW action.
- Fraud lists use typed, normalized, purpose-HMACed values. Raw card
  fingerprint, IP, email, device, session, customer PII, or generic string is
  never stored as a list entry or cache key.
- Merchant policy uses a constrained typed threshold contract, not arbitrary
  JavaScript, SQL, SpEL, or merchant-provided executable expressions.

### 3.5 Data and ML promises

- `RiskContext`, velocity observations, control evaluations, resolutions,
  labels, challenges, post-authorization signals, chargebacks, and incidents
  carry same-tenant payment lineage and server-assigned traffic origin where
  applicable.
- FraudLabel is append-only evidence, not a mutable truth column. It records
  source, value, fraud type, evidence time, system-availability time, actor or
  source event, and optional supersession lineage.
- Training eligibility uses `available_at`, never the earlier event time, so a
  model cannot learn from a chargeback or analyst outcome that was unknown at
  decision time.
- Contradictory labels remain visible. A later label may supersede an earlier
  label for dataset selection but never deletes or rewrites it.
- No Phase 4 score is called probability, model confidence, or calibration.
  Model version remains null until Phase 7.

### 3.6 Operations promises

- Every local action and all local consequences commit atomically with
  idempotency completion or roll back together.
- Redis state is ephemeral risk coordination, never durable payment, label,
  dispute, or authorization truth. Ambiguity has an explicit conservative
  behavior and bounded expiry.
- Operators can distinguish authorization denial, stale assignment, review
  race, processor continuation, authentication failure, velocity unavailability,
  rate limiting, label conflict, chargeback offset, and rollback using closed,
  bounded dimensions.
- Fresh and supported retained PostgreSQL upgrades validate before traffic;
  every Redis script is versioned, exact-byte tested, and startup-validated.

## 4. Scope And Named Exclusions

### 4.1 In scope

- risk-operator identities, role authorization, credential lifecycle, and audit
- review assignment, release, structured annotation, and resolution
- pre-authorization analyst approval/cancellation and immutable label feedback
- fraud/processor authentication challenge completion
- safe RiskContext capture and point-in-time feature extraction
- Redis velocity, typed negative fraud lists, and conservative failure policy
- versioned merchant risk threshold policies and deterministic aggregation
- chargeback ingestion, refund/dispute offset accounting, and evidence packets
- post-authorization signals, review-to-refund actions, and labels
- card-testing incidents and merchant/API-key/IP rate limits
- migrations, tenant constraints, metrics, demos, hostile reviews, and learning

### 4.2 Out of scope without debt

These are rejected from Phase 4 rather than hidden as unfinished Phase 4 work:

| Excluded capability | Exact reason | Owner slice | Evidence that unblocks it |
|---|---|---|---|
| Free-form analyst notes | Unbounded prose creates PII, retention, search, export, and encryption obligations unrelated to the first feedback loop | Phase 4.5 / Analyst Workspace | approved data classification, encryption/key lifecycle, retention/deletion, redaction, access/export audit, and hostile content tests |
| Allow-list safety overrides | Suppressing a stronger fraud control needs simulation, dual control, blast-radius limits, and rollback | Phase 4.5 / Risk Policy Simulation | replay impact report, approval matrix, canary/rollback, protected-rule catalogue, and false-negative review |
| General rule DSL and custom rules | Arbitrary composition materially expands parser, authorization, validation, and execution risk | Phase 4.5 / Governed Rule Authoring | accepted DSL ADR, grammar/property proof, simulation, approval/canary, versioning, rollback, and sandbox limits |
| General domain outbox/Kafka | Local Phase 4 truth does not require claiming asynchronous publication | Phase 5 / Durable Outbox And Replay | atomic domain/outbox commit, retry publisher, consumer idempotency, replay, lag, and poison-message proof |
| Dashboard UI | Phase 4 owns stable analyst APIs and workflows; UI follows proven contracts | Phase 6 / Dashboard MVP | stable auth/read/action contracts, cursor/filter behavior, accessibility, error/loading states, and end-to-end operator demo |
| Trained model scoring | Labels are necessary but not sufficient for leakage-safe ML | Phase 7 / Leakage-Safe ML Baseline | dataset/model cards, time split, calibration, segment analysis, shadow serving, fallback, and rollback version |
| Cross-merchant lists/features | Shared identity linkage expands privacy, consent, and tenant boundaries | Phase 8 / Graph Investigation | privacy-safe identity design, aggregate thresholds, authorization, non-disclosure proof, and synthetic-ring evaluation |
| Partial capture/refund and fees | Current money model intentionally supports one full capture/refund and no fees | Partial Money Accounting / Payments Core | durable Capture allocation, cumulative locks, fee policy, rounding, balanced ledger, and PostgreSQL concurrency proof |
| Real PSP/ACS callbacks | THAAP remains a simulator and has no approved provider identity or reconciliation boundary | Post-V1 Real PSP Integration | provider selection, hosted/tokenized scope, callback authenticity, async reconciliation, credential controls, and compliance review |

If implementation discovers a genuine Phase 4 invariant that cannot be met, it
must receive the next `TD-NNNN` in the canonical register. A later chapter name
alone is not debt evidence.

## 5. Actor And Authorization Contract

### 5.1 Risk operator identity

`RiskOperator` is a merchant-owned non-PII operational principal:

- immutable `id`, `merchant_id`, and `operator_code`
- role: `VIEWER`, `ANALYST`, or `RISK_MANAGER`
- status: `ACTIVE` or `DISABLED`
- versioned credential prefix, hash, HMAC identity, and lifecycle epoch
- `created_at`, `disabled_at`, and safe creator actor reference

The merchant API credential creates operators through an idempotent endpoint
and receives the operator secret once. Only a prefix/hash and encrypted replay
material needed for bounded creation replay may persist. Operator APIs use
`X-Risk-Operator-Key`; merchant keys are not silently accepted on that surface.

### 5.2 Authorization matrix

| Action | VIEWER | ANALYST | RISK_MANAGER | Merchant API key |
|---|---:|---:|---:|---:|
| Read reviews/labels/signals/incidents | yes | yes | yes | existing merchant-safe reads only |
| Self-assign/release open review | no | yes | yes | no |
| Reassign another operator | no | no | yes | no |
| Add structured annotation | no | assigned case only | yes | no |
| Pre-auth cancel/approve | no | assigned case only | yes | no |
| Refund or refund-and-report-fraud | no | no | yes | existing standalone refund API remains separate |
| Create/disable profile, list, or policy version | no | no | yes | no |
| Resolve incident | no | no | yes | no |
| Create/disable operator | no | no | no | yes |

Disabling a credential prevents new requests but does not rewrite historical
actor lineage. Authorization is checked again under the locked mutation
transaction so disable/action races cannot use a stale pre-check.

## 6. Review Resolution And Label Contract

### 6.1 Review stages and states

Review stage is immutable:

- `PRE_AUTHORIZATION`: created by a REVIEW fraud decision before processor work
- `POST_AUTHORIZATION`: created from a post-authorization risk signal after
  processor/payment truth exists

Review lifecycle:

```text
OPEN -> ASSIGNED -> OPEN       release
OPEN | ASSIGNED -> RESOLVED    analyst resolution
OPEN | ASSIGNED -> CANCELED    merchant cancellation where allowed
```

`RESOLVED` and `CANCELED` are terminal. Assignment history is append-only;
`assigned_operator_id` on the case is only the current projection and is bound
to assignment events.

### 6.2 Outcome compatibility

| Stage | Outcome | Required role | Payment effect | Label effect |
|---|---|---|---|---|
| PRE_AUTHORIZATION | `APPROVE` | assigned ANALYST or RISK_MANAGER | continue the same attempt to processor once | `LEGITIMATE` only with explicit `CONFIRMED_LEGITIMATE`; otherwise none |
| PRE_AUTHORIZATION | `CANCEL` | assigned ANALYST or RISK_MANAGER | cancel intent and attempt; no processor/money rows | none |
| PRE_AUTHORIZATION | `CANCEL_AND_REPORT_FRAUD` | assigned ANALYST or RISK_MANAGER | cancel intent and attempt; no processor/money rows | `FRAUD`, required fraud type |
| POST_AUTHORIZATION | `NO_ACTION` | assigned ANALYST or RISK_MANAGER | none | `LEGITIMATE` only with explicit `CONFIRMED_LEGITIMATE`; otherwise none |
| POST_AUTHORIZATION | `REFUND` | RISK_MANAGER | invoke existing exact full-refund boundary | none |
| POST_AUTHORIZATION | `REFUND_AND_REPORT_FRAUD` | RISK_MANAGER | exact full refund if refundable | `FRAUD`, required fraud type |

An outcome is not a label. The request carries a separate classification:
`CONFIRMED_FRAUD`, `CONFIRMED_LEGITIMATE`, or `INCONCLUSIVE`. Only confirmed
classifications create labels; `INCONCLUSIVE` creates none.
`CANCEL_AND_REPORT_FRAUD` and `REFUND_AND_REPORT_FRAUD` require
`CONFIRMED_FRAUD`, while plain CANCEL and REFUND require `INCONCLUSIVE`.
APPROVE and NO_ACTION allow `CONFIRMED_LEGITIMATE` or `INCONCLUSIVE`. This keeps
service refunds and cautious cancellations from poisoning training data.

### 6.3 Fraud label contract

Initial label values are `FRAUD` and `LEGITIMATE`. Initial fraud types are
closed simulator codes:

- `STOLEN_PAYMENT_CREDENTIAL`
- `ACCOUNT_TAKEOVER`
- `CARD_TESTING`
- `FIRST_PARTY_MISUSE`
- `OTHER_CONFIRMED_FRAUD`

Each label stores:

- tenant, payment intent/attempt, decision, and traffic origin
- source: `MANUAL_REVIEW`, `CHARGEBACK`, or `POST_AUTHORIZATION_SIGNAL`
- label value and nullable fraud type; fraud type is required only for FRAUD
- `evidence_occurred_at` and `available_at`
- source review resolution, chargeback, or signal identity
- actor identity when human-created
- optional `supersedes_label_id` within the same tenant/payment lineage
- created timestamp and immutable reason code

No label mutates FraudDecision, FeatureSnapshot, policy, Authorization, or
earlier label rows.

## 7. RiskContext And Feature Contract

### 7.1 Input boundary

Confirm receives an optional constrained `riskContext` object inside the
existing idempotent request. Raw values exist only in request memory, are
normalized and purpose-HMACed before persistence, and are prohibited from
errors and observability.

Accepted V1 merchant-supplied inputs:

- IP address and ISO country observation
- user-agent, device ID, and session ID
- shipping country, but no free-form or full address
- checkout channel: `WEB`, `MOBILE_WEB`, `IOS`, `ANDROID`, `API`
- customer account age days and login age minutes
- bot score basis points `0..10_000`
- CAPTCHA outcome: `NOT_PRESENT`, `PASSED`, `FAILED`, `UNAVAILABLE`

Country and bot fields are `MERCHANT_OBSERVED`, not trusted network or platform
attestation. The provenance is persisted and exposed in decision explanation.
Email/phone/customer and card correlation comes only from the already-owned,
purpose-HMACed Customer/PaymentMethod substrate; confirm does not accept repeat
raw email, phone, billing address, or card identity fields inside RiskContext.

### 7.2 Persisted context

RiskContext stores only versioned HMACs, closed classifications, bounded
numbers, provenance, key versions, timestamps, tenant/payment lineage, and
traffic origin. It is immutable after decision persistence.

### 7.3 Phase 4 feature additions

| Feature code | Type | Source | Missing/failure behavior |
|---|---|---|---|
| `risk.context_present` | BOOLEAN | confirm request | false |
| `risk.ip_country` | STRING | merchant-observed context | explicit missing |
| `risk.shipping_country` | STRING | merchant-observed context | explicit missing |
| `risk.checkout_channel` | STRING | closed context value | explicit missing |
| `risk.customer_account_age_days` | LONG | bounded context value | explicit missing |
| `risk.login_age_minutes` | LONG | bounded context value | explicit missing |
| `risk.bot_score_bps` | LONG | bounded context value | explicit missing |
| `risk.captcha_outcome` | STRING | closed context value | explicit missing |
| `risk.card_ip_country_match` | BOOLEAN | card and IP country | missing if either absent |
| `risk.card_shipping_country_match` | BOOLEAN | card/shipping country | missing if either absent |
| `velocity.card_attempts_10m` | LONG | Redis reservation | unavailable forces REVIEW floor |
| `velocity.ip_attempts_10m` | LONG | Redis reservation | unavailable forces REVIEW floor |
| `velocity.customer_attempts_1h` | LONG | Redis reservation | unavailable forces REVIEW floor |
| `velocity.merchant_attempts_1m` | LONG | Redis reservation | unavailable forces REVIEW floor |

Raw or hashed identity values are lineage inputs, not FeatureSnapshot values.
Every feature retains definition version, observed time, computed time,
freshness, provenance, and exact typed persistence.

## 8. Redis Velocity And Fraud Lists

### 8.1 Velocity semantics

The current logical attempt is identified by a purpose-HMACed stable member
derived from `(merchant_id, payment_intent_id, next_attempt_number)`. A
versioned Redis Lua operation uses Redis server time to:

1. prune expired sorted-set members
2. determine whether the current stable member already exists
3. count only other members in the requested window
4. add the current member with `ZADD NX`
5. apply bounded expiry and return count, window, script version, and status

This ordering excludes the current payment on first execution and retry. If the
database transaction rolls back, best-effort compensation removes the member;
if compensation fails, the same logical retry still recognizes and excludes
its own member. An abandoned member can only conservatively increase another
payment's count until bounded expiry.

Timeout, unavailable, malformed, wrong-version, and ambiguous-result
partitions never map to zero. The FeatureSnapshot records `UNAVAILABLE`, and
the aggregator applies a minimum REVIEW candidate. No Redis value is payment,
label, processor, or ledger truth.

### 8.2 Fraud lists

Phase 4 supports negative controls for:

- `CARD_FINGERPRINT`, `BIN`, `COUNTRY`, `IP`, `CUSTOMER`, `EMAIL`,
  `EMAIL_DOMAIN`, and allowlisted `GENERIC_STRING` namespaces

Identity-like entries use purpose-separated versioned HMACs. BIN/country use
validated canonical values. Each immutable entry has action (`BLOCK`, `REVIEW`,
or `REQUEST_3DS`), reason, effective interval, creator, and list version.
Deactivation appends lifecycle truth; it does not delete history. Phase 4 has
no ALLOW entry and no safety override.

## 9. Merchant Policy And Decision Aggregation

`MerchantRiskProfile` and `MerchantRiskPolicy` are separate. The profile is an
immutable versioned classification (`STANDARD`, `ELEVATED`, or `HIGH`) with a
closed reason, source (`APPLICATION_DEFAULT` or `RISK_MANAGER`), creator,
approver, and activation time. It becomes the governed
`merchant.risk_tier` feature and may select a default policy template. Phase 4
does not pretend to calculate the tier from chargeback-rate analytics that do
not exist until Phase 4.5.

Merchant policy is an immutable version aggregate with lifecycle:

```text
DRAFT -> PENDING_APPROVAL -> APPROVED -> ACTIVE -> RETIRED
```

Only one version is active per merchant. The creating operator cannot approve
the same version; `RISK_MANAGER` dual control is required. Approval records the
second actor without changing live decisions; activation is a separate locked
transition so the exact approved bytes can be made active or rejected without
silently editing them. A version contains
typed threshold rows over allowlisted FeatureDefinitions, V1 operators
`EQ`, `GTE`, and `IN`, one candidate action, priority, reason code, and optional
currency scope. It cannot contain executable text or an ALLOW override.
Profile activation uses the same dual-control rule, and profile/policy
activation is serialized through one tenant-owned risk-configuration guard.
If no merchant version is active, the application-owned STANDARD profile and
base `rules_v1` behavior apply; absence never disables protected controls.

Decision aggregation evaluates the complete immutable base rules, active list
families, velocity controls, and active merchant-policy thresholds. It persists
one `RiskControlEvaluation` for every finite policy/control definition and a
summary plus matched-entry identity for list checks. Final selection remains:

```text
BLOCK > REVIEW > REQUEST_3DS > ALLOW
```

Within one action, source precedence is protected safety rule, fraud list,
merchant policy, then velocity; numeric priority and immutable code break ties.
All matched reasons remain visible. The action-to-severity mapping remains the
Phase 3 deterministic score and is never called probability.

Phase 4 activation uses schema validation, dual control, protected boundaries,
and deterministic fixture preview. Historical replay impact/canary activation
becomes mandatory when Phase 4.5/5 ReplayEngine exists; it is not falsely
claimed in Phase 4.

## 10. Payment Authentication Completion

An `AuthenticationChallenge` records:

- same-tenant intent, attempt, decision, and optional Authorization lineage
- source: `FRAUD_ENGINE` or `PROCESSOR`
- status: `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`, `CANCELED`
- one-time simulator client-secret prefix/hash, bounded encrypted replay
  material with key version/nonce, and bounded expiry
- challenge generation, completion attempt, safe result code, timestamps, and
  traffic origin

The merchant-authenticated completion endpoint requires idempotency and the
one-time simulator challenge secret. Success never re-evaluates or rewrites the
original FraudDecision. It records authentication evidence, moves the same
attempt toward `PROCESSING`, and continues it to exactly one processor
authorization. Failure/expiry moves the challenge and attempt to explicit
authentication-failed/expired truth, moves the intent to
`REQUIRES_PAYMENT_METHOD`, creates no approved Authorization/Capture, and
requires a new confirm attempt or cancellation. Processor-requested challenges
retain their pre-existing pending Authorization lineage; fraud-requested
challenges have none before successful continuation.

An authenticated continuation is marked in the ProcessorAdapter request. The
simulator must consume that evidence and cannot request a second challenge for
the same attempt. A repeated `REQUIRES_ACTION` result after authenticated
continuation is a bounded processor-contract failure that rolls back rather
than creating a challenge loop.

The confirm or review-approval response that creates a challenge adds a
`nextAction` object containing only challenge ID, source, expiry, completion
endpoint, and the one-time simulator client secret. Exact idempotent replay
returns the same bytes. Non-replay reads never reveal the secret. Startup key
inventory includes every unexpired challenge ciphertext needed for replay.

Challenge completion locks in this order:

```text
idempotency -> PaymentIntent -> PaymentAttempt -> AuthenticationChallenge
  -> optional Authorization -> capture/ledger
```

Same-key replay returns the original response. Distinct-key concurrency has
one winner. Success-versus-expiry and completion-versus-cancel are forced at
real PostgreSQL locks.

## 11. Chargebacks, Post-Authorization Signals, Incidents, And Rate Limits

### 11.1 Chargeback and dispute evidence

V1 chargeback ingestion is a merchant-authenticated simulator operation with a
tenant-scoped external event key and full captured-amount ceiling. It stores
reason, status, received/evidence/due timestamps, payment/authorization/refund
lineage, traffic origin, and explicit
`SIMULATOR_MERCHANT_INGESTED` source attestation. Neither the chargeback nor its
label is represented as independently verified network/acquirer truth.

Financial liability is computed under the PaymentIntent lock from captured
amount minus already-final refund/dispute offsets. A chargeback after a full
refund becomes `OFFSET_BY_REFUND`, leaves the PaymentIntent `REFUNDED`, records
dispute evidence, and posts zero additional merchant debit. If the chargeback
wins first, it allocates the remaining full liability, moves the PaymentIntent
to `DISPUTED`, and a later full refund is rejected. A refund racing a chargeback
has exactly one liability allocation winner. Every non-zero ledger group
balances and references both the original capture and the offset source.

Fraud reason codes create an immutable FRAUD label; non-fraud/customer-dispute
codes do not. `DisputeEvidencePacket` stores only allowlisted structured fields
and safe references, never arbitrary JSON or uploaded documents in Phase 4.

### 11.2 Post-authorization risk signals

Signals are append-only, deduplicated by tenant/source/external event identity,
carry explicit simulator/merchant source attestation, and never mutate the
original FraudDecision. A signal may recommend
`NO_ACTION`, `REVIEW`, or `REFUND`; only REVIEW creates a POST_AUTHORIZATION
case. A RISK_MANAGER resolution owns any actual refund and optional fraud label.

Review-triggered refunds use a versioned closed refund-reason catalogue:

- `RISK_REVIEW_REMEDIATION`
- `CONFIRMED_FRAUD_CUSTOMER_PROTECTION`
- `CUSTOMER_SERVICE_REMEDIATION`

`REFUND_AND_REPORT_FRAUD` requires the confirmed-fraud reason and
classification. Existing standalone merchant refunds receive the explicit
`MERCHANT_REQUESTED` reason for new rows without breaking their request shape;
historical rows are represented as `LEGACY_UNSPECIFIED` by forward migration.
No free-form refund reason persists or appears in audit/webhook truth.

### 11.3 Card-testing incidents

An incident groups merchant-scoped velocity/control evidence into one durable
operational object. Detection can open or update an incident only from persisted
decision/control evidence; it does not treat a transient Redis key as durable
proof. Incident resolution records an operator, closed mitigation code, and
safe before/after state without automatically changing merchant policy.

### 11.4 Rate limits

Rate limits use versioned fixed-window Redis operations for merchant,
credential-prefix HMAC, and IP HMAC dimensions on confirm and payment-method
creation. The decision is separate from fraud scoring:

- definite limit exceedance -> `429` with bounded retry metadata
- Redis unavailable or ambiguous -> `503`, never a fabricated 429 or silent
  acceptance
- retries use the same bounded request identity and do not double consume
- keys contain no raw credential, IP, merchant prose, or card data

Rate-limit configuration is application-owned in Phase 4. Merchant-tunable
limits require Phase 4.5 policy simulation and approval.

## 12. Persistence Design

All merchant-owned tables use UUID primary keys plus tenant-safe composite
identities. Copied semantics are protected by composite foreign keys or checks,
not only application convention.

### 12.1 Chapter 26–27 tables

- `risk_operators`: tenant identity, role, status, lifecycle epoch
- `risk_operator_credentials`: prefix/hash/HMAC version and bounded replay data
- `review_assignment_events`: append-only assign/release/reassign history
- `review_annotations`: closed code, actor, timestamp; no free text
- `review_resolutions`: stage/outcome, actor/role, reason, payment-action source
- `fraud_labels`: append-only value/type/source/evidence/availability lineage
- `manual_review_cases`: stage, current assignment projection, terminal version

### 12.2 Chapter 28–31 tables

- `authentication_challenges`: source/status/secret/result/payment lineage
- `risk_contexts`: safe HMAC/classification/provenance/key-version fields
- `merchant_risk_policy_versions`: tenant/version/lifecycle/dual-control actors
- `merchant_risk_policy_thresholds`: typed constrained predicates/actions
- `merchant_risk_profile_versions`: tenant/tier/source/reason/dual-control actors
- `merchant_risk_configuration_guards`: one active profile/policy serialization
- `fraud_lists` and `fraud_list_entries`: typed versioned negative controls
- `risk_control_evaluations`: immutable per-decision control evidence
- existing FeatureDefinition/FeatureValue catalogue extensions

Redis keys and members are derived ephemeral state, not database identities.

### 12.3 Chapter 32–34 tables

- `chargebacks`: external identity, lifecycle, amount, reason, payment lineage
- `dispute_offset_allocations`: refund/chargeback liability allocation
- `dispute_evidence_packets`: allowlisted structured evidence
- `post_authorization_risk_signals`: immutable recommendation evidence
- `refund_reason_definitions` and Refund reason/version lineage
- `card_testing_incidents` and `incident_evidence_links`
- `rate_limit_policies` only if application-owned configuration requires
  durable startup validation; merchant mutation remains out of scope

Every mutable lifecycle has an append-only event/resolution row or protected
transition grammar. Applied migrations are never edited; repairs are forward.

## 13. API Contract

### 13.1 Merchant-authenticated additions

- `POST /risk/operators` — idempotent creation; secret revealed once
- `POST /risk/operators/{id}/disable` — idempotent terminal disable
- `POST /payment_attempts/{id}/authentication_challenges/{challengeId}/complete`
- existing confirm accepts optional constrained `riskContext`
- `POST /chargebacks` and `GET /chargebacks/{id}`
- `POST /chargebacks/{id}/evidence_packets`
- `POST /post_authorization_risk_signals`

### 13.2 Risk-operator APIs

- `GET /risk/reviews` and `GET /risk/reviews/{id}`
- `POST /risk/reviews/{id}/assign`
- `POST /risk/reviews/{id}/release`
- `POST /risk/reviews/{id}/annotations`
- `POST /risk/reviews/{id}/resolve`
- `GET /risk/labels` and `GET /risk/labels/{id}`
- `POST /risk/lists`, `POST /risk/lists/{id}/entries`, lifecycle endpoints
- `POST /risk/profiles`, submit, approve, activate, and retire endpoints
- `POST /risk/policies`, threshold, submit, approve, activate, retire endpoints
- `GET /risk/signals`, `GET /risk/incidents`, and incident resolution

Every mutation requires `Idempotency-Key`. List endpoints use bounded
limit-plus-cursor pagination with deterministic `(created_at, id)` ordering.
Unsupported methods return the standard contract. Cross-tenant and disabled
operator behavior is missing-equivalent without resource disclosure.

### 13.3 Compatibility

- Existing `/fraud/reviews` merchant reads remain available and do not expose
  operator credential or internal authorization fields.
- Existing Phase 3 decisions and reviews remain readable after migrations.
- Existing idempotent response bytes remain unchanged for requests without new
  fields.
- New webhook event types require explicit endpoint subscription; migrations
  never infer wildcard consent from historical event sets.

### 13.4 Audit and webhook events

Phase 4 local webhook event types are closed and versioned:

- `review.assigned`, `review.released`, `review.resolved`
- `fraud_label.created`
- `authentication_challenge.created`, `authentication_challenge.completed`
- `merchant_risk_profile.activated`, `merchant_risk_policy.activated`
- `chargeback.created`, `dispute_evidence_packet.created`
- `post_authorization_risk_signal.created`
- `card_testing_incident.opened`, `card_testing_incident.resolved`

Operator creation/disable, annotations, rejected authorizations, list/policy
draft edits, and rate-limit decisions are audit/metric truth but are not
merchant webhooks in Phase 4. Existing endpoints receive none of the new event
types without explicit subscription. Endpoints created after the event
catalogue migration may select them normally; omission follows the then-current
creation default and is persisted as explicit creation-time selection.

Audit records exist for every credential, assignment, annotation, resolution,
label, challenge, profile/policy/list lifecycle, chargeback/evidence, signal,
refund action, incident, and configuration mutation. Payloads contain closed
codes and safe resource references only.

## 14. Transaction And Locking Model

Global ordering extends the existing boundary:

```text
idempotency reservation
  -> merchant / risk operator authorization state
  -> PaymentIntent
  -> PaymentAttempt
  -> ManualReviewCase or AuthenticationChallenge
  -> Authorization / Refund / Chargeback allocation
  -> ledger
  -> audit + webhook + idempotent response
```

Rules:

- actor authorization is revalidated inside the mutation transaction
- assignment and resolution lock the case after PaymentIntent/Attempt
- review approval flushes resolution/override evidence before processor use
- refund/chargeback races lock PaymentIntent before allocation and ledger rows
- original decision, feature, rule, and label rows are never updated
- Redis calls occur only at documented ephemeral boundaries; no database lock
  is held while waiting on an unbounded Redis operation
- transaction-outcome observers emit success only after commit and emit bounded
  rollback cause after rollback

Required deterministic PostgreSQL interleavings include:

- analyst resolution versus merchant cancellation
- two analysts assign/resolve the same case
- operator disable versus an in-flight action
- review approval versus duplicate approval and processor continuation
- challenge completion versus expiry/cancel and duplicate keys
- policy activation versus confirm
- list activation/deactivation versus confirm
- refund versus chargeback liability allocation
- signal deduplication and incident open/update

## 15. Pre-Implementation Failure Inventory

Every chapter matrix must partition at least these families:

| Family | Hostile sequences to prove |
|---|---|
| Authorization | missing/disabled/wrong-role/wrong-tenant operator; disable after pre-check; self-approval of policy; unassigned analyst resolution |
| Review lifecycle | assign/release/reassign/resolve/cancel races; stale version; incompatible stage/outcome; annotation after terminal state |
| Processor and money | evidence fails before/after processor call; duplicate approve; refund before capture; refund/chargeback overlap; ledger/audit/webhook failure |
| Label lineage | duplicate source event; FRAUD without type; LEGITIMATE with fraud type; contradictory/superseding labels; future availability leakage |
| RiskContext | malformed IP/country; Unicode/oversize identifiers; HMAC key rotation; prohibited value in error/log/audit/Redis; missing provenance |
| Velocity | below/at/above windows; same-member retry; abandoned member; timeout-before/after apply; script/version mismatch; Redis restart/eviction |
| Lists/policy | normalization collisions; wrong key version; activation race; no active/default policy; invalid type/operator; protected action suppression attempt |
| Authentication | wrong/expired/replayed secret; fraud vs processor source substitution; success/failure/expiry race; approved funds before success |
| Chargeback | duplicate external event; non-fraud reason; amount overflow; already-refunded payment; concurrent refund; evidence due-date boundaries |
| Post-auth/incident | duplicate/out-of-order signals; original decision mutation attempt; transient Redis without durable evidence; incident resolution race |
| Rate limit | exact boundary; retry identity; window rollover; timeout ambiguity; key collision/PII; dimension-specific isolation |
| Migration/operations | fresh/retained V25 upgrade; partial/corrupt seed; old webhook subscription cohort; missing HMAC/KEK; configuration rollback |

An escaped finding reopens its entire boundary family and receives an immutable
`ER-NNNN` with both product evidence and process correction.

## 16. Observability And Operational Contract

Planned bounded metrics include:

- review assignment/resolution count and latency by stage/outcome/role/result
- authorization denials by action/role/cause
- labels by source/value/fraud_type/traffic_origin
- authentication completion by source/result/failure_stage
- risk-context availability/freshness by signal code and provenance
- velocity lookup by dimension/window/status/script_version
- control evaluation by source/action/match and profile/policy/list version class
- chargeback/offset/evidence by reason/status/allocation result
- post-authorization signal and incident lifecycle by closed code
- rate-limit decisions by endpoint/dimension/result
- transaction completion/rollback by operation and bounded cause

No metric tag contains merchant/resource/operator IDs, raw or HMACed identity,
feature values, list values, credentials, free text, exception messages, or
idempotency material. Logs may contain safe internal resource IDs for debugging
only under the existing logging policy; secrets and sensitive values remain
prohibited.

Readiness must validate exact policy/list schema, required HMAC/KEK versions,
Redis connectivity where the feature is enabled, script hashes/versions, and
supported retained migration history. Degraded Redis cannot advertise full
risk readiness; the API exposes the configured conservative behavior.

## 17. Chapter Execution Plan

### Chapter 26 — Analyst Identity, Review Claim, And Label Truth

- Product DRI: Risk Operations
- Technical DRI: Risk Platform
- Supporting DRI: Payments Core for cancellation invariants

Build operator credentials/roles, risk review reads, assignment/release,
structured annotations, `CANCEL`, `CANCEL_AND_REPORT_FRAUD`, and immutable label
lineage. Preserve merchant cancellation and prohibit processor/refund work.

Done when the first real loop is demoable:

```text
REVIEW -> assigned analyst -> structured evidence -> cancel/report fraud
  -> canceled payment + resolved review + immutable FRAUD label
```

Required proof: actor matrix, secret lifecycle, exact review races, rollback at
every local write, label availability lineage, migration, tenant/privacy,
webhook consent, public API demo, and a frozen pre-implementation matrix.

### Chapter 27 — Pre-Authorization Review Approval

- Product DRI: Risk Operations
- Technical DRI: Payments Core
- Dependency: Chapter 26 complete

Add `APPROVE`, immutable analyst override evidence, and exactly-once processor
continuation for the same PaymentAttempt. Reconcile processor decline,
processor 3DS, automatic/manual capture, replay, rollback, and concurrent
merchant cancellation.

Required proof includes every deterministic processor fixture, automatic and
manual capture, same/distinct-key approve, analyst/merchant ordering, operator
disable after pre-check, actual override-flush failure before processor, ledger/
audit/webhook rollback, unchanged REVIEW decision, explicit classification/no-
classification label partitions, and a public approve-to-processor demo.

Chapter 27 does not implement challenge completion. A processor
`REQUIRES_ACTION` remains truthful pending state owned by Chapter 28.

### Chapter 28 — Payment Authentication Completion

- Product DRI: Payments Core
- Technical DRI: Payments Core
- Security DRI: Security/Platform
- Dependency: Phase 4 promise freeze; Chapter 27 processor continuation is
  integrated when present

Add fraud/processor challenge objects, one-time simulator secrets, expiry,
idempotent completion, source distinction, processor continuation, and exact
success/failure/cancel concurrency proof.

Required proof includes secret creation/replay/rotation/readiness, success/
failure/expiry exact-time boundaries, fraud-versus-processor source
substitution, no approved funds before success, no second challenge loop,
automatic/manual capture, duplicate completion, completion/cancel and
completion/expiry forced PostgreSQL orderings, every local rollback position,
and public demos for both challenge sources.

### Chapter 29 — Safe RiskContext And Real-Time Feature Extraction

- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Data/ML DRI: ML Architecture
- Dependency: Phase 4 promise freeze

Add constrained confirm context, purpose-HMAC persistence, provenance, typed
feature catalogue extensions, privacy scans, key rotation/readiness, and
point-in-time explanation without changing existing outcomes yet.

Required proof includes every input grammar/range/missing partition, HMAC
purpose and key-version separation, forbidden request-field rejection,
prohibited-value absence across persistence/log/audit/webhook/error/metrics,
future-time rejection, context/feature/snapshot semantic foreign keys,
same/distinct-key confirm, fresh/retained migrations, exact replay, historical
byte preservation, and all-action outcome parity without Phase 4 controls.

### Chapter 30 — Redis Velocity And Typed Fraud Lists

- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Security DRI: Security/Platform
- Dependencies: Chapter 29 complete and ADR-0010 accepted

Add versioned exact-byte Redis velocity operations, current-attempt exclusion,
conservative failure behavior, typed negative list lifecycle, complete control
evidence, and decision candidates with no allow override.

Required proof includes first/retry/rollback/abandon/expiry Redis members,
below/at/above windows, timeout-before/after-apply ambiguity, restart/eviction,
wrong script hash/version/return grammar, exact current-attempt exclusion, HMAC
key privacy/rotation, every list type/normalization/effective-time partition,
activation/confirm concurrency, same-tenant direct-SQL semantics, REVIEW floor
on dependency uncertainty, and no protected-action suppression.

### Chapter 31 — Merchant Risk Policy And Decision Integration

- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Data/ML DRI: ML Architecture
- Dependencies: Chapters 26 and 30 complete

Add immutable merchant profile and policy versions, governed risk-tier feature,
typed thresholds, dual control, one-active-version locking, safe defaults,
complete evaluation, stable aggregation, historical explanations,
activation/confirm concurrency, and fixture preview.

Required proof includes every status transition, creator/approver separation,
no/missing/corrupt active configuration, type/operator/range/currency grammar,
one-active profile/policy constraints, all match vectors and insertion-order
permutations, protected action precedence, activation/confirm forced orderings,
rollback to prior active configuration, historical decision immutability, and
merchant-specific demos showing block/review/3DS threshold changes.

Chapter 31 does not claim historical impact simulation. Phase 4.5/5 owns that
gate once ReplayEngine exists.

### Chapter 32 — Chargebacks, Dispute Offsets, And Evidence Packets

- Product DRI: Risk Operations
- Technical DRI: Payments Core
- Data/ML DRI: ML Architecture for label availability
- Dependency: Phase 4 promise freeze and existing Chapter 18 refund truth

Add deduplicated chargeback ingestion, fraud-label provenance, exact
refund/dispute liability allocation, balanced ledger groups, structured
evidence packets, deadlines, tenant reads, and forced refund races.

Required proof includes fraud/non-fraud reason taxonomy, duplicate/out-of-order
external events, simulator source attestation, amount/state boundaries,
captured/refunded/disputed cohorts, refund-first and chargeback-first forced
PostgreSQL orderings, zero/non-zero balanced allocations, evidence deadline
before/at/after, allowlisted evidence grammar, label evidence/availability time,
tenant/privacy, rollback, audit/webhook, and public captured/refunded demos.

### Chapter 33 — Post-Authorization Signals And Review Money Actions

- Product DRI: Risk Operations
- Technical DRI: Payments Core
- Supporting DRI: Risk Platform
- Dependencies: Chapters 26 and 32 complete

Add append-only signals, POST_AUTHORIZATION review intake, `NO_ACTION`,
`REFUND`, and `REFUND_AND_REPORT_FRAUD`, RISK_MANAGER authorization, exact
existing-refund reuse, versioned closed refund reasons, label truth, and
original-decision immutability.

Required proof includes source/external-event deduplication, stale/out-of-order
signals, recommendation-versus-action separation, stage/outcome/role matrix,
all refund reason/classification combinations, full refundable-balance checks,
resolution/merchant-refund/chargeback races, actual ledger/audit/webhook
rollback, no decision rewrite, label source/time semantics, and public
signal-to-review-to-refund demos.

### Chapter 34 — Card-Testing Incidents And Rate Limits

- Product DRI: Risk Operations
- Technical DRI: Risk Platform
- Security DRI: Security/Platform
- Dependencies: Chapters 26, 30, and 31 complete

Group persisted control evidence into incidents, add operator resolution, and
enforce bounded merchant/credential/IP rate limits on confirm and payment-method
creation with ambiguity-safe `429`/`503` behavior.

Required proof includes incident open/update/resolve grammar, only-durable-
evidence creation, attempt/decline/distinct-card/BIN/IP aggregation, duplicate
and concurrent incident detection, no automatic policy mutation, rate-limit
below/at/above and rollover boundaries, retry non-consumption, tenant/dimension
isolation, HMAC privacy/key rotation, script ambiguity/restart/eviction,
bounded memory/TTL, exact `429` versus `503`, and an abusive-traffic demo.

### Chapter 35 — Integrated Phase 4 Closure

- Primary DRI: Founding Staff Engineering
- Supporting DRIs: Payments Core, Risk Platform, Risk Operations, ML
  Architecture, and Security/Platform
- Dependencies: Chapters 26–34 complete

Run fresh/retained migrations, all public analyst/merchant demos, exact Redis
fixtures, forced PostgreSQL interleavings, full suites, traceability, a later
hostile failure-sequence pass, source reconciliation, and zero blocking
debt/findings. Phase 4 closes only when every chapter matrix is `VERIFIED`,
every row is `PASS`, and the integrated dossier recommends `MERGE`.

## 18. Dependency Graph

```text
Phase 3 / Chapter 25
  -> Chapter 26 operator + label foundation
       -> Chapter 27 review approval
       -> Chapter 30 list authorization
       -> Chapter 31 policy dual control
       -> Chapter 33 review refund actions
       -> Chapter 34 incident resolution
  -> Chapter 28 authentication completion
  -> Chapter 29 RiskContext
       -> Chapter 30 velocity + lists
            -> Chapter 31 merchant policy
                 -> Chapter 34 card-testing controls
  -> Chapter 32 chargeback + offset accounting
       -> Chapter 33 post-auth review actions
  -> Chapters 26–34 -> Chapter 35 closure
```

Chapters 28, 29, and 32 are dependency-ready after the Phase 4 promise freeze
and may be pulled forward only if each uses its own pre-implementation matrix
and does not blur shared migration/locking boundaries. Chapter 35 is always
last.

## 19. Phase Completion Gate

Phase 4 is complete only when:

- the full merchant-to-label loop works through public APIs
- all promised review outcomes enforce the actor/stage/payment matrix
- authentication completion cannot authorize funds early or twice
- RiskContext and Redis contain no raw sensitive values
- velocity excludes the current logical attempt and fails conservatively
- lists/policies cannot override stronger safety actions
- every decision preserves complete point-in-time control evidence
- refund/chargeback overlap cannot over-credit or double-debit
- post-authorization signals never rewrite the original decision
- incidents depend on durable evidence and rate limits have exact ambiguity
  semantics
- fresh and retained migrations, exact Redis fixtures, forced PostgreSQL
  orderings, rollback, tenant, privacy, API, and public demo proof pass
- every chapter matrix is `VERIFIED`/`PASS`, the integrated dossier says
  `MERGE`, structural gates pass, and no non-resolved debt or blocking escaped
  finding remains

## 20. Founder-Confirmed Product Decisions

Founder confirmation on 2026-07-13 froze these product decisions:

1. Dedicated merchant-owned risk-operator credentials are required; merchant
   API keys do not impersonate analysts.
2. Phase 4 analyst notes are structured codes only; free text moves to Phase
   4.5 after privacy/encryption/retention design.
3. Analyst action and fraud classification are separate; only explicit
   confirmed classifications create labels, and a refund or cancel does not
   automatically mean fraud.
4. Phase 4 lists are negative controls only and cannot suppress a stronger
   safety action.
5. Redis uncertainty creates explicit unavailable lineage and a REVIEW floor
   for risk decisioning; rate-limit uncertainty returns `503`.
6. Policy is constrained typed thresholds with dual control, not a general DSL.
7. Authentication is explicitly simulator truth, never represented as a real
   ACS/network callback.
8. V1 chargeback financial liability is capped by remaining captured value
   after refund/dispute offsets.

The confirmation gate and Chapters 26–35 closure are complete:

- this plan is `COMPLETE`
- Chapter 26 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 26 passed 351 backend tests, 80 Python tests, retained V25→V28,
  deterministic PostgreSQL proof, and the latest-code live public demo
- Chapter 27 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 27 passed 373 backend tests, 83 Python tests, retained V25→V29,
  deterministic PostgreSQL proof, and the latest-code live public demo
- Chapter 28 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 28 passed 404 backend tests, 86 Python tests, fresh and retained V31,
  deterministic PostgreSQL proof, and the latest-code two-source live demo
- Chapter 29 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 29 passed 432 backend tests, 89 Python tests, fresh and retained V34,
  deterministic PostgreSQL proof, exact privacy/HMAC proof, and the latest-code
  all-action live RiskContext demo
- Chapter 30 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 30 passed 541 backend tests, 94 Python tests, fresh and retained V40,
  exact Redis and deterministic PostgreSQL proof, complete aggregation/privacy,
  and the latest-code live list/velocity/outage-recovery demo
- Chapter 31 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 31 passed 572 backend tests, 97 Python tests, fresh and retained V42,
  typed evaluator/aggregation proof, deterministic PostgreSQL concurrency, and
  the latest-code live merchant-policy demo
- Chapter 32 matrix is `FROZEN`/`VERIFIED` and its dossier recommends `MERGE`
- Chapter 32 passed 587 backend tests, 100 Python tests, fresh and retained V44,
  exact dispute/label/evidence proof, deterministic PostgreSQL refund and source
  identity ordering, and the latest-code captured/refunded live demo
- Chapter 33 and Chapter 34 matrices are `FROZEN`/`VERIFIED`, every row is
  `PASS`, and both dossiers recommend `MERGE`
- retained V48 startup and public Chapter 33/34 demos passed; retained V48-to-V49
  migration proof covers identity aliases and idempotency-principal backfill
- full latest-code regression passes 629 Java and 114 Python tests
- all Chapter 26–34 public demos passed sequentially on the V48 binary; V49
  hardening is covered by real Redis, PostgreSQL migration, and public API tests
- Chapter 35 matrix is `FROZEN`/`VERIFIED`, every row is `PASS`, and its
  dossier recommends `MERGE`
- master plan and README identify Phase 4 as complete

The completed Chapter 26 foundation contains the dedicated operator credential
boundary, role-aware risk review reads and mutations, assignment/release events,
structured annotations, pre-authorization cancel/report-fraud resolution,
immutable label lineage, conservative webhook opt-in, and forward V26–V28
migrations. Chapter 27 adds immutable approval override, same-attempt processor
continuation, truthful processor/capture outcomes, and legitimate-label
lineage. Chapter 28 adds source-distinct, expiring, reveal-once simulator
authentication with same-attempt processor continuation and truthful capture,
decline, failure, expiry, and cancel outcomes. Chapter 29 adds safe immutable
RiskContext, purpose-separated correlation HMACs, governed point-in-time
features, and all-action compatibility. Chapter 30 adds exact Redis velocity,
typed negative lists, conservative ambiguity, and point-in-time control lineage.
Chapter 31 adds immutable merchant profile/policy versions, dual control,
typed threshold evaluation, complete action aggregation, safe defaults, and
point-in-time lineage. Chapter 32 adds immutable merchant-ingested chargeback,
refund-offset, ledger, label-availability, and structured evidence truth.
Chapter 33 adds post-authorization signal and review-money truth. Chapter 34
adds durable suspected card-testing incidents and exact Redis rate-limit truth.
Both are complete. Chapter 35 reconciled the complete loop, full suites,
migrations, demos, learning trail, debt/findings, and source status; Phase 4 is
complete.

## 21. Stripe/Mastercard-Style Design Review Narrative

THAAP does not equate a review click with payment or fraud truth. It establishes
a distinct tenant-owned operator identity, authorizes the exact action under a
locked payment/review state, stores immutable resolution and label evidence,
and only then permits the corresponding processor or ledger boundary. Mutable
risk inputs are versioned and recorded point-in-time; Redis ambiguity cannot
silently allow; merchant policy cannot execute arbitrary code or suppress
protected safety outcomes. Post-authorization evidence appends recommendations,
labels, and offsets without rewriting the original decision. Every concurrency,
privacy, migration, and money claim is assigned to a real PostgreSQL, Redis,
API, or ledger proof boundary before implementation begins.
