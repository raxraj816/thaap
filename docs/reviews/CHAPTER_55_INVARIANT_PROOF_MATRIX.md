# Chapter 55 Invariant Proof Matrix

- Chapter: Chapter 55 — Investigation Saved Views And Bounded Bulk Review Assignment
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was drafted and frozen on 2026-08-02 before Chapter 55 production
code, Spring Batch configuration, V88–V90, UI, fixtures, or demos. Chapter 55
remains dependency-blocked until Chapters 53–54 are merge-ready and their
search/detail/relationship contracts are available. All rows are proof
obligations and remain `PENDING`.

Chapter 55 owns distinct payment-investigation saved views plus bounded bulk
assignment/release. It preserves the existing review saved-search API and
single-case review service. It cannot bulk-resolve, label, cancel, refund,
submit disputes, activate controls/models, or otherwise change fraud or money
truth.

## C55-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: A bulk request may only distribute review work through `ASSIGN_TO_SELF`, manager-only `ASSIGN_TO_OPERATOR`, or authorized `RELEASE`; it cannot resolve/cancel a review, create/supersede a fraud label, change a decision, cancel/refund a payment, submit a dispute, mutate ledger money, or activate a control/model.
- Failure sequence: A generic action string reaches another service, assignment failure falls back to resolution, manager authority is treated as money authority, or replay invokes a completed item through a different action.
- Implementation: closed `ReviewBulkActionType`, operation-specific DTO/service dispatch to the existing assignment/release domain method only, no generic command registry, and dependency/source scans rejecting high-risk service references.
- Proof: Planned `ReviewBulkActionScopeTests`, route/body negative tests, source call-graph scans, and before/after payment/decision/review-resolution/label/dispute/ledger/control fingerprints.
- Adversarial evidence: Every allowed action plus resolve/label/cancel/refund/dispute/control/model/arbitrary strings; viewer/analyst/manager; exact/conflicting replay; stale item; and injected service error.
- Status: PENDING

## C55-SAV-001

- Lens: API_PRODUCT
- Invariant: Payment-investigation saved views are a distinct typed contract with canonical filter bytes, schema version, definition digest, owner, `PRIVATE|MERCHANT_SHARED` visibility, lifecycle/version, and explicit `saved_view_upgrade_required`; existing review saved searches remain byte/API/schema compatible and are never silently converted.
- Failure sequence: The new grammar reuses a review-search row with different meaning, JSON key/Unicode/order changes its digest, unsupported schema broadens filters, private view becomes shared, or a projection generation change disables a valid definition instead of only invalidating its cursor.
- Implementation: V88 `payment_investigation_saved_views`, closed canonical filter codec matching Chapter 53 search semantics, separate repository/service/routes, live owner/role checks, immutable digest/schema version, and no migration of existing review saved searches.
- Proof: Planned `PaymentInvestigationSavedViewContractTests`, canonicalization property/golden tests, existing `ReviewSavedSearch` regressions, schema upgrade/visibility tests, and retained row reconciliation.
- Adversarial evidence: Key/list order, Unicode normalization/control/length, absent/null/empty/default, every filter/sort/column, unsupported old/future schema, digest mutation, private/shared substitution, owner disable/role change, projection generation swap, and old API calls.
- Status: PENDING

## C55-BUL-001

- Lens: API_PRODUCT
- Invariant: One tenant/principal-bound bulk request contains 1–25 distinct review cases, one expected assignment version per item, one closed action/target, stable idempotency identity, immutable request digest, and durable request/item status; 26 items, duplicates, missing versions, changed replay content, or in-place terminal retry fail explicitly before work broadens.
- Failure sequence: Duplicate IDs execute twice, item 26 is silently dropped, changed body reuses the original action, a terminal item returns to pending, or request counters claim success before item truth exists.
- Implementation: bounded validated request records, canonical digest over ordered normalized item identities/action/target, existing principal idempotency boundary, V89 request/items, unique request-review and ordinal constraints, and state derived only from terminal items.
- Proof: Planned `ReviewBulkActionApiContractTests`, table/property 0/1/25/26 and duplicate inputs, exact/conflicting replay, status/count state machine tests, and database shape assertions.
- Adversarial evidence: Empty/one/25/26; duplicate same/different expected version; random/order permutation; null/foreign/zero/negative/max version; exact/conflicting key/body/principal; terminal resubmit; malformed IDs; and partial request parse.
- Status: PENDING

## C55-ATO-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Each claimed item completes in one fenced PostgreSQL domain transaction that locks request/item, rechecks and locks live actor/target in inherited order, locks the tenant-qualified review, compares expected version/eligibility, and atomically commits assignment/release, assignment fact, canonical event, audit, webhook-delivery intent, and terminal item outcome; any unexpected failure rolls all of those effects back.
- Failure sequence: Assignment commits without terminal item evidence, item says success while event/audit/webhook intent rolls back, failure status commits beside a source mutation, or retry duplicates assignment/event/audit after an ambiguous crash.
- Implementation: one transaction-participating bulk item executor delegates to a refactored existing `ReviewOperationsService` domain method with no independent propagation; known validation maps to non-success without source writes, and unexpected exceptions leave the claimed item lease-recoverable.
- Proof: Planned `ReviewBulkActionAtomicityPostgreSqlTests` inject failure before/after every lock/write/event/audit/webhook/item boundary and assert exact source/event/audit/delivery/item/idempotency postconditions across restart.
- Adversarial evidence: Checked/runtime/database exception; crash before source write, after assignment, after event, after audit, after webhook intent, before item terminal; exact retry; no-op self-assignment; release; and transaction timeout.
- Status: PENDING

## C55-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Bulk execution preserves global lock order `request -> item -> initiating actor -> target actor -> review -> inherited payment/stream locks`; forced assignment/release/resolution/disable/takeover orderings converge without deadlock, stale authorization, duplicate effect, or stale-worker finalization.
- Failure sequence: Bulk locks review before actor while single-case locks actor first, actor/target disables after request admission, resolution wins after stale eligibility read, lease expires during mutation, or an old worker finalizes after takeover.
- Implementation: short claim transaction with lease token/epoch/expiry, fenced domain transaction, inherited actor-first assignment service, expected assignment version, PostgreSQL row locks, and terminal compare-and-set guarded by current epoch/token.
- Proof: Planned `ReviewBulkActionConcurrencyPostgreSqlTests` use barriers plus `NOWAIT` probes for both directions of assignment/assignment, assignment/release, assignment/resolution, actor disable, target disable, lease takeover, and unrelated-case progress.
- Adversarial evidence: Same/different request/item/review/tenant; actor=target/different target; both orderings for each conflict; exact lease expiry boundary; stale epoch/token; crash/restart; no-op assignment; and rollback winner.
- Status: PENDING

## C55-REC-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Spring Batch owns bounded restart/chunk mechanics while durable request/item/lease state owns product truth; completed items remain immutable, recoverable claims resume after fencing, partial success is explicit, request terminal status/counts derive from item terminals, and restart cannot reapply completed source effects.
- Failure sequence: In-memory batch state loses item truth, restart resets success to pending, skipped/failed item vanishes, counters drift from rows, a stale worker renews or finalizes after takeover, or one poison item blocks all later eligible items forever.
- Implementation: mature Spring Batch job/step/repository configuration, V89–V90 durable product rows distinct from framework metadata, small bounded chunks, leased/fenced item reader/processor/writer, immutable terminal constraints, reconciliation job/readiness, and operator recovery that never edits history.
- Proof: Planned `ReviewBulkActionRestartTests`, real PostgreSQL crash/takeover/restart suites, Spring Batch repository integration tests, counter reconciliation, poison-item continuation, and repeated startup.
- Adversarial evidence: Crash before/after claim/domain commit/chunk checkpoint/request finalize; retry/restart one/many times; lease expiry; stale finalizer; item validation/domain/unexpected failure; partial success; worker disabled; and framework metadata loss/corruption.
- Status: PENDING

## C55-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Saved views, bulk requests/items, actors/targets/reviews, idempotency, leases, status reads, cursors, events, audits, and webhook intents are merchant-qualified from the live risk-operator context; copied foreign identifiers reveal no existence and cannot create a cross-tenant child or effect.
- Failure sequence: A request row is tenant-qualified but an item/review lookup uses bare UUID, manager targets an operator from another merchant, a private view executes for another tenant, or status pagination leaks foreign item counts.
- Implementation: composite tenant keys/FKs/indexes, live context-derived merchant, tenant-qualified actor/review/view repositories, not-found-equivalent errors, principal-bound idempotency, and tenant/request/query-bound cursors/cache keys.
- Proof: Planned `InvestigationWorkflowTenantIsolationTests`, V88–V90 direct PostgreSQL substitution tests, equal-shaped two-merchant fixtures, status/count timing equivalence, and browser actor/tenant switch tests.
- Adversarial evidence: Foreign view/owner/request/item/review/actor/target/idempotency/cursor; same IDs/digests/keys across merchants; absent versus foreign; actor disabled/changed role; private/shared; and direct invalid insert.
- Status: PENDING

## C55-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Saved definitions, bulk rows, APIs, errors, events, audits, webhook intents, batch metadata, logs, metrics, UI state, fixtures, and reports store/expose only safe typed filters and identifiers; they exclude raw search identities, SQL/URLs, note contents, credentials, idempotency keys, request bodies, correlation values, PAN/CVV/PII, webhook payloads/targets, and exception/database text.
- Failure sequence: Canonical saved JSON accepts arbitrary metadata, Spring Batch serializes the request/credential into execution context, a failure code stores exception text, idempotency key appears in status, or browser selection persists sensitive state.
- Implementation: closed filter/action/item DTOs, HMACed idempotency identity and digest, minimal bounded batch execution context using request ID only, safe failure-code enum, sanitizers/canaries, and in-memory UI selection/progress state.
- Proof: Planned `InvestigationWorkflowPrivacyTests`, database/API/log/metric/DOM/batch-metadata canary scans, filter schema inventory, error failure injection, and storage/history inspection.
- Adversarial evidence: PAN/CVV/email/phone/IP/device/session/HMAC/key/idempotency/note/URL/SQL canaries; Unicode/control/oversized view name/filter; dependency exception; malformed item; browser refresh/disconnect; and batch report.
- Status: PENDING

## C55-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: Saved views preserve exact filter schema/digest but store no cursor or projection result; execution binds a fresh active projection generation and returns its freshness/coverage. Bulk items preserve requested/observed/final assignment versions and actor/action chronology but never create or reinterpret fraud labels, outcomes, features, model data, or analytics facts.
- Failure sequence: A saved cursor points into a retired generation, stored results masquerade as current, request order overwrites business chronology, an assignment becomes a fraud outcome, or a failed item loses the observed source version needed to explain staleness.
- Implementation: definition-only V88 rows, execution-time projection context, V89/V90 immutable request/item lineage, existing canonical assignment events/audit, and explicit safe terminal failure/version fields.
- Proof: Planned `InvestigationWorkflowLineageTests`, generation swap/stale cursor fixtures, saved definition replay, bulk version transition tables, event/audit/source reconciliation, and zero label/model/dataset mutation assertions.
- Adversarial evidence: Active generation swap; stale/partial projection; old/future filter schema; request item order versus commit time; assign/reassign/release/no-op/stale/resolved; later label; live/synthetic; and crash/retry.
- Status: PENDING

## C55-UI-001

- Lens: API_PRODUCT
- Invariant: The existing routed UI creates/updates/disables/executes private or shared investigation views and selects at most 25 eligible review cases for assignment/release with explicit confirmation, progress, per-item terminal outcome, partial-success truth, safe retry guidance, keyboard/focus/accessibility, and no claim that the entire selection succeeded when any item did not.
- Failure sequence: Hidden selection includes item 26, role change leaves manager action enabled, optimistic UI marks every item success, refresh loses durable progress, failed items are automatically replayed with stale versions, or focus/status is inaccessible.
- Implementation: server-derived eligibility/actions, bounded in-memory selection, explicit action/target/version confirmation, polling/query of durable request/items, per-status rendering, new-request retry using current versions, cache invalidation from source results, and accessible announcements/focus restoration.
- Proof: Planned `savedViewsAndBulkReview.test.tsx`, browser role/selection/progress/reload/accessibility tests, mixed-result fixtures, ambiguous retry behavior, storage inspection, and production build.
- Adversarial evidence: 0/1/25/26 selection; duplicate/stale/resolved/foreign case; viewer/analyst/manager and actor disable; target change; exact/conflicting submit; pending/partial/completed/failed; reload/back/disconnect; keyboard-only; and 401/403/404/409/429/503.
- Status: PENDING

## C55-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose saved-view operation/upgrade outcome and bulk request backlog/oldest age, claim, item terminal category, lease recovery, reconciliation, and request terminal outcome only after durable truth, without tenant, actor, target, view, request, review, idempotency, cursor, filter, payment, event, credential, message, or exception text as dimensions.
- Failure sequence: Pre-read intention increments success, rollback counts completion, one item/request creates a time series, raw filter/error is logged, stuck lease is invisible, or counters disagree silently.
- Implementation: closed operation/status/failure enums, transaction-after-completion metrics, bounded gauges, safe request-ID diagnostics, reconciliation/readiness, and runbook queries returning aggregates only.
- Proof: Planned `InvestigationWorkflowObservabilityTests`, rollback/cardinality/canary tests, stuck lease/backlog/readiness fixtures, and exact metric-to-durable-row reconciliation.
- Adversarial evidence: Create/update/disable/execute/upgrade; item success/stale/not-eligible/not-found/denied/failed; rollback; crash/takeover; high-volume IDs/filters; sensitive exception; counter corruption; and worker unavailable.
- Status: PENDING

## C55-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V88–V90 migrate fresh V1 and realistic retained V87 PostgreSQL without changing existing review saved searches or review assignments, reject malformed/cross-tenant/digest/status/count/lease history before unsafe completion, install immutable terminal/history guards and bounded indexes, and converge on repeated startup/rolling ownership.
- Failure sequence: Existing review search is rewritten, request/item constraints accept impossible counts or foreign reviews, terminal evidence mutates, lease epoch regresses, partial migration leaves claimable corrupt work, or old code owns new rows.
- Implementation: capability-named forward migrations, separate saved-view table, tenant-qualified FKs/checks/unique indexes, V90 immutable and fencing triggers, preflight validation, readiness gates, and explicit worker enablement after schema validation.
- Proof: Planned `InvestigationWorkflowMigrationPostgreSqlTests` for fresh V1→V90 and retained V87→V90, existing row digests, malformed state partitions, mutation rejection, index plans, repeated startup, and old/new application order.
- Adversarial evidence: Empty/populated legacy searches/reviews; invalid visibility/schema/digest/status/count/ordinal/version/lease; duplicate/cross-tenant item; terminal update/delete; interrupted/repeated migration; worker off/on; and retained in-flight assignment.
- Status: PENDING

## C55-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 55 owns typed payment-investigation views and at-most-25 assignment/release orchestration only; it does not implement bulk resolution/labels/money/disputes/controls, queue/outcome insight calculations, a custom distributed queue, or direct source-table writes.
- Failure sequence: Spring Batch becomes a second source of assignment truth, a generic action escape hatch invokes high-risk commands, dashboard totals are presented as Chapter 56 insight proof, or a demo seeds terminal item/source state through SQL.
- Implementation: mature Spring Batch with domain truth in V89/V90, closed service dependencies/routes/actions, explicit Chapter 56 ownership links, and ordinary public UI/API demo setup.
- Proof: Planned dependency/route/schema/source scans, source non-interference for prohibited domains, demo safety tests, and absence of insight/bulk-high-risk endpoints.
- Adversarial evidence: Custom queue/worker protocol, direct SQL, generic reflection/action name, resolution/label/refund/dispute/control request, aggregate insight card, and framework metadata used as product status.
- Status: PENDING

## Frozen Bulk Item State

| Item outcome | Source mutation permitted | Retry meaning |
|---|---|---|
| `SUCCEEDED` | exactly one assignment/release effect or proven no-op | terminal; never replay in place |
| `STALE` | none | new request with current assignment version |
| `NOT_ELIGIBLE` | none | source state must change before new request |
| `NOT_FOUND` | none | non-disclosing terminal result |
| `DENIED` | none | authority must change before new request |
| `FAILED` | none in the terminal failure transaction | diagnose safe code; new request only when recoverable |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 55 obligations |
|---|---|
| Saved-view grammar/compatibility | C55-SAV-001 |
| Bulk grammar/idempotency | C55-BUL-001 |
| High-risk action exclusion | C55-PAY-001 and C55-SCP-001 |
| Atomic item effect | C55-ATO-001 |
| Locking/leases/restart | C55-CON-001 and C55-REC-001 |
| Tenant/security | C55-TEN-001 and C55-SEC-001 |
| Lineage/UI | C55-DAT-001 and C55-UI-001 |
| Observability/migration | C55-OBS-001 and C55-MIG-001 |
