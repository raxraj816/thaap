# Chapter 53 Invariant Proof Matrix

- Chapter: Chapter 53 — Unified Payment Investigation And Decision Explanation
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

This matrix was drafted and frozen on 2026-08-02 after the founder confirmed
the twenty Phase 6 decisions and before Chapter 53 production code, migration,
runtime configuration, product fixture, UI, or demo changes. Every planned
class, query, test, and command below is a proof obligation, not evidence that
the behavior exists. Every row remains `PENDING` until implementation, focused
proof, traceability, and a temporally later hostile pass are complete.

Chapter 53 composes existing authoritative state. It does not introduce a
second case database, change payment/fraud/ledger mutation behavior, add an ML
model, or implement the Chapter 54 timeline and related-payment pivots. Search
may use the active `payment-risk-v1` projection; detail and all action
authorization remain source-owned.

## C53-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: One authorized investigation detail reconciles the current PaymentIntent, every attempt and processor outcome, immutable fraud decision and feature/control lineage, authentication, review/label, capture/refund/chargeback/dispute, balanced-ledger summary, post-authorization, and webhook summary without changing or recomputing any source fact.
- Failure sequence: A later attempt is shown under an earlier decision, projection status replaces current payment state, an audit or webhook attempt is presented as money/fraud truth, a missing section is silently omitted, or opening detail invokes a mutation/re-evaluation path.
- Implementation: `PaymentInvestigationService` and typed section assemblers load tenant-qualified source repositories inside the frozen read transaction, emit explicit section authority/status, and use only read-only domain methods; the Chapter 54 timeline and related pivots remain links with explicit availability.
- Proof: Planned `PaymentInvestigationContractTests`, `PaymentInvestigationSourceReconciliationTests`, source call-graph scans, and public risky-payment demo reconcile exact source IDs/status/counts and prove unchanged payment, decision, review, ledger, audit, event, webhook, and idempotency row digests before/after reads.
- Adversarial evidence: Zero/one/multiple attempts; allow/block/review/3DS/decline/unknown; uncaptured/captured/partially refunded/chargeback; open/assigned/resolved/canceled review; label absent/present/superseded; webhook absent/failed/succeeded; missing optional versus contradictory required lineage; and repeated reads.
- Status: PENDING

## C53-EXP-001

- Lens: PAYMENT_FRAUD
- Invariant: Decision explanation reports decision-time score units, risk level, winning action/reasons, feature value/version/freshness/availability, every evaluated control's version/match/outcome/proposed action/latency, deterministic precedence, and dependency-unavailable evidence; it never reruns current rules or renders absent Phase 7 model state as score zero.
- Failure sequence: Only matched rules render, an unavailable input becomes `NOT_MATCHED`, basis points render as percent without conversion, today's control version replaces the historical version, losing controls disappear, or model absence appears as a neutral model result.
- Implementation: `DecisionExplanationAssembler` maps immutable `FraudDecision`, `FeatureSnapshot`, `FraudRuleResult`, governed-control result, and policy/rule version rows into closed DTO enums with `modelState=NOT_CONFIGURED`; one pure precedence verifier detects stored contradictions without replacing stored truth.
- Proof: Planned `DecisionExplanationContractTests`, table/property partitions for every rule/control outcome and score boundary, retained historical-version fixtures, and browser explanation tests assert complete ordered evidence and human rendering.
- Adversarial evidence: Score 0/1/10000 basis points, invalid retained value, match/non-match/unavailable/fail-safe, multiple proposed actions, equal and stronger precedence, missing/duplicate control result, old/current/disabled control, live/synthetic origin, and model absent.
- Status: PENDING

## C53-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Search, detail, every child-section join, action link, and cache key derive merchant and operator identity from the live risk-operator context; copied foreign payment, attempt, decision, review, label, dispute, ledger, webhook, cursor, or projection identity reveals no existence, counts, timing, or route.
- Failure sequence: The seed payment is merchant-qualified but a child join follows a bare UUID, a projection hit names a foreign source row, a safe source-link builder accepts an event/payload URL, or one operator's browser cache is reused after reconnecting as another tenant.
- Implementation: merchant-qualified repositories and composite joins, live `RiskOperatorContext`, not-found-equivalent substitution, allowlisted server-owned route builders, and tenant/operator-qualified TanStack Query keys cleared on disconnect or actor change.
- Proof: Planned `PaymentInvestigationTenantIsolationTests`, direct PostgreSQL composite-key substitution tests, equal-shaped two-merchant fixtures, timing/count non-disclosure assertions, and browser reconnect/cache tests.
- Adversarial evidence: Foreign seed and each child ID, same-shaped UUIDs, private/shared state, viewer/analyst/manager, disabled actor, actor/merchant switch, copied cursor/link, stale cache, absent seed, and source/projection ownership disagreement.
- Status: PENDING

## C53-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Investigation responses, errors, routes, logs, metrics, audit, browser DOM/storage/history, fixtures, and screenshots exclude PAN, CVV, raw email/phone/IP/device/session, correlation HMACs, API/operator/scenario credentials, idempotency keys, webhook targets/payloads, unrestricted metadata, note plaintext outside its existing authorized read, and exception/database text.
- Failure sequence: An entity serializer exposes a newly added field, `safeFacts` accepts arbitrary JSON, a server-provided URL becomes navigation, a section exception leaks SQL/row data, or a credential/cursor enters browser storage or the route.
- Implementation: closed presentation DTOs and explicit mappers, existing audit sanitizer, safe error descriptors, allowlisted UUID-only route construction, memory-only credential state, no broad entity/event serialization, and prohibited-value source/DOM/log/metric canaries.
- Proof: Planned `PaymentInvestigationPrivacyTests`, response/log/metric/database canary scans, DTO field inventory, route-link negative tests, UI storage/history inspection, and source scans for entity or arbitrary-map serialization.
- Adversarial evidence: PAN/CVV/email/phone/IP/device/session/HMAC/API-key/operator-key/idempotency/webhook/note/SQL canaries; Unicode/control text; hostile absolute/protocol-relative/backslash URLs; oversized metadata; dependency failure; and screenshot/error rendering.
- Status: PENDING

## C53-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The authoritative detail snapshot is one read-only PostgreSQL `REPEATABLE READ` transaction bounded to one payment and five seconds, reports `sourceAsOf` plus observed maximum payment-stream version, and cannot combine source rows committed on opposite sides of the snapshot; projection/webhook sections declare their independent cutoffs instead of pretending snapshot equivalence.
- Failure sequence: Payment changes between attempt and ledger reads, a review resolves while labels load, a new capture appears in only one source section, an external section extends the source transaction indefinitely, or a read takes write locks and blocks a valid payment mutation.
- Implementation: `PaymentInvestigationSourceReader` owns one explicit read-only repeatable-read transaction and timeout for bounded source queries; projection and transport assemblers execute outside it and carry their own authority/as-of/status fields; no response object authorizes a later action.
- Proof: Planned `PaymentInvestigationSnapshotPostgreSqlTests` force writer-before-reader, reader-before-writer, commit-between-section, rollback, timeout, and unrelated-payment orderings with lock probes and exact response/database postconditions.
- Adversarial evidence: Both commit orderings, insert/update/delete attempt, new attempt/review/label/capture/refund, source rollback, query timeout, connection cancellation, unrelated tenant/payment progress, projection lag, and event append after maximum version observation.
- Status: PENDING

## C53-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each detail section names `SOURCE`, `CANONICAL_EVENT`, `ACTIVE_PROJECTION`, `AUDIT`, or `WEBHOOK_TRANSPORT` authority plus availability and time/generation/coverage where applicable; historical feature/control/decision lineage remains decision-time truth, and current projection data is never promoted to source or model truth.
- Failure sequence: Stale projection status overwrites source payment status, missing coverage appears as empty, current policy replaces historical control evidence, synthetic traffic becomes live, or Phase 7 model fields are fabricated from deterministic score data.
- Implementation: typed `InvestigationAuthority`, `SectionStatus`, `AnalyticsContext`, and decision-lineage DTOs; projection reads require active generation/cutoff/freshness/coverage/origin policy; unavailable model output is represented only as `NOT_CONFIGURED`.
- Proof: Planned `PaymentInvestigationLineageTests`, projection-generation swap/partial/stale fixtures, point-in-time decision fixtures, origin partitions, and source-versus-projection disagreement tests.
- Adversarial evidence: Fresh/stale/partial/unavailable projection, active generation swap, bootstrap versus canonical origin, live/synthetic traffic, old/current feature/rule version, missing availability, later label/dispute, and deterministic-only versus hypothetical model fields.
- Status: PENDING

## C53-API-001

- Lens: API_PRODUCT
- Invariant: `POST /risk/workspace/payments/search` and `GET /risk/workspace/payments/{paymentIntentId}` expose bounded typed contracts with explicit `AVAILABLE`, `EMPTY`, `NOT_APPLICABLE`, `PARTIAL`, `STALE`, `UNAVAILABLE`, or `DENIED` section state; search uses a closed filter/sort grammar and detail never silently truncates a growing collection.
- Failure sequence: Arbitrary JSON/text/metadata search becomes SQL, invalid amount/currency/range/sort broadens results, default page size is unbounded, a missing dependency erases successful source sections, or an embedded first page looks complete without continuation metadata.
- Implementation: typed request/response records with bean and service validation, closed enum-to-query mappings, signed generation/filter-bound cursor, bounded page/range limits, explicit collection summaries/links, and section-local failure mapping; if the Chapter 57 baseline already exists, these routes also regenerate/lint/diff that public contract.
- Proof: Planned `PaymentInvestigationApiContractTests`, table/property filter/cursor tests, route/method/media/validation tests, generated JSON shape snapshots, independent section failure tests, and conditional Chapter 57 OpenAPI regeneration proof when that baseline is already merged.
- Adversarial evidence: Every enum, null/empty, zero/max/max+1 amount and page size, currency mismatch, inverted/future/unbounded range, unsupported sort, duplicate filters, malformed/foreign/tampered cursor, 400/401/403/404/409/429/503, and one failed section among successful peers.
- Status: PENDING

## C53-UI-001

- Lens: API_PRODUCT
- Invariant: The existing React cockpit adds safe `/workspace/payments` and `/workspace/payments/:paymentIntentId` routes with memory-only credentials, deep-link reconnection, semantic and keyboard-accessible investigation sections, visible source/freshness/coverage, and independent loading/empty/partial/stale/denied/dependency/conflict recovery.
- Failure sequence: A second frontend duplicates workflows, credentials or cursors enter URL/storage, a route race renders the wrong payment, viewer UI offers a mutation, one failed query blanks successful evidence, or focus disappears after navigation/retry.
- Implementation: one route-aware application shell, existing credential boundary and TanStack Query cache discipline, section components keyed by tenant/operator/payment, server-projected available actions, accessible landmarks/status announcements/focus restoration, and allowlisted internal navigation.
- Proof: Planned `paymentInvestigation.test.tsx`, browser routing/accessibility tests, storage/history inspection, role-switch/cache tests, stale-response race tests, and production build.
- Adversarial evidence: Direct deep link before/after credential, browser back/forward, rapid payment switch, reconnect as another actor/role, viewer/analyst/manager, keyboard-only operation, reduced viewport, slow/failed/partial responses, hostile source link, and focus after retry.
- Status: PENDING

## C53-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose investigation/search outcome, closed section status, source/projection mismatch, query timeout, and result-size bucket only after response truth, without merchant, operator, payment, attempt, decision, review, event, request, cursor, route parameter, HMAC, credential, message, or exception text in metric dimensions.
- Failure sequence: One payment ID creates a time series, partial response increments full success, rollback/timeout is counted as available, a sensitive exception enters logs, or source/projection disagreement is invisible.
- Implementation: `PaymentInvestigationMetrics` with closed operation/section/outcome enums, safe request-ID-only logs, transaction/outcome-aware recording, and section dependency health reused from existing source/projection readiness.
- Proof: Planned `PaymentInvestigationObservabilityTests`, bounded-cardinality assertions, privacy canaries, partial/timeout outcome tests, and readiness tests for source/projection availability combinations.
- Adversarial evidence: Success/empty/partial/stale/denied/unavailable/timeout, source database failure, projection generation absent/stale, repeated high-cardinality IDs, sensitive database exception, disconnect, and client cancellation.
- Status: PENDING

## C53-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 53 requires no schema migration, starts only when existing source/projection contracts validate, degrades optional projection/context sections without falsifying authoritative source detail, bounds database work, and provides runbook recovery without cache edits or source mutation.
- Failure sequence: Startup serves a detail mapper against unsupported source/event schema, projection outage makes source payment unavailable, a pathological query exhausts the pool, an operator repairs a response by changing source rows, or deployment rollback leaves an incompatible UI/API contract.
- Implementation: schema/catalogue/projection compatibility readiness, bounded query/page/timeout configuration, additive DTO/API changes, independently deployable UI fallback, and investigation triage runbook with read-only reconciliation commands.
- Proof: Planned `PaymentInvestigationReadinessTests`, H2 plus PostgreSQL query-plan/cardinality tests, projection/source outage matrix, old/new UI/API compatibility tests, repeated startup, and retained V85 source reconciliation.
- Adversarial evidence: Source up/down/timeout, projection absent/stale/partial/swap, unsupported event/control version, maximum attempts/reviews/ledger rows, old UI/new API and new UI/old API, retry storm, and rollback.
- Status: PENDING

## C53-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 53 stops at source-reconciled search/detail and deterministic explanation; it does not claim Chapter 54 timeline/relationship traversal, Chapter 55 saved views/bulk work, Chapter 56 insight aggregates, Phase 7 model explanation, cross-merchant intelligence, hosted identity, or new mutation authority.
- Failure sequence: Provisional timeline data is presented as canonical history, a “related” count crosses merchants, model score is fabricated, detail response becomes a write model, or a demo silently uses later endpoints/direct SQL.
- Implementation: capability and component scans, explicit `NOT_CONFIGURED`/not-available links, no V86+ migration, no bulk/insight/model packages, and a public demo limited to ordinary merchant setup plus risk-operator search/detail.
- Proof: Planned source/dependency/route/migration scans, public demo safety tests, and before/after source fingerprints prove the owned surface and non-impact.
- Adversarial evidence: Direct SQL, hidden fixture endpoint, mock timeline, fake relation count, model-shaped data, mutation through read DTO, V86 table expectation, and later-route invocation.
- Status: PENDING

## Frozen Source And Section Ownership

| Section | Authority | Chapter 53 contract |
|---|---|---|
| Payment lifecycle | source domain tables | bounded repeatable-read detail and current state |
| Decision explanation | immutable decision/feature/control lineage | complete historical explanation; no recomputation |
| Review and labels | source review/label rows | current state and existing action links only |
| Money and disputes | source money/dispute rows plus balanced ledger groups | currency-qualified summary; no money mutation |
| Timeline | canonical payment stream | Chapter 54 link/availability only |
| Related payments | source identifiers/HMAC anchors | Chapter 54 link/availability only |
| Analytics context | active Phase 5 projection | independent generation/freshness/coverage-qualified context |
| Audit and webhook | distinct accountability/transport stores | bounded summary/link; never source payment order |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 53 obligations |
|---|---|
| Source composition | C53-PAY-001, C53-TXN-001, C53-DAT-001 |
| Decision explanation | C53-EXP-001 and C53-DAT-001 |
| Tenant and authorization | C53-TEN-001 and C53-UI-001 |
| Privacy and safe navigation | C53-SEC-001 and C53-UI-001 |
| Search/detail API grammar | C53-API-001 |
| Projection/source disagreement | C53-DAT-001 and C53-OPS-001 |
| Browser resilience/accessibility | C53-UI-001 |
| Observability/readiness | C53-OBS-001 and C53-OPS-001 |
| Scope and deferral truth | C53-SCP-001 |
