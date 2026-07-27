# Phase 5 Detailed Implementation Plan

- Phase: Phase 5 — Durable Events And Rebuildable Analytics
- Status: READY FOR FOUNDER REVIEW
- Entry gate: SATISFIED by Phase 4.5 / Chapter 43R closure
- Promise freeze: PENDING FOUNDER CONFIRMATION
- Merchant-facing product destination: FOUNDER CONFIRMED 2026-07-27
- Primary deployables: `apps/payments-api`, local Kafka, and
  `apps/analyst-dashboard-ui`
- Product DRI: Fraud/Risk Product
- Technical DRIs: Data Platform, Payments Core, and Risk Platform
- Data/ML DRI: ML Architecture
- Operations DRI: Security/Platform
- Current implementation chapter: NOT STARTED
- First planned implementation chapter: Chapter 44
- First planned proof artifact:
  `docs/reviews/CHAPTER_44_INVARIANT_PROOF_MATRIX.md`
- First planned closure artifact:
  `docs/reviews/CHAPTER_44_CLOSURE_DOSSIER.md`
- Concept foundation:
  `docs/learning/concepts/TRANSACTIONAL_OUTBOX_AND_REBUILDABLE_PROJECTIONS.md`
- Vision coverage: `VP-017` directly; prerequisites for `VP-002`, `VP-003`,
  `VP-004`, `VP-005`, `VP-008`, `VP-009`, `VP-012`, `VP-014`, `VP-016`,
  `VP-020`, `VP-021`, `VP-022`, and `VP-023`

This plan converts the Phase 5 roadmap into an executable product sequence.
The decisions in Section 22 are proposed for founder confirmation as one
coherent event and analytics contract. They are not implementation
authorization yet. After confirmation, Chapter 44 must freeze its
pre-implementation matrix and failure inventory before any production code or
migration changes.

## 1. Outcome

Phase 4.5 proves that THAAP can operate a payment-risk decision through analyst,
rule, dispute, webhook, and developer workflows. Phase 5 makes those durable
facts safely publishable, consumable, and rebuildable:

```text
payment or risk mutation
  -> domain state + immutable event commit atomically in PostgreSQL
  -> leased publisher sends after commit
  -> Kafka acknowledges at-least-once delivery
  -> idempotent consumer applies one event effect
  -> versioned PostgreSQL projection advances
  -> merchant sees freshness, coverage, and source watermark
  -> rebuild replays the same immutable events into a shadow generation
  -> reconciliation proves equivalence before activation
```

The Phase 5 challenger wedge is explainable data movement. Every published
event, projection row, replay result, and operational intervention must answer:

1. Which merchant, domain mutation, request, actor, and immutable source row
   produced this fact?
2. Did the source state and event commit together, or did both roll back?
3. Can a duplicate, delayed, or out-of-order delivery change the result?
4. What is the projection's exact event cutoff, freshness, and coverage?
5. Can the same source events rebuild the result without contacting Redis,
   mutating payment truth, or inventing present-day context?

Phase 5 is not “add Kafka.” Kafka is a transport. The product outcome is a
trustworthy lineage boundary from committed payment and fraud facts to
rebuildable merchant analytics and later ML/graph consumers.

## 2. Entry Truth

Phase 5 extends the completed V64 substrate:

- merchant-scoped payment, attempt, authorization, capture, refund,
  chargeback, ledger, fraud, review, label, policy, rule, dispute, incident,
  scenario, audit, and webhook state
- transaction-local `AuditLog` evidence for high-risk actions
- immutable webhook event envelopes written with their originating mutation
- point-in-time monitoring and historical control simulation implemented
  directly over PostgreSQL facts and decision-time feature lineage
- server-assigned `MERCHANT_API` and `SYNTHETIC_SCENARIO` traffic origin
- exact idempotency, actor fencing, append-only evidence, and deterministic
  PostgreSQL concurrency proof
- local Docker Compose PostgreSQL and Redis, plus the Phase 4.5 React cockpit
- retained migration history through V64, zero open technical debt, and no
  unresolved escaped-review finding

The exact starting gaps are:

- there is no general domain-event/outbox record for all state-changing
  payment, fraud, risk, and operational actions
- webhook events are endpoint-delivery facts, not a general event bus or
  analytics source
- there is no Kafka broker, producer contract, consumer contract, or poison
  handling in the product runtime
- no durable inbox prevents a redelivered Kafka record from applying a
  projection effect twice
- monitoring and simulations read source tables; there is no event-derived,
  rebuildable analytics projection with an explicit watermark
- existing audit evidence is broad but has not been reconciled against the
  final Phase 4.5 mutation catalogue for complete actor/request/trace coverage
- there is no projection-generation, shadow rebuild, comparison, or atomic
  activation protocol
- there is no general ReplayEngine contract for event-driven projection
  rebuilds and stable offline datasets

Phase 5 therefore reuses rather than replaces three existing truths:

1. PostgreSQL domain tables remain authoritative for payment, fraud, ledger,
   actor, and control state.
2. `AuditLog` remains the human/security accountability record.
3. Phase 4.5 historical simulation remains the governed counterfactual control
   evaluator until a later plan explicitly migrates it.

## 3. Proposed Phase Promises

These promises become frozen only after founder confirmation. A later chapter
may refine internals but may not weaken an observable promise without reopening
its active matrix before code changes.

### 3.1 Merchant and analyst promises

- A committed in-scope payment or risk mutation has one durable canonical event
  in the same PostgreSQL transaction; if either write fails, both roll back.
- Kafka unavailability does not roll back an already committed merchant
  operation. The event remains durably pending and is retried after recovery.
- A merchant never sees another merchant's event, projection row, freshness
  state, replay, quarantine item, or operational metadata.
- Analytics responses expose source cutoff, projection generation, last applied
  event time, processing time, freshness status, coverage, and traffic-origin
  policy. Missing or stale data is never rendered as zero.
- Duplicate or delayed delivery cannot double amounts, counts, labels, reviews,
  incidents, or other analytics effects.
- Rebuilding a projection cannot expose partial results. Readers remain on the
  active generation until a complete shadow generation passes reconciliation
  and is atomically activated.
- Existing payment, fraud, review, monitoring, simulation, dispute, webhook,
  and developer APIs remain backward compatible unless a chapter explicitly
  freezes a versioned change.

### 3.2 Payment, fraud, and ledger promises

- An event reports a committed fact; it is never a command, prediction, or
  permission to repeat a payment, processor, refund, ledger, label, or control
  mutation.
- Event publication, consumption, replay, projection rebuild, or quarantine
  recovery never re-enters payment orchestration or creates money movement.
- Ledger truth remains in balanced ledger groups. Analytics may summarize
  amounts but cannot become settlement or accounting authority.
- A payment lifecycle stream preserves its domain ordering across intent,
  attempt, decision, authorization, capture, refund, chargeback, and terminal
  operations. One blocked stream does not block unrelated merchants or
  aggregates.
- Historical FraudDecision, FeatureSnapshot, rule/control evidence, traffic
  origin, and label availability remain immutable and point-in-time.
- Later labels or disputes may update outcome analytics only at their recorded
  availability time; rebuilds do not leak those outcomes into an earlier
  decision-time feature or candidate evaluation.

### 3.3 Event producer promises

- Every event has a globally unique event ID and a tenant-qualified stream
  identity, monotonically increasing stream version, event type, schema
  version, occurrence time, recorded time, correlation/causation lineage,
  request/trace lineage where available, traffic origin, canonical payload,
  and exact payload digest.
- Event IDs, stream versions, occurrence times, and canonical payload bytes are
  stable across publication retry, application restart, and projection replay.
- Payloads are built from an allowlisted schema. Raw PAN, CVV, API keys,
  credentials, signing material, note plaintext, IP, email, phone, address,
  raw device/session identifiers, idempotency material, exception text, and
  unrestricted metadata are prohibited.
- Producers do not publish to Kafka inside the domain transaction and do not
  call Kafka before commit.
- Outbox backlog does not silently discard events. Repeated publication failure
  becomes visible blocked state with an operator-controlled retry path.
- An event schema's meaning is immutable. Additive compatible evolution is
  preferred; removal, representation change, or semantic change requires a new
  event version.

### 3.4 Publisher and Kafka promises

- Delivery is explicitly at least once. THAAP does not claim atomic exactly-once
  behavior across PostgreSQL and Kafka.
- A publisher claims bounded work through a lease and fencing token, sends
  outside the database transaction, and finalizes only while it still owns the
  claim.
- A successful Kafka acknowledgement followed by a database-finalization crash
  may publish a duplicate; consumer inbox deduplication makes the duplicate
  harmless.
- A timeout or unknown producer result is retried with the same event ID and
  bytes. Unknown outcome is never relabeled as known failure or success.
- Kafka records use the tenant-qualified stream identity as the partition key,
  preserving per-stream order without claiming a global business order.
- Publisher readiness and operator surfaces expose backlog age, count, blocked
  streams, retry class, and last acknowledgement without exposing payload or
  tenant secrets in metric dimensions.

### 3.5 Consumer and projection promises

- Every consumer has a durable inbox identity. Its inbox record and projection
  changes commit in one PostgreSQL transaction before the Kafka offset is
  acknowledged.
- A crash before that transaction commits applies nothing. A crash after the
  transaction commits but before offset acknowledgement redelivers the record
  and converges through the completed inbox entry.
- Unknown optional fields are tolerated. Unknown event types, unsupported
  versions, invalid digests, invalid stream ordering, or schema-invalid
  payloads are quarantined with closed safe reason codes and do not advance the
  projection checkpoint.
- Projection handlers are deterministic functions of the event envelope,
  canonical payload, and current projection generation. They do not fetch
  current Redis state or mutate source domain tables.
- A consumer may process different streams concurrently but never applies
  stream version `n + 1` before version `n` for the same projection.
- Projection checkpoints and freshness are tenant- and generation-qualified;
  a global healthy consumer cannot hide one stale tenant or stream.

### 3.6 Replay and analytics promises

- Rebuild reads immutable stored events up to an explicit cutoff and writes a
  new projection generation. It does not republish domain events to Kafka by
  default.
- A rebuild is restart-safe, leased, bounded, cancelable, and fenced against a
  stale worker. Stable job identity prevents duplicate active rebuilds for the
  same projection/version/cutoff.
- Reconciliation compares event counts, stream/version coverage, projection
  row counts, canonical content digests, and declared business invariants
  before activation.
- Live catch-up after the rebuild cutoff is explicit. Activation is permitted
  only when the shadow generation reaches the required source watermark.
- Offline replay datasets bind tenant, event cutoff, label-availability cutoff,
  projection/evaluator version, feature catalogue, traffic-origin policy,
  query digest, and output digest.
- The existing governed historical simulation remains authoritative for Phase
  4.5 activation gates. Phase 5 may feed it stable event-derived cohorts only
  after equivalence with its source-table contract is proven.

### 3.7 Security, audit, and operations promises

- Merchant and operator authorization is evaluated from live server-side
  identity before any event, projection, replay, or quarantine resource is
  disclosed or mutated.
- Audit and domain events remain separate products: audit answers who did what
  and why; domain events communicate what committed. Neither is reconstructed
  from the other.
- Every high-risk Phase 5 operation—blocked-event retry, quarantine decision,
  rebuild start/cancel/activate, and projection rollback—is actor-bound,
  idempotent, and audited.
- Broker credentials, TLS/SASL material, payloads, tenant IDs, event IDs, trace
  IDs, and exception text do not become unbounded metric labels or unsafe log
  fields.
- Kafka-disabled startup is explicit for tests that do not exercise events;
  shared/runtime profiles fail readiness when publishing is required but
  unavailable or backlog policy is breached.
- Runbooks distinguish broker outage, publisher bug, invalid event, consumer
  bug, projection drift, replay failure, and source-schema incompatibility.

## 4. Scope And Named Exclusions

### 4.1 Included

- canonical immutable domain-event envelope and schema catalogue
- atomic PostgreSQL event/outbox writes for in-scope mutation families
- tenant-qualified stream ordering and payload integrity
- leased, bounded, retryable Kafka publisher
- one local Kafka topic and production-shaped configuration
- durable consumer inbox, quarantine, and checkpoint substrate
- payment-risk and merchant-risk PostgreSQL analytics projections
- freshness, coverage, and projection health APIs plus thin cockpit visibility
- generation-based rebuild, reconciliation, activation, and rollback
- offline replay dataset manifest over immutable event/feature/label lineage
- audit completeness reconciliation and new Phase 5 operational audits
- local public demo from source mutation through Kafka to rebuild

### 4.2 Excluded with exact owner and unblock evidence

| Excluded capability | Exact reason | Owner slice | Evidence that unblocks it |
|---|---|---|---|
| ClickHouse | PostgreSQL volume and query limits are not yet measured; adding a second analytics store would expand reconciliation and operations before product need | Phase 8 analytics scale | Sustained projection/query SLO breach plus an approved dual-store migration and reconciliation design |
| Debezium/CDC | THAAP needs an application-owned semantic event contract, not raw table change leakage; CDC does not remove consumer/schema governance | Phase 9 scale/hardening | Proven outbox polling bottleneck and an ADR preserving canonical event and tenant semantics |
| Schema Registry/Avro/Protobuf | Phase 5 has one first-party Java producer/consumer and local Kafka; versioned canonical JSON plus checked schemas is the smallest inspectable boundary | Phase 9 external event platform | Multiple independently deployed consumers or external contracts requiring compatibility automation |
| External merchant event subscriptions | Public Kafka contracts require identity, entitlement, retention, and compatibility commitments absent from V1 | Phase 10 Hosted Merchant Onboarding And Risk Control Plane | Hosted identity, tenant entitlements, external schema policy, pilot support/runbook ownership, and a design-partner need that cannot be met by existing APIs/webhooks |
| Global or cross-merchant analytics | Privacy-safe aggregation, k-anonymity/noise policy, consent, and network governance do not exist | Phase 8 network intelligence | Approved privacy architecture and cross-merchant invariant matrix |
| ML training or online scoring | Phase 5 creates trustworthy inputs but does not claim calibrated models, registry, serving, or drift control | Phase 7 ML baseline | Dataset card, leakage proof, model card, evaluation, serving timeout, and rollback boundaries |
| Graph ingestion and ring detection | Entity graph identity and privacy scope are not frozen | Phase 8 graph intelligence | Approved graph identity/tenant design and event-derived dataset contract |
| Multi-region Kafka or disaster recovery | Local/single-region product truth must be stable before cross-region ordering and failover claims | Phase 9 hardening | Recovery objectives, broker topology, failover fencing, and restore rehearsal |
| General data lake/object archive | Retention, encryption, deletion, and dataset access boundaries are not designed | Phase 7 data platform | Approved object-storage security/retention architecture and dataset lineage contract |

These are rejected from Phase 5 scope, not undocumented debt. If Phase 5
implementation introduces a narrower unresolved obligation inside a frozen
promise, it must receive the next `TD-NNNN` rather than being hidden here.

### 4.3 Actor and authorization contract

Phase 5 separates tenant risk users from platform data operators:

- Existing `VIEWER`, `ANALYST`, and `RISK_MANAGER` identities may read only
  their merchant's authorized analytics and freshness state. They cannot retry
  publications, dispose quarantines, control consumers, or activate projection
  generations.
- A new persistent local-only `PlatformOperator` has one closed role:
  `DATA_OPERATOR` may inspect safe global/component health and execute bounded
  retry, quarantine-recovery, rebuild, activation, and rollback operations;
  `PLATFORM_ADMIN` may additionally issue or disable platform operators.
- The admin bootstrap token may create the first `PLATFORM_ADMIN`, but it is not
  an operator identity and cannot perform data-plane recovery or appear as a
  fictional human actor.
- Platform credentials are reveal-once, stored as prefix plus versioned hash,
  accepted only on local/internal operations routes, and never forwarded to
  merchant APIs, Kafka payloads, browser storage, logs, metrics, or reports.
- Every platform mutation locks and revalidates the live operator and
  credential before the publication, quarantine, consumer, projection, or
  rebuild resource. Completed idempotent replay returns prior safe evidence;
  disabled credentials cannot start new work.
- Platform operators cannot create, confirm, capture, refund, dispute, label,
  or otherwise mutate merchant payment/fraud truth. Merchant risk managers
  cannot grant themselves platform authority.

Chapter 44 owns the identity schema and bootstrap contract before privileged
event operations exist. Chapter 47 activates the first data-operator recovery
surface. Hosted SSO, broad administration, and remote operator access remain
Phase 9 scope.

## 5. Canonical Event Contract

### 5.1 Event versus audit versus webhook

The three records have different responsibilities:

| Record | Question answered | Consumer | Replay effect |
|---|---|---|---|
| Domain event | What immutable business fact committed? | internal data consumers | updates projections only |
| Audit log | Who attempted or completed a sensitive action, under which request? | security/operator review | never drives domain state |
| Webhook event | Which exact signed merchant callback payload was created? | merchant receiver | receiver deduplicates business effect |

A domain event may cause a projection update. It must never regenerate a
webhook, replay a processor call, recreate a ledger entry, or infer an audit
actor. Existing webhook payload identity remains independent even when both
records describe the same source mutation.

### 5.2 Envelope

The immutable event record contains:

- `eventId`: random UUID assigned once before the domain transaction commits
- `merchantId`: required V1 tenant root
- `streamType` and `streamId`: closed stream identity
- `streamVersion`: positive, gap-free version within one stream
- `eventType`: closed semantic fact name
- `eventVersion`: positive schema/meaning version
- `occurredAt`: authoritative domain occurrence time at database precision
- `recordedAt`: time the event record was inserted
- `correlationId`: stable workflow/request correlation
- `causationEventId`: optional prior event that directly caused this fact
- `requestId` and `traceId`: optional bounded operational lineage
- `actorType` and `actorId`: optional safe actor reference when the committed
  fact has an actor; no credential material
- `trafficOrigin`: server-assigned origin when the source is traffic-derived
- `payloadJson`: canonical versioned JSON object
- `payloadSha256`: digest of the exact canonical UTF-8 bytes

Envelope fields are authenticated by persistence and validated again at the
consumer boundary. The payload never duplicates merchant or stream identity as
an independently trusted value. If a versioned schema includes those fields
for usability, exact agreement with the envelope is mandatory.

### 5.3 Stream roots and ordering

Initial stream roots are domain-owned rather than chapter-owned:

- `PAYMENT`: one PaymentIntent root, including attempt, decision,
  authorization, capture, refund, chargeback, and related label facts
- `REVIEW`: one ManualReviewCase root for assignment and resolution facts
- `RISK_CONTROL`: one policy, authored rule, exception, or profile lifecycle
- `RISK_INCIDENT`: one card-testing or merchant-risk incident
- `DISPUTE`: one dispute workflow
- `WEBHOOK_ENDPOINT`: one endpoint operational lifecycle
- `DEVELOPER_SCENARIO`: one trusted scenario run

The aggregate row or an explicit tenant-qualified stream-head row is locked
before allocating `streamVersion`. Every chapter must publish its lock order.
The unique key `(merchant_id, stream_type, stream_id, stream_version)` prevents
two facts from claiming the same position.

There is no global business order. A database surrogate sequence may support
bounded polling but must not be described as commit order because concurrent
transactions can allocate sequence values and commit in a different order.

### 5.4 Initial event catalogue

The initial catalogue is closed and reviewed per chapter. Candidate families
are:

- payment: intent created/canceled, attempt completed, authorization created,
  capture created, refund created, chargeback created
- fraud: decision created, review opened/assigned/resolved, label recorded or
  superseded, post-authorization signal recorded, incident opened/resolved
- controls: policy/rule/exception activated, rolled back, expired, or disabled
- disputes: workflow opened, evidence version submitted, outcome recorded,
  workflow expired
- webhook operations: endpoint verified/disabled/rotated, delivery exhausted,
  manual redelivery requested/succeeded
- developer scenarios: run created/completed/failed

Names and payload fields become executable schema artefacts in Chapter 44.
Events are emitted only for committed facts with a demonstrated consumer or
lineage purpose. State-change noise is not a product event catalogue.

### 5.5 Evolution and compatibility

- Canonical JSON uses stable UTF-8 encoding, lexicographically sorted object
  keys, no insignificant whitespace, closed number representations, UTC
  timestamps at PostgreSQL microsecond precision, and explicit null policy.
- Additive optional fields may retain the same version only after producer,
  stored fixture, and tolerant-consumer proof.
- Removing, renaming, changing type, changing units, changing timestamp
  meaning, or changing enum meaning requires a new event version.
- Consumers declare the exact supported type/version matrix. Unsupported
  versions quarantine; they never fall through to a “best effort” parser.
- Checked schema fixtures and golden canonical bytes live with the product and
  are reviewed as protocol code.

## 6. Persistence Design

Planned domain-named tables:

- `platform_operators` and `platform_operator_credentials`: durable local-only
  data-operations identity, role, lifecycle, and reveal-once credential truth
- `domain_events`: immutable envelope, payload, digest, and stream position
- `event_stream_heads`: tenant-qualified next-version coordination only where
  an authoritative aggregate projection version is unavailable
- `event_publications`: mutable claim, lease, retry, acknowledgement, and
  blocked-state projection for one immutable event
- `event_publication_attempts`: append-only safe outcome evidence
- `consumer_inbox`: consumer/event deduplication and terminal application truth
- `consumer_quarantine`: closed invalid/unsupported reason and operator
  disposition without raw exception or payload duplication
- `projection_definitions`: closed projection name and handler version
- `projection_generations`: build/catch-up/reconcile/active/retired lifecycle
- `projection_checkpoints`: tenant/generation/partition or stream watermark
- `projection_stream_positions`: last applied version for each tenant-qualified
  stream in one projection generation
- `projection_bootstrap_facts`: immutable, safe, digest-bound seed input for
  pre-Phase-5 source rows, with explicit source-snapshot provenance and cutoff;
  never presented as a historical domain event
- `projection_rebuilds`: leased restart-safe rebuild identity and progress
- payment-risk and merchant-risk projection tables keyed by merchant and
  generation
- replay dataset manifests and aggregate result tables; no row-level sensitive
  export in Phase 5

Required database invariants include:

- tenant-qualified composite foreign keys for every merchant-owned relation
- immutable event identity, envelope, payload, digest, and stream position
- unique event ID and unique stream version
- legal publication, attempt, inbox, quarantine, generation, and rebuild state
  machines
- append-only publication attempts and rebuild events
- exactly one active generation per projection definition
- a generation cannot activate until complete reconciliation evidence exists
- an inbox completion cannot exist without the matching projection generation
  effect in the same transaction
- payload size, event type/version, actor type, traffic origin, safe error code,
  retry count, and timestamp-order bounds

PostgreSQL protection triggers are required where normal application checks
cannot certify history against privileged or future code paths. Migrations must
fail before their first DDL statement when legacy data cannot be mapped without
inventing event, publication, audit, or projection truth.

## 7. Transaction And Locking Model

### 7.1 Domain mutation plus event

```text
authenticate and validate tenant/actor
  -> lock domain aggregate in its existing global order
  -> validate idempotency and current state
  -> perform domain, ledger, audit, and webhook writes
  -> allocate next stream version while owning the stream boundary
  -> insert immutable domain event + pending publication
  -> complete idempotency response
  -> commit once
```

An event insert or stream-version failure rolls back the mutation. Kafka is not
contacted in this transaction. Completed idempotency replay returns the stored
response and never emits a second event.

### 7.2 Publication

```text
short transaction: claim next publishable event with lease/token
  -> commit claim
  -> send exact stored bytes to Kafka outside PostgreSQL transaction
  -> receive acknowledgement, failure, timeout, or unknown result
  -> short transaction: lock publication and verify token
  -> append attempt evidence and mark published/retry/blocked
```

`FOR UPDATE SKIP LOCKED` bounds contention. Claiming enforces per-stream order:
later versions do not pass an unpublished earlier version. Tenant/stream
round-robin or budgets prevent one large stream from starving unrelated work.
Lease expiry recovers abandoned work; an old token cannot finalize a new claim.

### 7.3 Consumption

```text
Kafka record received
  -> validate topic, key, envelope, schema, digest, and tenant agreement
  -> begin PostgreSQL transaction
  -> reserve/lock consumer + event inbox identity
  -> completed inbox: no-op exact duplicate
  -> new inbox: validate generation and expected stream position
  -> apply deterministic projection mutation
  -> update checkpoint + complete inbox
  -> commit
  -> acknowledge Kafka offset
```

Projection code never owns a lock required by payment or fraud mutation code.
Source-domain tables are read only for explicit reconciliation, never locked or
mutated by the consumer.

### 7.4 Rebuild and activation

```text
actor starts idempotent rebuild at explicit event cutoff
  -> lease one shadow generation
  -> consume immutable events in bounded chunks
  -> checkpoint each committed chunk
  -> resume after crash from completed inbox/checkpoint truth
  -> catch up shadow generation to activation watermark
  -> reconcile counts, streams, digests, and business invariants
  -> lock projection definition
  -> take a consistent PostgreSQL view of visible source events
  -> revalidate actor + reconciliation + every visible stream position
  -> atomically switch active generation
```

Cancellation stops future chunks and retains safe evidence. It does not delete
the active generation. Rollback switches to a retained previously reconciled
generation; it does not mutate source events.

A surrogate event sequence is not treated as commit order during activation.
The activation transaction compares the shadow generation's tenant-qualified
stream positions with every domain event visible in one consistent PostgreSQL
snapshot. Events that commit after that snapshot are normal new work for the
new active generation.

### 7.5 Global lock order

The proposed top-level order is:

```text
live actor / merchant credential
  -> idempotency reservation
  -> source domain aggregate(s) in existing domain order
  -> event stream head
  -> immutable event + publication
  -> audit
```

Publisher order is `publication -> attempt`. Consumer order is
`projection definition/generation -> inbox -> projection row -> checkpoint`.
Rebuild activation is `projection definition -> generation -> reconciliation`.
Chapter matrices must prove both orderings of every conflict with forced
PostgreSQL interleavings, not simultaneous starts.

## 8. Kafka And Publication Contract

### 8.1 Topology

Phase 5 uses one local/internal topic, provisionally `thaap.domain-events.v1`,
with multiple partitions. The record key is a canonical tenant-qualified
stream key. Headers may repeat safe routing fields but are never independently
trusted over the canonical stored envelope.

The partition count is fixed for this topic version. Increasing it in place can
remap a stream key and admit cross-partition reordering. Scaling partitions
therefore requires a new topic version plus an explicit drain/cutover and
consumer-position migration; configuration drift fails readiness.

The plan uses mature Spring Kafka producer and consumer infrastructure. A
proprietary broker client, serializer framing protocol, or queue is out of
scope without an accepted ADR and complete boundary table.

### 8.2 Producer behavior

- Kafka idempotent producer mode is enabled where supported, but it is treated
  as duplicate reduction, not end-to-end exactly-once proof.
- `acks=all` and bounded delivery/request timeouts are explicit.
- Producer serialization sends the exact stored canonical event bytes; retry
  never reconstructs from current domain state.
- Broker/topic authentication and transport configuration are environment
  owned and fail safe without logging credentials.
- Topic absence, authorization failure, serialization defect, timeout,
  disconnect, unavailable leader, broker rejection, and unknown acknowledgement
  are distinct closed outcome classes.

### 8.3 Retention and recovery

Kafka retention is not the only replay source. PostgreSQL immutable events are
the Phase 5 rebuild authority. Broker retention is configured long enough for
normal consumer recovery, while projection rebuild remains possible after
Kafka records expire.

No event is automatically discarded after a retry ceiling. Repeated producer
failure moves the publication projection to `BLOCKED`, preserves the immutable
event, alerts operators, and requires audited retry after the cause is fixed.

Phase 5 retains immutable domain events and bootstrap facts in PostgreSQL for
rebuild. Automatic deletion is prohibited until a later archive/retention
boundary proves encrypted durable copy, tenant deletion policy, restore, and
digest reconciliation. Storage growth and oldest-event age are operational
signals from the first implementation chapter.

### 8.4 Mature infrastructure and ADR boundary

Spring Kafka owns broker protocol, producer acknowledgement, consumer group,
offset, and rebalance behavior. Spring Batch, already used by Phase 4.5, owns
chunking, restart metadata, and bounded rebuild job execution. THAAP adds
domain-specific leases, fencing, stream order, reconciliation, and
authorization around those mature components.

Chapter 44 must create an ADR for the application-owned event/outbox envelope
and publication state, including the alternative analysis for CDC and direct
publication. Canonical JSON and schema validation must use maintained
components selected through the ADR; a hand-written canonicalizer or serializer
requires a separate accepted ADR and full representation-boundary table.

## 9. Consumer, Inbox, And Quarantine Contract

Each consumer has a versioned name tied to one projection responsibility. A
generic handler that switches across unrelated products is rejected.

Inbox identity is `(consumer_name, event_id)`. The persisted record retains the
event digest and supported handler version so an event ID reused with different
bytes is a security/integrity failure, not a duplicate.

Quarantine reasons are closed, for example:

- unsupported event type or version
- invalid envelope or schema
- record-key/envelope mismatch
- payload digest mismatch
- tenant or stream identity disagreement
- payload limit exceeded
- impossible stream version or missing predecessor
- deterministic projection invariant failure

Transient PostgreSQL/Kafka dependency failures do not become poison events.
They stop or retry consumption without committing an inbox or offset. A
quarantine record commits durably before its Kafka offset is acknowledged, but
it does not advance the affected domain-stream position. This lets unrelated
streams in the same partition continue. Later records for the affected stream
are held with `PREDECESSOR_UNAVAILABLE` rather than applied out of order.

Because Phase 5's internal producer and consumer share PostgreSQL, authorized
recovery resolves the immutable source event by ID and verifies its stored
digest. It then reapplies the original quarantined event followed by held
successors in stream order. Reloading source truth is explicit recovery, never
a hidden replacement for validating the Kafka record that failed.

Operator actions are limited to retry after software/schema repair, map through
an explicitly versioned compatibility adapter, or abandon only under a founder-
approved break-glass process. Editing event bytes in place is forbidden.

## 10. Analytics Projection Contract

### 10.1 Payment-risk projection

The first read model is one tenant-qualified payment-attempt/lifecycle fact
that can explain:

- intent amount, currency, capture method, and server-assigned origin
- attempt and processor outcome
- decision action, score/level, reason codes, rules/control versions, and
  feature-snapshot identity
- review lifecycle and latest outcome without erasing prior evidence
- captured, refunded, and disputed amounts as summaries of ledger-authoritative
  sources
- label value, fraud type, source, and availability time
- last applied stream version/event/time and projection generation

It stores safe identifiers and governed enums, not raw RiskContext, note text,
credentials, or unrestricted metadata.

### 10.2 Merchant-risk aggregate projection

Bounded hourly/daily aggregates support authorization, decline, review,
confirmed-fraud, chargeback, enumeration, volume, and amount trends. Every row
binds:

- merchant and projection generation
- interval and timezone contract (UTC)
- traffic-origin inclusion policy
- numerator, denominator, unknown/unlabeled coverage
- event cutoff and latest source occurrence/processing time
- projection/evaluator version

These aggregates complement Phase 4.5 point-in-time monitoring; they do not
silently redefine its thresholds or automatic profile recommendations.
Equivalence tests must identify where an event-derived metric is intentionally
the same and where it is a separately named aggregate.

### 10.3 Read/API behavior

Read APIs are bounded, tenant-scoped, cursor-paginated, and role-filtered. Every
response includes a freshness block. The cockpit displays loading, empty,
partial, stale, dependency-unavailable, permission-denied, and retry-safe
states. It does not persist credentials or projection payloads in browser
storage.

## 11. Replay And Rebuild Contract

Phase 5 has two deliberately separate replay modes:

1. **Projection rebuild**: reapply immutable domain events to a shadow read
   model and reconcile it before activation.
2. **Offline evaluation dataset**: materialize a bounded, point-in-time cohort
   from immutable event, feature, decision, and label lineage for later rule or
   model evaluation.

Neither mode calls merchant APIs, processor simulation, webhook delivery,
Redis, current RiskContext extraction, or source-domain mutations.

Dataset manifests record inclusion/exclusion counts for synthetic traffic,
unlabeled rows, contradictory/superseded labels, unsupported event versions,
and missing feature coverage. Row-level dataset export remains internal and
tenant-scoped in Phase 5. Model fitting and promotion remain Phase 7.

The Phase 4.5 `RiskSimulation` contract continues to gate rule/policy
activation. Feeding it an event-derived cohort requires a chapter-specific
equivalence proof against the current immutable source-table query, including
before/exact/after label availability and mixed-control reconstruction.

## 12. Audit Completeness Contract

Phase 5 does not create a second `AuditLog` product. It performs a mutation
inventory over all completed phases and closes missing coverage where a frozen
high-risk action lacks:

- merchant, live actor, role, and safe actor identifier
- request and trace identifiers
- aggregate type and identifier
- action and bounded outcome
- safe before/after context where useful
- occurrence time and durable source reference

Audit payloads do not embed domain event payloads. Event publication and
consumer success are operational telemetry; only human or privileged recovery
actions create audit records. Automated retries do not impersonate an actor.

## 13. API And Product Surface

Proposed public/local endpoints are capability-named and versioned under the
appropriate tenant-risk or platform-operator boundary:

- payment-risk analytics search/detail with explicit freshness
- merchant-risk time-series/summary with origin and coverage filters
- projection status and generation history
- data-operator rebuild start/status/cancel/activate/rollback
- data-operator bounded blocked-publication and quarantine summaries/recovery

Raw event payload download, arbitrary topic access, arbitrary SQL, event-byte
editing, offset manipulation, and merchant-operator cross-tenant administration
are not exposed. Platform routes are local/internal and return only bounded
safe envelope/health metadata, never event payloads.

The public demo must create real merchant traffic through existing APIs, prove
the source mutation and event committed together, stop/restart the publisher
and consumer, reconcile a duplicate safely, display analytics freshness, build
a shadow generation, compare it, activate it, and prove payment/ledger/fraud
source counts did not change.

## 14. Pre-Implementation Failure Inventory

Every Phase 5 chapter matrix must partition at least these families:

| Family | Hostile sequences to prove |
|---|---|
| Atomic creation | domain write succeeds/event fails; event succeeds/domain fails; audit or idempotency fails; completed API replay; transaction rollback after stream allocation |
| Stream ordering | concurrent facts allocate same version; later version commits first; missing predecessor; blocked version with later due work; unrelated-stream progress |
| Canonical protocol | key order, Unicode, null/absent, integer/decimal, timestamp precision, maximum payload, digest mismatch, record-key/envelope disagreement |
| Publication | broker unavailable; auth/topic failure; timeout before send; acknowledgement then DB crash; lease expiry during send; stale finalizer; retry ceiling; disabled publisher recovery |
| Consumption | duplicate delivery; concurrent duplicate; crash before/after projection commit; offset acknowledgement failure; rebalance; unsupported type/version; poison versus dependency failure |
| Tenant isolation and authorization | foreign event/projection/generation/rebuild/quarantine ID; record key substitutes tenant; merchant operator attempts platform operation; platform operator attempts merchant-domain mutation; role/credential disabled after pre-check; shared metric/log label leakage |
| Projection correctness | out-of-order event; duplicate amount/count; later label availability; refund/chargeback overlap; terminal event followed by illegal fact; missing source coverage |
| Rebuild | crash between chunks; stale worker after lease takeover; cancel/complete race; live events after cutoff; partial generation exposure; compare/activate race; rollback to incompatible version |
| Security/privacy | prohibited value in payload/schema fixture/log/metric/audit/UI; payload or exception disclosure in quarantine; broker credential error; oversized decompression/parse input |
| Migration | fresh V64 upgrade; realistic retained rows; partial/corrupt event seed; constraint added after invalid row; forward-only rollback; mixed application versions |
| Operations | backlog age breach; disk/storage pressure; Kafka retention expiry; consumer lag; one noisy tenant/stream; blocked event; partition-count/configuration drift; rebuild resource exhaustion; configuration rollback |
| Documentation/demo | insecure producer/consumer example; environment proxy or redirect; hidden local configuration; demo reads source code; stale success counts; source mutation during replay |

An escaped finding reopens its entire grammar, ordering, dependency-result,
migration-state, authorization, or projection family and receives an immutable
`ER-NNNN` with product evidence and process correction.

## 15. Observability And Operational Contract

Required bounded metrics include:

- outbox pending count and oldest age
- publication claim, acknowledgement, retry, unknown, blocked, and lease-
  recovery counts by closed outcome/topic class
- Kafka consumer lag by consumer/topic/partition, not merchant or event ID
- inbox duplicate count and handler outcome
- quarantine count by consumer and closed reason
- projection applied count, handler latency, source-to-visible latency,
  checkpoint age, and stale status
- rebuild started/running/resumed/canceled/failed/reconciled/activated counts and
  bounded duration/progress
- reconciliation mismatch by closed invariant family

Safe structured logs include request/trace IDs where available, component,
closed event type/version, safe stream type, consumer/projection name, retry
class, and exception class. They exclude tenant IDs where unnecessary, event
and aggregate IDs as metric labels, payloads, broker credentials, exception
messages, and sensitive source values.

Readiness is degraded or failed through explicit policy for:

- required Kafka unavailable
- oldest unpublished event or consumer checkpoint beyond threshold
- blocked publication or quarantine above threshold
- no active projection generation
- active generation incompatible with the running handler
- rebuild worker enabled without required database or broker configuration

Required runbooks cover pause/resume publisher, broker recovery, blocked stream,
consumer rollback, poison event, projection drift, rebuild cancel/resume,
generation rollback, schema compatibility, and safe local reset. Destructive
event deletion is not a routine recovery action.

## 16. Migration And Deployment Strategy

- All schema changes are forward-only after V64.
- Chapter 44 installs event infrastructure without emitting retroactive events
  for historical rows. Existing history is not silently backfilled as if events
  had committed with old transactions.
- Integration chapters emit events only after their migrations and code are
  deployed together under explicit producer enablement.
- A separately named bootstrap rebuild may seed an initial projection from
  authoritative V64 source tables at an explicit cutoff. It first writes
  immutable safe `projection_bootstrap_facts` plus a manifest and digest under
  `SOURCE_SNAPSHOT` provenance distinct from live `DOMAIN_EVENT` provenance.
  Future rebuilds reuse those exact bootstrap bytes before applying later
  domain events. The bootstrap may not invent per-row historical event time or
  domain-event IDs.
- Mixed-version deployment defaults to fail closed for an event version the
  active consumer cannot support. Producer enablement follows consumer/schema
  readiness.
- Rollback disables new publication/consumption safely while retaining pending
  events and the previously active projection generation.

Every migration chapter proves fresh PostgreSQL installation and a realistic
retained upgrade. H2 is regression context, not proof of PostgreSQL JSON,
locking, trigger, index, or migration claims.

## 17. Chapter Execution Plan

### Chapter 44 — Canonical Event Contract And Atomic Outbox Foundation

- Status: PLANNED; begins only after founder confirmation and a frozen
  pre-implementation matrix
- Primary DRI: Data Platform
- Supporting DRIs: Payments Core, Risk Platform, Security/Platform
- Dependency: Phase 4.5 / Chapter 43R complete; satisfied

Freeze the event catalogue, canonical JSON/schema contract, stream identities,
payload privacy rules, immutable event/publication schema, stream allocation,
platform-operator identity/bootstrap, configuration, migration, and atomic
writer. Use a test-owned transactional probe to prove infrastructure atomicity
without advertising a product event; the first real product integrations
belong to Chapters 45 and 46.

Exit evidence: event golden fixtures and schema compatibility checks; fresh and
retained migrations; both forced stream-version orderings; atomic commit/
rollback proof; privacy sink scans; matrix/dossier ready to merge.

### Chapter 45 — Payment And Money Event Integration

- Status: PLANNED
- Primary DRI: Payments Core
- Supporting DRIs: Data Platform and Risk Platform
- Dependency: Chapter 44 merge-ready

Integrate intent, attempt/decision, authorization, capture, cancel/void, refund,
chargeback, ledger-linked label, and payment authentication facts using the
single PaymentIntent stream. Preserve existing transaction and idempotency lock
orders. No event consumer may create payment or ledger state.

Exit evidence: every action/replay/rollback partition has exact event count and
payload lineage; forced confirm/capture/refund/chargeback orderings; balanced
ledger non-interference; retained public payment lifecycle demo.

### Chapter 46 — Fraud And Risk-Operations Event Integration

- Status: PLANNED
- Primary DRI: Risk Platform
- Supporting DRIs: Risk Operations, Data Platform, and Payments Core
- Dependency: Chapter 44 merge-ready; Chapter 45 event conventions stable

Integrate review, label, incident, merchant-profile/policy, authored-rule,
safety-exception, dispute, webhook-operation, post-authorization signal, and
trusted-scenario facts. Complete the audit mutation inventory without creating
a second audit system.

Exit evidence: actor disable/role/tenant races, dual-control semantics,
before/exact/after label availability, note/payload privacy, event/audit
separation, and public risk-operations reconciliation.

### Chapter 47 — Kafka Publication And Recovery

- Status: PLANNED
- Primary DRI: Data Platform
- Supporting DRIs: Security/Platform and Payments Core
- Dependency: Chapters 44–46 event producers merge-ready

Add local Kafka, mature producer configuration, tenant-qualified stream keys,
leased bounded publishing, append-only attempt evidence, retry/unknown/blocked
classification, fairness, readiness, metrics, and the first DATA_OPERATOR-
authenticated recovery surface.

Exit evidence: real Kafka acknowledgement; broker outage/recovery; ack-before-
database-finalization duplicate; lease takeover and stale finalizer; noisy-
stream fairness; exact stored bytes across retry; safe operational demo.

### Chapter 48 — Idempotent Consumer And Projection Substrate

- Status: PLANNED
- Primary DRI: Data Platform
- Supporting DRIs: Risk Platform and Security/Platform
- Dependency: Chapter 47 merge-ready

Add the durable inbox, versioned consumer identity, schema/digest/record-key
validation, checkpoint, quarantine, per-stream ordering, and generation-aware
projection handler framework using Spring Kafka.

Exit evidence: duplicate/concurrent duplicate, crash on both sides of database
commit, offset acknowledgement failure, rebalance, poison/dependency
classification, unsupported schema, tenant substitution, and unrelated-stream
progress on real Kafka/PostgreSQL.

### Chapter 49 — Payment-Risk And Merchant-Risk Analytics

- Status: PLANNED
- Primary DRI: Risk Platform
- Supporting DRIs: Data Platform, Fraud/Risk Product, and Product Design
- Dependency: Chapter 48 merge-ready

Build versioned PostgreSQL payment-risk and merchant-risk projections, bounded
read APIs, freshness/coverage contracts, and thin cockpit views. Reconcile
event-derived metrics with Phase 4.5 monitoring definitions without silently
changing them.

Exit evidence: lifecycle and money summary correctness, origin inclusion,
label-availability boundaries, duplicate/out-of-order handling, stale/partial
UI states, two-tenant non-disclosure, query/index proof, and public analytics
demo.

### Chapter 50 — Projection Rebuild, Reconciliation, And Activation

- Status: PLANNED
- Primary DRI: Data Platform
- Supporting DRIs: Risk Platform, Security/Platform, and Risk Operations
- Dependency: Chapter 49 merge-ready

Add leased, restart-safe shadow generations; bounded replay; live catch-up;
count/stream/digest/business reconciliation; atomic activation; cancellation;
and retained-generation rollback. Reuse Spring Batch for durable job/chunk
execution and add only the domain-specific generation, lease, authorization,
and activation fencing it does not provide.

Exit evidence: crash/resume, stale-worker fencing, live-write cutoff race,
partial generation non-visibility, reconciliation mismatch rejection,
activate/rollback forced orderings, and exact source-state non-interference.

### Chapter 51 — Offline Replay Dataset And Simulation Compatibility

- Status: PLANNED
- Primary DRI: ML Architecture
- Supporting DRIs: Data Platform and Risk Platform
- Dependency: Chapters 49–50 merge-ready

Create the tenant-scoped immutable replay dataset manifest and aggregate
results over decision-time features, outcomes, label availability, traffic
origin, and explicit event cutoff. Prove compatibility before allowing this
cohort source to feed the existing governed simulation boundary.

Exit evidence: point-in-time feature and label proof, unlabeled/contradictory/
superseded partitions, stable query/output digest, no Redis/current-context
access, restart safety, tenant isolation, and equivalence with the existing
simulation contract for the approved overlap.

### Chapter 52 — Integrated Phase 5 Closure

- Status: PLANNED
- Primary DRI: Founding Staff Engineering
- Supporting DRIs: all Phase 5 product and technical owners
- Dependency: Chapters 44–51 merge-ready

Run fresh/retained migrations, the complete source-to-Kafka-to-projection live
demo, broker/consumer restarts, duplicate and poison recovery, shadow rebuild
and activation, all public analytics flows, full regression, traceability,
temporally later hostile review, source reconciliation, and zero blocking debt
or findings.

Phase 5 closes only when every Chapter 44–52 matrix is
`FROZEN`/`VERIFIED`/all-`PASS`, every dossier recommends `MERGE`, every in-scope
mutation has atomic event evidence, the real Kafka consumer is restart-safe,
the projection rebuild is equivalent and non-invasive, and the public demo
requires no source reading or hidden configuration.

## 18. Dependency Graph

```text
Phase 4.5 / Chapter 43R
  -> Chapter 44 event contract + atomic outbox
       -> Chapter 45 payment/money producers
       -> Chapter 46 fraud/risk producers + audit reconciliation
            -> Chapter 47 Kafka publication
                 -> Chapter 48 inbox + projection substrate
                      -> Chapter 49 analytics projections
                           -> Chapter 50 rebuild + activation
                                -> Chapter 51 offline replay dataset
                                     -> Chapter 52 integrated closure
```

Chapter 45 and Chapter 46 may be developed as independent producer families
after Chapter 44 conventions freeze, but Kafka publication begins only when
both families agree on the canonical contract. This prevents the transport from
hardening a partial event taxonomy.

## 19. Proof Strategy

Every chapter requires:

- a `PRE_IMPLEMENTATION` invariant matrix covering all eight required lenses
- a closure dossier with exact enforcement boundaries and fresh proof
- H2 regression where useful plus real PostgreSQL proof for migrations,
  constraints, JSON, triggers, and forced interleavings
- real Kafka proof for acknowledgement, duplicate, ordering, rebalance,
  retention/configuration, and consumer restart claims
- canonical-byte golden fixtures and property/table-driven schema partitions
- an accepted custom-infrastructure ADR and complete event/protocol boundary
  table before the application-owned outbox contract is implemented
- two-tenant authorization and disclosure proof
- prohibited-data canaries across database, Kafka, logs, metrics, audit, API,
  UI, fixtures, and reports
- public API/client demo plus durable PostgreSQL/Kafka reconciliation
- a traceability pass followed by a temporally separate hostile failure pass
- technical-debt, escaped-review, executable-naming, learning, and readiness
  gates

Mock producer/consumer tests may prove local control flow. They cannot certify
Kafka acknowledgement, partition ordering, rebalance, consumer offset, or
restart behavior. H2 cannot certify PostgreSQL stream locks or migrations.

## 20. Phase Completion Gate

Phase 5 is complete only when:

- all frozen in-scope mutations write their canonical event atomically with
  source truth and exact idempotency behavior
- event schemas, bytes, versions, stream order, tenant, origin, request, actor,
  and causation lineage are durable and privacy-safe
- Kafka outage never loses a committed event and never blocks unrelated domain
  mutations after commit
- publisher retries preserve event identity/bytes; unknown acknowledgement can
  duplicate but cannot corrupt downstream state
- consumers are restart-safe through inbox + projection atomicity and do not
  claim exactly-once transport
- poison events, dependency failures, blocked streams, and unrelated-stream
  progress are truthfully separated and operable
- analytics expose generation, cutoff, freshness, coverage, origin, and
  unknown/unlabeled truth
- rebuilds are generation-isolated, restart-safe, reconciled, atomically
  activated, rollback-capable, and source-state non-invasive
- offline datasets are point-in-time, tenant-scoped, digest-bound, and do not
  use Redis or future labels
- audit coverage is reconciled without conflating audit and domain events
- fresh/retained migration, real Kafka, forced PostgreSQL, property, privacy,
  browser, rollback, public demo, and full regression proof all pass
- every matrix/dossier, README, service docs, architecture source, master plan,
  debt/finding register, and learning artifact agrees
- `scripts/vision-coverage-check.py` passes, `VP-017` has executable product
  evidence, and every downstream promise still has an exact owner and trigger

## 21. First-Principles Design Review

The hard problem is the dual-write gap. Writing PostgreSQL state and then
publishing directly to Kafka creates two independent commits. If the database
commits and the process crashes before Kafka acknowledges, product truth exists
without downstream evidence. If Kafka publishes first and the database rolls
back, consumers observe a fact that never committed. The transactional outbox
makes the database commit the source state and event together, then permits
publication to retry later.

That does not create exactly-once delivery. A publisher can receive a Kafka
acknowledgement and crash before marking the event published. Retrying produces
a duplicate. THAAP handles the real guarantee: stable event identity plus a
consumer inbox whose completed record commits with the projection effect.

Analytics are projections, not truth. A projection is valuable because it is
fast and purpose-shaped; it is dangerous when nobody can say how current or
complete it is. Generation, cutoff, coverage, checkpoint, and rebuild evidence
turn a convenient table into a defensible product surface.

## 22. Proposed Founder Decisions

Founder confirmation is required before Chapter 44 implementation. The proposed
decisions are:

1. Phase 5 optimizes for a trustworthy internal event/data backbone and one
   useful payment-risk analytics loop, not external Kafka access or warehouse
   scale.
2. PostgreSQL domain tables remain authoritative; domain events are immutable
   integration facts, and analytics projections remain rebuildable read models.
3. Use an application-owned transactional outbox with separate immutable event
   and mutable publication state; do not use direct dual writes or CDC in this
   phase.
4. Promise at-least-once Kafka delivery and inbox-idempotent effects. Do not
   claim exactly-once behavior across PostgreSQL and Kafka.
5. Use one internal versioned Kafka topic keyed by tenant-qualified domain
   stream, preserving per-stream rather than global business ordering. Its
   partition count is immutable; expansion uses a versioned topic cutover.
6. Use mature Spring Kafka infrastructure and versioned canonical JSON with
   checked schemas/golden bytes; defer Schema Registry until independently
   deployed or external consumers justify it.
7. Keep domain events, audit logs, and webhook events as separate truth
   products with explicit non-interchangeability.
8. Block rather than discard an event after repeated publication failure;
   permit audited recovery while unrelated streams continue.
9. Use a durable consumer inbox and closed quarantine. Invalid events do not
   advance the affected stream, and event bytes are never edited in place.
10. Start analytics in versioned PostgreSQL projections with explicit
    freshness/coverage; defer ClickHouse until measured scale evidence exists.
11. Rebuild into a shadow generation, reconcile, catch up, and atomically
    activate; never truncate/rebuild the live generation in place.
12. Keep Phase 4.5 governed simulation authoritative. Phase 5 supplies a stable
    offline dataset only after point-in-time and equivalence proof; ML fitting
    remains Phase 7.
13. Bootstrap pre-Phase-5 history through an explicitly labeled source snapshot
    at a cutoff, not fabricated retroactive domain events.
14. Phase 5 closes only after a real source-to-Kafka-to-projection-to-rebuild
    public demo, real PostgreSQL/Kafka failure proof, zero undocumented debt,
    and every Chapter 44–52 closure contract passes.
15. Introduce a persistent local-only platform operator for event, quarantine,
    and projection operations. Merchant risk roles cannot control platform data
    infrastructure, and the bootstrap token cannot impersonate an operator.
16. Phase 5 cannot close as an infrastructure milestone. It must prove the
    `VP-017` merchant/analyst source-to-analytics-to-rebuild workflow and keep
    every dependent `VP-NNN` owner, trigger, demo, and done evidence valid.
    In particular, event provenance, replay, freshness, and dataset lineage
    must remain usable foundations for `VP-020` external PSP ingestion,
    `VP-022` shadow reconciliation, and `VP-023` measured pilot outcomes.

## 23. Stripe/Mastercard-Style Design Review Narrative

THAAP Phase 5 does not confuse a broker acknowledgement with a business commit,
a duplicate-free happy path with exactly-once processing, or an analytics row
with ledger/fraud truth. The application commits domain state and an immutable,
privacy-safe event together. A fenced publisher sends exact stored bytes after
commit. A durable inbox makes redelivery harmless, while closed quarantine and
per-stream ordering prevent malformed or missing facts from being silently
skipped. Analytics expose their generation, cutoff, coverage, and freshness and
can be rebuilt into a shadow generation without touching payment state.

The result is narrower than a global card-network data platform, but it is
defensible: every downstream number can be traced to one tenant-qualified,
versioned, digest-bound event and reconstructed through executable failure
proof. That is the minimum credible substrate for Phase 6 dashboards, Phase 7
ML, Phase 8 graph/network intelligence, and Phase 10 real-merchant validation.
