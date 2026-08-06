# THAAP Technical And Implementation Debt Register

This is the canonical source of truth for accepted technical, implementation,
security, data/ML, and operational debt.

A debt is not safely deferred because it appears in chat, a daily note, or a
roadmap paragraph. It is safely deferred only when it has a stable ID here,
appears in its owning phase or chapter in the implementation master plan, and
passes `scripts/technical-debt-check.py`.

## Required Workflow

1. Fix dependency-ready work immediately when its invariants can be protected.
2. If work must be deferred, allocate the next immutable `TD-NNNN` ID.
3. Add every required field below in the same change that creates or accepts the
   debt.
4. Reference the ID at both the originating chapter and destination owner slice
   in `docs/IMPLEMENTATION_MASTER_PLAN.md`.
5. Record the debt ID in the current daily note and weekly review when relevant.
6. Run `scripts/technical-debt-check.py` and the commit-readiness gate.
7. Never delete or reuse an ID. Resolve it in place with evidence.
8. Review every non-resolved entry at least once every 35 days; stale debt
   records fail commit readiness until `Last reviewed` and the roadmap reality
   are reconciled.

Allowed statuses are `OPEN`, `IN_PROGRESS`, and `RESOLVED`. “Accepted risk” is
a review disposition, not a debt lifecycle state: once accepted work is
deferred, its registered status is `OPEN` until implementation starts.
`P0` work cannot be deferred. Debt priority therefore uses `P1`, `P2`, or `P3`.

## TD-0001: Trusted Synthetic Traffic Origin

- Status: RESOLVED
- Debt type: DATA_ML_DEBT
- Priority: P2
- Introduced: 2026-06-27
- Origin: Chapter 16 — Synthetic Scenario Generator V1
- Resolution target: Phase 2.5 / Chapter 21B — Trusted Synthetic Traffic Origin
- Primary DRI: Payments Core
- Supporting owners: Risk Operations
- Risk / invariant: Synthetic traffic must never be indistinguishable from live merchant traffic in payment operations, risk metrics, analyst views, events, or ML datasets.
- Deferral reason: The current payment schema has no trusted server-assigned origin boundary, and merchant-controlled metadata cannot become an authorization, analytics, or training-data trust signal.
- Trigger / deadline: Resolve before Phase 4.5 enables any shared non-local scenario runner; trusted origin must also exist before Phase 7 builds training or evaluation datasets.
- Done when: An authenticated server boundary assigns tenant-scoped origin, merchants cannot self-declare trusted synthetic origin, lineage propagates through payment, fraud, event, analytics, and dataset records, and inclusion/exclusion behavior has tenant and tamper-resistance tests.
- Resolution evidence: V10 adds immutable `traffic_origin` columns and indexes; `MerchantContextArgumentResolver` assigns `SYNTHETIC_SCENARIO` only after constant-time validation of the dedicated runner credential; payment, attempt, fraud decision, feature snapshot, event envelope, API, and generator evidence propagate the value. `trustedScenarioCredentialAssignsOriginAndMerchantMetadataCannotForgeIt`, `trustedSyntheticOriginPropagatesThroughDecisionTimeAndEventLineage`, the full backend suite, and the Chapter 21 live public-API run prove spoof resistance and complete lineage.
- Last reviewed: 2026-07-04

## TD-0002: Resumable Synthetic Generator Runs

- Status: RESOLVED
- Debt type: OPERATIONS_DEBT
- Priority: P2
- Introduced: 2026-06-27
- Origin: Chapter 16 — Synthetic Scenario Generator V1
- Resolution target: Phase 2.5 / Chapter 21C — Resumable Synthetic Generator Runs
- Primary DRI: ML/Data Platform
- Supporting owners: Payments Core
- Risk / invariant: Interrupted or retried dataset-scale generation must not duplicate merchants, payments, attempts, fraud decisions, labels, or other durable product truth.
- Deferral reason: The current bounded local generator has collision-resistant run identity but no durable GeneratorRun state, step checkpoint model, cleanup API, or safe resume protocol.
- Trigger / deadline: Resolve before Phase 7 introduces scheduled generation, dataset-scale volume, automatic retry, concurrency, or long-running shared-environment generator jobs.
- Done when: GeneratorRun and step state are durable, retries use stable idempotency identities, interrupted runs resume or terminate with explicit cleanup disposition, concurrency is bounded, and failure/retry tests prove no duplicate payment, fraud, or label truth.
- Resolution evidence: The owner-only SQLite journal, stable run/step identities, exclusive leases, and terminal cleanup protocol remain in force. `ER-0029` made persisted retry classification authoritative and immutable across restart. `ER-0030` added V15 fenced creation leases, non-plaintext deterministic merchant API-key replay, tenant-scoped customer idempotency, and stable generator keys. `ER-0031` moves the general idempotency row lock before supplier/processor work and proves expiry cannot admit a second supplier. `ER-0032` adds V16 unique current/previous HMAC aliases, original-version replay derivation, migration backfill, and fail-closed required-key inventory. `ER-0033` heartbeats ownership around every bounded HTTP request and proves cumulative delayed responses cannot admit another process. `ER-0034` extends original-version request verification and live-key inventory to generic replay while separating stable fraud/PII correlation from rotatable replay keys. `ER-0035` makes generator resource type immutable durable step metadata, backfills legacy completed/interrupted journals, and proves the verifier merchant remains in the secret-safe cleanup ledger. `ER-0036` adds a record-UUID lifecycle epoch so expired same-pepper reuse creates a distinct merchant credential while live replay remains deterministic. Exact-window client failure, forced PostgreSQL lease/rotation orderings, same-pepper lifecycle reuse, real-SQLite/HTTP recovery, and V15-to-V17 migration evidence close the boundary; aggregate suites are regression context only.
- Last reviewed: 2026-07-11

## TD-0003: Webhook Signing-Secret Rotation Grace Windows

- Status: RESOLVED
- Debt type: SECURITY_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 2.5 / Chapter 21E — Webhook Signing-Secret Rotation Overlap
- Primary DRI: Payments Core
- Supporting owners: Developer Experience, Security
- Risk / invariant: A worker can load an endpoint's signing secret, then the merchant can rotate the endpoint secret before that already-claimed delivery reaches HTTP; without an explicit old/new verification window, the merchant may reject a legitimate in-flight delivery signed with the just-rotated secret.
- Deferral reason: Chapter 19 V1 has a single active reveal-once signing secret and a local/test delivery wedge; product-grade rotation windows require schema/API support for previous-secret retention, expiry, merchant-facing verification guidance, and replay/deduplication behavior that belongs with the webhook operations console before shared non-local onboarding.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before recommending webhook secret rotation as an operational playbook, or before enabling endpoint verification/replay workflows for external merchants.
- Done when: Endpoint rotation stores current and previous signing-secret material with explicit expiry, delivery signing and merchant verification docs define the accepted overlap window, tests prove old-secret in-flight delivery acceptance until expiry and rejection after expiry, audit/API responses remain secret-safe, and replay/deduplication behavior is unchanged.
- Resolution evidence: V11 and `WebhookEndpoint` retain encrypted current/previous secret generations with explicit expiry; claims bind the generation. `WebhookEndpointSecretService.signForDelivery` acquires the endpoint lock before capturing exact use time, then validates, decrypts, and signs before release, so rotation and elapsed lock wait cannot cross cryptographic use. A stale generation retries without sending; if first observed at the retry ceiling, current state is rebound before the first HTTP opportunity. Startup KEK inventory includes active current ciphertext, active unexpired previous ciphertext, and unexpired generation-matching endpoint-creation replay ciphertext in both execution modes. Forced PostgreSQL tests prove signer-first rotation wait and lock-first expiry rejection; retry contracts prove intermediate no-send recovery and final current-secret HMAC. `ER-0025`, `ER-0026`, `ER-0037` through `ER-0039`, `ER-0041`, and `ER-0042` retain the process corrections.
- Last reviewed: 2026-07-11

## TD-0004: Webhook Delivery Fairness And Rate Controls

- Status: RESOLVED
- Debt type: OPERATIONS_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 2.5 / Chapter 21F — Webhook Delivery Fairness And Rate Controls
- Primary DRI: Payments Core
- Supporting owners: Developer Experience, Risk Operations
- Risk / invariant: In a shared merchant environment, one merchant or endpoint with a large delivery burst must not consume all webhook worker capacity or starve other merchants' delivery attempts.
- Deferral reason: Chapter 19 V1 bounds total local worker behavior with disabled-by-default execution, capped batch size, one-at-a-time claims, leases, and network deadlines; per-merchant/per-endpoint fairness requires product policy, operator controls, metrics, and shared-environment workload targets that belong with product-grade webhook operations.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before enabling high-volume endpoint subscriptions for external merchants, or when measured webhook queue age shows one tenant can starve another.
- Done when: Delivery claiming or scheduling enforces configurable per-merchant and/or per-endpoint fairness limits, burst behavior is observable, tests prove a noisy merchant cannot starve another merchant, and operational docs define default limits and override procedures.
- Resolution evidence: V11 adds a durable merchant/endpoint dispatch cursor; `WebhookDeliveryClaimService` performs database-locked round-robin selection while preserving `FOR UPDATE SKIP LOCKED`, lease recovery, and one-at-a-time dispatch. Configurable per-poll merchant/endpoint budgets default to 5/2 and emit bounded `thaap.webhook.claim.throttled{scope}` metrics. `durableCursorPreventsNoisyMerchantAndEndpointStarvation`, concurrent claim tests, executor budget tests, and the complete PostgreSQL suite prove progress without duplicate claims.
- Last reviewed: 2026-07-04

## TD-0005: Webhook Endpoint Creation Idempotency

- Status: RESOLVED
- Debt type: IMPLEMENTATION_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 2.5 / Chapter 21D — Webhook Endpoint Creation Idempotency
- Primary DRI: Developer Experience
- Supporting owners: Payments Core, Security
- Risk / invariant: A retried endpoint-creation request after a network timeout can create duplicate active endpoints with distinct signing secrets, causing duplicate delivery streams and confusing one-time secret handling.
- Deferral reason: Chapter 19 V1 supports explicit duplicate cleanup through endpoint disable; safe idempotent creation requires an API contract for idempotency keys, URL uniqueness or replay behavior, and one-time secret reveal semantics that must be designed with the developer console and merchant onboarding workflow.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before recommending automated endpoint provisioning, or before exposing webhook endpoint creation through a public developer console.
- Done when: Endpoint creation has a tenant-scoped idempotency/retry contract that prevents accidental duplicate active endpoints, safely handles lost one-time secret responses, preserves audit evidence, and includes tests for same-key replay, changed-request conflict, cross-tenant isolation, and duplicate URL behavior.
- Resolution evidence: V11 persists tenant-scoped HMACed idempotency identity and encrypted replay response material; V14 adds a fenced owner token and expiry to every `IN_PROGRESS` reservation and makes historical stranded rows immediately reclaimable. `WebhookEndpointService` reserves creation independently, locks and validates the live owner before secret generation or any endpoint/subscription/audit mutation, refuses to steal a live lease, reclaims an expired lease under a write lock, returns the same endpoint/secret for replay, and conflicts on changed requests or superseded rotation. V12 enforces tenant-scoped exact URL identity. V17 records the original request-HMAC version, adds tenant-scoped current/previous identity aliases, backfills V16 state, releases expired key requirements, and fails startup if a live replay needs an unavailable version. Endpoint operation tests prove replay, changed-request conflict, URL, audit, cross-tenant, active/expired lease, rolling-pepper, and key-retirement behavior; deterministic PostgreSQL lock ordering expires the first lease while its first mutation is blocked and proves the second caller waits and converges, while mixed-v1/v2 replay still produces one endpoint. V14 and V16-to-V17 migration tests prove historical recovery. `ER-0027`, `ER-0034`, and `ER-0040` retain the escaped-review process corrections.
- Last reviewed: 2026-07-11

## TD-0006: Investigation Navigation Page Envelope Naming Split

- Status: RESOLVED
- Debt type: IMPLEMENTATION_DEBT
- Priority: P2
- Introduced: 2026-08-03
- Origin: Chapter 54 — Canonical Timeline And Safe Related-Payment Pivots
- Resolution target: Phase 6 / Chapter 59 — Public Investigation Contract Freeze
- Primary DRI: Developer Experience
- Supporting owners: Analyst Experience, Payments Core
- Risk / invariant: The three cursor-paged investigation navigation routes do not name their row collection identically. `GET /risk/workspace/payments/{id}/timeline` and `.../webhook-activity` publish `entries` while `.../related` publishes `data`. Every route is otherwise the same cursor-paged contract with a nullable `nextCursor` that signals finality. An integrator who writes one correct pager must special-case the collection key per route, and a per-route branch is exactly where pagination defects hide. This is a published-contract wart, not a correctness defect: no page is unbounded, mis-scoped, or mis-ordered, and `PaymentInvestigationNavigationApiTests` proves the cursor, bound, refusal, and finality behavior is uniform across all three.
- Deferral reason: Renaming a field on an already-published route is a breaking contract change. The public API is versioned by date under `openapi/thaap-public-api-2026-08-01.yaml`, and Chapters 55 through 58 will add further investigation routes whose envelopes must be decided together. Unifying now would either break existing consumers or require a dual-emit compatibility window that must be designed once for the whole navigation surface rather than route by route.
- Trigger / deadline: Resolve during the Chapter 59 public investigation contract freeze, before the versioned public OpenAPI artifact declares any investigation navigation route as generally available, and before the Python SDK publishes a shared navigation pager.
- Done when: All investigation navigation routes publish one documented cursor-page envelope with a single collection field name; the versioned OpenAPI artifact expresses that envelope as one reusable schema component; the Python SDK exposes one pager that works unmodified across every navigation route; a contract test asserts collection-field uniformity across the full route list rather than tolerating either name; and any transitional dual-emit field carries a documented removal version.
- Resolution evidence: Resolved in Chapter 59, at the trigger the entry named. All four cursor-paged investigation surfaces — search, timeline, related, and webhook-activity — now publish their rows as `data` with a nullable `nextCursor` that signals finality. `TimelinePage` and `WebhookActivityPage` were renamed from `entries`; `PublicContractPhaseInventoryTests.everyCursorPagedInvestigationRoutePublishesOneCollectionFieldName` asserts every route publishes `data` and that none still publishes `entries`, so the proof no longer tolerates either name. `PaymentInvestigationNavigationApiTests.collection` lost its `has("entries") ? … : …` branch. `clients/python/thaap_sdk/thaap_sdk/pagination.py` supplies one `iterate_rows` pager proven unmodified across two different page types, with a bounded traversal and repeated-cursor detection so a non-advancing server fails loudly rather than looking like a hang. No transitional dual-emit field was needed: duplicating every timeline row would have doubled the largest responses in the product to preserve a compatibility window for zero deployed consumers. The rename is a breaking change and was treated as one — `scripts/openapi-compatibility-check.py` rejected it, and the reviewed baseline was updated by the deliberate `--update-baseline` command so the change appears in review as a baseline diff. That is sound here specifically because Phase 6 is a local sandbox with no independently deployed clients and the SDK regenerates from the contract in the same commit; Phase 9 owns deployed compatibility, where this option will not exist.
- Last reviewed: 2026-08-06
