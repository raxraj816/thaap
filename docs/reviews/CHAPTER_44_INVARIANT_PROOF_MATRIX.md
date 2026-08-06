# Chapter 44 Invariant Proof Matrix

- Chapter: Chapter 44 — Canonical Event Contract And Atomic Outbox Foundation
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

Verification update (2026-08-01): V65–V66, the 42-entry catalogue,
RFC-8785-compatible exact-byte codec, transactional writer/outbox, immutable
history guards, and platform-operator boundary are implemented. Codec/writer
contracts and four real-PostgreSQL atomicity, locking, tenant, and retained-
migration tests passed; the later hostile pass found no open boundary.

This matrix was drafted and frozen on 2026-07-27 after founder implementation
authorization confirmed the sixteen Phase 5 decisions and before Phase 5
production code, migrations, runtime configuration, product fixtures, UI, or
demo changes. Planned classes, migrations, tests, and commands below are proof
obligations, not implementation or completion claims. Every invariant remains
`PENDING` until production-boundary evidence, traceability review, and the
temporally later hostile pass complete.

The platform-identity surface was reopened and refrozen on 2026-07-27 before
its controller, service, or request-ledger implementation. The clarification
below fixes exact local routes, headers, idempotent-replay semantics, and the
reveal-once boundary without widening the already frozen platform-operator
promise. The event/outbox scope and failure inventory did not change.

ADR-0011 is accepted before implementation. It selects an application-owned
transactional outbox, strict Jackson-built allowlisted trees, external RFC
8785/JCS-compatible canonicalization, exact stored payload and complete-record
bytes, and separate payload/full-record SHA-256 boundaries.

## C44-EVT-001

- Lens: PAYMENT_FRAUD
- Invariant: Chapter 44 creates infrastructure for committed facts but emits no merchant-visible payment, fraud, ledger, label, audit, webhook, or analytics event from a production mutation; the test-owned atomic probe cannot be enabled or addressed as a product capability.
- Failure sequence: Infrastructure installation backfills historical rows as invented events, a probe endpoint is reachable outside tests, or an event replay calls payment, processor, ledger, fraud, audit, or webhook orchestration.
- Implementation: `DomainEventWriter` accepts only a closed catalogue entry from an already-owned domain transaction; Chapter 44 registers schema definitions without wiring production mutation services; a test-scope `TransactionalEventProbe` proves atomicity without production component scanning.
- Proof: `DomainEventWriterContractTests`, source/component scans, fresh and retained PostgreSQL row reconciliation, and public API route inventory prove zero production producer and zero invented historical event.
- Adversarial evidence: Fresh V1-to-current database, retained V64 facts in every major domain, test profile on/off, component scan, copied event name, replay invocation, and before/after payment/fraud/ledger/audit/webhook counts.
- Status: PASS

## C44-ATO-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: A source mutation, immutable domain event, pending publication, required audit evidence, and idempotency completion form one PostgreSQL commit; any participating failure rolls every write back, and completed idempotent replay creates no second event or stream version.
- Failure sequence: Source state commits while event/publication insertion fails, event commits while source or audit fails, idempotency completion rolls back alone, or an ambiguous API retry inserts a duplicate event.
- Implementation: transaction-owned `DomainEventWriter`, immutable `DomainEvent`, mutable `EventPublication`, and existing `IdempotencyService` participation without independent propagation or broker calls.
- Proof: `DomainEventWriterContractTests` inject failure before/after stream allocation, event insert, publication insert, audit insert, and idempotency completion; exact replay asserts one source row, event, publication, audit result, and response.
- Adversarial evidence: Failure at every write boundary, checked/runtime exception, transaction rollback after version allocation, exact/different key, same/different request digest, restart, and retained history.
- Status: PASS

## C44-STR-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Each tenant-qualified domain stream has one gap-free positive version sequence allocated while its authoritative aggregate or stream-head row is locked; two concurrent facts cannot claim one version, and a later unpublished version cannot pass its predecessor.
- Failure sequence: Two transactions read the same next version, sequence allocation survives rollback as a fabricated business gap, commit order is mistaken for global order, or one blocked stream prevents unrelated-stream allocation.
- Implementation: `EventStreamVersionAllocator` locks the domain aggregate in its existing order or an `event_stream_heads` row after the aggregate; unique `(merchant_id, stream_type, stream_id, stream_version)` enforcement and publication predecessor queries preserve local order only.
- Proof: `DomainEventPostgreSqlTests` force allocator-first and competing-mutation-first orderings with lock probes, rollback one winner, and assert exact stream positions plus unrelated-stream progress.
- Adversarial evidence: Same/different tenant, same/different stream, empty/existing head, rollback, later transaction committing first, unique-key collision, blocked predecessor, and noisy unrelated stream.
- Status: PASS

## C44-CAN-001

- Lens: SECURITY_PRIVACY
- Invariant: An allowlisted payload has one RFC 8785/JCS-compatible UTF-8 representation; `payloadSha256` binds those exact bytes and `eventRecordSha256` independently binds the complete stored envelope-plus-payload bytes that Kafka will later receive.
- Failure sequence: Jackson ordering is mistaken for JCS, nested key order or numeric spelling changes a digest, Unicode or duplicate keys become ambiguous, PostgreSQL JSON reserialization replaces stored bytes, or retry reconstructs an envelope with changed time/actor/origin.
- Implementation: `CanonicalEventCodec` isolates the pinned RFC-listed Java JCS implementation behind strict Jackson tree construction; `domain_events` persists payload bytes/digest and complete record bytes/digest as immutable values; the full-record digest remains outside its own input.
- Proof: `CanonicalEventCodecTests`, RFC 8785/golden cross-language fixtures, property/table partitions, database precision round trips, and `DomainEventPostgreSqlTests` recompute both digests and reject byte/column mutation.
- Adversarial evidence: Key permutations, nested objects, null/absent, Boolean, safe/unsafe integer, decimal/exponent/negative zero, malformed UTF-8, lone surrogate, combining characters, control characters, pre/exact/post microsecond, maximum size/depth, duplicate key, digest mismatch, and full-record self-reference attempt.
- Status: PASS

## C44-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Event, stream-head, publication, platform-operation, and later consumer references are merchant-qualified; copied foreign event, stream, aggregate, or publication identifiers reveal no existence and cannot create a cross-tenant child row.
- Failure sequence: A bare UUID lookup binds a foreign event to the caller's merchant, a record key substitutes tenant identity, or a publication foreign key omits merchant ownership.
- Implementation: composite tenant keys and foreign keys, authenticated merchant derivation for product writers, platform-operator routes that never accept merchant authority from request bodies, and repositories requiring merchant-qualified identities.
- Proof: `DomainEventWriterContractTests`, direct PostgreSQL composite-key tests, and equal-shaped two-merchant fixtures prove not-found substitution and zero cross-tenant mutation.
- Adversarial evidence: Foreign event/stream/publication ID, same aggregate UUID under two merchants, copied request key, mismatched envelope merchant, mismatched future record key, disabled merchant, and direct invalid insert.
- Status: PASS

## C44-PLT-001

- Lens: API_PRODUCT
- Invariant: A persistent local-only platform operator has server-owned `DATA_OPERATOR` or `PLATFORM_ADMIN` authority, reveal-once hashed credentials, live actor/credential revalidation, and no capability to mutate merchant payment or fraud truth; the bootstrap token creates only the first administrator and never impersonates one.
- Failure sequence: A merchant risk role invokes a platform route, the bootstrap token performs data-plane work, a disabled operator replays prior authority to start work, plaintext credentials persist or render, or a platform operator calls merchant mutation services.
- Implementation: `PlatformOperator`, `PlatformOperatorCredential`, `platform_operator_bootstrap_state`, and `platform_operator_requests`; loopback-only controller boundary; prefix plus versioned credential hash; actor-first locking; HMAC-bound principal idempotency; safe-result replay; and closed authorization policy. Raw credentials are returned only on the winning creation response and are never persisted; exact replay returns the prior operator identity with `credentialRevealed=false`, while conflicting replay returns `409`.
- Proof: `PlatformOperatorContractTests`, `PlatformOperatorPostgreSqlTests`, credential privacy scans, local-route configuration tests, and source call-graph checks cover bootstrap, role, disable, replay, and domain non-interference.
- Adversarial evidence: Bootstrap before/after first admin, viewer/analyst/risk manager/data operator/admin, enabled/disabled/rotated credential, exact/conflicting key, disable-after-authentication, foreign tenant resource, remote profile, plaintext canary, and merchant-domain mutation attempt.
- Status: PASS

## C44-LIN-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every stored event binds globally unique identity, tenant-qualified stream and version, event type/version, occurrence/recording time, correlation/causation, safe request/trace/actor lineage where available, server-owned traffic origin, exact payload bytes/digest, and exact complete-record bytes/digest without inventing unavailable historical facts.
- Failure sequence: Current actor/origin replaces decision-time lineage, missing lineage is fabricated, timestamps gain precision after persistence, payload identity disagrees with envelope, or V64 source rows receive retroactive event IDs/times.
- Implementation: closed envelope value types, schema catalogue, database precision normalization, nullable lineage with explicit meaning, source-snapshot bootstrap separation, and immutability guards.
- Proof: `CanonicalEventCodecTests`, `DomainEventPostgreSqlTests`, golden fixtures, retained V64 upgrade reconciliation, and canary scans assert exact available lineage and zero fabricated history.
- Adversarial evidence: Actor present/absent, request/trace present/absent/oversized, live/synthetic origin, causation present/foreign/missing, before/exact/after precision, payload/envelope disagreement, and populated retained V64 domains.
- Status: PASS

## C44-CAT-001

- Lens: API_PRODUCT
- Invariant: The V1 event catalogue is closed, versioned, capability-named, privacy-reviewed, and reconciled to exactly one producer owner and demonstrated consumer or lineage purpose; unknown event types/versions cannot be written through a generic string escape hatch.
- Failure sequence: Two chapters emit the same semantic fact differently, a state-change-noise event has no consumer, an unrestricted map enters a payload, or a later producer silently changes meaning under version 1.
- Implementation: `DomainEventCatalogue` with typed schema identifiers and per-event payload codecs; Chapter 44 registers definitions while Chapters 45 and 46 own the only initial production mappings shown below.
- Proof: `CanonicalEventCodecTests`, schema/golden fixture inventory, producer ownership scan, additive/semantic compatibility table tests, and documentation reconciliation reject unknown or duplicate ownership.
- Adversarial evidence: Known/unknown type, version zero/one/future, duplicate semantic owner, additive optional field, removed/renamed/type/unit/enum change, unrestricted metadata, producer without consumer purpose, and consumer without supported matrix.
- Status: PASS

## C44-OBS-001

- Lens: OBSERVABILITY
- Invariant: Foundation signals expose bounded pending count, oldest-event age, storage growth, writer outcome, integrity failure, and configuration state only after transaction truth, without tenant, event, stream, aggregate, actor, request, trace, payload, digest, credential, or exception text as metric dimensions.
- Failure sequence: Rolled-back event increments success, payload or tenant becomes a tag, digest mismatch logs bytes, missing schema reports ready, or a test-only probe appears as product traffic.
- Implementation: transaction-aware `DomainEventMetrics`, safe structured logs with closed event type/version and outcome, and a fail-closed `DomainEventFoundationHealthIndicator` over required schema/catalogue state.
- Proof: `DomainEventObservabilityTests` asserts that a committed append is invisible until commit, that a rolled-back append reports `ROLLED_BACK` rather than `COMMITTED`, that an integrity rejection counts immediately because its transaction is about to roll back, that a committed write cannot be claimed without transaction truth, and that every catalogue event type reports under the bounded `event_type`/`event_version`/`outcome` tag set with no tenant, stream, aggregate, actor, request, trace, payload, digest or credential canary reachable. The writer emitted no signal at all until `DomainEventWriterMetrics` was added with this suite; the pending-count and oldest-age half of the row is held by the Chapter 47 publication meters.
- Adversarial evidence: Commit/rollback, schema absent/present/corrupt, payload/PAN/CVV/email/token/trace canaries, database exception, digest mismatch, oversized input, disabled producer, and repeated event types.
- Status: PASS

## C44-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Forward V65/V66 event-foundation migrations converge on fresh and realistic retained V64 PostgreSQL without fabricating events, preserve all source truth, reject incompatible legacy data before the first unsafe DDL, install immutable history guards and bounded indexes, and keep Kafka/consumer/rebuild behavior disabled because Chapters 47–50 own it.
- Failure sequence: Retained upgrade backfills false events, partial migration leaves mutable history, application starts with missing guard/catalogue, rollback deletes pending evidence, or Chapter 44 claims broker acknowledgement, consumption, projection, or rebuild behavior it cannot prove.
- Implementation: capability-named V65 foundation schema and V66 protection migrations, explicit producer-enable gates, schema/catalogue readiness, bounded indexes, and no Kafka dependency or worker registration in this chapter.
- Proof: `DomainEventPostgreSqlTests` for fresh V1-to-V66 and retained V64-to-V66, invalid-legacy preflight partitions, mutation rejection, repeated startup, configuration rollback, exact source row digests, and zero broker/consumer/rebuild components.
- Adversarial evidence: Empty/populated V64, corrupt stream head, over-limit payload seed, invalid enum/string domain, interrupted/repeated startup, mixed application version, enabled/disabled producer flag, missing table/index/trigger, and attempted event UPDATE/DELETE.
- Status: PASS

## Frozen Catalogue Ownership

| Producer owner | Initial semantic families | Chapter 44 disposition |
|---|---|---|
| Chapter 45 | intent, attempt/decision, authorization, capture, cancel/void, refund, chargeback, payment authentication | register schema and owner only; no producer wiring |
| Chapter 46 | review, label, incident, profile/policy, authored rule, safety exception, dispute, webhook operation, post-authorization signal, trusted scenario | register schema and owner only; no producer wiring |
| Chapter 47 | publication attempt and transport progress | operational evidence, not a domain event family |
| Chapter 48 | inbox, quarantine, and projection application | operational/projection evidence, not a domain event family |
| Chapters 49–51 | analytics, rebuild, and dataset materialization | derived evidence, never a new source-domain fact by default |

## Frozen Platform Identity Contract

| Capability | Exact contract |
|---|---|
| Bootstrap first administrator | loopback-only `POST /internal/platform/operators/bootstrap`; `X-Thaap-Platform-Bootstrap-Token` plus `Idempotency-Key`; bootstrap token is HMAC identity only and never an operator actor |
| Read live session | loopback-only `GET /internal/platform/operators/me`; `X-Platform-Operator-Key`; returns safe operator ID/code/role/status only |
| Create operator | loopback-only `POST /internal/platform/operators`; live `PLATFORM_ADMIN` plus `X-Platform-Operator-Key` and `Idempotency-Key`; body contains bounded display name and one closed role |
| Disable operator | loopback-only `POST /internal/platform/operators/{operatorId}/disable`; live `PLATFORM_ADMIN` plus `X-Platform-Operator-Key` and `Idempotency-Key`; actor is locked/revalidated before target |
| Reveal-once replay | winning bootstrap/create response returns the new credential once; exact replay returns the persisted safe operator result with `credential=null` and `credentialRevealed=false`; conflicting operation/body/principal reuse returns `409` |
| Durable mutation identity | `platform_operator_requests` stores purpose-HMACed idempotency identity, versioned request digest, principal kind/operator/credential binding, safe result operator ID, lifecycle, and timestamps; it stores no raw key, credential, request body, or response body |

## Frozen Hostile-Family Coverage

| Phase 5 family | Chapter 44 obligations |
|---|---|
| Atomic creation | C44-ATO-001 |
| Stream ordering | C44-STR-001 |
| Canonical protocol | C44-CAN-001 and C44-CAT-001 |
| Publication | C44-STR-001 and C44-OPS-001 establish pending state/order only; Kafka proof remains Chapter 47 |
| Consumption | C44-CAN-001 and C44-CAT-001 freeze validation inputs; runtime proof remains Chapter 48 |
| Tenant isolation and authorization | C44-TEN-001 and C44-PLT-001 |
| Projection correctness | C44-EVT-001 proves non-impact; projection effects remain Chapters 48–49 |
| Rebuild | C44-LIN-001 preserves immutable source bytes; runtime proof remains Chapter 50 |
| Security/privacy | C44-CAN-001, C44-PLT-001, and C44-OBS-001 |
| Migration | C44-OPS-001 |
| Operations | C44-OBS-001 and C44-OPS-001 |
| Documentation/demo | C44-EVT-001, C44-CAT-001, and the planned atomic foundation reconciliation |
