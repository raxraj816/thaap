# Chapter 42 Invariant Proof Matrix

- Chapter: Chapter 42 — Webhook Verification, Redelivery, And Dead-Letter Operations
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

The recovery for `ER-0066`, `ER-0068`, and `ER-0071` adds an explicit locked
dispatch permit and bounded in-flight lease, actor-and-credential-first
redelivery locking, deterministic PostgreSQL disable races, and manager-only
operator webhook access reconciled to the authoritative phase role table. The
post-sign terminal-winner interleaving passed on PostgreSQL 16.14 with zero
transport calls, and all three findings are resolved.

This matrix was drafted and frozen on 2026-07-27 after Chapter 41 closure and
before Chapter 42 architecture, schema, production code, configuration, test,
UI, or demo changes. It derives from the Chapter 19/21 signed at-least-once
delivery, endpoint idempotency, signing-generation, address-pinning, retry,
claim-token, and fairness substrate plus the approved Phase 4.5 promises.

## C42-EVT-001

- Lens: PAYMENT_FRAUD
- Invariant: Manual redelivery reuses the exact immutable webhook event UUID, UTF-8 payload bytes, payload digest, event type, resource, origin, and occurred time; it appends operational evidence and never repeats or rewrites the originating payment, fraud, ledger, audit, or domain mutation.
- Failure sequence: Replay creates a new event/body, reserializes old payment state, duplicates money/audit truth, or changes an event timestamp/ID.
- Implementation: V63 generation-aware reopening of the existing delivery projection plus append-only redelivery request/attempt evidence; the service never calls a domain mutation or reconstructs an event envelope.
- Proof: `WebhookOperationsContractTests` and the public receiver prove original event identity/digest; PostgreSQL history tests reject mutation; the live demo proves identical event ID and payload SHA-256 across failure and generation-2 success.
- Adversarial evidence: Old/current resource state, successful/exhausted delivery, receiver duplicate, exact/different request key, synthetic/live origin, and event UPDATE/DELETE.
- Status: PASS

## C42-RED-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: An authorized redelivery locks endpoint then delivery, requires a terminal generation and active endpoint, appends one actor-bound request, increments generation once, resets only generation-local retry state, and converges under exact idempotent replay.
- Failure sequence: Replay while delivering, two keys reopen one generation, stale request resets a newer attempt, total attempt identity collides, or a disabled endpoint becomes sendable.
- Implementation: `delivery_generation`, generation-local attempt numbering, immutable `webhook_redelivery_requests`, actor-bound idempotency, and endpoint-before-delivery locking are enforced by service code, V63 constraints, and V64 triggers.
- Proof: `WebhookDeliveryPostgreSqlConcurrencyTests` forces redelivery-before-endpoint-disable plus operator-disable-before-redelivery ordering, observes the exact PostgreSQL lock wait, and proves either one legal generation-2 result or zero mutation after disable; API contracts prove exact replay and different-key conflict with exact request/attempt postconditions.
- Adversarial evidence: PENDING/DELIVERING/RETRY/SUCCEEDED/EXHAUSTED, active/disabled/rotated endpoint, one/two keys, stale generation, claim token, and retry exhaustion reset.
- Status: PASS

## C42-VER-001

- Lens: SECURITY_PRIVACY
- Invariant: Verification uses a cryptographically random short-lived single-use token stored only as a hash; the signed challenge is bound to tenant, endpoint ID, exact normalized URL, signing-secret generation, challenge ID, and expiry, and only exact response proof can verify it.
- Failure sequence: Database compromise reveals token, response replays, rotation/URL change verifies stale ownership, expiry/recovery terminalizes between validation and dispatch yet the caller still sends, or another endpoint/tenant consumes it.
- Implementation: `WebhookEndpointVerificationService` generates a 32-byte token, persists only its SHA-256, signs a canonical envelope, splits database transactions around HTTP, and requires an explicit permit returned from the endpoint-then-challenge lock. The permit rejects terminal, expired, disabled, changed, or already-dispatched state; expiry cannot terminalize a dispatch inside the configured transport lease, and abandoned work becomes expirable after that lease.
- Proof: API contracts prove signed success, hash-only persistence/privacy, exact replay, wrong token, expiry, and tenant denial; `terminalVerificationWinnerAfterSigningRevokesDispatchBeforeAnyNetworkSideEffect` forces a terminal winner after signing but before the permit and proves `dispatch_started_at` stays null with zero transport calls on H2 and PostgreSQL 16.14. Forced HTTP/rotation ordering proves stale generation finalization fails.
- Adversarial evidence: Missing/wrong/duplicate token, case/whitespace/Unicode mutation, pre/at/post expiry, rotation, URL replacement, disable, foreign endpoint/challenge, and database dump scan.
- Status: PASS

## C42-PROTO-001

- Lens: SECURITY_PRIVACY
- Invariant: The ADR-0009 socket transport returns only one optional exact `THAAP-Webhook-Verification` response field from the already bounded final response head; duplicate, folded, non-ASCII/control, oversized, informational-only, redirect, body-only, or malformed representations fail closed.
- Failure sequence: Parser accepts an unauthenticated/ambiguous representation, header smuggling selects one duplicate, a 1xx field survives into final state, or response body broadens the custom client.
- Implementation: ADR-0009 now defines the exact response-field boundary; `SocketWebhookHttpTransport` captures one valid field from the final 2xx head without body consumption, redirects, decompression, cookies, or a general header map.
- Proof: `WebhookSecurityAndRetryTests` proves valid case/OWS plus 1xx, non-2xx, body-only, duplicate, invalid length, padding, and character partitions while retaining the Chapter 19 status/head/limit/control/timeout/DNS/TLS suite.
- Adversarial evidence: Zero/one/two fields, different casing, empty/space/tab, obs-fold, CR/LF/control/high-byte, 8KiB line, 32KiB cumulative head, 100→2xx, 3xx, and body echo.
- Status: PASS

## C42-SIG-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each automatic or manual attempt records delivery generation, generation attempt, endpoint signing generation, exact immutable event digest, send certainty, and terminal result; redelivery signs fresh dispatch time plus the unchanged event bytes with the valid endpoint generation at dispatch.
- Failure sequence: Rotation signs with stale generation, attempt lineage loses which replay caused it, retry mutates bytes, or unknown-send is reported as known failure/success.
- Implementation: Delivery claims and attempts carry global/generation counters, dispatch source, request lineage, exact payload digest, and signing generation while retaining claim-token, secret-generation, payload-integrity, and send-certainty enforcement.
- Proof: Executor/security contracts cover stale generation, integrity, known-no-send/request-sent/unknown partitions, and closed HTTP retry classification; the live receiver verifies both fresh signatures and exact event bytes.
- Adversarial evidence: Rotation before/after claim/prepare/send, previous-secret expiry, payload digest mismatch, transport before/during/after write, duplicate receiver event ID, and manual versus automatic source.
- Status: PASS

## C42-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Merchant and operator dead-letter reads, challenges, requests, deliveries, events, endpoints, attempts, actors, and audits are merchant-qualified from authenticated context; copied foreign IDs reveal no existence and never create cross-tenant child rows.
- Failure sequence: Operator replays another merchant's delivery, merchant verifies a foreign endpoint, or a join follows bare event/delivery UUID.
- Implementation: Controllers derive tenant and actor from authenticated contexts; queries and V63 child relationships use merchant-qualified identities and composite foreign keys with not-found substitution.
- Proof: `WebhookOperationsContractTests` uses two merchants and copied endpoint/delivery IDs to prove 404/non-disclosure and zero cross-tenant mutation; migration constraints cover composite child lineage.
- Adversarial evidence: Same-shaped foreign IDs, wrong operator merchant, copied cursor/idempotency key, absent actor, disabled actor/merchant, and composite-FK insertion.
- Status: PASS

## C42-AUTH-001

- Lens: API_PRODUCT
- Invariant: Merchant credentials may verify their endpoint and redeliver their delivery; only `RISK_MANAGER` may inspect or request operator redelivery; viewer, analyst, disabled, and wrong-tenant actors are rejected again on idempotent replay.
- Failure sequence: A viewer or analyst replays via a copied key, a disabled manager receives stored success, merchant input chooses actor/tenant, or challenge authorization bypasses merchant ownership.
- Implementation: Merchant and risk-operator controllers use principal-bound idempotency and replay authorization; operator execution and replay lock and revalidate the live actor plus credential before endpoint/delivery state and enforce the closed `RISK_MANAGER` role.
- Proof: API contracts prove viewer/analyst rejection, manager and merchant access, exact replay/different-key conflict, missing-key rejection, audit actor metadata, and tenant denial; the PostgreSQL operator-disable interleaving proves a request authenticated before disable cannot advance generation afterward.
- Adversarial evidence: Viewer/analyst/manager, enabled/disabled, merchant/operator credential, absent/wrong token, same/different principal/key/body, and replay after authorization change.
- Status: PASS

## C42-API-001

- Lens: API_PRODUCT
- Invariant: Dead letters are a bounded stable keyset-filtered view of `EXHAUSTED`, never a second queue; redelivery and challenge APIs are strict, typed, idempotent, state-exact, and expose sanitized generation/progress without payloads, response bytes, secrets, nonce hashes, URLs, or raw errors.
- Failure sequence: Unbounded scan, cursor skips equal timestamps, GET leaks event payload/URL, unknown fields widen reason, or UI invents optimistic terminal state.
- Implementation: The dead-letter repository/service use explicit typed presence flags, composite descending cursor semantics, closed enums, strict DTOs, and a lazy React refresh/action state.
- Proof: API contracts cover bounded limit/cursor/filter/unknown-field/privacy partitions; seven UI tests cover lazy empty state, operation refresh, and credential non-rendering; the public client executes the documented example.
- Adversarial evidence: Limit 0/1/100/101, malformed/partial/foreign cursor, unknown/duplicate JSON, unsupported media/method, payload/key/token/PAN canaries, and stale UI.
- Status: PASS

## C42-OBS-001

- Lens: OBSERVABILITY
- Invariant: Closed low-cardinality dimensions distinguish automatic retry, manual redelivery, verification, generation, endpoint state, send certainty, and bounded error class only after transaction truth; no tenant, actor, endpoint, delivery, event, URL, payload, token/hash, signature, secret, response bytes, or idempotency value becomes a metric tag or raw log/error.
- Failure sequence: Response token or URL becomes a tag, rolled-back reopen emits success, transport exception echoes headers, or dead letters disappear from readiness/operations.
- Implementation: `WebhookOperationsMetrics`, safe audits/logs, closed error vocabularies, backlog/challenge queries, and `WebhookOperationsHealthIndicator` publish only bounded post-transaction operational truth.
- Proof: Contracts verify metric tag closure, rollback-safe counting, health down/up, and privacy canaries across API/audit; existing executor proofs cover stale finalizers, and the retained live stack reported readiness before the demo.
- Adversarial evidence: URL/nonce/payload/signature/key/PAN/email canaries, parser/transport/DB failures, rollback, stale claim, receiver duplicate, and dependency outage.
- Status: PASS

## C42-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained upgrades preserve all event/delivery/attempt/secret truth, assign compatible generation defaults, create no requests/challenges, install bounded indexes/history guards, expire pending challenges idempotently, and keep external HTTP outside database transactions.
- Failure sequence: Migration requeues exhausted deliveries, legacy attempts collide, scheduler sends while locked, expired challenge stays pending, missing schema reports ready, or optional worker configuration claims false health.
- Implementation: Forward-only V63/V64 provide compatibility backfill, bounded indexes, and history guards. The service adds bounded idempotent expiry that excludes an active dispatch lease while preserving prepare/permit, HTTP, and finalize transaction separation.
- Proof: `PostgreSqlMigrationTests` proves fresh and retained V62 history; concurrency tests prove no database lock spans verification HTTP and a terminal pre-dispatch winner emits no socket call; contracts prove bounded expiry/health; the live retained-stack demo proves verification→failure→dead-letter→redelivery. Full regression and gates are recorded in the dossier.
- Adversarial evidence: Empty and retained V62, all legacy statuses/attempt counts, repeated startup, worker on/off, missing transport/schema, client/worker crash, timeout, and configuration rollback.
- Status: PASS

## Frozen Boundary Table

| Boundary | Valid | Rejected |
|---|---|---|
| Event truth | same event ID and exact persisted bytes | reconstruction or domain replay |
| Redelivery | terminal generation, active endpoint, authorized exact request | in-flight/stale/disabled/cross-tenant reopen |
| Verification | exact live nonce, URL, secret generation, expiry | replay, mismatch, stale generation/URL, expired proof |
| Response field | one bounded final-head field | duplicate/folded/control/body/1xx/oversized representation |
| Attempts | global identity plus generation-local retry lineage | collision, retry-budget carryover, stale claim finalization |
| Dead letters | bounded filtered `EXHAUSTED` projection | second queue, payload/secret leak, unbounded scan |
| Actor | merchant owner for merchant routes; live manager for operator routes | viewer, analyst, disabled, substituted, foreign actor |
| I/O | validate/prepare transaction, HTTP outside, fenced finalize | network while holding endpoint/delivery lock |
