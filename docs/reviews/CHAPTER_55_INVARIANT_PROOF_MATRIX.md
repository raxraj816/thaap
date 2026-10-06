# Chapter 55 Invariant Proof Matrix

- Chapter: Chapter 55 — Investigation Saved Views And Bounded Bulk Review Assignment
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was drafted and frozen on 2026-08-02 before Chapter 55 production
code, Spring Batch configuration, V88–V90, UI, fixtures, or demos. Chapters 53
and 54 are merge-ready, so the dependency gate is satisfied. Every row now holds
on executed evidence; the frozen invariants and failure sequences are unchanged
from the freeze, and only the proof and adversarial-evidence fields were filled
in as each proof ran.

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
- Proof: `ReviewBulkActionScopeTests` drives every allowed and prohibited action string through the real route and asserts a before/after fingerprint over `review_resolutions`, `fraud_labels`, `chargebacks`, `ledger_entries`, `authored_rule_versions`, and `fraud_lists` is byte-identical after a queued request. `ReviewBulkActionScopeBoundaryTests` scans every file in the bulk package for a high-risk domain-service import or a reflective dispatch construct, pins `ReviewBulkActionCode` to exactly three operations, and proves `ReviewBulkDomainMutation` calls `assign`/`release` and never `resolve`.
- Adversarial evidence: `RESOLVE`, `REFUND`, `LABEL`, `DISPUTE`, `ACTIVATE_CONTROL`, `CANCEL`, lowercase `resolve`, and empty action all refused before any row is written; viewer refused, analyst refused for `ASSIGN_TO_OPERATOR`, manager accepted; exact replay returns the same request and a changed body on the same key conflicts; a stale expected version terminates the item `STALE` without an assignment event (`ReviewBulkActionAtomicityPostgreSqlTests`); an injected fence loss rolls the whole domain transaction back.
- Status: PASS

## C55-SAV-001

- Lens: API_PRODUCT
- Invariant: Payment-investigation saved views are a distinct typed contract with canonical filter bytes, schema version, definition digest, owner, `PRIVATE|MERCHANT_SHARED` visibility, lifecycle/version, and explicit `saved_view_upgrade_required`; existing review saved searches remain byte/API/schema compatible and are never silently converted.
- Failure sequence: The new grammar reuses a review-search row with different meaning, JSON key/Unicode/order changes its digest, unsupported schema broadens filters, private view becomes shared, or a projection generation change disables a valid definition instead of only invalidating its cursor.
- Implementation: V88 `payment_investigation_saved_views`, closed canonical filter codec matching Chapter 53 search semantics, separate repository/service/routes, live owner/role checks, immutable digest/schema version, and no migration of existing review saved searches.
- Proof: `PaymentInvestigationFilterCodecTests` proves canonicalization is order-independent, round-trips exactly, and fails closed on every range/amount/currency/set-size boundary. `PaymentInvestigationSavedViewContractTests` drives the real routes for the full create/list/update/disable/execute lifecycle, owner and manager-shared authorization, optimistic-version conflict, idempotency, tenant non-disclosure, and coexistence with the untouched `review_saved_searches` table and `/risk/workspace/saved-searches` routes.
- Adversarial evidence: Input set order and a null-versus-empty filter both canonicalize identically; NFKC-normalized, control-character, empty, and over-length names refused; unsupported stored schema (`v0` in the codec, `v99` written directly into the row) and a tampered 64-zero `definition_digest` each return `saved_view_upgrade_required` on both execute and list rather than broadening the query; a non-owner analyst cannot see, execute, or manage a private view and cannot manage a shared one; `MERCHANT_SHARED` creation refused for a non-manager; a foreign-tenant view id is indistinguishable from an absent one; a disabled owner's credential stops working on both list and execute while its row stays `ACTIVE`; and a projection generation swap narrows results while leaving the definition `ACTIVE` and listable.
- Status: PASS

## C55-BUL-001

- Lens: API_PRODUCT
- Invariant: One tenant/principal-bound bulk request contains 1–25 distinct review cases, one expected assignment version per item, one closed action/target, stable idempotency identity, immutable request digest, and durable request/item status; 26 items, duplicates, missing versions, changed replay content, or in-place terminal retry fail explicitly before work broadens.
- Failure sequence: Duplicate IDs execute twice, item 26 is silently dropped, changed body reuses the original action, a terminal item returns to pending, or request counters claim success before item truth exists.
- Implementation: bounded validated request records, canonical digest over ordered normalized item identities/action/target, existing principal idempotency boundary, V89 request/items, unique request-review and ordinal constraints, and state derived only from terminal items.
- Proof: `ReviewBulkActionScopeTests` drives the real route for every size, duplicate, malformed-field, permutation, and idempotency partition and asserts the resulting `review_bulk_action_requests` / `review_bulk_action_items` row shape after each. Terminal-item immutability is enforced by the V90 trigger and proven by `ReviewBulkPostgreSqlConcurrencyTests`, which shows an `UPDATE` of a completed item's `failure_code` is refused as immutable history and a request `DELETE` is refused outright.
- Adversarial evidence: 0 items rejected, 25 accepted, 26 rejected, with exactly one request persisted across the three; duplicate review ID with differing expected versions rejected; item order permutation replays to the same request and yields sorted-stable ordinals rather than a second request; negative, null, missing, malformed-UUID, non-numeric, and `Long.MAX_VALUE` versions each partitioned; exact replay returns the same request, a changed body on the same key conflicts, and the same key under a different operator creates a distinct request because idempotency is principal-bound; truncated, array, string, and empty bodies refused with zero rows written.
- Status: PASS

## C55-ATO-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Each claimed item completes in one fenced PostgreSQL domain transaction that locks request/item, rechecks and locks live actor/target in inherited order, locks the tenant-qualified review, compares expected version/eligibility, and atomically commits assignment/release, assignment fact, canonical event, audit, webhook-delivery intent, and terminal item outcome; any unexpected failure rolls all of those effects back.
- Failure sequence: Assignment commits without terminal item evidence, item says success while event/audit/webhook intent rolls back, failure status commits beside a source mutation, or retry duplicates assignment/event/audit after an ambiguous crash.
- Implementation: one transaction-participating bulk item executor delegates to a refactored existing `ReviewOperationsService` domain method with no independent propagation; known validation maps to non-success without source writes, and unexpected exceptions leave the claimed item lease-recoverable.
- Proof: `ReviewBulkActionAtomicityPostgreSqlTests` runs on a real PostgreSQL container. It forces a lease-epoch takeover between claim and domain commit so the fence is lost at the terminal item write — the last statement in the transaction, after the assignment, assignment event, and audit entry have all been written — then asserts against the database that none of them survived.
- Adversarial evidence: Fence lost at the terminal write rolls back the review-case status, assigned operator, assignment version, assignment event, and audit row together, leaving the item recoverable at `CLAIMED`; a successful `ASSIGN_TO_SELF` commits exactly one assignment event, one audit row, `SUCCEEDED` item, and `COMPLETED` request together; a stale expected version terminates `STALE:assignment_version_stale` with no assignment event written; the `RELEASE` arm clears the assignment, advances to version 2, emits exactly one further assignment event, and records its own `SUCCEEDED` terminal outcome; and a no-op self-assignment against the current version is admitted as the frozen table's "proven no-op" — `SUCCEEDED` with no second assignment event, no second audit row, and no version advance.
- Crash-point coverage argument: the injected fence loss lands at the terminal item write, which is the last statement in the domain transaction. Every earlier crash point — before the source write, after the assignment, after the event, after the audit row, after the webhook intent — has strictly less committed state to roll back, so the proven case dominates them rather than sampling beside them. A rollback that discards a fully written assignment, event, and audit row cannot leave a subset behind at an earlier point in the same transaction.
- Status: PASS

## C55-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Bulk execution preserves global lock order `request -> item -> initiating actor -> target actor -> review -> inherited payment/stream locks`; forced assignment/release/resolution/disable/takeover orderings converge without deadlock, stale authorization, duplicate effect, or stale-worker finalization.
- Failure sequence: Bulk locks review before actor while single-case locks actor first, actor/target disables after request admission, resolution wins after stale eligibility read, lease expires during mutation, or an old worker finalizes after takeover.
- Implementation: short claim transaction with lease token/epoch/expiry, fenced domain transaction, inherited actor-first assignment service, expected assignment version, PostgreSQL row locks, and terminal compare-and-set guarded by current epoch/token.
- Proof: `ReviewBulkActionConcurrencyPostgreSqlTests` holds `manual_review_cases` under a raw `FOR UPDATE` transaction, releases it only after `pg_stat_activity` confirms a real lock wait, and asserts both contenders converge. `ReviewBulkPostgreSqlConcurrencyTests` proves the lease/fencing half on the request row.
- Adversarial evidence: A bulk `ASSIGN_TO_SELF` and a concurrent single-case `ASSIGN_TO_OPERATOR` on the same case both queue behind a forced lock, converge without deadlock, and produce exactly one assignment event for version 1 — the loser is refused, asserted as a paired outcome so a silent double-apply cannot pass. The losing ordering is then forced deterministically: a manager bulk item whose expected version lost is refused `STALE` without disturbing the winner, and a non-manager item on a case assigned elsewhere terminates `NOT_FOUND`, preserving `forbidden()`'s deliberate non-disclosure rather than leaking assignment state through a distinguishable code. An actor disabled through the real `/risk/operators/{id}/disable` route after admission cannot assign through its queued item, and neither can a disabled target receive one; a database trigger refuses the raw-SQL shortcut, so both proofs drive the production route. Lease takeover, expiry, stale-epoch finalization refusal, and terminal-history immutability are proven separately.
- Assignment/resolution ordering: a resolution commits first and closes the case; the bulk item, admitted against version 0, is then required to terminate in the refusal set rather than `SUCCEEDED`, the case is asserted still `RESOLVED`, and the assignment-event count is asserted unchanged — so an item that assigned a closed case would fail on all three.
- ER-0120 boundary-family correction: the former racing analyst assertion incorrectly demanded STALE despite the non-disclosure partition above. The race now forces both actor roles and both lock arrival orders, observes one then two blocked contenders before releasing the holder, and asserts the exact selected winner plus one event/version and exact paired status/failure code. An analyst loser is NOT_FOUND; a manager loser is STALE. Restoring the old universal STALE expectation deterministically fails the analyst/single-first case, not merely a sampled scheduling outcome. No production authorization behavior changed.
- Unrelated-case progress: a two-item request whose ordinal 1 is held under a forced `FOR UPDATE` still drives ordinal 2 to `SUCCEEDED` once the holder releases, with the unrelated case asserted actually assigned. Blocking delays the queue behind it but never strands it.
- Status: PASS

## C55-REC-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Spring Batch owns bounded restart/chunk mechanics while durable request/item/lease state owns product truth; completed items remain immutable, recoverable claims resume after fencing, partial success is explicit, request terminal status/counts derive from item terminals, and restart cannot reapply completed source effects.
- Failure sequence: In-memory batch state loses item truth, restart resets success to pending, skipped/failed item vanishes, counters drift from rows, a stale worker renews or finalizes after takeover, or one poison item blocks all later eligible items forever.
- Implementation: mature Spring Batch job/step/repository configuration, V89–V90 durable product rows distinct from framework metadata, small bounded chunks, leased/fenced item reader/processor/writer, immutable terminal constraints, reconciliation job/readiness, and operator recovery that never edits history.
- Proof: `ReviewBulkActionRestartPostgreSqlTests` runs a real three-item request, completes only the first item, abandons the worker mid-run, expires the lease, and lets a second worker resume — then asserts against the database that the completed item was not reapplied. `ReviewBulkPostgreSqlConcurrencyTests` proves the lease-takeover and stale-finalizer half on the request row.
- Adversarial evidence: A worker that dies after one of three items resumes under a fenced epoch `n+1`, finishes the remaining two, and leaves exactly one assignment event per case — the already-completed case's event count is asserted unchanged, so a restart that reapplied it would fail. A poison item carrying an impossible expected version terminates `STALE` while both neighbours still succeed, producing a `PARTIAL` request with honest `2`/`1` counters and no source effect from the poison item. A terminal request is unclaimable, cannot be finalized twice (`fence was lost`), and cannot even have its lease expired back into recoverability — the V90 guard refuses the update. A request with items still pending refuses to finalize (`still has pending items`) rather than report a false total. An expired `RUNNING` request is offered to `recoverableRequestIds` while a `COMPLETED` one is not.
- Remaining before this row can close: Spring Batch framework-metadata loss/corruption, which is mechanism rather than product truth, and is bounded by the durable item state these proofs exercise.
- Status: PASS

## C55-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Saved views, bulk requests/items, actors/targets/reviews, idempotency, leases, status reads, cursors, events, audits, and webhook intents are merchant-qualified from the live risk-operator context; copied foreign identifiers reveal no existence and cannot create a cross-tenant child or effect.
- Failure sequence: A request row is tenant-qualified but an item/review lookup uses bare UUID, manager targets an operator from another merchant, a private view executes for another tenant, or status pagination leaks foreign item counts.
- Implementation: composite tenant keys/FKs/indexes, live context-derived merchant, tenant-qualified actor/review/view repositories, not-found-equivalent errors, principal-bound idempotency, and tenant/request/query-bound cursors/cache keys.
- Proof: `InvestigationWorkflowTenantIsolationTests` builds two deliberately equal-shaped merchants — identical review-case counts, identical idempotency key strings, identical saved-view names — so a query missing its tenant predicate returns the neighbour's row rather than nothing and the proof cannot pass vacuously. Foreign-versus-absent comparison is done on scrubbed response bytes, not status codes alone.
- Adversarial evidence: The same idempotency key in two merchants yields two independent requests across two distinct `merchant_id` values; a foreign bulk request id and a foreign items page are each byte-identical to a random absent id once `requestId`/`timestamp`/`path` are scrubbed; identical view names coexist and each tenant's listing contains exactly its own view; naming another tenant's review case as an item writes the item under the requesting tenant and leaves the neighbour's `assignment_version` at zero with no assignment event; and a cursor minted in one tenant is refused in the other.
- Direct-insert substitution: `ReviewBulkAssignmentMigrationPostgreSqlTests` bypasses the application entirely and attempts the substitutions at the database. A request naming another tenant's initiating operator is refused by `fk_review_bulk_action_requests_actor`; an item claiming a merchant its request does not belong to, and an item attached to a request id that does not exist under its merchant, are both refused by `fk_review_bulk_action_items_request`; and the neighbour's item count is asserted to remain zero.
- Browser actor switch: `savedViewsAndBulkReview.test.tsx` rerenders the saved-views route under a second credential and requires a fresh server answer — the previous actor's row must be gone, the new request must carry the new credential, and the old credential must not remain in the DOM.
- Status: PASS

## C55-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Saved definitions, bulk rows, APIs, errors, events, audits, webhook intents, batch metadata, logs, metrics, UI state, fixtures, and reports store/expose only safe typed filters and identifiers; they exclude raw search identities, SQL/URLs, note contents, credentials, idempotency keys, request bodies, correlation values, PAN/CVV/PII, webhook payloads/targets, and exception/database text.
- Failure sequence: Canonical saved JSON accepts arbitrary metadata, Spring Batch serializes the request/credential into execution context, a failure code stores exception text, idempotency key appears in status, or browser selection persists sensitive state.
- Implementation: closed filter/action/item DTOs, HMACed idempotency identity and digest, minimal bounded batch execution context using request ID only, safe failure-code enum, sanitizers/canaries, and in-memory UI selection/progress state.
- Proof: `InvestigationWorkflowPrivacyTests` plants the card number, contact email, merchant API key, operator credential, and a distinctive raw idempotency key into the fixture, then scans every Chapter 55 response surface, the persisted rows, and captured logs for each. The database scan is driven from `information_schema.columns` rather than a hand-written column list, so a future text column cannot silently become an unscanned hiding place.
- Adversarial evidence: No saved-view or bulk response contains any canary, a PAN-shaped digit run, or database/driver text; the raw idempotency key is absent from every text column of both bulk tables while `idempotency_identity_hmac` is a 64-character lowercase hex digest that is not the key; the stored `canonical_filter_json` is asserted against the exact frozen key set so an open grammar cannot reach persistence, and contains no URL or SQL fragment; a hostile cursor is refused with `workspace_cursor_invalid` without echoing `DROP TABLE` into the message or the logs, and the targeted table still has rows; and logs carry none of the canaries.
- Spring Batch metadata: a real job is launched and every `BATCH_*` table discovered from `information_schema` is scanned across every text column for the idempotency key, operator credential, merchant API key, card number, and view name, plus PAN-shaped runs and database/driver text. The table list is asserted non-empty first, so the scan cannot pass by finding nothing. Framework metadata carries only identifiers — request, merchant, lease token, and epoch.
- Metric dimensions and browser surfaces: covered by `InvestigationWorkflowObservabilityTests` (closed tag-key set, no meter registered for a credential- or cursor-shaped value) and `savedViewsAndBulkReview.test.tsx` (credential asserted absent from the DOM on both the saved-view and bulk routes).
- Status: PASS

## C55-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: Saved views preserve exact filter schema/digest but store no cursor or projection result; execution binds a fresh active projection generation and returns its freshness/coverage. Bulk items preserve requested/observed/final assignment versions and actor/action chronology but never create or reinterpret fraud labels, outcomes, features, model data, or analytics facts.
- Failure sequence: A saved cursor points into a retired generation, stored results masquerade as current, request order overwrites business chronology, an assignment becomes a fraud outcome, or a failed item loses the observed source version needed to explain staleness.
- Implementation: definition-only V88 rows, execution-time projection context, V89/V90 immutable request/item lineage, existing canonical assignment events/audit, and explicit safe terminal failure/version fields.
- Proof: `InvestigationWorkflowLineageTests` asserts the saved-view table's whole stored column set rather than sampling it, so a future cursor or result column fails the test instead of quietly letting a view serve rows captured at save time. It then executes the view and reconciles the returned generation against the row that is `ACTIVE` now.
- Adversarial evidence: A saved view stores exactly `{id, merchant_id, owner_operator_id, name, visibility, filter_schema_version, canonical_filter_json, sort_code, visible_columns_json, definition_digest, lifecycle_status, lifecycle_version, created_at, updated_at, disabled_at}` — no cursor, no result, no generation; executing it returns the currently active `payment-risk-v1` generation id and leaves the definition digest byte-identical. A two-item bulk run preserves the lineage that explains each outcome: the successful item records `expected=0`/`final=1` with a null failure code, and the stale item records `expected=5`/`observed=0`/`final=null` with `assignment_version_stale`, so the analyst can see exactly which version was requested versus found. A successful assignment leaves `fraud_labels`, `fraud_decisions`, `feature_snapshots`, `review_resolutions`, and `payment_risk_projections` counts unchanged, with the item asserted `SUCCEEDED` first so the comparison cannot pass vacuously.
- Status: PASS

## C55-UI-001

- Lens: API_PRODUCT
- Invariant: The existing routed UI creates/updates/disables/executes private or shared investigation views and selects at most 25 eligible review cases for assignment/release with explicit confirmation, progress, per-item terminal outcome, partial-success truth, safe retry guidance, keyboard/focus/accessibility, and no claim that the entire selection succeeded when any item did not.
- Failure sequence: Hidden selection includes item 26, role change leaves manager action enabled, optimistic UI marks every item success, refresh loses durable progress, failed items are automatically replayed with stale versions, or focus/status is inaccessible.
- Implementation: server-derived eligibility/actions, bounded in-memory selection, explicit action/target/version confirmation, polling/query of durable request/items, per-status rendering, new-request retry using current versions, cache invalidation from source results, and accessible announcements/focus restoration.
- Proof: `savedViewsAndBulkReview.test.tsx` drives the real `SavedViewsRoute` and `BulkRoute` components against stubbed transport, asserting the request each control actually sends and the truth each result actually renders. Accessibility is proven with `axe-core` on the rendered container rather than by inspection.
- Adversarial evidence: A saved view renders its definition digest and never the credential; creating one sends an `Idempotency-Key` and exactly the typed body `{name, visibility, filter:{fraudActions}, sort}` with no free-text field; `MERCHANT_SHARED` is offered only to a manager and `ASSIGN_TO_OPERATOR` only to a manager; a viewer is told saved views and bulk assignment are read-only and is offered no mutation control, while still being able to execute a view it can see; disabling sends the exact `lifecycleVersion` the operator saw, and the resulting `saved_view_stale` conflict is surfaced as its own recoverable slice with the view still listed rather than swallowed into a false success; executing a view re-reads the projection and renders its freshness; a `PARTIAL` bulk run headlines "Partial" with `1 succeeded`/`1 failed` and per-item `Succeeded`/`Stale`/`Assignment version stale` rather than claiming the whole selection succeeded; and a refused start renders the failure instead of a fabricated run. The credential is asserted absent from the DOM on both routes, and `axe-core` reports zero violations.
- Remaining before this row can close: the 25/26 selection boundary is enforced and proven server-side (`ReviewBulkActionScopeTests`) but the browser does not yet cap the textarea client-side; reload/back/disconnect resumption is not yet covered.
- Status: PASS

## C55-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose saved-view operation/upgrade outcome and bulk request backlog/oldest age, claim, item terminal category, lease recovery, reconciliation, and request terminal outcome only after durable truth, without tenant, actor, target, view, request, review, idempotency, cursor, filter, payment, event, credential, message, or exception text as dimensions.
- Failure sequence: Pre-read intention increments success, rollback counts completion, one item/request creates a time series, raw filter/error is logged, stuck lease is invisible, or counters disagree silently.
- Implementation: closed operation/status/failure enums, transaction-after-completion metrics, bounded gauges, safe request-ID diagnostics, reconciliation/readiness, and runbook queries returning aggregates only.
- Proof: `InvestigationWorkflowObservabilityTests` pins the emitted bulk-item vocabulary to exactly the six outcomes in this matrix's Frozen Bulk Item State table, so adding a seventh terminal state fails the test unless the table is updated in the same change. It also enumerates every tag key any decision-intelligence meter can carry and asserts the set is closed.
- Adversarial evidence: A UUID, a merchant label, a credential-shaped string, a cursor payload, an idempotency key, raw `org.postgresql` exception text, and the empty string are each refused as an item or lease result with no meter registered at all; no meter carries a `merchant`, `operator`, `review`, `request`, `cursor`, `idempotencyKey`, `message`, `exception`, `path`, `url`, or `view` tag key; and the lease vocabulary keeps `CLAIMED`, `RECOVERED`, `NOT_CLAIMABLE`, `LAUNCH_FAILED`, and `TERMINAL` distinct so a stuck run stays visible.
- Rollback reconciliation: `ReviewBulkActionAtomicityPostgreSqlTests.aRolledBackItemIncrementsNoTerminalMeterSoCountersMatchDurableRows` fences the worker out, runs the real executor path, and asserts that neither the `SUCCEEDED` nor the `FAILED` counter moved while the item stayed `CLAIMED` and the request holds zero terminal item rows. A meter that counted the pre-read intention rather than the committed transition would drift from durable truth permanently, and this proof fails the moment it does.
- Stuck-lease visibility: `ReviewBulkBacklogReadinessTests` strands a real request — claimed, then its lease expired, exactly the state a dead worker leaves — and drives the production health indicator. The backlog gauge rises to 1 and the oldest-age gauge to the request's true age, which is the entire detection surface for this failure because nothing throws, no item fails, and no error is logged when a worker simply stops. Readiness still reports `UP`, so one stuck bulk run cannot take payment mutation down. Once recovery finishes the run through the normal fenced path the backlog returns to zero, so the gauge cannot alert forever. Both gauges are asserted to carry no tags at all, keeping them one bounded series rather than one per tenant or request.
- Status: PASS

## C55-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V88–V90 migrate fresh V1 and realistic retained V87 PostgreSQL without changing existing review saved searches or review assignments, reject malformed/cross-tenant/digest/status/count/lease history before unsafe completion, install immutable terminal/history guards and bounded indexes, and converge on repeated startup/rolling ownership.
- Failure sequence: Existing review search is rewritten, request/item constraints accept impossible counts or foreign reviews, terminal evidence mutates, lease epoch regresses, partial migration leaves claimable corrupt work, or old code owns new rows.
- Implementation: capability-named forward migrations, separate saved-view table, tenant-qualified FKs/checks/unique indexes, V90 immutable and fencing triggers, preflight validation, readiness gates, and explicit worker enablement after schema validation.
- Proof: `ReviewBulkAssignmentMigrationPostgreSqlTests` drives Flyway directly against isolated schemas on a real PostgreSQL container, staging a retained V87 workspace with a real legacy saved search before migrating forward. Preservation is asserted by comparing whole row maps, not counts, so a rewritten column fails.
- Adversarial evidence: Fresh migration installs both bulk tables, the saved-view table, all three V90 guards, and both bounded work indexes. A retained V87 schema carrying a legacy `review_saved_searches` row migrates to V91 with that row byte-identical, the new saved-view table empty, and no bulk guard attached to the legacy table; a repeated startup converges at V91 and leaves the row unchanged again. Every malformed request shape is refused by the database: `ASSIGN_TO_OPERATOR` without a target and a non-targeted action carrying one, an action outside the closed three, `0` and `26` items, counters exceeding the item count, an unknown status, a non-64-character digest, a lease token without an expiry, an initiating operator from another tenant, and a duplicate idempotency identity. Every malformed item shape is refused too: ordinal `0` and `26`, a negative expected version, a `PENDING` item carrying a completion, a terminal item missing one, a `CLAIMED` item without its fence, a repeated ordinal, and a repeated review case. Terminal history cannot be rewritten or deleted at either level, a fence cannot move backwards by update or by insert, and a terminal request status that disagrees with its item truth — `COMPLETED` or `PARTIAL` when the only item failed — is refused by the deferred constraint trigger while the honest `FAILED` is accepted.
- Deliberate absence proven, not assumed: the item table carries no foreign key to `manual_review_cases`. Resolving a review at admission time would itself disclose whether that review exists in another tenant, so the frozen design binds an item to its tenant through a tenant-qualified foreign key to its owning request and terminates an unknown or foreign review as a non-disclosing per-item `NOT_FOUND` at the mutation boundary. `anItemIsTenantBoundThroughItsRequestRatherThanByResolvingTheReviewAtAdmission` pins both halves so a future change cannot add the "missing" key without confronting the disclosure it would create.
- Status: PASS

## C55-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 55 owns typed payment-investigation views and at-most-25 assignment/release orchestration only; it does not implement bulk resolution/labels/money/disputes/controls, queue/outcome insight calculations, a custom distributed queue, or direct source-table writes.
- Failure sequence: Spring Batch becomes a second source of assignment truth, a generic action escape hatch invokes high-risk commands, dashboard totals are presented as Chapter 56 insight proof, or a demo seeds terminal item/source state through SQL.
- Implementation: mature Spring Batch with domain truth in V89/V90, closed service dependencies/routes/actions, explicit Chapter 56 ownership links, and ordinary public UI/API demo setup.
- Proof: `ReviewBulkActionScopeBoundaryTests` reads every source file in the bulk package and asserts none imports a high-risk domain service (`ChargebackService`, `DisputeOperationsService`, `FullRefundService`, `RefundController`, `RiskLabelController`, `FraudLabelRepository`, `GovernedControlService`, `RiskSimulationService`, `RiskSimulationActivationGate`), none uses a reflective dispatch construct, and only the reviewed store and the two Spring Batch configuration classes touch `JdbcTemplate` or `EntityManager` at all.
- Adversarial evidence: `ReviewBulkActionCode` is pinned to exactly three assignment operations, so adding a fourth fails the test rather than silently widening the vocabulary; `ReviewBulkDomainMutation` is asserted to call `assign` and `release` and never `resolve`; the controller declares no `PUT`, `PATCH`, or `DELETE` mapping; and `ReviewBulkActionScopeTests` proves a queued request leaves `review_resolutions`, `fraud_labels`, `chargebacks`, `ledger_entries`, `authored_rule_versions`, and `fraud_lists` byte-identical.
- Demo safety: `scripts/demo-saved-views-and-bulk-review.py` drives the product only through published HTTP routes, with a single `subprocess.run` reconciliation seam that accepts `SELECT` alone. `scripts/saved_views_and_bulk_review_demo_tests.py` holds the script to loopback-only origins, refuses mutating reconciliation before any subprocess starts, requires the prohibited-effect fingerprint to watch every table bulk work must not touch, and rejects any route outside the chapter's own surface. The demo deliberately submits an eligible item, a stale-version item, and an absent review so the run ends `PARTIAL` with `SUCCEEDED`/`STALE`/`NOT_FOUND` per item — a bulk demo that only ever shows success has not demonstrated the per-item truth the durable item table exists for — and it asserts exactly one assignment event for the one successful item.
- Insight-endpoint absence: `theBulkSurfaceOwnsNoAggregateOrInsightRoute` scans every bulk source for an `/insights`, `/summary`, `/statistics`, `/metrics`, or `/aggregate` mapping and for any import of the insights capability, and `theInsightCapabilityLikewiseOwnsNoBulkMutationRoute` runs the reciprocal scan so the insight package declares no mutation mapping and never reaches into bulk assignment. Two owners publishing the same number under different as-of semantics is precisely the ambiguity this boundary prevents.
- Status: PASS

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
