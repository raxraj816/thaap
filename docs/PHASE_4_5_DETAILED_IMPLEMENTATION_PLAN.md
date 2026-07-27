# Phase 4.5 Detailed Implementation Plan

- Phase: Phase 4.5 — Product-Grade Risk Operations
- Status: COMPLETE — ER-0064 THROUGH ER-0073 RESOLVED AND RE-PROVED
- Entry gate: SATISFIED by Phase 4 / Chapter 35 closure
- Promise freeze: CONFIRMED
- Primary deployables: `apps/payments-api`, `apps/analyst-dashboard-ui`
- Product DRI: Fraud/Risk Product
- Technical DRIs: Risk Platform and Payments Core
- Risk-operations DRI: Risk Operations
- Data/ML DRI: ML Architecture
- Developer-experience DRI: Developer Experience
- Security DRI: Security/Platform
- Current implementation chapter: CHAPTER 43R COMPLETE — PHASE 4.5 CLOSED
- First planned implementation chapter: Chapter 36
- First proof artifact:
  `docs/reviews/CHAPTER_36_INVARIANT_PROOF_MATRIX.md`
- First closure artifact:
  `docs/reviews/CHAPTER_36_CLOSURE_DOSSIER.md`

This plan converts the Phase 4.5 roadmap into an executable product sequence.
Founder implementation authorization on 2026-07-25 confirmed the decisions in
Section 22 as one product set before any Phase 4.5 production code or migration
change. The original matrices retain honest `PRE_IMPLEMENTATION` provenance.
`ER-0064`–`ER-0073` withdrew the earlier completion claim, reopened the affected
families, and are now resolved through implementation plus H2, PostgreSQL,
browser, and governance proof. Chapters 36–43R are again `VERIFIED`/all-`PASS`
with `READY_TO_MERGE`/`MERGE` dossiers.

## 1. Outcome

Phase 4 proved that THAAP can make and operate truthful payment-risk decisions.
Phase 4.5 makes those decisions usable by an analyst, risk manager, merchant
developer, and product reviewer without reading source code or reconstructing
truth from disconnected API calls.

The product loop becomes:

```text
merchant traffic
  -> payment + immutable decision evidence
  -> bounded merchant-risk monitoring
  -> filtered analyst queue + investigation workspace
  -> governed policy/rule simulation
  -> dual-controlled activation or rollback
  -> dispute and webhook operations
  -> trusted developer scenario
  -> observable outcome without rewriting historical truth
```

The challenger wedge is explainable operations. Every dashboard number,
simulation result, search result, policy action, webhook replay, and developer
scenario must answer:

1. Which merchant, time window, traffic-origin policy, and durable facts were
   included?
2. Which facts were available at the stated `asOf` time?
3. Which actor was authorized to view or change the result?
4. Which historical decision remains immutable, and which later operational
   action was appended?
5. Can the result be replayed from stable inputs without contacting Redis,
   inventing labels, or using future information?

## 2. Entry Truth

Phase 4.5 extends the completed V49 substrate:

- merchant-scoped API keys and dedicated `VIEWER`, `ANALYST`, and
  `RISK_MANAGER` credentials
- immutable PaymentIntent, PaymentAttempt, Authorization, FraudDecision,
  FeatureSnapshot, FeatureValue, rule, control-evaluation, review, label,
  refund, chargeback, signal, incident, audit, and webhook evidence
- deterministic `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW` aggregation
- safe RiskContext, versioned HMAC identities, Redis velocity, typed negative
  lists, merchant policy versions, authentication, review actions, chargeback
  offsets, incident detection, and rate limiting
- fenced actor-bound idempotency and deterministic PostgreSQL lock proof
- server-assigned `MERCHANT_API` and `SYNTHETIC_SCENARIO` traffic origin
- 49 forward migrations, 629 backend tests, 114 repository Python tests, zero
  non-resolved debt, and no unresolved escaped finding

The exact starting gaps are:

- risk metrics exist as operational telemetry but not as merchant-visible,
  denominator-defined product facts
- analyst reads have minimal filters and no saved workspace
- free-form notes remain intentionally prohibited
- policy preview evaluates caller-supplied features but cannot replay a
  historical tenant cohort or gate activation
- rules remain application-owned `rules_v1`; merchants have no safe authored
  rule grammar or guarded exception mechanism
- dispute evidence can be created but has no deadline/submission/outcome
  workflow
- webhook delivery evidence exists but merchants cannot verify an endpoint,
  request a manual redelivery, or inspect a dead-letter queue as an operation
- synthetic scenarios are trusted and resumable locally but have no developer
  console control or analytics inclusion policy
- `apps/analyst-dashboard-ui` is planning text only
- there is no general domain outbox, Kafka publisher, analytics projection, or
  general ReplayEngine; those remain Phase 5

## 3. Proposed Phase Promises

These promises are proposed for founder confirmation. Once confirmed, a chapter
may refine internals but may not weaken observable behavior without reopening
the active matrix before code changes.

### 3.1 Merchant and risk-manager promises

- A risk manager can view authorization, decline, chargeback, confirmed-fraud,
  review-backlog, enumeration, and volume-spike indicators with explicit
  numerator, denominator, time window, `asOf`, coverage, and traffic-origin
  policy.
- Versioned monitoring thresholds may append a merchant-risk profile
  recommendation with exact metric evidence. They never activate or rewrite a
  profile automatically; the existing dual-control profile lifecycle remains
  authoritative.
- No product metric is derived from Micrometer counters. PostgreSQL durable
  payment/fraud facts are authoritative; telemetry remains operational only.
- A candidate policy, rule, or safety exception cannot activate until a durable
  simulation over an explicit historical cohort completes and satisfies the
  configured approval gates.
- Activation is dual-controlled. The author cannot approve the same candidate.
- Every activation preserves the prior active version as an explicit rollback
  target and never changes an earlier FraudDecision or explanation.
- Synthetic traffic is excluded from merchant health by default and can be
  included only through an explicit visible filter.

### 3.2 Analyst promises

- Analysts can filter the review queue by stage, status, assignment, outcome,
  fraud type, risk tier, action, reason, risk-context signal, traffic origin,
  SLA state, dispute state, and stable time cursor.
- Saved searches store only validated structured filters, sort, visible columns,
  and owner/scope metadata. They do not store raw SQL, executable expressions,
  credentials, or unbounded identifiers.
- A private saved search is visible only to its owner. A merchant-shared search
  requires `RISK_MANAGER`; ownership and sharing changes are audited.
- Free-form analyst notes are encrypted, length-bounded, access-audited, never
  searchable by content, never copied to webhooks/metrics, and automatically
  removed under a declared retention policy while immutable metadata records
  that a note existed and was redacted.
- A note, saved search, or UI action cannot mutate payment, processor, ledger,
  FraudDecision, FeatureSnapshot, or label truth.

### 3.3 Fraud-policy promises

- Historical simulation evaluates only immutable decision-time features and
  control versions. It never re-fetches current RiskContext or Redis state.
- Simulation binds merchant, cohort interval, `asOf`, traffic-origin policy,
  candidate digest/version, evaluator version, feature-catalogue version, label
  availability cutoff, query digest, and exact aggregate results.
- Reports distinguish labelled, legitimate, confirmed-fraud, contradictory,
  superseded, and unlabeled coverage. Unlabeled rows are never silently counted
  as legitimate.
- The governed rule grammar is a bounded typed JSON abstract syntax tree, not
  JavaScript, SQL, SpEL, regex supplied by merchants, or arbitrary code.
- Rule evaluation remains deterministic. Maximum nodes, nesting depth, string
  length, set size, and evaluation cost are fixed and property-tested.
- Safety exceptions can suppress only explicitly overridable merchant-authored
  candidates. They can never suppress tenant isolation, rate limiting,
  card-testing protection, dependency-unavailable REVIEW floors, payment-state
  validation, authentication requirements, processor truth, or application-
  owned mandatory blocks.
- Every suppressed candidate remains present in decision evidence with the
  exact exception version and reason. No exception creates a fictional ALLOW.

### 3.4 Dispute and webhook promises

- Dispute evidence deadlines and outcomes are simulator facts with explicit
  source and status; they are never described as card-network acceptance.
- Evidence submission freezes an immutable packet version. Correction creates
  a new version before the deadline; it never rewrites submitted evidence.
- A webhook manual replay reuses the immutable original event ID and payload,
  appends an authorized redelivery request and new attempt evidence, and never
  mutates payment or fraud domain truth.
- Receiver deduplication remains keyed by the authenticated signed body event
  ID. A manual replay is at-least-once delivery, not permission to apply the
  business effect twice.
- Endpoint verification uses a short-lived, single-use signed challenge bound
  to merchant, endpoint, destination, generation, and expiry. Redirects and
  destination changes remain forbidden.
- Dead-letter inspection exposes closed error codes and bounded safe attempt
  evidence, never decrypted secrets, response bodies, DNS internals, or raw
  exception text.

### 3.5 Developer and UI promises

- The Phase 4.5 console is local/test-mode product infrastructure, not a claim
  of hosted production identity, SSO, or broad merchant onboarding.
- Browser clients keep merchant/operator credentials in memory only; secrets
  never enter URLs, browser persistence, analytics, logs, automated screenshots,
  test reports, or build artifacts. One-time reveal views are masked by default
  and explicitly marked sensitive.
- The console exposes the deterministic test-card catalogue, idempotency and
  webhook verification examples, scenario runner, analyst queue, monitoring,
  and simulation results using the same public APIs as external clients.
- Only the authenticated scenario-runner boundary can assign
  `SYNTHETIC_SCENARIO`; merchant metadata and browser input cannot forge it.
- Every page has loading, empty, partial-coverage, stale, permission-denied,
  dependency-unavailable, and retry-safe failure states. Absence is never
  rendered as zero.

### 3.6 Data, ML, security, and operations promises

- Product metrics and simulations are point-in-time and tenant-scoped. Their
  use of labels is bound to `available_at <= asOf`.
- Retrospective outcome analysis may use labels known at the simulation cutoff,
  but the candidate decision itself uses only features available at the
  original decision time.
- Raw PAN, CVV, IP, email, phone, address, device/session identifier, list
  value, API key, operator credential, note plaintext, or idempotency material
  never appears in product aggregates, saved filters, simulation rows, logs,
  metrics, audit payloads, webhook evidence, or UI storage.
- Long-running simulation work uses a mature job component with durable job
  identity, bounded chunks, leases/restart semantics, cancellation, and exact
  progress. Phase 4.5 does not invent a queue or claim Kafka semantics.
- Every local mutation commits its domain/audit/idempotency evidence atomically.
  External webhook sends and simulation execution occur outside long database
  transactions and finalize through fenced durable state.
- Fresh and retained V49 upgrades install empty-by-default Phase 4.5 state,
  preserve historical decisions/labels/subscriptions, and validate before
  traffic.

## 4. Scope And Named Exclusions

### 4.1 In scope

- versioned fraud taxonomy and server-owned metric definitions
- bounded merchant-risk monitoring with explicit denominators and coverage
- advanced review filters, saved searches, SLA views, and a thin analyst
  cockpit
- encrypted retained analyst notes with access and redaction audit
- durable historical policy simulation and activation gates
- governed bounded rule authoring and narrowly scoped safety exceptions
- dispute deadlines, versioned evidence submission, and simulator outcomes
- local developer console, documentation examples, and trusted scenario runner
- webhook verification, authorized manual redelivery, and dead-letter reads
- migrations, authorization, privacy, concurrency, operational signals, public
  demos, learning artifacts, and integrated closure

### 4.2 Out of scope without debt

These capabilities are rejected from Phase 4.5 rather than disguised as
unfinished work. They are not implementation debt because Phase 4.5 does not
introduce or promise them.

| Excluded capability | Exact reason | Owner slice | Evidence that unblocks it |
|---|---|---|---|
| General domain outbox, Kafka, and cross-service replay | Phase 4.5 can prove bounded local product reads/jobs from PostgreSQL; it must not claim an event platform | Phase 5 / Durable Events And Analytics | atomic outbox, publisher retry, consumer idempotency, poison handling, lag, replay, and rebuild proof |
| General analytics warehouse or ClickHouse | The Phase 4.5 product loop needs bounded tenant operations, not warehouse scale | Phase 8 / Analytics Scale Decision | measured PostgreSQL SLO breach plus validated event/projection source, retention, partitioning, rebuild, cost, and dual-store reconciliation proof |
| Unified decision and analyst workspace | Phase 4.5 pulls forward only the cockpit needed to operate existing workflows | Phase 6 / Unified Decision Explanation And Analyst Investigation Workspace | freshness-aware transaction detail, related pivots, business insights, accessibility, partial/stale states, and browser E2E proof |
| ML score or probability | Historical simulation is deterministic rule/policy evaluation, not model training or calibration | Phase 7 / Governed ML Baseline And Lifecycle | leakage-safe dataset, model/dataset cards, calibration, segment evaluation, shadow mode, champion/challenger, drift, fallback, and rollback |
| Real network dispute submission | No acquirer/network identity or callback authenticity boundary exists | Phase 10 / External PSP Adapter And Provenance Contract, only if the selected PSP/pilot includes representment | provider contract, authenticated callbacks, reconciliation, credentials, privacy/compliance review, and explicit PSP-versus-THAAP truth |
| Hosted production console identity | Local secrets-in-memory are not production SSO/session security | Phase 9 / Hosted Identity And Platform RBAC | OIDC/SSO, MFA, session/CSRF controls, RBAC, recovery, audit, deployment, and threat model |
| Arbitrary code, SQL, regex, or plugins in rules | Unbounded execution violates determinism, cost, and tenant safety | Rejected product direction | a new accepted ADR and sandbox/proof model; chapter naming alone cannot unblock it |
| Cross-merchant monitoring or shared negative lists | Shared identity and benchmarks expand consent and privacy boundaries | Phase 8 / Privacy-Safe Network Aggregate Intelligence | minimum cohort, contribution bounds, anonymization, non-disclosure, authorization, consent/opt-out, and synthetic attack proof |
| Partial capture/refund, fees, and currency conversion | Phase 4.5 does not change money semantics | Phase 10 / External PSP Adapter And Provenance Contract, only if the selected pilot promotes them through a frozen promise | design-partner need, allocation model, rounding, balanced ledger, lifecycle, and PostgreSQL concurrency proof |

If implementation discovers a genuine Phase 4.5 promise that cannot be met,
it receives the next immutable `TD-NNNN` before deferral. A roadmap destination
alone is not debt evidence.

## 5. Actor And Authorization Contract

Phase 4 roles remain authoritative:

- `VIEWER`: read monitoring, reviews, labels, disputes, webhook status, and
  completed simulation reports; manage only private saved searches
- `ANALYST`: VIEWER rights plus assigned review actions, structured annotations,
  and encrypted notes on assigned cases
- `RISK_MANAGER`: tenant-wide analyst workspace, shared searches, monitoring,
  simulation creation, policy/rule/exception lifecycle, dispute operations,
  incident actions, webhook operations, and note redaction
- merchant API credential: developer console, endpoint/scenario operations, and
  existing merchant APIs; it never impersonates a risk operator

Authorization matrix:

| Action | VIEWER | ANALYST | RISK_MANAGER | Merchant API key |
|---|---:|---:|---:|---:|
| Read monitoring and completed simulation | yes | yes | yes | no |
| Manage private saved search | yes | yes | yes | no |
| Publish merchant-shared saved search | no | no | yes | no |
| Create/read note on assigned review | no | yes | yes | no |
| Read/redact any tenant note | no | no | yes | no |
| Create simulation | no | no | yes | no |
| Author/submit candidate rule, policy, or exception | no | no | yes | no |
| Approve/activate own candidate | no | no | no | no |
| Approve another manager's candidate | no | no | yes | no |
| Submit dispute evidence/record simulator outcome | no | no | yes | no |
| Inspect/replay webhook delivery | no | no | yes | yes |
| Verify endpoint/run trusted scenario | no | no | no | yes |

Every mutation authenticates first, reserves actor-scoped idempotency, and
revalidates live actor/credential status and role under the mutation lock.
Completed replay never bypasses current authentication or authorization.

## 6. Fraud Taxonomy And Merchant-Risk Monitoring

### 6.1 Taxonomy

`FraudTaxonomyDefinition` is an application-owned immutable catalogue with:

- stable `taxonomy_version`, fraud family, fraud type, display label, and
  active-from time
- mappings for existing Phase 4 fraud types, chargeback reasons, incident
  trigger codes, and synthetic scenario labels
- no mutable remapping of historical FraudLabel rows
- an explicit `UNCLASSIFIED` reporting bucket that is not a fraud label

New labels bind the active taxonomy version. Existing labels retain their exact
fraud type and resolve through the immutable V1 catalogue; migration must not
invent a new classification. Filters accept only allowlisted catalogue values.

### 6.2 Metric definitions

Every metric result returns `windowStart`, `windowEnd`, `asOf`, `numerator`,
`denominator`, `value`, `coverageStatus`, `trafficOriginPolicy`, and definition
version. Windows are half-open `[start, end)` and `end <= asOf`.

Initial definitions:

- `AUTHORIZATION_APPROVAL_RATE`: approved Authorizations divided by terminal
  processor Authorization outcomes
- `PROCESSOR_DECLINE_RATE`: terminal declined Authorizations divided by the
  same processor-outcome denominator
- `CHARGEBACK_COUNT_RATE`: captured PaymentIntents with a chargeback available
  by `asOf` divided by captured PaymentIntents eligible in the cohort
- `CONFIRMED_FRAUD_RATE`: attempts with a non-superseded FRAUD label available
  by `asOf` divided by eligible decided attempts; unlabeled remains coverage,
  not legitimate
- `FRAUD_DISPUTE_OVERLAP_RATE`: chargeback-affected attempts with an available
  FRAUD label divided by chargeback-affected attempts
- `ENUMERATION_RATIO`: attempts carrying exact CVC/AVS/card-testing evidence
  divided by confirm attempts with sufficient processor/control coverage
- `REVIEW_BACKLOG`: point-in-time OPEN/ASSIGNED review count plus age buckets;
  there is no fake denominator
- `REVIEW_SLA_BREACH_RATE`: open/terminal reviews crossing the versioned SLA
  threshold divided by reviews due by `asOf`
- `VOLUME_SPIKE_RATIO`: current complete bucket count divided by the median of
  the six prior complete equal buckets, with `INSUFFICIENT_HISTORY` rather than
  division by zero

Monitoring reads query durable PostgreSQL facts under a repeatable `asOf`
contract. Optional immutable `MerchantRiskMetricSnapshot` rows may cache an
exact request/result digest for review evidence, but cached rows are not the
source of truth and cannot create fraud actions automatically in Phase 4.5.
Versioned monitoring thresholds may append a
`MerchantRiskProfileRecommendation` containing the exact snapshot/definition
versions and reason codes. A risk manager must still create or advance a
profile through the existing creator/approver/activation workflow.

## 7. Analyst Workspace, Saved Searches, And Notes

### 7.1 Filter grammar

The queue filter is a typed DTO, not a generic query language. It supports
bounded sets of closed values, validated time intervals, assigned operator,
SLA state, and deterministic `(created_at,id)` cursors. At most 20 filter terms,
50 values across all sets, a 90-day interactive interval, and 100 rows per page
are allowed. Wider historical work belongs to a simulation job.

Saved searches store a canonical versioned JSON filter, canonical sort, visible
column codes, owner, visibility (`PRIVATE` or `MERCHANT_SHARED`), schema
version, digest, and lifecycle. A saved search revalidates against the current
grammar on every execution; an unsupported old version returns an explicit
upgrade-required error instead of changing meaning.

### 7.2 Free-form notes

Notes are operational context, not fraud labels or decision features:

- UTF-8 text, 1–2,000 characters after normalization
- control characters, HTML, URLs with embedded credentials, obvious PAN/CVV,
  API-key, webhook-secret, and operator-secret shapes are rejected
- content is encrypted with AES-256-GCM under a versioned note KEK; associated
  data binds merchant, review, note ID, author, and creation time
- plaintext exists only during the authorized request and is never logged,
  audited, metered, webhooked, indexed, exported, or included in simulation
- default retention is 90 days; retention expiry or manager redaction destroys
  ciphertext and appends a redaction event while preserving safe metadata
- correction appends a new note linked by `supersedes_note_id`; edit-in-place
  and delete-without-audit do not exist
- startup/readiness fails closed when live ciphertext requires an unavailable
  KEK version

Obvious-pattern rejection reduces accidental leakage but is not represented as
perfect content classification. The UI teaches the prohibited-data contract.

## 8. Historical Policy Simulation

Simulation is a durable job, not an HTTP request held open over a large cohort.
Use a mature Spring Batch boundary backed by PostgreSQL. Do not add a custom
queue, broker, scheduler protocol, or distributed worker claim implementation.

Lifecycle:

```text
DRAFT -> QUEUED -> RUNNING -> SUCCEEDED
                         -> FAILED
                         -> CANCELED
```

Each run binds:

- merchant and creating operator
- candidate policy/rule/exception version and canonical SHA-256 digest
- cohort start/end and `asOf`
- traffic-origin inclusion policy, feature catalogue, evaluator version, and
  rule catalogue version
- stable keyset checkpoint, processed/eligible/excluded counts, exclusion
  reasons, and final report digest
- label cutoff and label resolution policy

Evaluation uses stored decision-time FeatureValues and durable control evidence
only. Missing features, unsupported historical catalogue versions, corrupt
lineage, and contradictory labels are explicit exclusions or failures under a
closed policy; they never become ALLOW or legitimate.

The result includes:

- old-action versus candidate-action transition matrix
- protected-action suppression attempts
- labelled fraud caught/missed, known-legitimate friction, contradictory-label
  count, unlabeled coverage, and traffic-origin breakdown
- authorization/capture/refund exposure as descriptive historical amounts,
  never predicted revenue or guaranteed loss
- segment results only for allowlisted, sufficiently populated safe dimensions

Activation gates require `SUCCEEDED`, exact candidate digest, no protected
suppression, minimum cohort/coverage, no blocking data-quality result, creator/
approver separation, and an unexpired report. Default freshness is 24 hours.

## 9. Governed Rules And Safety Exceptions

### 9.1 Rule grammar

The V1 authored grammar is a canonical JSON AST:

```text
rule = { all: [clause...] } | { any: [clause...] }
clause = predicate | rule
predicate = { feature, operator, value }
```

Allowed operators are feature-type specific: numeric comparison, exact enum
membership, boolean equality, and bounded set membership. Maximum depth is 3,
maximum predicates 32, maximum set size 20, maximum serialized bytes 8 KiB.
There is no merchant regex, string contains, script, dynamic field reference,
clock access, network access, or database access.

Lifecycle is `DRAFT -> SUBMITTED -> APPROVED -> ACTIVE -> RETIRED`, with one
active rule version per rule identity, dual control, immutable submitted bytes,
mandatory simulation, and explicit rollback activation of a prior approved
version. Candidate actions are `BLOCK`, `REVIEW`, or `REQUEST_3DS`; authored
rules never emit ALLOW.

### 9.2 Safety exceptions

A safety exception identifies one tenant-owned purpose-HMACed exact subject,
one explicit overridable rule/policy source version, a closed reason, start,
expiry of at most 30 days, author, approver, and required simulation. It may
suppress only that named candidate. It cannot use a broad wildcard, raw PII,
country, BIN, or arbitrary query as identity.

Decision evidence persists both the matched candidate and the applied
exception. Aggregation then chooses the strongest remaining action. Protected
sources are never suppressible; invalid/stale/missing exception evidence is
ignored conservatively and measured.

## 10. Dispute Evidence Operations

The simulator dispute workflow extends immutable chargeback and packet truth:

```text
DRAFT -> READY -> SUBMITTED -> WON | LOST | EXPIRED
                         \-> WITHDRAWN
```

- a reason-code definition supplies a simulated response deadline and required
  structured evidence codes
- each packet version is immutable; only the latest eligible draft may become
  READY/SUBMITTED
- submission at the exact deadline boundary is rejected; the valid interval is
  `now < response_due_at`
- outcome ingestion is deduplicated by authenticated simulator event identity,
  cannot precede submission, and never changes existing chargeback/ledger/
  label truth unless a separately specified future money event exists
- `WON`/`LOST` is simulator workflow truth, not card-network attestation
- missed deadlines append `EXPIRED`; a scheduler retry is idempotent and
  tenant-safe

## 11. Developer Console And Trusted Scenario Runner

The local developer console provides:

- deterministic test-card catalogue and outcome explanations
- copyable idempotency and webhook-signature examples with generated
  placeholders, never live secrets
- endpoint creation, rotation, verification, delivery/dead-letter inspection,
  and manual replay controls
- bounded scenario selection, seed, count, expected outcome, and traffic-origin
  explanation
- run status and sanitized report download from the existing resumable journal

Scenario execution remains server-authorized. The browser requests an allowed
scenario configuration; the runner credential, not merchant metadata, assigns
`SYNTHETIC_SCENARIO`. The console defaults every merchant-health view to exclude
synthetic traffic and displays a persistent inclusion badge when overridden.

## 12. Webhook Operations

### 12.1 Manual redelivery and dead-letter reads

`WebhookRedeliveryRequest` is append-only and binds actor, merchant, delivery,
event, endpoint, request reason, requested generation, and idempotency result.
Under the endpoint then delivery lock, an authorized request may reopen a
terminal delivery projection, increment `delivery_generation`, and schedule a
new append-only attempt. The unique event/endpoint identity remains intact.

An active endpoint is required. The immutable event body and event ID are
reused byte-for-byte; signing uses the valid endpoint generation at dispatch
time. Repeated same-key requests converge; different keys may request distinct
redeliveries only after the prior generation is terminal. Dead-letter views are
filtered terminal deliveries, not a second mutable source of truth.

### 12.2 Endpoint verification

Verification challenge lifecycle is `PENDING -> VERIFIED | FAILED | EXPIRED`.
A random nonce is reveal-once, stored only as a hash, and included in a signed
verification event. The receiver must return the exact bounded challenge token
through the specified authenticated response field. Verification is bound to
the exact normalized endpoint URL and secret generation; rotation or URL
replacement invalidates pending verification.

The existing custom socket transport may be extended only after ADR-0009's
boundary table covers the bounded response field, byte/character grammar,
limits, timeout, redirects, resolved-address identity, TLS posture, and
replacement trigger. No general response-body client is introduced.

## 13. Thin Product UI Contract

Phase 4.5 pulls forward only a narrow UI required to demonstrate and operate
the new backend contracts:

- merchant monitoring summary with numerator/denominator drill-down
- review queue, saved filters, review evidence, and note/action panel
- simulation run creation/progress/report comparison
- policy/rule/exception approval queue
- dispute deadline queue
- developer scenario and webhook operations pages

The recovered UI uses React and TypeScript with TanStack Query for bounded
server-state caching and explicit invalidation. It deliberately retains the
small audited handwritten CSS layer instead of adding Tailwind, and uses
accessible evidence tables instead of Recharts because the current monitoring
promise is exact numerator/denominator/coverage/cohort lineage—not exploratory
visual analytics. Phase 6 may reconsider a charting system when a frozen
visualization promise justifies its dependency and accessibility cost. The UI
must meet keyboard navigation, labelled controls, contrast,
deterministic time-zone display, stable error mapping, and responsive minimum
layout proof. Browser tests cover secret persistence, tenant substitution,
role visibility, stale version conflicts, idempotent retry, empty/partial/error
states, and the integrated demo. Phase 6 extends this cockpit into the unified
decision explanation, analyst investigation, business-insight, and developer-
contract product; it does not rebuild Phase 4.5 workflows.

## 14. Persistence Design

Planned capability-named tables:

- `fraud_taxonomy_definitions`
- `merchant_risk_metric_snapshots`
- `merchant_risk_profile_recommendations`
- `saved_risk_searches`
- `review_note_records`, `review_note_ciphertexts`, `review_note_redactions`
- Spring Batch metadata plus `risk_simulation_runs`,
  `risk_simulation_segments`, and `risk_simulation_results`
- `fraud_rule_versions`, `fraud_rule_predicates`, and
  `risk_safety_exception_versions`
- `dispute_workflow_events` and versioned `dispute_evidence_submissions`
- `webhook_redelivery_requests`, `webhook_endpoint_verifications`, and a
  generation field on operational delivery/attempt evidence

Every tenant-owned relationship uses merchant-qualified composite keys.
Submitted definitions, reports, notes metadata, redaction events, evidence
submissions, redelivery requests, verification attempts, and simulation results
are append-only. Mutable status projections are protected by version/semantic
constraints and backed by append-only lifecycle events. Applied migrations are
never rewritten.

## 15. API Contract

Planned risk-operator APIs:

- `GET /risk/monitoring/summary`
- `GET /risk/monitoring/metrics/{metricCode}`
- `GET|POST /risk/saved-searches`
- `POST /risk/saved-searches/{id}/share`
- `POST /risk/saved-searches/{id}/retire`
- `POST /risk/reviews/{id}/notes`
- `GET /risk/reviews/{id}/notes`
- `POST /risk/reviews/{id}/notes/{noteId}/redact`
- `POST /risk/simulations`, `GET /risk/simulations/{id}`
- `POST /risk/simulations/{id}/cancel`
- governed rule/policy/exception draft, submit, approve, activate, retire, and
  rollback endpoints under `/risk/controls`
- dispute evidence ready, submit, outcome, and deadline queue endpoints
- `GET /risk/webhook-dead-letters`
- `POST /risk/webhook-deliveries/{id}/redeliver`

Planned merchant/developer APIs:

- `POST /webhook_endpoints/{id}/verification_challenges`
- `GET /webhook_endpoints/{id}/verification_challenges/{challengeId}`
- `POST /webhook_deliveries/{id}/redeliver`
- bounded test-card catalogue reads
- trusted scenario run creation/status/report endpoints under a dedicated
  test-mode route

All mutations require `Idempotency-Key`. Lists use stable keyset cursors.
Unknown fields, unknown enum values, duplicate JSON keys, unsupported content
types, partial cursors, oversized intervals, and ambiguous time zones fail
under the standard error contract.

## 16. Transaction And Locking Model

Global ordering extends Phase 4:

```text
idempotency reservation
  -> merchant / operator credential and role
  -> merchant configuration guard
  -> candidate control or saved-search owner
  -> PaymentIntent / PaymentAttempt / ReviewCase / Chargeback when applicable
  -> simulation, dispute, webhook delivery, or verification projection
  -> append-only evidence + audit
  -> idempotency completion
```

Rules:

- monitoring reads use one database-consistent `asOf`; they do not lock payment
  rows or read mutable Redis
- simulation chunks use keyset checkpoints and short transactions; no payment
  or decision row is locked or changed
- candidate activation locks the merchant configuration guard, candidate, and
  exact successful simulation in that order, then revalidates digest/freshness
- note ciphertext and metadata commit atomically; authorized reads decrypt only
  after tenant/role/case validation
- redaction locks note metadata, destroys ciphertext, and appends redaction/audit
  truth in one transaction
- dispute operations lock Chargeback then packet/workflow projection; no new
  ledger effect is implied
- webhook redelivery locks endpoint then delivery; external HTTP remains outside
  the transaction and finalizes through the existing claim token
- endpoint verification locks endpoint then challenge before dispatch/finalize
- no browser, Redis, HTTP, or batch processing occurs while holding an
  unbounded payment or merchant-configuration lock

Required forced PostgreSQL interleavings include simulation-complete versus
candidate-edit/activation, two approvals, activation versus confirm, exception
expiry versus confirm, note redaction versus read, saved-search share versus
owner disable, dispute submit versus deadline expiry, redelivery versus endpoint
disable/rotation, and verification success versus rotation/expiry.

## 17. Pre-Implementation Failure Inventory

Every chapter matrix partitions at least these families:

| Family | Hostile sequences to prove |
|---|---|
| Monitoring | zero denominator, incomplete current bucket, late chargeback/label, synthetic contamination, boundary timestamp, stale cache, incompatible taxonomy |
| Authorization | disabled/wrong-role/wrong-tenant actor, actor-bound replay substitution, creator self-approval, saved-search owner substitution, browser credential persistence |
| Filters/search | unknown field, duplicate value, Unicode, oversized set/interval, cursor tie, retired schema version, shared/private visibility race |
| Notes | PAN/CVV/secret pattern, malicious HTML/control text, wrong case/tenant, unauthorized read, KEK loss/rotation, redaction/read race, retention retry, plaintext sink leakage |
| Simulation | cohort boundary, missing feature, unsupported catalogue, contradictory/superseded labels, future availability, synthetic inclusion, checkpoint crash, cancel/finalize race, candidate digest drift |
| Rule grammar | malformed/duplicate JSON, type mismatch, depth/node/set/byte limits, numeric precision, Unicode/canonicalization, evaluation-order permutation, cost exhaustion |
| Safety exception | raw/wildcard identity, protected source suppression, expiry boundary, HMAC rotation, wrong source version, self-approval, exception/confirm race |
| Dispute | submit before ready, before/at/after deadline, duplicate outcome, outcome before submit, old packet version, scheduler retry, cross-tenant event |
| Webhook operations | replay while delivering, same/different key, disabled/rotated endpoint, stale generation, receiver duplicate, verification redirect/DNS change, nonce replay/expiry, malformed response field |
| UI/developer | secret in storage/URL/log, stale optimistic state, double click, back/refresh, partial API failure, wrong role, synthetic badge omission, unsafe copied example |
| Migration/operations | fresh and retained V49, missing KEK, partial legacy rows, empty-by-default state, Batch restart, worker crash, dependency outage, configuration rollback |

An escaped finding reopens its whole grammar, numeric, concurrency, dependency,
migration, or authorization family and receives an immutable `ER-NNNN`.

## 18. Observability And Operational Contract

Product-facing data and operational telemetry are separate:

- product results contain explicit durable counts, denominators, coverage,
  version, interval, and `asOf`
- metrics expose only closed low-cardinality operation, result, role, job state,
  metric code, control type, and failure-stage tags
- job metrics cover queued age, chunk latency, progress, exclusions, restart,
  cancellation, and terminal outcome
- note metrics cover create/read/redact/retention outcomes but never content,
  note/review/operator/merchant IDs, ciphertext/key version, or rejection value
- webhook metrics distinguish manual redelivery, automatic retry, verification,
  endpoint state, send certainty, and bounded error class
- logs may include safe internal job/request/resource IDs under the existing
  logging policy but no filter values, identity HMACs, note plaintext, event
  payload, response bytes, or secrets

Readiness validates required taxonomy and rule catalogues, active note KEK
inventory, Spring Batch schema, simulation worker mode, existing Redis/HMAC/KEK
requirements, and webhook transport contract. A disabled optional worker may
leave API readiness up only when creation endpoints are also disabled and the
degraded state is explicit.

## 19. Chapter Execution Plan

### Chapter 36 — Fraud Taxonomy And Merchant-Risk Monitoring

- Status: COMPLETE — `ER-0064` resolved; before/exact/after incident-link
  availability passes all 7 inherited PostgreSQL monitoring tests
- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Data/ML DRI: ML Architecture
- Dependency: Chapter 35 complete and founder promise freeze

Build immutable taxonomy definitions, exact metric formulae, tenant-scoped
bounded monitoring reads/snapshots, origin inclusion policy, coverage states,
SLA and spike semantics, non-activating profile recommendations, and the first
monitoring API/demo.

Done when one merchant can compare live-only versus explicitly included
synthetic traffic and drill every number to the exact durable cohort without
PII, current Redis, future labels, fake zeroes, or cross-tenant leakage.
The same demo may show a deterministic risky-merchant recommendation, but only
the existing dual-controlled profile workflow can activate a tier.

Required proof: every numerator/denominator and half-open time boundary,
zero/insufficient coverage, available-at label/chargeback timing, taxonomy
compatibility, origin policy, deterministic SQL, tenant/privacy, fresh/retained
migration, bounded performance, and public API reconciliation.

### Chapter 37 — Analyst Workspace, Saved Searches, And Protected Notes

- Status: COMPLETE (`FROZEN`/`VERIFIED` matrix, all invariants PASS,
  `READY_TO_MERGE` dossier, browser and public PostgreSQL demos PASS)
- Product DRI: Risk Operations
- Technical DRI: Risk Platform
- Security DRI: Security/Platform
- Dependency: Chapter 36 monitoring/filter definitions

Add advanced queue filters, stable saved searches, private/shared authorization,
SLA views, encrypted notes, retention/redaction, and the thin analyst cockpit.

Required proof: complete filter grammar and cursor partitions, saved-search
schema upgrade, owner/share/disable races, note role/case/tenant boundaries,
content limits, secret-pattern rejection, AES-GCM associated data, key rotation
and readiness, redaction/read and retention retry orderings, plaintext sink
scans, browser secret-storage proof, accessibility, and live queue-to-note demo.

### Chapter 38 — Historical Risk Policy Simulation

- Status: COMPLETE — `ER-0065` resolved; mixed-control reconstruction passes
  independent semantic review and all 8 inherited PostgreSQL simulation tests

- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Data/ML DRI: ML Architecture
- Dependency: Chapter 36 metric/taxonomy semantics

Add durable Spring Batch simulation jobs, point-in-time cohort construction,
action-transition reports, label/coverage analysis, checkpoints, cancellation,
restart, candidate digest binding, and the activation gate consumed by later
chapters.

Required proof: cohort/time/origin/label partitions, no current Redis/data
fetch, candidate edit drift, chunk crash/restart without duplicate rows,
cancel/finalize race, exact report digest, tenant/privacy, bounded resource use,
fresh/retained migration, and public create-progress-report demo.

### Chapter 39 — Governed Rule Authoring And Safety Exceptions

- Status: COMPLETE (`FROZEN`/`VERIFIED` matrix, all invariants PASS,
  `READY_TO_MERGE` dossier, public PostgreSQL demo PASS)

- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Security DRI: Security/Platform
- Dependencies: Chapters 30, 31, and 38

Add bounded typed JSON rules, canonicalization, immutable lifecycle, dual
control, simulation-gated activation/rollback, narrow purpose-HMACed safety
exceptions, and complete suppression evidence.

Required proof: grammar/property cross-product, numeric/Unicode/canonical byte
boundaries, cost limits, evaluation permutations, creator/approver separation,
activation/confirm orderings, protected-source catalogue, exception identity/
rotation/expiry/source-version partitions, rollback, historical explanation,
and a public candidate-to-simulation-to-activation demo.

### Chapter 40 — Dispute Deadline And Outcome Operations

- Status: COMPLETE — `ER-0067` resolved; actor-first locking,
  disable-before-submit, and completed replay after disable pass all 19
  PostgreSQL dispute contracts

- Product DRI: Risk Operations
- Technical DRI: Payments Core
- Dependency: Chapter 32 dispute/evidence truth

Add reason-code deadline definitions, immutable evidence versions, readiness,
submission, expiry, deduplicated simulator outcomes, queue filters, and cockpit
views without inventing network or money truth.

Required proof: before/at/after deadline, old/latest packet, required evidence,
submit/expire forced ordering, duplicate/out-of-order outcome, scheduler retry,
tenant/privacy, unchanged ledger/label truth, audit, migration, and public
draft-to-submission-to-outcome demo.

### Chapter 41 — Developer Console And Trusted Scenario Operations

- Status: COMPLETE — V61/V62, separately authenticated origin, deterministic
  leased scenario runs, verified public-API steps, the memory-only console,
  PostgreSQL/client security proof, and the live reconciliation demo pass

- Product DRI: Developer Experience
- Technical DRI: Payments Core
- Supporting DRIs: Risk Operations and Security/Platform
- Dependency: Chapter 21 trusted/resumable generator substrate

Build the local developer console, public test-card catalogue, safe integration
examples, scenario run API, progress/report UI, origin policy controls, and
monitoring reconciliation.

Required proof: runner-only origin assignment, metadata forgery, redirect/proxy
rejection, stable seed/resume, concurrent run ownership, sanitized report and
copy examples, memory-only browser credentials, live/synthetic monitoring
separation, accessibility, and a zero-source-reading merchant integration demo.

### Chapter 42 — Webhook Verification, Redelivery, And Dead-Letter Operations

- Status: COMPLETE — `ER-0066`, `ER-0068`, and `ER-0071` resolved; the
  post-sign terminal-winner PostgreSQL interleaving performs zero network calls

- Product DRI: Developer Experience
- Technical DRI: Payments Core
- Security DRI: Security/Platform
- Dependency: Chapter 21 endpoint idempotency/rotation/fairness substrate

Add short-lived endpoint verification, immutable redelivery requests,
generation-aware append-only attempts, dead-letter filters, console operations,
and the ADR-0009 response-field boundary extension.

Required proof: actor/tenant authorization, same/different-key replay, delivery
and endpoint lock order, disable/rotation/redelivery orderings, exact immutable
event bytes/ID, receiver dedupe, nonce hash/single use/expiry, redirect/DNS/IP/
timeout/response grammar, secret rotation window, bounded rate behavior,
observability, migration, and public verification-failure-redelivery demo.

### Chapter 43 — Integrated Phase 4.5 Closure

- Status: COMPLETE — every Chapter 36–42 recovery proof and aggregate gate pass

- Primary DRI: Founding Staff Engineering
- Supporting DRIs: Payments Core, Risk Platform, Risk Operations, ML
  Architecture, Developer Experience, and Security/Platform
- Dependencies: Chapters 36–42 merge-ready; satisfied

Run fresh/retained migrations, all Phase 4.5 public API and browser demos,
simulation restarts, note key/retention proof, rule property suites, forced
PostgreSQL/webhook orderings, full regression, traceability, a temporally later
hostile pass, source reconciliation, and zero blocking debt/findings.

Phase 4.5 closes only when every matrix is `FROZEN`/`VERIFIED`, every row is
`PASS`, every dossier recommends `MERGE`, and a new merchant can operate the
simulator through the console without reading source code.

### Chapter 43R — Phase 4.5 Product Closure Recovery

- Status: COMPLETE — `ER-0059`–`ER-0073` resolved; all nine closure pairs and
  the aggregate phase gate pass

- Primary DRI: Founding Staff Engineering
- Dependency: Chapter 43 integrated completion claim; satisfied

Recover the missing monitoring/cohort and endpoint-verification cockpit
promises, server-derived role behavior, explicit client failure/retry states,
scenario transport idempotency, frontend dependency truth, and aggregate
Chapter 36–43 closure enforcement. Required proof includes full regression,
all seven public workflows on isolated V64 PostgreSQL, manager/viewer live
browser verification, and one phase-level gate over all nine closure pairs.

## 20. Dependency Graph

```text
Phase 4 / Chapter 35
  -> Chapter 36 taxonomy + monitoring
       -> Chapter 37 analyst workspace
       -> Chapter 38 historical simulation
            -> Chapter 39 governed rules + exceptions
  -> Chapter 40 dispute operations
  -> Chapter 41 developer console + trusted scenarios
  -> Chapter 42 webhook operations
  -> Chapters 36–42 -> Chapter 43 integrated closure -> Chapter 43R recovery
```

Chapters 36–43R and Phase 4.5 are complete. Integrated traceability, the later
hostile review, source reconciliation, all seven public workflows, live browser
verification, fresh/retained migrations, full regression, and the zero-open-
debt claim pass. Shared UI foundations from Chapter 37 remain reusable, but no
later chapter may broaden browser credential or hosted-identity claims.

## 21. Phase Completion Gate

Phase 4.5 is complete only when:

- every merchant-risk metric has exact denominator, window, `asOf`, coverage,
  version, and origin semantics
- analysts can operate filtered queues, saved searches, notes, simulations,
  disputes, and approvals within the actor matrix
- note plaintext has bounded lifecycle, encrypted persistence, audited access,
  retention/redaction proof, and zero prohibited sink leakage
- simulations are point-in-time, restart-safe, candidate-bound, explicit about
  label availability and unlabeled coverage, and mandatory for activation
- governed rules are bounded/deterministic and exceptions cannot suppress
  protected controls or erase matched evidence
- dispute workflow never claims real network or unsupported money truth
- scenario traffic cannot forge trusted origin and is excluded from monitoring
  by default
- webhook verification/redelivery preserve exact event/dedupe truth and safe
  transport boundaries
- the thin UI proves secrets-in-memory, authorization, accessibility, failure
  states, and all core workflows
- Phase 5 durable events/analytics, Phase 6 unified decision/developer product,
  and Phase 7 governed ML remain honestly unclaimed
- fresh/retained migrations, forced concurrency, property, protocol, browser,
  privacy, rollback, public demo, and full regression proof pass
- every chapter matrix/dossier, learning/source artifact, debt/finding register,
  and readiness gate agrees

## 22. Founder-Confirmed Product Decisions

Founder implementation authorization on 2026-07-25 confirmed these decisions
as one product set:

1. Phase 4.5 optimizes first for a usable risk-operations and developer demo,
   not general analytics infrastructure.
2. Pull forward a thin analyst/developer cockpit now; the rebaselined Phase 6
   extends it into unified decision explanation, analyst investigation/business
   insights, and developer contracts rather than leaving Phase 4.5 API-only or
   rebuilding the cockpit.
3. Merchant metrics come from durable PostgreSQL facts with explicit
   denominator/coverage/`asOf`; synthetic traffic is excluded by default.
4. Free-form notes are included only with encryption, 90-day retention,
   redaction events, access audit, no content search/export, and prohibited-data
   guidance/rejection.
5. Historical simulation uses a mature PostgreSQL-backed Spring Batch job and
   decision-time features; it does not wait for or impersonate Phase 5 Kafka.
6. Every policy, authored rule, and safety exception requires fresh simulation
   plus a second risk manager's approval before activation.
7. Authored rules use the bounded typed JSON AST in Section 9 and can emit only
   `BLOCK`, `REVIEW`, or `REQUEST_3DS`.
8. Safety exceptions may suppress only one explicitly overridable
   merchant-authored candidate for one purpose-HMACed subject and at most 30
   days; protected system controls are never suppressible.
9. Dispute deadlines/outcomes remain explicit simulator truth and create no new
   money movement in Phase 4.5.
10. The Phase 4.5 console is local/test-mode with memory-only credentials;
    hosted SSO/session security remains Phase 9.
11. Webhook replay reuses the original authenticated event ID/payload and
    appends redelivery evidence; endpoint verification is generation-bound and
    single-use.
12. Phase 4.5 may close at Chapter 43R only with zero undocumented debt and all
    inherited proof gates green; Phase 5 general events/analytics remains the
    next platform boundary.

The confirmation gate remains satisfied, but the completion gate is reopened.
The implementation remediation and sandbox-safe regressions are green; Chapters
36, 38, 40, and 42 require their named PostgreSQL recovery runs before every
matrix can return to `PASS`, every dossier can recommend `MERGE`, and Phase 5
durable events and analytics can begin.

## 23. Stripe/Mastercard-Style Design Review Narrative

THAAP Phase 4.5 does not confuse telemetry with merchant risk, current state
with historical truth, or a rule simulation with an ML prediction. Product
metrics expose their denominator, coverage, time, origin, and source. Candidate
controls replay immutable decision-time features, retain unlabeled and
contradictory coverage, require dual control, and cannot erase protected safety
evidence. Analyst prose is treated as encrypted regulated operational data, not
casual metadata. Webhook replay preserves authenticated event identity and
receiver deduplication. The console makes the loop usable while explicitly
remaining local/test-mode until production identity and deployment boundaries
exist. The result is a narrower product than Stripe Radar or Mastercard, but a
defensible one: every number and action can be traced to the exact tenant,
actor, time, version, durable evidence, and failure policy that produced it.
