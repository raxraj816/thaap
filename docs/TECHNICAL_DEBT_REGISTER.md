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
- Resolution evidence: `GeneratorRunStore` persists run/step state in an owner-only SQLite journal, enforces one active lease, retains stable run-scoped idempotency identities, replays completed checkpoints, retries only idempotent ambiguous operations, and requires a durable cleanup disposition for ambiguous merchant/customer creation. A separate sanitized resource ledger survives secret-bearing checkpoint erasure so operators retain tenant/resource cleanup references. The credentialed client rejects redirects and explicitly ignores environment proxies. Generator tests cover crash, restart, active-owner exclusion, immutable identity, redaction, retained cleanup lineage, completion, abandonment, and real-server redirect/proxy escape attempts. The `chapter21-live-resume` PostgreSQL/public-API failpoint run crashed after PaymentIntent commit and resumed to exactly five intended intents, attempts, and decisions with no duplicates. `ER-0024` and `ER-0028` retain the escaped-review process corrections.
- Last reviewed: 2026-07-05

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
- Resolution evidence: V11 and `WebhookEndpoint` retain encrypted current/previous secret generations with explicit expiry; claims bind the generation. `WebhookEndpointSecretService` authorizes a bounded `WebhookSecretUseWindow` under the endpoint lock, and the executor validates the exact signature timestamp before cryptographic use. Startup KEK inventory includes active current ciphertext, active unexpired previous ciphertext, and unexpired generation-matching endpoint-creation replay ciphertext. API responses expose only prefixes/expiry plus the reveal-once current secret, active overlap blocks another rotation, and audit state stays secret-safe. Before/at/after tests, `previousGenerationIsRejectedWhenDispatchWaitCrossesItsExpiry`, and `requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext` prove the complete lifecycle. `ER-0025` and `ER-0026` retain the escaped-review process corrections.
- Last reviewed: 2026-07-05

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
- Resolution evidence: V11 persists tenant-scoped HMACed idempotency identity and encrypted replay response material; V14 adds a fenced owner token and expiry to every `IN_PROGRESS` reservation and makes historical stranded rows immediately reclaimable. `WebhookEndpointService` reserves creation independently, refuses to steal a live lease, reclaims an expired lease under a write lock, validates ownership at completion/cleanup, returns the same endpoint/secret for replay, and conflicts on changed requests or superseded rotation. V12 enforces tenant-scoped exact URL identity. Endpoint operation tests prove replay, conflict, URL, audit, cross-tenant behavior, and active-versus-expired lease handling; deterministic PostgreSQL lock ordering proves competing reclaim/replay converges to one endpoint; the V14 migration test proves stranded historical reservations recover. `ER-0027` retains the escaped-review process correction.
- Last reviewed: 2026-07-05
