# Chapter 42 Closure Dossier

- Chapter: Chapter 42 — Webhook Verification, Redelivery, And Dead-Letter Operations
- Chapter number: 42
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_42_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

`ER-0066`, `ER-0068`, and `ER-0071` are resolved. Verification signs first and
then acquires an exact locked dispatch permit immediately before transport; the
post-sign terminal-winner proof passes on H2 and PostgreSQL 16.14 with zero
transport calls.

This dossier was frozen on 2026-07-27 after Chapter 41 closure and before any
Chapter 42 architecture, schema, production code, configuration, test, UI, or
demo change. It retains the original implementation, traceability, hostile,
production-dialect, live-demo, and regression passes as evidence; the newly
expanded dispatch family is now re-proved at the production lock boundary.

## Observable promises

- Merchant developers can prove endpoint reachability/secret possession with a short-lived, single-use, generation- and URL-bound signed challenge.
- Merchants can redeliver their own exact event through merchant authentication; only a live RISK_MANAGER can inspect operator dead letters or request operator redelivery.
- Manual generations preserve exact event ID/payload bytes, append actor/request/attempt history, and never blur automatic retry with operator action.
- Endpoint disable/rotation, worker claims, redelivery, verification, expiry, and stale finalizers converge to one legal durable state.
- The console and executable receiver demonstrate verification, failure, dead-letter inspection, redelivery, signature verification, and event-ID dedupe without storing credentials or exposing payload/response secrets.

## Failure inventory

The frozen matrix covers event reconstruction, money/fraud duplication,
in-flight and concurrent replay, idempotency/actor substitution, stale
generations and claim tokens, endpoint disable/rotation orderings, nonce
hash/single-use/expiry, response-field smuggling and limits, redirect/DNS/IP/TLS
identity, receiver duplicates, cursor ties, tenant substitution, privacy sinks,
retained migration, scheduler retry, dependency outage, and external-I/O
transaction boundaries.

## Enforcement boundaries

Required boundaries are immutable original events, endpoint-before-delivery and
endpoint-before-challenge locking, generation-local retries, append-only
requests/attempts/challenge events, protected challenge projections,
actor-derived composite tenant keys, exact
idempotency and replay authorization, bounded dead-letter keysets, a narrow
ADR-0009 final-head field extension, fixed address/hostname identity,
transaction-aware safe signals, fresh/retained PostgreSQL, memory-only UI, and
a public signed receiver demo with duplicate-event reconciliation.

| Promise | Real boundary | Evidence |
|---|---|---|
| Exact event replay | persisted `webhook_events` identity/payload plus generation-aware delivery reopening | live receiver ID/digest equality, V64 mutation guards |
| Authorized generation | principal-bound idempotency and endpoint-before-delivery locks | role/tenant matrix, forced PostgreSQL ordering |
| Endpoint proof | signed hash-only challenge plus URL/secret/expiry/pending fences | API privacy/signature tests, forced rotation race |
| Response grammar | ADR-0009 final successful response head | hostile socket representation partitions |
| Attempt truth | claim token, payload digest, signing generation, send certainty | executor contracts and append-only attempt triggers |
| Bounded operations | typed keyset dead letters, expiry batch, readiness and closed signals | PostgreSQL/live query, API boundary tests, health tests |

## Boundary family evidence

Valid and invalid event identity, delivery state, generation, actor role,
tenant, endpoint state, idempotency key, challenge proof, expiry, response-head
representation, HTTP retry class, send certainty, cursor/filter, privacy sink,
migration, readiness, and browser partitions reconcile with the frozen matrix.
The production-dialect failure reopened the complete optional-filter family;
typed presence flags now cover every absent/present combination.

## Migration evidence

- V63 adds generation-aware delivery/attempt state, immutable actor-bound
  redelivery requests, hash-only endpoint challenges, append-only verification
  events, endpoint verification projection state, tenant-qualified constraints,
  and bounded indexes.
- V64 installs PostgreSQL guards for delivery identity/counter monotonicity,
  exact generation transitions, terminal attempt/challenge immutability, and
  append-only redelivery/verification history.
- The retained V62→V64 test preserves a real exhausted delivery and terminal
  attempt, backfills generation 1 and the exact event digest, leaves legacy
  signing generation unknown rather than invented, and creates zero requests or
  challenges.
- Verification uses prepare transaction → exact-generation signing → locked
  post-sign dispatch permit → external HTTP without a database transaction →
  locked/fenced finalize transaction. Redelivery locks
  endpoint before delivery and reuses the persisted event bytes.

## Exact proof

- `WebhookOperationsContractTests`: signed verification, hash-only/raw-token
  privacy, exact replay, wrong proof, rotation fencing, role and two-tenant
  authorization, bounded/cursor-filtered dead letters, response canaries,
  merchant/operator redelivery, idempotency conflict, expiry, and health.
- `WebhookSecurityAndRetryTests` and `WebhookDeliveryExecutorUnitTests`: exact
  final response-field grammar, 1xx/non-2xx/body exclusion, malformed and
  duplicate partitions, closed HTTP retry classification, and preserved
  request-sent/response-status evidence.
- `WebhookDeliveryPostgreSqlConcurrencyTests`: deterministic redelivery/disable
  lock ordering, verification HTTP/rotation fencing, the passing post-sign
  terminal-winner partition with zero transport calls, and database rejection
  of skipped generation plus request/attempt/challenge/event history mutation.
- `PostgreSqlMigrationTests`: fresh V63/V64 objects/triggers plus the retained
  history upgrade described above.
- Cockpit: 20 Vitest tests and TypeScript/Vite production build pass; the
  operation test proves lazy dead-letter load, generation-2 refresh, and no
  rendered credential.
- Public client: 3 receiver/client safety tests pass. The retained local
  PostgreSQL live demo reached `VERIFIED -> EXHAUSTED -> generation 2 ->
  SUCCEEDED`, verified both HMAC signatures, and reconciled event ID
  `e6f8b28d-05e4-44af-a544-3e495f713c08` with payload SHA-256
  `98765d88330d285b0a6b13e1291bfc19e25436b57c3a4b0c533dacedfddac0f1`.
- Full regression: 759 backend tests, 140 repository Python tests, and 20 UI
  tests pass with zero failures/errors/skips; the UI production build passes.

## Demo evidence

- Three receiver/client safety tests cover loopback origin restrictions,
  credential preconditions, exact verification echo, and exact event bytes.
- The retained local PostgreSQL live demo reached `VERIFIED -> EXHAUSTED ->
  generation 2 -> SUCCEEDED`, verified both HMAC signatures, and reconciled the
  event ID and payload SHA-256 recorded in the exact proof section.
- Provisioned merchant, operator, and signing credentials remained environment
  or memory only and were absent from sanitized output.

## Hostile and traceability review

The later hostile pass re-enumerated event reconstruction, exact/different-key
replay, viewer and foreign-tenant substitution, endpoint disable/rotation,
stale verification completion, expired challenge batches, response-field
duplication/malformed/body/1xx/non-2xx partitions, send certainty, retryable
versus terminal HTTP status, retained migration, missing schema/health, and UI
credential sinks. One production-only finding escaped the H2 contract:
PostgreSQL could not type a standalone nullable timestamp parameter in the
dead-letter JPQL. The entire optional-filter family was reopened and changed to
explicit typed presence flags; focused H2, PostgreSQL, live-demo, and full
regression proof then passed. The finding occurred before any completion claim,
so it is not an escaped-review-register entry.

## Operational signals

- `webhookOperations` participates in readiness and checks required schema plus
  bounded pending and overdue challenge counts.
- Verification expiry is switchable and batch-bounded from 1 through 100;
  challenge TTL is startup-bounded from 30 seconds through 5 minutes.
- Metrics use only closed operation, outcome, actor/source, and transaction
  dimensions; contracts reject tenant, actor ID, endpoint, URL, event, payload,
  token/hash, signature, secret, and idempotency values.
- Delivery logs retain only bounded IDs, attempt, outcome, error code, and
  latency; public errors remain request/path/type-safe.

## Escaped-review assessment

`ER-0066`, `ER-0068`, and `ER-0071` escaped the prior Chapter 42 completion
claim. Actor-first redelivery fencing, the manager-only role reconciliation,
and the final post-sign terminal-winner PostgreSQL interleaving all pass. The
permit leaves `dispatch_started_at` null and performs no network side effect
when a terminal transition wins.

## Known debt

No Chapter 42 debt is accepted at freeze. A general event platform, Kafka,
arbitrary replay/body mutation, general HTTP response client, hosted public
egress, mTLS, production certificate provisioning, and external dead-letter
queue are rejected scope rather than deferred implementation.

## Source-of-truth reconciliation

The Phase 4.5 detailed plan, implementation master plan, root and component
READMEs, daily note, weekly review, ADR-0009, proof matrix, dossier, and
escaped-review register identify all three findings as resolved and Chapter 42
as `READY_TO_MERGE` with every invariant `PASS`.

## Custom infrastructure decision

`ADR_0009_EXTENDED_AND_VERIFIED`. Chapter 19's accepted narrow socket transport
captures one exact bounded verification field from the final successful
response head. ADR-0009 records representation, duplication, limits, timeout,
redirect, address/TLS identity, persistence, operational, and replacement
boundaries. Tests prove no response body or general header map was introduced.

## Merge recommendation

`MERGE`. The post-sign permit contract passes on H2 and PostgreSQL, actor
fencing and role boundaries agree across code/UI/docs, all three findings are
resolved, and no Chapter 42 debt is open.
