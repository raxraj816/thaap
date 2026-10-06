# Phase 6 Detailed Implementation Plan

- Phase: Phase 6 — Decision Intelligence Workspace And Developer Product
- Status: FOUNDER CONFIRMED — IMPLEMENTATION COMPLETE AND PROVEN
- Entry gate: SATISFIED by Phase 5 / Chapter 52 closure and merge
- Promise freeze: CONFIRMED 2026-08-02
- Merchant-facing product destination: FOUNDER CONFIRMED 2026-07-27
- Primary deployables: `apps/payments-api`, `apps/analyst-dashboard-ui`, and
  the supported Python sandbox SDK
- Product DRIs: Fraud/Risk Product, Risk Operations, Product Design, and
  Developer Experience
- Technical DRIs: Risk Platform and Payments Core
- Security DRI: Security/Platform
- Data/analytics DRI: Data Platform
- Vision coverage: `VP-004`, `VP-005`, and the Phase 6-owned portion of
  `VP-016`
- Planned chapters: Chapters 53–59
- Planned forward migrations: V86–V91
- First dependency-ready chapters after confirmation: Chapter 53 and Chapter 57
- Frozen proof artifacts: Chapter 53–59 `PRE_IMPLEMENTATION` matrices under
  `docs/reviews/`
- Closure artifacts: Chapter 53–59 `READY_TO_MERGE` / `MERGE` dossiers under
  `docs/reviews/`
- Escaped findings from the later hostile pass: `ER-0096`, `ER-0097`, `ER-0098`,
  all resolved with product evidence and a process correction
- Concept foundation:
  `docs/learning/concepts/SOURCE_RECONCILED_INVESTIGATION_AND_EXECUTABLE_API_CONTRACTS.md`

Phase 6 implementation status: COMPLETE

This plan converts the Phase 6 roadmap into an executable product sequence.
It is deliberately not a dashboard rewrite. Phase 4.5 already provides a thin
analyst cockpit, review queue, saved searches, protected notes, monitoring,
simulation, governed controls, disputes, webhook operations, and developer
scenarios. Phase 5 already provides generation-qualified payment/merchant
analytics, freshness, coverage, rebuild, and point-in-time replay datasets.

Phase 6 composes those capabilities into two believable user journeys:

1. an analyst can search, open, explain, investigate, and safely act on a
   payment from one coherent workspace; and
2. a merchant developer can integrate from a versioned executable contract,
   supported SDK, and sandbox guide without reading THAAP source code.

The founder confirmed the decisions in Section 24 as one coherent product set
on 2026-08-02. The affected vision rows are reconciled, and all Chapter 53–59
pre-implementation matrices are frozen proof contracts with verified closure
dossiers. Chapters 53 and 57 were the independent implementation entries;
dependent chapters froze their promises before predecessor implementation could
bias their evidence. Chapters 53–59 have passed their matrix and dossier gates.

## 1. Outcome

The analyst mental model is one investigation envelope around one payment:

```text
freshness-aware search result
  -> authoritative payment and attempt snapshot
  -> complete decision explanation
  -> canonical payment-stream timeline
  -> bounded same-merchant relationship pivots
  -> review / label / dispute / money / webhook evidence
  -> authorized action through the existing source mutation API
  -> visible audit, event, queue, and outcome effect
```

The developer mental model is one executable contract:

```text
versioned OpenAPI
  -> generated typed Python client
  -> safe sandbox credentials and test data
  -> idempotent payment confirmation
  -> exact error/retry handling
  -> signed webhook verification and deduplication
  -> risk analytics readback
```

The challenger wedge is not the number of panels. It is the ability to answer,
without database access or oral caveats:

1. What happened to this payment, in authoritative business order?
2. Why did the fraud engine choose this action, including controls that did not
   match or were unavailable?
3. Which later human, label, dispute, webhook, and ledger facts changed the
   operational understanding without rewriting the original decision?
4. Which related transactions are linked, by what safe same-merchant reason,
   over what bounded time range?
5. How fresh and complete is every derived number or relationship?
6. Can a new merchant reproduce the journey from published contracts alone?

## 2. Entry Truth

Phase 6 starts from the merged V85 product:

- authoritative merchant-scoped payment, attempt, processor, authorization,
  capture, refund, chargeback, ledger, fraud decision, feature snapshot,
  review, label, dispute, incident, control, audit, and webhook state
- dedicated `RISK_VIEWER`, `RISK_ANALYST`, and `RISK_MANAGER` identities with
  live-actor checks and tenant-qualified authorization
- review assignment/resolution, saved review searches, encrypted protected
  notes, monitoring, governed simulation/control activation, and dispute flows
- a local React cockpit with memory-only credentials and per-slice failure
  handling
- 42 immutable canonical event types, exact event bytes, tenant-qualified
  payment streams, atomic outbox, Kafka publication, inbox deduplication, and
  quarantine/recovery
- generation-qualified `payment-risk-v1` and `merchant-risk-v1` projections
  with cutoff, freshness, coverage, origin policy, rebuild, activation, and
  rollback
- immutable offline replay datasets and public developer scenario operations
- 854 backend tests, 166 repository tests, 22 UI tests, fresh/retained V85
  migration evidence, zero open registered debt, and zero unresolved escaped
  finding at the Phase 5 merge

The exact starting gaps are:

- payment and fraud evidence is available through separate APIs, but no single
  risk-operator contract reconciles the complete lifecycle with section-level
  authority and availability
- the cockpit can show projection detail but not a complete source-backed
  decision explanation or lifecycle timeline
- no bounded server-derived relationship API pivots from a seed payment to
  same-merchant transactions without accepting a raw identifier
- payment investigation searches cannot be saved independently of review-queue
  searches
- there is no bounded, restart-safe bulk review-assignment workflow
- queue productivity, SLA movement, review yield, decision outcome, label
  coverage, and money impact are not presented as one denominator- and
  coverage-honest product surface
- public endpoints have request IDs and a common error envelope, but there is
  no formal API version lifecycle or complete stable error-code catalogue
- there is no generated OpenAPI contract, compatibility baseline, or supported
  SDK package
- developer quickstarts exist as scripts, but the contract, SDK, webhook,
  idempotency, test-card, and analytics journey is not one cohesive portal
- the current single-page cockpit lacks durable route/deep-link semantics for
  an investigation workspace

Phase 6 reuses rather than replaces these truths:

1. source domain tables remain authoritative for payment, fraud, review,
   label, dispute, ledger, webhook, and actor state
2. canonical domain events provide immutable business ordering and lineage;
   they never authorize a new mutation
3. Phase 5 projections remain the discovery and aggregate-analytics source and
   always carry generation/freshness/coverage
4. audit logs remain security/accountability evidence and are not rendered as
   payment truth
5. mutations continue through their existing typed services and lock orders;
   the workspace never writes source tables directly

## 3. Proposed Phase Promises

These promises were founder-confirmed and frozen on 2026-08-02. A later chapter
may refine implementation details but may not weaken an observable promise
without reopening the active matrix before code changes.

### 3.1 Analyst investigation promises

- One risk-operator route opens a payment investigation that reconciles
  payment, attempts, processor outcomes, fraud decisions, feature snapshots,
  evaluated controls, authentication, reviews, labels, captures, refunds,
  chargebacks, dispute workflow, ledger effects, post-authorization signals,
  and webhook delivery summary.
- Every section names its authority and one of `AVAILABLE`, `EMPTY`,
  `NOT_APPLICABLE`, `PARTIAL`, `STALE`, `UNAVAILABLE`, or `DENIED`. Missing or
  stale derived data is never rendered as zero, complete, or source truth.
- The initial detail snapshot is read under one bounded PostgreSQL
  `REPEATABLE READ` transaction and exposes `sourceAsOf` and the payment
  stream's observed maximum version.
- The decision explanation exposes score units, risk level, winning action,
  reason codes, feature values and freshness, every evaluated control with
  version/matched/outcome/action/latency, precedence, and dependency-unavailable
  evidence. Phase 7 model state is explicitly `NOT_CONFIGURED`; absence is not
  presented as a zero model score.
- The lifecycle timeline orders payment-stream facts by immutable
  `streamVersion`. Timestamps are displayed evidence, not identity or the
  authoritative ordering key.
- Timeline, webhook, notes, related-payment, and other potentially growing
  collections are separately cursor-paginated. The detail envelope never
  hides truncation or silently slices a complete-looking list.
- A route to a source object is created only by an allowlisted internal route
  builder after tenant-qualified existence and role checks. Event payload URLs,
  webhook target URLs, notes, or merchant metadata never become navigation.

### 3.2 Related-payment promises

- Related-payment lookup starts from an authorized seed payment. The API never
  accepts raw card, email, phone, IP, device, or session values as search
  parameters.
- Relationship candidates are derived from tenant-scoped source identifiers
  and normalized HMAC aliases. Raw values and HMACs are never returned.
- V1 relationship reasons are bounded to `SAME_CUSTOMER`,
  `SAME_CARD_FINGERPRINT`, `SAME_EMAIL`, `SAME_PHONE`, `SAME_DEVICE`,
  `SAME_SESSION`, and `SAME_IP`.
- Results never cross a merchant boundary. Cross-merchant network intelligence
  remains Phase 8 and cannot be inferred through counts, errors, timings, or
  cursors.
- A request covers at most 90 aligned days, defaults to seven days, returns at
  most 50 payments per page, freezes an `asOf` boundary, and uses a
  tenant/seed/query-bound signed cursor.
- Each result exposes only safe reason codes, matched-reason count, first/last
  observed times, payment/decision summary, origin, and section coverage. It
  does not disclose correlation digests or another customer's raw attributes.
- Missing identity types, key-version gaps, source corruption, or partial
  anchor coverage become explicit coverage state. They do not silently produce
  an authoritative “no related activity” answer.

### 3.3 Queue, saved-view, and bulk-action promises

- Existing review saved searches remain backward compatible. Payment
  investigation saved views use a distinct capability-named schema, typed
  filter grammar, canonical bytes, schema version, digest, owner/visibility,
  and explicit upgrade failure.
- Search filters are server-normalized and bounded. A saved view cannot persist
  arbitrary SQL, URLs, raw identifiers, note contents, or unbounded metadata.
- Bulk review actions are limited to assignment operations:
  `ASSIGN_TO_SELF`, manager-only `ASSIGN_TO_OPERATOR`, and authorized
  `RELEASE`. Bulk resolve, label, cancel, refund, dispute submission, rule/model
  activation, and money actions are prohibited.
- A bulk request contains at most 25 distinct review cases, an expected
  assignment version for every item, one tenant-qualified idempotency key, and
  an immutable request digest.
- Spring Batch owns restart/chunk mechanics. Durable request/item state owns
  per-item `SUCCEEDED`, `STALE`, `NOT_ELIGIBLE`, `NOT_FOUND`, `DENIED`, or
  `FAILED` truth. Retrying cannot duplicate an assignment event or audit entry.
- Every item rechecks the live initiating actor and target operator at the
  mutation boundary, locks only its tenant-qualified review case, and invokes
  the existing assignment service. Actor disable, target disable, reassignment,
  resolution, or tenant substitution cannot be bypassed by an earlier request.
- Partial success is explicit. Completed items remain completed; failed/stale
  items are visible and independently retryable through a new idempotent request
  using current versions. The UI never claims the whole selection succeeded.

### 3.4 Insights promises

- Decision and merchant-performance insight reads use the active Phase 5
  projection generation and expose its exact freshness, coverage, cutoff, and
  traffic-origin policy.
- Queue/SLA productivity reads use authoritative review facts under a bounded
  `sourceAsOf`. The UI never merges them into a single unqualified timestamp
  with event-derived analytics.
- Every rate exposes numerator, denominator, unknown/unlabeled count, range,
  and segment/origin filters. Empty denominator is `NOT_COMPUTABLE`, never 0%.
- Money remains separated by currency. No endpoint or UI card sums USD, EUR,
  INR, or another currency into one amount.
- Phase 6 exposes decision distribution, review backlog/throughput/SLA,
  review yield, confirmed-fraud coverage, legitimate-label/false-positive
  proxy, chargeback outcome, and captured/refunded/disputed amounts. It does
  not claim prevented fraud, profit, interchange savings, or causal model lift.
- Projection `PARTIAL` may expose qualified counts with warnings, but money and
  derived rates whose denominator is incomplete are withheld.
- Operator-level productivity is manager-only. Analysts may see their own
  workload plus merchant-wide non-identifying aggregates; viewers receive
  read-only merchant aggregates and no individual operator ranking.

### 3.5 Developer-product promises

- `Thaap-Version: 2026-08-01` is the initial date-based public API contract.
  The effective version is returned on every public success and error
  response. A malformed, unsupported, or retired request is rendered with and
  returns the current/default error-contract version; raw invalid input is
  never echoed as an effective version.
- Existing clients without the header remain compatible with the initial
  version and receive the resolved version response header. An unknown or
  retired requested version fails with stable `unsupported_api_version` truth
  before a mutation runs.
- The generated OpenAPI contract includes every merchant, risk-operator, and
  sandbox endpoint intentionally public in Phase 6 and excludes `/internal/**`,
  Actuator, platform-operator bootstrap, and implementation-only routes.
- Every operation has a stable `operationId`, correct credential scheme,
  version behavior, idempotency semantics, bounded request/response schema,
  success/error responses, safe examples, and documented retry policy.
- The committed OpenAPI artifact is generated from executable application
  metadata and compared in CI. Drift or an unapproved breaking change fails.
- The error catalogue assigns stable machine codes, HTTP status, retryability,
  idempotency guidance, and recovery action. Clients never need to parse the
  human message.
- One supported synchronous Python SDK is generated from the committed
  contract and wrapped by a small reviewed transport/auth layer. Generated
  operations and models are not hand-copied.
- The SDK keeps credentials in caller memory, excludes them from URLs,
  logs/repr/exceptions, rejects redirects, ignores ambient proxy configuration
  for the local sandbox, uses bounded connect/read timeouts, and retries a
  mutation only with the same idempotency key.
- The SDK includes exact-byte webhook signature verification with timestamp
  tolerance and event-ID deduplication guidance; it never parses/re-serializes
  the body before verification.
- A new merchant can use only published docs and the SDK to create sandbox
  objects, run a payment-risk scenario, handle a safe retry, verify a webhook,
  and inspect analytics.

### 3.6 Security and product-trust promises

- Risk-operator, merchant API, scenario-runner, and platform-data identities
  remain separate. The browser and SDK attach exactly one applicable credential
  per request and cannot downgrade an operation to another credential type.
- Browser credentials remain in component memory only: never local/session
  storage, IndexedDB, URL, history, referrer, analytics, error text, or copied
  command examples.
- The routed UI has explicit loading, empty, partial, stale, denied,
  dependency-unavailable, conflict, and retry states per section. One failed
  slice does not erase successful source evidence.
- Keyboard navigation, visible focus, semantic headings/landmarks, labelled
  controls, status announcements, contrast, non-color-only status, and focus
  restoration after route/action changes are executable product requirements.
- No insight, related-payment, OpenAPI, SDK, or browser surface exposes PAN,
  CVV, raw email/phone/IP/device/session, API keys, operator credentials,
  signing secrets, idempotency keys, note plaintext outside authorized note
  reads, webhook payloads, unrestricted metadata, or exception/database text.

## 4. Scope And Named Exclusions

### 4.1 Included

- source-reconciled payment investigation detail
- complete deterministic decision explanation
- canonical event-backed payment lifecycle timeline
- same-merchant safe related-payment pivots
- investigation search and saved views
- bounded restart-safe bulk review assignment/release
- queue productivity, SLA, outcome, and business insight surfaces
- route-based analyst/developer information architecture in the existing React
  application
- date-based API version visibility and lifecycle documentation
- generated OpenAPI, stable error catalogue, and compatibility gates
- one supported Python sandbox SDK/reference client
- onboarding, idempotency, webhook, test-card/scenario, analytics, and recovery
  guides
- public analyst and developer demos plus accessibility/browser proof

### 4.2 Excluded with exact owner and unblock evidence

| Excluded capability | Exact reason | Owner slice | Evidence that unblocks it |
|---|---|---|---|
| Active model explanation or model score influence | No governed trained/served model exists; fabricating a model panel would create false product truth | Phase 7 — Shadow Model Serving And Timeout-Safe Decision Integration | Registered model/version, card, calibration, shadow result, timeout fallback, promotion and rollback evidence |
| Cross-merchant relationships, network counts, or graph paths | Same-merchant HMAC pivots do not establish consent, anonymity, contribution, deletion, or reconstruction boundaries | Phase 8 — Privacy-Safe Network Aggregate Intelligence and Explainable Graph Investigation | Approved privacy ADR, cohort/contribution proof, graph provenance, deletion, tenant opt-out, and reconstruction-resistance evidence |
| Bulk review resolution, labels, refunds, disputes, or control activation | These actions can change fraud truth, money, or protection behavior and require case-specific evidence/approval | Existing single-case workflows; Phase 7 unified release governance for model/control changes | A later frozen promise with role, evidence, conflict, money, approval, rollback, and per-case audit proof |
| Hosted SSO, browser sessions, general tenant administration, production CSP/CORS | Phase 6 remains a local/test product and has no hosted identity lifecycle | Phase 9 — Hosted Identity And Platform RBAC | Hosted deployment, identity provider, lifecycle, session, break-glass, CSP/CORS, support and incident boundaries |
| Public PyPI release, multi-version deployed compatibility, blue/green SDK matrix | There is one local executable API version, not independently deployed public clients | Phase 9 — Release Compatibility And External Integration Hardening | Independently versioned client/server releases, support window, compatibility baseline, deployment and rollback proof |
| Real PSP-specific integration guide | No PSP/design partner, provenance contract, credential scope, callback, or money authority is selected | Phase 10 — External PSP Adapter And Provenance Contract | Selected partner/PSP, approved token/credential/provenance boundary, support owner, safety budget, reconciliation and kill-switch plan |
| Warehouse, ClickHouse, arbitrary BI builder, or operator ranking | Phase 5 PostgreSQL projections have no measured product SLO breach and arbitrary analytics would weaken bounded semantics | Phase 8 — Analytics Scale Decision | Recorded query/volume SLO breach plus dual-store reconciliation, retention, tenant, backfill and rollback design |
| File evidence, exports, CSV download, or unrestricted clipboard | Downloadable data expands privacy, retention, malware, and exfiltration boundaries | Phase 8 — Friendly-Fraud And Representment Simulator document-security boundary, or a separately frozen security slice | File-type/size/scanning/storage/retention/authorization/export/audit design and hostile proof |

These are rejected from Phase 6 scope, not accepted implementation debt. If an
in-scope frozen promise is later deferred, it must receive a `TD-NNNN` entry in
the same change. Speculative polish does not become debt.

## 5. Source-Ownership And Composition Model

Phase 6 uses an API composition layer, not a new authoritative case database.

| Product question | Authoritative source | Phase 6 behavior |
|---|---|---|
| Current payment/attempt/processor state | payment, attempt, authorization, capture/refund/chargeback tables | bounded repeatable-read snapshot |
| Why the decision happened | immutable decision, feature, control-result and policy/rule lineage | complete typed explanation; no recomputation |
| Business ordering | canonical payment-stream `streamVersion` | cursor-paginated safe timeline DTO |
| Human action and label truth | review, resolution, label, notes and actor tables | role-gated current state and links to existing action APIs |
| Money truth | balanced ledger groups plus capture/refund/dispute source rows | summarized with currency and balance evidence; never rewritten |
| Webhook delivery truth | webhook event/delivery/attempt tables | bounded summary and separately paginated transport evidence |
| Search and aggregate insight | active Phase 5 projections | generation/freshness/coverage-qualified reads |
| Queue productivity | authoritative review facts | independent `sourceAsOf`, denominator, role filtering |
| Accountability | audit log | not replayed as payment timeline; queried only by authorized audit surfaces |

The composition service may return section-level failure, but it may not
silently mix rows read at unrelated times into one source snapshot. The source
detail transaction is read-only, `REPEATABLE READ`, five-second timeout, and
bounded by one payment. Projection and webhook sections execute independently
and declare their own cutoff/as-of state.

## 6. Product Information Architecture

The existing React application becomes a route-based local product with four
top-level areas:

| Route | Primary user | Purpose |
|---|---|---|
| `/workspace/reviews` | risk analyst/manager/viewer | review queue, saved searches, assignment and protected notes |
| `/workspace/payments` | risk analyst/manager/viewer | freshness-aware search and saved investigation views |
| `/workspace/payments/:paymentIntentId` | risk analyst/manager/viewer | source detail, explanation, timeline, related pivots and existing actions |
| `/workspace/insights` | risk analyst/manager/viewer | decision/outcome and queue/SLA insights |
| `/workspace/controls` | manager/viewer | existing monitoring, simulations and governed-control operations |
| `/workspace/disputes` | analyst/manager/viewer | existing dispute operations |
| `/workspace/webhooks` | manager | existing webhook recovery operations |
| `/developer/quickstart` | merchant developer | SDK-led zero-source onboarding |
| `/developer/api` | merchant developer | version, OpenAPI and error catalogue |
| `/developer/webhooks` | merchant developer | verification, signatures, retries and redelivery |
| `/developer/scenarios` | merchant developer/test operator | existing trusted scenario console and progress |

Routes contain only allowlisted UUIDs or static slugs. Credentials, cursors,
saved filter bodies, note text, raw identifiers, and API responses never enter
the URL. Deep links opened without an in-memory credential land on the explicit
connection screen and resume only the safe path after authentication.

## 7. Frozen API Surface Proposal

### 7.1 Investigation reads

```text
POST /risk/workspace/payments/search
GET  /risk/workspace/payments/{paymentIntentId}
GET  /risk/workspace/payments/{paymentIntentId}/timeline
GET  /risk/workspace/payments/{paymentIntentId}/related
GET  /risk/workspace/payments/{paymentIntentId}/webhook-activity
```

The search body is a bounded typed grammar: lifecycle status, fraud action,
risk level, reason code, review status/outcome, label value, fraud type,
traffic origin, currency, amount band, created range, sort, and page size.
Text search, arbitrary JSON paths, note search, raw identity search, and dynamic
sort columns are prohibited.

### 7.2 Investigation saved views

```text
GET  /risk/workspace/payment-views
POST /risk/workspace/payment-views
POST /risk/workspace/payment-views/{viewId}
POST /risk/workspace/payment-views/{viewId}/disable
POST /risk/workspace/payment-views/{viewId}/execute
```

Create/update/disable require `Idempotency-Key`. Private views are owner-only;
merchant-shared views require a manager. Execute revalidates schema version,
canonical filter digest, live actor, visibility, and active projection
generation. A generation change makes the old cursor stale, not the view.

### 7.3 Bulk review assignment

```text
POST /risk/workspace/review-bulk-actions
GET  /risk/workspace/review-bulk-actions/{actionId}
GET  /risk/workspace/review-bulk-actions/{actionId}/items
```

Start requires `Idempotency-Key` and returns `202 Accepted` with durable
request state. Item reads are cursor-paginated. A retry with the same identity
and request returns the same action; changed content conflicts. No endpoint
reopens or mutates a terminal item in place.

### 7.4 Insights

```text
GET /risk/workspace/insights/decision-outcomes
GET /risk/workspace/insights/review-operations
GET /risk/workspace/insights/operators
```

All ranges are UTC-aligned half-open intervals. Default range is the previous
seven complete UTC days; maximum is 31 days. Operator breakdown is
manager-only. Every response carries `range`, `asOf` or projection freshness,
origin policy, numerator/denominator/unknown values, and currency-separated
money.

### 7.5 Developer contract

```text
GET /developer/openapi/2026-08-01
GET /developer/api-versions
GET /developer/error-catalog
```

The served OpenAPI bytes must match the committed bundled artifact. The error
catalogue and version list are allowlisted product metadata, not runtime
reflection of internal endpoints or exception classes.

## 8. Unified Investigation Detail Contract

The detail response is a bounded envelope:

```text
InvestigationDetail
  identity
  sourceSnapshot
  analyticsContext
  paymentLifecycle
  decisionExplanation
  reviewAndLabels
  moneyAndDisputes
  webhookSummary
  availableActions
  relatedLinksSummary
  sectionStatuses
```

`identity` contains merchant-safe payment, attempt, decision, review, and
traffic-origin identifiers. It never returns another tenant's existence.

`sourceSnapshot` contains `sourceAsOf`, `maximumPaymentStreamVersion`, and a
source consistency status. It does not claim projection freshness.

`analyticsContext` contains the exact Phase 5 generation, cutoff, last event,
last processing time, eligible/applied/unavailable counts, origin policy, and
freshness state used to discover the payment.

`decisionExplanation` includes:

- `riskScoreBps` and UI-rendered percent; no unitless score
- risk level, final action, reason codes, start/completion/latency
- feature catalogue version, computed/oldest time, max age, freshness, missing
  and stale names, and allowlisted values
- ordered control evaluations for system rules, fraud lists, velocity,
  merchant policy, authored rules, and safety exceptions
- for each evaluation: family, code, version, priority, availability,
  evaluation outcome, matched flag, proposed action, reason, latency, and
  whether it contributed to the winning action
- an explicit precedence explanation naming the winning control/action and why
  weaker or unavailable controls did not win
- model state `NOT_CONFIGURED` until Phase 7; no fabricated contribution

`availableActions` is server-derived from current role and locked business
state but is advisory UI metadata. The actual mutation endpoint reauthorizes
and relocks; the browser cannot treat a previously returned action as a grant.

## 9. Lifecycle Timeline Contract

The timeline maps an allowlisted canonical event to a safe presentation DTO:

```text
TimelineEntry
  streamVersion
  eventId
  eventType
  eventVersion
  occurredAt
  recordedAt
  actorType
  trafficOrigin
  category
  titleCode
  safeFacts
  sourceLinks
```

`safeFacts` is constructed by an event-type-specific mapper. The API never
returns raw `payloadBytes`, full event-record bytes, digests as correlation
keys, request IDs, trace IDs, webhook URLs, note text, or unrestricted event
JSON. Unknown event versions fail the affected timeline section explicitly;
they are not rendered with a best-effort generic payload viewer.

The first page freezes `maximumStreamVersion`. The signed cursor binds merchant,
payment, maximum stream version, sort direction, page size, and last returned
stream version/event ID. Appended events appear after refresh and cannot cause
loss or duplication within the frozen traversal.

Webhook attempts are transport evidence and are not inserted into the payment
stream order. They appear in a separately labelled, paginated activity section
with event type, endpoint-safe identifier, delivery/attempt status, bounded
error code, request-sent truth, and times. Audit entries likewise remain
separate security evidence.

## 10. Related-Payment Data Design

V86 creates `payment_investigation_relation_anchors`:

- `id`
- `merchantId`
- `paymentIntentId`
- `paymentAttemptId`
- `relationType`
- `relationIdentityHmac`
- `identityKeyVersion`
- `observedAt`
- `trafficOrigin`
- `sourceType`
- `sourceId`
- `createdAt`

The table covers the six HMAC-backed reasons; `SAME_CUSTOMER` instead uses the
existing `(merchantId, customerId)` source foreign key loaded from the
authorized seed payment. It requires no new digest and never accepts or returns
the customer ID as a search parameter or relationship value.

Only already-HMAC-derived correlation values are stored in anchor rows;
`sourceId` is existing UUID lineage and is never used or returned as a
relationship key. One payment may have aliases for active and retained key
versions. Card, email, and phone use their persisted stable correlation HMACs;
IP, device, and session use the versioned risk-context HMAC and aliases. V86
records the existing stable correlation family as `correlation-v1`; the
architecture already prohibits changing that pepper without an explicit
offline re-key. Tenant-qualified foreign keys bind every anchor to the real
payment and attempt. A V87 constraint trigger validates that `sourceType` and
`sourceId` identify the exact same-merchant customer, payment method, or risk
context reachable from that attempt. Unique constraints prevent duplicate
aliases. Lookup indexes begin with merchant, relation type, digest, observed
time, and payment.

The normalized child rows are written in the same transaction as the immutable
risk-context/payment-attempt lineage they index. They are not authorization or
decision inputs. Failure therefore fails that local source transaction before
processor execution; it never permits a payment whose frozen investigation
lineage is only partially written. Existing V85 rows are backfilled only from
already persisted customer, payment-method fingerprint, and risk-context HMAC
or alias values; no raw value is recovered or rehashed from absent data.

V87 protects anchor identity, source, tenant, type, HMAC, time, origin, and key
version against update/delete and rejects malformed historical rows. Retained
migration proof covers missing aliases, mixed key versions, duplicates,
cross-tenant source substitution, and exact timestamp precision.

## 11. Search And Saved-View Design

Payment search runs against the active `payment-risk-v1` generation. The
filter grammar is canonicalized with Unicode normalization, explicit enum
ordering, aligned timestamps, bounded amount/currency pairs, and a schema
version. Signed cursors bind merchant, generation, filter digest, sort, range,
and anchor row.

V88 creates `payment_investigation_saved_views` with:

- tenant, owner operator, name, `PRIVATE|MERCHANT_SHARED` visibility
- filter schema version and canonical filter JSON
- sort, visible columns, definition digest
- lifecycle/version and created/updated/disabled times

The filter decoder is closed and table-driven. Unsupported schema or digest
mismatch returns `saved_view_upgrade_required`; it never silently broadens the
query. Saved views store no cursor, credential, raw identifier, response data,
or note text.

## 12. Bulk Review Assignment Design

V89 creates `review_bulk_action_requests` and
`review_bulk_action_items`. The request stores tenant, initiating actor,
action/target, stable idempotency identity and request digest, item count,
status, succeeded/failed counts, lease token/epoch/expiry, and bounded times.
Items store review ID, expected assignment version, ordinal, terminal status,
safe failure code, observed/final assignment version, and completion time.

V90 adds tenant-qualified foreign keys, request/item constraints, immutable
terminal evidence, exact status/count-shape checks, unique ordinal/review
identity, lease fencing, and retained-history guards.

The worker processes items in ordinal order with a small chunk size. Each item
is first claimed in a short transaction and then completed in one fenced
domain transaction:

```text
lock bulk request + claimed item and verify lease token/epoch
  -> lock/recheck live initiating actor and target in inherited service order
  -> lock tenant-qualified review case
  -> compare expected assignment version and eligibility
  -> call the existing assignment/release domain service in this transaction
  -> commit source mutation + assignment fact + canonical payment event
     + audit + webhook-delivery intent + terminal bulk item evidence atomically
```

Known validation failures commit only a terminal non-success item outcome and
no source mutation/event/audit. An unexpected exception rolls the entire
domain transaction back, leaving the claimed item recoverable after its lease;
there is no state where an assignment commits without the matching terminal
item evidence. The existing service must be refactored behind one
transaction-participating domain method if necessary; duplicate assignment
events are not accepted as harmless recovery behavior.

Claiming uses a lease and fencing epoch. A stale worker cannot finalize an item
or request after takeover. The request becomes `COMPLETED`, `PARTIAL`, or
`FAILED` only from terminal item truth. Metrics count committed item
transitions, not pre-read intentions.

## 13. Insight Semantics

### 13.1 Decision and outcome insights

The active payment-risk projection supplies:

- decision action distribution
- review-open and review-outcome counts
- confirmed-fraud, legitimate, unknown, and unlabeled counts
- label coverage and review yield
- chargeback counts
- captured, refunded, and disputed money by currency
- live versus synthetic origin partitions

Definitions are explicit:

- `labelCoverage = labeledEligible / labelEligible`
- `confirmedFraudRate = confirmedFraud / knownLabels`
- `reviewYield = confirmedFraudFromResolvedReviews / resolvedReviewsWithKnownLabel`
- `legitimateReviewRate = legitimateFromResolvedReviews / resolvedReviewsWithKnownLabel`

These are descriptive product metrics, not causal lift or prevented-loss
claims. Every numerator/denominator and not-computable partition is returned.

### 13.2 Review-operation insights

Authoritative review facts supply:

- open/unassigned/assigned backlog at `sourceAsOf`
- within-SLA and breached backlog
- assignments, releases, resolutions, and reopened/conflicted attempts within
  the range where durable evidence exists
- time-to-assignment and time-to-resolution count/sum/min/max with explicit
  eligible and missing counts
- individual operator workload/throughput only under the role rules in
  Section 3.4

Phase 6 does not claim approximate percentiles without a stored distribution or
exact bounded sample. If p50/p95 becomes a frozen requirement, the Chapter 56
matrix must choose and prove a deterministic histogram or exact bounded query.

V91 adds only measured query-support indexes needed by the frozen endpoints.
It does not add a second aggregate store. PostgreSQL `EXPLAIN` plans and maximum
range/cardinality fixtures prove the indexes; ClickHouse remains trigger-based
Phase 8 scope.

## 14. API Version And Error Contract

Public filter order is request ID, syntactic API-version resolution,
authentication/authorization, and then controller work. Version selection
does not read tenant resources or disclose resource existence. The resolver:

- accepts zero or one trimmed date value
- defaults absence to `2026-08-01`
- rejects duplicates, malformed dates, unsupported future dates, and retired
  dates with stable error codes
- writes the effective `Thaap-Version` response header on success and failure;
  resolution failures use the current/default error-contract version rather
  than reflecting invalid input
- never places credentials or version input into high-cardinality metrics

The initial error envelope remains additively compatible:

```json
{
  "requestId": "...",
  "timestamp": "...",
  "status": 409,
  "error": "payment_intent_not_confirmable",
  "message": "PaymentIntent cannot be confirmed in its current state",
  "path": "/payment_intents/.../confirm",
  "retryable": false,
  "fieldErrors": []
}
```

`error` becomes the stable machine code. `message` is safe human guidance and
is not a compatibility contract. The catalogue records category, status,
retryability, idempotency requirements, and recovery. Generic status-only codes
remain only as a documented last-resort boundary; every known public domain
failure receives a specific code.

The implementation inventories every public `ResponseStatusException`,
validation branch, dependency exception, conflict, rate-limit result, and
unexpected-error mapping. Typed exceptions or error descriptors replace
message-derived classification. Source scans and negative tests reject newly
introduced public message-only failures.

## 15. OpenAPI And SDK Contract

Phase 6 uses mature OpenAPI tooling rather than a proprietary schema language:

- Spring/OpenAPI integration generates the executable service description
- a mature OpenAPI linter validates structure, operation IDs, examples,
  security, error responses, and style
- an established semantic diff tool rejects unapproved breaking changes
- OpenAPI Generator produces the Python operations and models
- a small THAAP-owned wrapper supplies version, credential, idempotency,
  timeouts, redirect/proxy policy, safe errors, and webhook verification

The generated artifact is committed at:

```text
openapi/thaap-public-api-2026-08-01.yaml
```

The supported client lives under a capability-named path such as:

```text
clients/python/thaap_sdk/
```

Generation must be reproducible and diff-clean. Generated files carry their
source contract digest. A hand edit to generated operations fails the
regeneration gate. The security wrapper and handwritten examples are separately
unit tested and reviewed as protocol clients.

Chapter 57 establishes the executable baseline for every public route present
at its merge cutoff. If Chapter 57 merges before a Chapter 53–56 endpoint, the
later endpoint-owning chapter must regenerate, lint, semantically diff, and
test the additive OpenAPI change as part of its own completion evidence. That
does not reopen Chapter 57 unless version, error, security, or generation
semantics change. Chapter 59 performs the final inventory proving that every
intentional Phase 6 public route is present and every internal route is absent.

The first supported quickstart covers:

1. create or load a sandbox merchant credential
2. create customer and tokenized test payment method
3. create and confirm a PaymentIntent with stable idempotency
4. handle approved, review, 3DS, blocked, decline, timeout, and replay outcomes
5. create/verify a webhook endpoint and validate exact callback bytes
6. run/resume one trusted developer scenario
7. read payment-risk analytics and interpret freshness/coverage

## 16. Persistence And Migration Allocation

The Phase 6 forward allocation was founder-confirmed on 2026-08-02:

| Migration | Capability |
|---|---|
| V86 | Add normalized same-merchant payment investigation relation anchors |
| V87 | Protect investigation relation anchor history and tenant/source lineage |
| V88 | Add payment investigation saved views and typed lifecycle constraints |
| V89 | Add durable bounded review bulk-action requests and items |
| V90 | Protect bulk-action history, item outcomes, idempotency, leases and fencing |
| V91 | Add measured investigation, timeline, saved-view and insight query indexes |

Migrations are forward-only and capability-named. Every fresh V1→V91 and
retained V85→V91 path must pass PostgreSQL migration plus Hibernate validation.
Backfills may derive only from existing immutable/safe fields and must fail
closed on ambiguous tenant, source, identity, timestamp, or count truth.

No migration stores decrypted notes, API credentials, raw correlation values,
OpenAPI examples with secrets, SDK tokens, browser state, or cross-merchant
relationship data.

## 17. Transaction And Locking Model

Read lock/order rules:

1. resolve live risk operator and tenant
2. resolve seed PaymentIntent by `(merchantId, paymentIntentId)`
3. freeze source `asOf` and payment-stream maximum version
4. read source sections in a bounded repeatable-read transaction
5. read projection, related, webhook, or note sections independently with their
   declared cutoff/status

Bulk mutation order:

1. bulk request lease/fencing row
2. one bulk item row
3. live initiating actor, then target actor when applicable
4. manual review case
5. existing payment/review locks required by the assignment service
6. canonical payment-stream head when the source mutation emits its event
7. audit and terminal item evidence

Chapter 55 must reconcile this with every inherited assignment lock order and
prove both directions of any competing path in PostgreSQL. Sorting a submitted
list is not proof of deadlock freedom when the invoked service locks additional
rows.

UI “available actions,” a previous detail response, a saved view, a bulk-item
request, or a projection row never authorizes mutation. Live role and source
state are checked at the final transaction boundary.

## 18. Pre-Implementation Failure Inventory

Every chapter matrix expands its owned rows from this inventory.

| Boundary family | Mandatory hostile sequences |
|---|---|
| Source composition | payment changes between section reads; one child belongs to another payment/merchant; missing attempt/decision; contradictory current state; query timeout; source `asOf` mismatch |
| Decision explanation | missing feature snapshot; duplicate/missing control result; unavailable dependency shown as non-match; precedence disagreement; basis-points rendered as percent; model absence shown as zero; cross-attempt substitution |
| Timeline | same timestamp for multiple events; out-of-order timestamps; stream gap/duplicate; unknown event/version; payload contains hostile URL/control text; new append during pagination; cursor tenant/payment/query substitution |
| Related pivots | raw identity injection; cross-tenant identical HMAC; mixed key versions; missing aliases; alias duplication; 90-day exact boundaries; seed deletion/absence; cursor tamper; zero-match versus partial-coverage confusion |
| Search/saved views | invalid enum/range/amount/currency; canonicalization collision; stale generation cursor; view digest mutation; schema upgrade; owner disable; private/shared substitution; name Unicode/control boundaries |
| Bulk actions | duplicate review ID; 25/26 items; changed idempotent request; actor/target disable after start; assignment/resolution race; stale expected version; worker crash before/after source commit; lease takeover; stale finalizer; partial success; cross-tenant item; metric overcount |
| Insights | empty denominator; unlabeled/unknown outcomes; partial projection; projection generation swap mid-read; future/unaligned range; mixed currency; operator role downgrade; query timeout; counts from different as-of boundaries |
| API version | absent, exact, malformed, duplicate, future, unsupported and retired headers; error before/after resolver; response header on every outcome; cache/proxy variation |
| Error contract | every public status/exception branch; message mutation; database/exception echo; retryable misclassification; validation field ordering; unknown path/method/media; rate/dependency Retry-After behavior |
| OpenAPI | undocumented route; wrong credential scheme; missing idempotency header; incorrect success/error status; internal route leakage; unsafe example; generated artifact drift; breaking rename/removal/type/requiredness change |
| SDK transport | credential in URL/log/repr/error; redirect; proxy environment; hostile base URL; connect/read timeout; unknown mutation outcome; retry with changed key/body; malformed error; exact webhook bytes/timestamp/dedupe |
| Browser | deep link without credential; credential in URL/storage/referrer/DOM; section-specific 401/403/404/409/429/503; route race; stale query cache after actor change; focus loss; keyboard trap; partial/stale data rendered as complete |
| Migration | V85 retained rows; malformed alias/backfill; duplicate tenant/source; existing saved search preservation; bulk request/item shape corruption; repeated startup; rollback expectation; old/new code ownership claims |
| Operations | backlog/lag not visible; high-cardinality tags; sensitive exception; stuck bulk run; relation/insight query saturation; OpenAPI drift gate disabled; SDK example contacts non-loopback host |

An escaped finding reopens its entire boundary family. The fix must enumerate
valid/invalid partitions and add real-boundary proof rather than only closing
the observed example.

## 19. Observability And Operations Contract

Bounded metrics include:

- investigation request outcome and section status by closed section/operation
  enums
- timeline and related-payment query latency, result-size bucket, timeout, and
  partial-coverage outcome
- saved-view create/update/execute/upgrade outcome
- bulk action backlog, oldest age, claimed/items/succeeded/stale/denied/failed,
  lease recovery, and terminal request outcome
- insight request outcome by closed insight type, freshness state, coverage,
  and not-computable reason
- API version resolution outcome and unsupported/retired requests
- OpenAPI generation/diff gate result in CI
- SDK quickstart step outcome in test/demo processes only
- browser route/slice load outcome without route IDs or credentials

Metric dimensions never include merchant, operator, payment, review, event,
view, bulk request, cursor, error message, URL, version input, credential prefix,
HMAC, note, SDK base URL, or exception text.

Readiness reports bounded causes for source database, active Phase 5
projections, bulk worker, OpenAPI artifact consistency, and required local
dependencies. One unavailable optional insight does not make payment mutation
readiness false; the UI reports the affected slice.

The operations runbook adds bulk-run recovery, stuck lease diagnosis,
projection generation change, related-anchor coverage, OpenAPI drift, SDK
contract mismatch, and browser partial-state triage. Operator recovery may
retry or fence work but cannot edit terminal history or source payment truth.

## 20. Chapter Execution Plan

### Chapter 53 — Unified Payment Investigation And Decision Explanation

- Status: dependency-ready; frozen `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Fraud/Risk Product
- Technical DRI: Risk Platform
- Supporting DRIs: Payments Core and Product Design
- Dependency: Phase 5 / Chapter 52 merged
- Migrations: none unless frozen query-plan proof requires V91 to be pulled
  forward explicitly

Build the source-reconciled investigation envelope, complete deterministic
decision explanation, source/projection authority statuses, action eligibility
metadata, bounded search entry point, and first routed payment-detail UI.

Required proof: one/two-tenant reads, cross-attempt/source substitution,
repeatable-read snapshot, every control family and non-match/unavailable state,
precedence winner, score units, model-not-configured truth, source versus
projection freshness, role behavior, section timeout, accessibility, and one
public risky-payment explanation demo.

Done when an analyst can explain the complete decision and current payment
state from one route without calling separate APIs or treating projection data
as source truth.

### Chapter 54 — Canonical Timeline And Safe Related-Payment Pivots

- Status: dependency-blocked on Chapter 53 merge-ready evidence; frozen
  `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Fraud/Risk Product and Risk Operations
- Technical DRI: Risk Platform
- Security DRI: Security/Platform
- Dependency: Chapter 53 merge-ready
- Migrations: V86–V87

Build the event-type-safe canonical timeline, frozen cursor traversal,
separately labelled webhook activity, normalized relationship anchors, related
payment API, coverage contract, and UI pivots.

Required proof: every supported event mapper, unknown versions, same-time and
out-of-order timestamps, append-during-pagination, signed cursor boundaries,
raw/HMAC non-disclosure, relation-type and time/limit boundaries, mixed key
versions, missing aliases, source immutability, retained backfill, cross-tenant
timing/count non-disclosure, and a multi-payment investigation demo.

Done when an analyst can traverse exact payment history and safe same-merchant
relationships without raw-identifier search or cross-merchant inference.

### Chapter 55 — Investigation Saved Views And Bounded Bulk Review Assignment

- Status: dependency-blocked on Chapters 53–54 merge-ready evidence; frozen
  `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Risk Operations
- Technical DRI: Risk Platform
- Supporting DRIs: Product Design and Security/Platform
- Dependencies: Chapters 53–54 merge-ready and inherited review assignment
  service/lock order
- Migrations: V88–V90

Build payment investigation saved views, the 25-item assignment/release grammar,
durable Spring Batch request/item execution, lease/fencing recovery, per-item
results, and queue UI selection/progress/retry.

Required proof: saved-view canonical bytes/version/digest/visibility, existing
review-search compatibility, idempotency replay/conflict, every role/action,
25/26 and duplicate items, actor/target disable, forced assignment/resolution
orderings, crash/restart/takeover, stale finalizer, partial completion,
event/audit exactly-once application truth, tenant non-disclosure,
accessibility, and a mixed-result public bulk-assignment demo.

Done when a manager can save a payment-investigation view and safely distribute
a bounded review workload with durable item-level truth.

### Chapter 56 — Queue Productivity And Decision/Business Insights

- Status: dependency-blocked on Chapter 55 merge-ready evidence; frozen
  `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Risk Operations and Product Design
- Technical DRIs: Risk Platform and Data Platform
- Dependencies: Chapter 55 merge-ready and Phase 5 active projections
- Migration: V91 only for measured query support

Build exact decision/outcome and review-operation endpoints, denominator and
not-computable types, currency-separated money, projection/source as-of
separation, role-filtered operator breakdown, insight UI, and safe drill-through
to saved/search routes.

Required proof: every numerator/denominator partition, empty/unknown/unlabeled,
partial/stale/generation swap, origin policy, UTC ranges, currency separation,
role downgrade, query plans/timeout, maximum range/cardinality, no causal or
prevented-loss overclaim, browser partial states, and a queue-to-outcome demo.

Done when an analyst can show what work is waiting, how it moved, what outcomes
are known, and what remains unknown without spreadsheet/database access.

### Chapter 57 — Versioned OpenAPI And Stable Error Contract

- Status: dependency-ready; frozen `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Developer Experience
- Technical DRI: Payments Core
- Supporting DRIs: Risk Platform and Security/Platform
- Dependency: Phase 5 / Chapter 52 merged; may run independently of Chapters
  53–56 after the Phase 6 promise freeze
- Migrations: none

Build the `Thaap-Version` resolver, version lifecycle metadata, typed public
error catalogue, executable OpenAPI generation, public/internal route
partition, lint/diff/regeneration gates, and developer API/error pages.

The generated baseline covers all public routes present at the Chapter 57
merge cutoff. Every later Phase 6 public endpoint owns an additive regeneration
and contract-test obligation; Chapter 59 owns final phase-wide completeness.

Required proof: complete public route inventory, every version header
partition, response header on all errors, every public exception/result family,
specific stable codes and retry guidance, no sensitive exception leakage,
security/idempotency metadata, safe examples, generated/served byte agreement,
breaking-change negative fixtures, and zero internal route exposure.

Done when the current API contract is machine-readable, executable, version-
visible, and sufficient for client generation without reading controllers;
later Phase 6 route additions remain gated by the same generation/diff rules.

### Chapter 58 — Supported Python SDK And Sandbox Onboarding

- Status: dependency-blocked on Chapter 57 merge-ready evidence; frozen
  `PRE_IMPLEMENTATION` matrix; evidence pending
- Product DRI: Developer Experience
- Technical DRI: Payments Core
- Security DRI: Security/Platform
- Dependency: Chapter 57 merge-ready; Chapter 54 investigation links and the
  inherited Phase 5 analytics operations merge-ready for final readback
- Migrations: none

Generate the Python operations/models, add the narrow secure transport/auth and
webhook-verification wrapper, publish the local package and guides, integrate
the quickstart/scenario UI, and replace ad hoc example drift with SDK-backed
contract tests.

Required proof: regeneration cleanliness, every quickstart operation, type and
error mapping, credential/header separation, no URL/log/repr/error leakage,
redirect/proxy/base-host/timeout partitions, idempotent unknown-outcome retry,
exact webhook bytes/timestamp/dedupe, hostile examples, package installation
from a clean environment, and one zero-source-reading public merchant journey.

Done when a new developer can complete the frozen sandbox workflow using only
published docs and the supported SDK.

### Chapter 59 — Workspace Trust And Integrated Phase 6 Closure

- Status: dependency-blocked on Chapters 53–58 merge-ready evidence; frozen
  `PRE_IMPLEMENTATION` matrix; evidence pending
- Primary DRI: Founding Staff Engineering
- Supporting DRIs: all Phase 6 product and technical owners
- Dependencies: Chapters 53–58 merge-ready
- Migrations: V86–V91 complete

Reconcile every route, role, API, migration, SDK artifact, example, dependency,
metric, runbook, matrix, dossier, and source document. Run the complete analyst
and merchant journeys, fresh/retained migrations, full regression,
accessibility/browser proof, contract generation/diff, SDK clean install,
traceability, a temporally later hostile pass, and zero blocking debt/findings.

Phase 6 closes only when every Chapter 53–59 matrix is
`FROZEN`/`VERIFIED`/all-`PASS`, every dossier recommends `MERGE`, and both
public journeys require no source reading or hidden configuration.

## 21. Dependency Graph

```text
Phase 5 / Chapter 52
  -> Chapter 53 source investigation + decision explanation
       -> Chapter 54 timeline + related pivots
            -> Chapter 55 saved views + bulk assignment
                 -> Chapter 56 productivity + business insights

Phase 5 / Chapter 52
  -> Chapter 57 API version + OpenAPI + errors
       -> Chapter 58 Python SDK + sandbox onboarding

Chapters 53–58
  -> Chapter 59 workspace-trust integrated closure
```

Chapter 53 and Chapter 57 became independently dependency-ready when the
founder confirmed the shared Phase 6 promise freeze on 2026-08-02. Chapter 58
may develop its generated client
against the frozen Chapter 57 artifact while Chapters 54–56 continue, but its
final quickstart readback waits for Chapter 54 investigation links and the
inherited Phase 5 analytics operations it uses. Chapter 59 is always last.

## 22. Proof Strategy

The frozen chapter proof package is:

| Chapter | Invariant matrix | Closure dossier |
|---|---|---|
| 53 | [`CHAPTER_53_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_53_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_53_CLOSURE_DOSSIER.md`](reviews/CHAPTER_53_CLOSURE_DOSSIER.md) |
| 54 | [`CHAPTER_54_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_54_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_54_CLOSURE_DOSSIER.md`](reviews/CHAPTER_54_CLOSURE_DOSSIER.md) |
| 55 | [`CHAPTER_55_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_55_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_55_CLOSURE_DOSSIER.md`](reviews/CHAPTER_55_CLOSURE_DOSSIER.md) |
| 56 | [`CHAPTER_56_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_56_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_56_CLOSURE_DOSSIER.md`](reviews/CHAPTER_56_CLOSURE_DOSSIER.md) |
| 57 | [`CHAPTER_57_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_57_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_57_CLOSURE_DOSSIER.md`](reviews/CHAPTER_57_CLOSURE_DOSSIER.md) |
| 58 | [`CHAPTER_58_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_58_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_58_CLOSURE_DOSSIER.md`](reviews/CHAPTER_58_CLOSURE_DOSSIER.md) |
| 59 | [`CHAPTER_59_INVARIANT_PROOF_MATRIX.md`](reviews/CHAPTER_59_INVARIANT_PROOF_MATRIX.md) | [`CHAPTER_59_CLOSURE_DOSSIER.md`](reviews/CHAPTER_59_CLOSURE_DOSSIER.md) |

Every implementation chapter requires:

- a `PRE_IMPLEMENTATION` matrix covering payment/fraud, tenant, security,
  transaction/concurrency, data/ML lineage, API/product, observability, and
  operations/resilience
- a closure dossier with exact enforcement boundaries and fresh proof
- H2 tests where useful plus real PostgreSQL for migrations, JSON, indexes,
  repeatable-read claims, triggers, retained data, and forced interleavings
- table/property tests for filter, cursor, timeline mapper, relationship,
  version, error, and SDK protocol partitions
- two-tenant authorization and timing/count non-disclosure proof
- prohibited-data canaries across database, events, logs, metrics, API,
  OpenAPI, examples, SDK, browser DOM/storage/history, demos, and reports
- browser tests for routing, roles, accessibility, focus, memory-only secrets,
  independent section states, and safe navigation
- executable contract drift/breaking-change tests from Chapter 57 onward
- a clean-environment SDK installation and reference-client run from Chapter
  58 onward
- a capability-appropriate public demo and durable reconciliation
- traceability followed by a temporally separate hostile failure-sequence pass
- vision, debt, escaped-review, naming, learning, invariant, dossier, phase and
  commit-readiness gates

Mocks may prove UI/controller composition. They cannot certify PostgreSQL
snapshot/concurrency, migration/backfill, Spring Batch restart/fencing,
generated-versus-served OpenAPI, real SDK HTTP behavior, or browser storage and
navigation claims.

## 23. Phase Completion Gate

Phase 6 is complete only when:

- one route explains the complete payment/risk lifecycle with honest source,
  projection, time, freshness, coverage, and model-not-configured truth
- every deterministic decision input/control/outcome and winning precedence is
  visible without recomputation or source access
- timeline ordering follows immutable stream version and safely paginates
  append-only history
- related pivots are raw-free, HMAC-safe, same-merchant, key-rotation-aware,
  bounded, coverage-honest, and non-inferential across tenants
- search/saved views are typed, versioned, digest-bound, role-safe, and cursor
  stable
- bulk assignment/release is bounded, idempotent, restart-safe, fenced,
  item-truthful, and cannot perform high-risk resolution/money/control actions
- insight rates expose exact numerators, denominators, unknowns, range, origin,
  currency, as-of/freshness and role boundaries without causal overclaim
- every intentional public endpoint is present in a versioned generated
  OpenAPI contract and every internal endpoint is absent
- every known public failure has a stable code/retry contract and no sensitive
  message/exception leakage
- the supported Python SDK regenerates cleanly and completes the public
  zero-source-reading sandbox journey with safe credential/transport behavior
- the routed UI proves memory-only credentials, accessibility, safe links,
  role changes, loading/empty/partial/stale/denied/dependency/conflict states,
  and independent slice recovery
- fresh V1→V91 and retained V85→V91 PostgreSQL paths, full regression, public
  analyst/developer demos, matrices/dossiers, learning, vision, debt/findings,
  and readiness gates pass
- `VP-004` and the Phase 6 boundary of `VP-005` become
  `PROVEN_CURRENT_SCOPE`; `VP-016` records exact Phase 6 proof while retaining
  Phase 9 deployed-compatibility and Phase 10 PSP-guide owners

## 24. Founder-Confirmed Decisions

The founder confirmed these decisions as one coherent product set on
2026-08-02 before implementation began:

1. Extend the existing React cockpit into one routed analyst/developer product;
   do not create a second UI or rebuild proven Phase 4.5 workflows.
2. Compose authoritative source facts, canonical event order, and Phase 5
   projections; do not create a second payment/case source of truth.
3. Order the payment timeline by immutable stream version. Timestamps are
   display/availability evidence, never identity.
4. Make authority, `asOf`, freshness, coverage, and section status visible.
   Missing/stale data is never silently zero or complete.
5. Expose the complete deterministic control explanation now and explicit
   `NOT_CONFIGURED` model state until Phase 7.
6. Limit related pivots to same-merchant server-derived source identifiers and
   HMAC anchors, seven reason types, seven-day default, 90-day maximum, and 50
   results per page.
   Cross-merchant graph/network intelligence remains Phase 8.
7. Keep notes, audit entries, webhook transport attempts, and canonical payment
   events as distinct evidence types; do not flatten them into one misleading
   event stream.
8. Add separate typed saved views for payment investigations while preserving
   existing review saved-search contracts.
9. Limit bulk actions to at most 25 assignment/release items. Resolution,
   labels, money, disputes, and control activation remain single-case/approved
   workflows.
10. Use Spring Batch plus durable item state for restart-safe partial bulk
    results; do not invent a custom distributed queue.
11. Use active Phase 5 projections for decision/business outcomes and current
    source review facts for queue productivity, with separate as-of semantics.
12. Publish descriptive rates with exact denominators/unknowns and currency-
    separated money; do not claim causal lift, profit, or prevented fraud.
13. Use `Thaap-Version` with initial date version `2026-08-01`, backward-
    compatible absence, and explicit unsupported/retired errors.
14. Keep the existing error envelope additively compatible while making
    `error` a stable machine code and adding explicit retryability.
15. Generate and commit one public OpenAPI artifact, exclude internal routes,
    and use mature lint/diff/generation tooling instead of a custom schema.
16. Support one synchronous Python SDK for the local sandbox, generated from
    OpenAPI with a narrow reviewed security/transport wrapper.
17. Keep browser and SDK credentials memory-only, separated by authority,
    excluded from URLs/logs/storage, and protected by no-redirect/bounded-
    timeout behavior.
18. Treat accessibility, partial failure, safe routing, and zero-source-reading
    onboarding as completion evidence, not post-phase polish.
19. Reserve V86–V91 and Chapters 53–59 for the capability sequence in this
    plan; use capability names for all executable artifacts.
20. Keep hosted identity, public package release, multi-version deployment,
    cross-merchant intelligence, active ML, and real PSP guidance under their
    named Phase 7–10 owners.

Approval evidence is this explicit founder response plus the reconciled master
plan, vision matrix, and 2026-08-02 learning trail. Chapter 53 and Chapter 57
may now begin against their `PRE_IMPLEMENTATION` matrices; later chapters
remain dependency-gated by Section 21.

## 25. Stripe/Mastercard-Style Design Review Narrative

THAAP Phase 6 does not confuse a polished screen with a truthful investigation.
The payment and ledger remain authoritative source facts; the canonical stream
provides immutable business order; projections provide fast discovery and
aggregate insight only with generation, cutoff, freshness, and coverage. The
workspace exposes these boundaries instead of hiding them.

Decision explanation is reconstructed from immutable decision-time evidence,
not by rerunning today's rules. Related-payment pivots never accept or return a
raw identity and never cross a tenant. Bulk operation scope is deliberately
narrow: distributing work can be bounded and restart-safe, while resolution,
labels, money, disputes, and control changes retain their case-specific human
authority.

The developer product is held to the same standard. OpenAPI is generated from
executable behavior, errors are machine-classifiable, versions are visible,
and the supported client proves idempotency, unknown outcomes, webhook exact
bytes, redirects, proxy behavior, timeouts, and secret hygiene. The result is a
narrower developer and analyst surface than Stripe or Mastercard, but a
defensible one: every displayed fact, action, metric, relationship, API shape,
and retry instruction identifies its authority, tenant, time, version,
coverage, and failure policy.
