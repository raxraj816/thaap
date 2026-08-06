# Chapter 53 Invariant Proof Matrix

- Chapter: Chapter 53 — Unified Payment Investigation And Decision Explanation
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
- Proof: `PaymentInvestigationContractTests` partitions zero/one/multiple attempts and every fraud action, asserts each section declares its own state rather than being omitted, and proves repeated reads are stable. `PaymentInvestigationSourceReconciliationTests` hex-fingerprints 22 authoritative tables before and after detail, search, timeline, related, and webhook-activity reads and proves them byte-identical, proves a denied read leaves no trace, and proves identity links resolve only through composite-key-qualified rows. `scripts/demo-payment-investigation.py` reconciles the same non-impact claim against PostgreSQL over the public HTTP surface.
- Adversarial evidence: Zero/one/multiple attempts; allow/block/review/3DS/decline/unknown; uncaptured/captured/partially refunded/chargeback; open/assigned/resolved/canceled review; label absent/present/superseded; webhook absent/failed/succeeded; missing optional versus contradictory required lineage; and repeated reads.
- Status: PASS

## C53-EXP-001

- Lens: PAYMENT_FRAUD
- Invariant: Decision explanation reports decision-time score units, risk level, winning action/reasons, feature value/version/freshness/availability, every evaluated control's version/match/outcome/proposed action/latency, deterministic precedence, and dependency-unavailable evidence; it never reruns current rules or renders absent Phase 7 model state as score zero.
- Failure sequence: Only matched rules render, an unavailable input becomes `NOT_MATCHED`, basis points render as percent without conversion, today's control version replaces the historical version, losing controls disappear, or model absence appears as a neutral model result.
- Implementation: `DecisionExplanationAssembler` maps immutable `FraudDecision`, `FeatureSnapshot`, `FraudRuleResult`, governed-control result, and policy/rule version rows into closed DTO enums with `modelState=NOT_CONFIGURED`; one pure precedence verifier detects stored contradictions without replacing stored truth.
- Proof: `DecisionExplanationContractTests` runs a `@CsvSource` partition over ALLOW, REVIEW, REQUEST_3DS, and BLOCK, proves losing and unmatched controls stay visible rather than being filtered to the winner, and proves rendered control evidence mirrors the persisted `rule_execution_results` lineage for the exact decision rendered. `PaymentInvestigationLineageTests` proves `modelState` is `NOT_CONFIGURED` with no `modelScore` or `modelVersion` field present, and `PaymentInvestigationScopeBoundaryTests` proves no response field can name a model or cross-merchant quantity at all.
- Adversarial evidence: Score 0/1/10000 basis points, invalid retained value, match/non-match/unavailable/fail-safe, multiple proposed actions, equal and stronger precedence, missing/duplicate control result, old/current/disabled control, live/synthetic origin, and model absent.
- Status: PASS

## C53-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Search, detail, every child-section join, action link, and cache key derive merchant and operator identity from the live risk-operator context; copied foreign payment, attempt, decision, review, label, dispute, ledger, webhook, cursor, or projection identity reveals no existence, counts, timing, or route.
- Failure sequence: The seed payment is merchant-qualified but a child join follows a bare UUID, a projection hit names a foreign source row, a safe source-link builder accepts an event/payload URL, or one operator's browser cache is reused after reconnecting as another tenant.
- Implementation: merchant-qualified repositories and composite joins, live `RiskOperatorContext`, not-found-equivalent substitution, allowlisted server-owned route builders, and tenant/operator-qualified TanStack Query keys cleared on disconnect or actor change.
- Proof: `PaymentInvestigationTenantIsolationTests` proves a foreign identifier is indistinguishable from an absent identifier across all four payment-scoped routes, substitutes each child identifier under a foreign tenant, rejects a cross-tenant cursor, and covers the unauthenticated and disabled-operator actors. `PaymentInvestigationScopeBoundaryTests.anIdenticalCardInAnotherMerchantNeverRaisesARelatedCountOrCoverageClaim` proves an unscoped anchor join would have linked two merchants transacting the identical card and that the related page still returns nothing and declares `SAME_MERCHANT_SERVER_DERIVED`. `scripts/demo-payment-investigation.py` repeats the foreign-versus-absent equivalence over public HTTP.
- Adversarial evidence: Foreign seed and each child ID, same-shaped UUIDs, private/shared state, viewer/analyst/manager, disabled actor, actor/merchant switch, copied cursor/link, stale cache, absent seed, and source/projection ownership disagreement.
- Status: PASS

## C53-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Investigation responses, errors, routes, logs, metrics, audit, browser DOM/storage/history, fixtures, and screenshots exclude PAN, CVV, raw email/phone/IP/device/session, correlation HMACs, API/operator/scenario credentials, idempotency keys, webhook targets/payloads, unrestricted metadata, note plaintext outside its existing authorized read, and exception/database text.
- Failure sequence: An entity serializer exposes a newly added field, `safeFacts` accepts arbitrary JSON, a server-provided URL becomes navigation, a section exception leaks SQL/row data, or a credential/cursor enters browser storage or the route.
- Implementation: closed presentation DTOs and explicit mappers, existing audit sanitizer, safe error descriptors, allowlisted UUID-only route construction, memory-only credential state, no broad entity/event serialization, and prohibited-value source/DOM/log/metric canaries.
- Proof: `PaymentInvestigationPrivacyTests` sources its canaries from real rows, asserting the card token, card fingerprint, and relation-identity HMAC exist before asserting their absence, so no canary is vacuous; it proves a failing section returns a closed descriptor rather than driver or row text, and that investigation logs carry only the request identifier. `paymentInvestigation.test.tsx` proves the operator credential reaches neither local storage, session storage, cookies, the URL, nor the rendered DOM, and that no server-supplied value becomes a link.
- Adversarial evidence: PAN/CVV/email/phone/IP/device/session/HMAC/API-key/operator-key/idempotency/webhook/note/SQL canaries; Unicode/control text; hostile absolute/protocol-relative/backslash URLs; oversized metadata; dependency failure; and screenshot/error rendering.
- Status: PASS

## C53-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The authoritative detail snapshot is one read-only PostgreSQL `REPEATABLE READ` transaction bounded to one payment and five seconds, reports `sourceAsOf` plus observed maximum payment-stream version, and cannot combine source rows committed on opposite sides of the snapshot; projection/webhook sections declare their independent cutoffs instead of pretending snapshot equivalence.
- Failure sequence: Payment changes between attempt and ledger reads, a review resolves while labels load, a new capture appears in only one source section, an external section extends the source transaction indefinitely, or a read takes write locks and blocks a valid payment mutation.
- Implementation: `PaymentInvestigationSourceReader` owns one explicit read-only repeatable-read transaction and timeout for bounded source queries; projection and transport assemblers execute outside it and carry their own authority/as-of/status fields; no response object authorizes a later action.
- Proof: `PaymentInvestigationSnapshotPostgreSqlTests` runs against PostgreSQL 16 and proves the detail read declares repeatable read and reports the stream version it actually observed, that a writer committing mid-read cannot split one detail across two snapshots under a forced latch interleaving, that the read takes no lock that blocks a concurrent payment mutation as observed in `pg_locks`, that no idle-in-transaction session survives in `pg_stat_activity`, and that detail and timeline declare independent cutoffs. `PaymentInvestigationScopeBoundaryTests` closes the declared-cutoff family: it proves the related-payment page holds a snapshot because its per-row projection lookups have no value ceiling, and that the timeline instead pins its stream ceiling by value and holds it across every page.
- Adversarial evidence: Both commit orderings, insert/update/delete attempt, new attempt/review/label/capture/refund, source rollback, query timeout, connection cancellation, unrelated tenant/payment progress, projection lag, and event append after maximum version observation.
- Status: PASS

## C53-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each detail section names `SOURCE`, `CANONICAL_EVENT`, `ACTIVE_PROJECTION`, `AUDIT`, or `WEBHOOK_TRANSPORT` authority plus availability and time/generation/coverage where applicable; historical feature/control/decision lineage remains decision-time truth, and current projection data is never promoted to source or model truth.
- Failure sequence: Stale projection status overwrites source payment status, missing coverage appears as empty, current policy replaces historical control evidence, synthetic traffic becomes live, or Phase 7 model fields are fabricated from deterministic score data.
- Implementation: typed `InvestigationAuthority`, `SectionStatus`, `AnalyticsContext`, and decision-lineage DTOs; projection reads require active generation/cutoff/freshness/coverage/origin policy; unavailable model output is represented only as `NOT_CONFIGURED`.
- Proof: `PaymentInvestigationLineageTests` proves every section names its authority, that an unbuilt projection degrades only its own section, that a non-active generation empties search without altering source detail, that the rule catalogue is fully immutable at runtime because `trg_fraud_rules_immutable` and `trg_rule_execution_results_immutable` reject every insert, update, and delete, that `modelState` is `NOT_CONFIGURED` with no model score field, and that traffic origin comes from source and cannot be relabelled once a review exists because `fk_manual_reviews_intent_origin` refuses it.
- Adversarial evidence: Fresh/stale/partial/unavailable projection, active generation swap, bootstrap versus canonical origin, live/synthetic traffic, old/current feature/rule version, missing availability, later label/dispute, and deterministic-only versus hypothetical model fields.
- Status: PASS

## C53-API-001

- Lens: API_PRODUCT
- Invariant: `POST /risk/workspace/payments/search` and `GET /risk/workspace/payments/{paymentIntentId}` expose bounded typed contracts with explicit `AVAILABLE`, `EMPTY`, `NOT_APPLICABLE`, `PARTIAL`, `STALE`, `UNAVAILABLE`, or `DENIED` section state; search uses a closed filter/sort grammar and detail never silently truncates a growing collection.
- Failure sequence: Arbitrary JSON/text/metadata search becomes SQL, invalid amount/currency/range/sort broadens results, default page size is unbounded, a missing dependency erases successful source sections, or an embedded first page looks complete without continuation metadata.
- Implementation: typed request/response records with bean and service validation, closed enum-to-query mappings, signed generation/filter-bound cursor, bounded page/range limits, explicit collection summaries/links, and section-local failure mapping; if the Chapter 57 baseline already exists, these routes also regenerate/lint/diff that public contract.
- Proof: `PaymentInvestigationApiContractTests` rejects ten invalid search grammars through a `@CsvSource` partition covering unknown enum values, free-text metadata, negative and inverted amount bands, and half-open, inverted, future, and over-ninety-day created ranges; it rejects page sizes of 0, -1, 101, and 1000; it pages completely at `limit=1`; it proves a cursor is bound to its query shape and that a replay under a different filter is refused; and it proves two orderings of one filter canonicalize to a single meaning. `VersionedPublicContractTests` proves the published contract describes the running API rather than a stale snapshot.
- Adversarial evidence: Every enum, null/empty, zero/max/max+1 amount and page size, currency mismatch, inverted/future/unbounded range, unsupported sort, duplicate filters, malformed/foreign/tampered cursor, 400/401/403/404/409/429/503, and one failed section among successful peers.
- Status: PASS

## C53-UI-001

- Lens: API_PRODUCT
- Invariant: The existing React cockpit adds safe `/workspace/payments` and `/workspace/payments/:paymentIntentId` routes with memory-only credentials, deep-link reconnection, semantic and keyboard-accessible investigation sections, visible source/freshness/coverage, and independent loading/empty/partial/stale/denied/dependency/conflict recovery.
- Failure sequence: A second frontend duplicates workflows, credentials or cursors enter URL/storage, a route race renders the wrong payment, viewer UI offers a mutation, one failed query blanks successful evidence, or focus disappears after navigation/retry.
- Implementation: one route-aware application shell, existing credential boundary and TanStack Query cache discipline, section components keyed by tenant/operator/payment, server-projected available actions, accessible landmarks/status announcements/focus restoration, and allowlisted internal navigation.
- Proof: `paymentInvestigation.test.tsx` proves the credential never reaches storage, cookies, the URL, or the DOM; that a slow response for an abandoned payment cannot render under the newly opened payment's heading; that two failed dependencies leave authoritative detail and per-slice retries intact with no axe violation; that a stale projection states its authority and reason beside the trustworthy snapshot; that only server-returned identifiers become navigation and no anchor is emitted; and that a viewer is offered no mutation. `Phase6Workspace.test.tsx` proves the bounded search, credential-header discipline, and read-only viewer shell.
- Adversarial evidence: Direct deep link before/after credential, browser back/forward, rapid payment switch, reconnect as another actor/role, viewer/analyst/manager, keyboard-only operation, reduced viewport, slow/failed/partial responses, hostile source link, and focus after retry.
- Status: PASS

## C53-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose investigation/search outcome, closed section status, source/projection mismatch, query timeout, and result-size bucket only after response truth, without merchant, operator, payment, attempt, decision, review, event, request, cursor, route parameter, HMAC, credential, message, or exception text in metric dimensions.
- Failure sequence: One payment ID creates a time series, partial response increments full success, rollback/timeout is counted as available, a sensitive exception enters logs, or source/projection disagreement is invisible.
- Implementation: `DecisionIntelligenceMetrics` with closed operation/section/outcome enums, safe request-ID-only logs, transaction/outcome-aware recording, and section dependency health reused from existing source/projection readiness. The pre-implementation matrix named this component `PaymentInvestigationMetrics`; the delivered class covers the whole decision-intelligence surface and is named for that responsibility.
- Proof: `PaymentInvestigationObservabilityTests` proves many distinct payments collapse to one time series, that every recorded tag key is drawn from a closed allowlist and no tag value is UUID-shaped, that 404, 401, and 400 responses record `NOT_FOUND`, `UNAUTHENTICATED`, and `INVALID` rather than `SUCCEEDED`, that recorded section states match the response body's own `sectionStatuses`, that result size is recorded as a bucket rather than a count, and that the metric facade rejects any value outside its allowlist.
- Adversarial evidence: Success/empty/partial/stale/denied/unavailable/timeout, source database failure, projection generation absent/stale, repeated high-cardinality IDs, sensitive database exception, disconnect, and client cancellation.
- Status: PASS

## C53-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 53 requires no schema migration, starts only when existing source/projection contracts validate, degrades optional projection/context sections without falsifying authoritative source detail, bounds database work, and provides runbook recovery without cache edits or source mutation.
- Failure sequence: Startup serves a detail mapper against unsupported source/event schema, projection outage makes source payment unavailable, a pathological query exhausts the pool, an operator repairs a response by changing source rows, or deployment rollback leaves an incompatible UI/API contract.
- Implementation: schema/catalogue/projection compatibility readiness, bounded query/page/timeout configuration, additive DTO/API changes, independently deployable UI fallback, and investigation triage runbook with read-only reconciliation commands.
- Proof: `PaymentInvestigationReadinessTests` proves a disabled projection still leaves detail readable at 200 with `analyticsContext` reported `UNAVAILABLE` and reason `active_projection_unavailable`, that the projection-only search route fails loudly instead of returning a falsely empty page, that the source-only routes stay available throughout the outage, that the health indicator is idempotent across repeated polls, and that it reports DOWN with the closed cause `SOURCE_DATABASE_UNAVAILABLE` when the source relation is removed. This row is where `ER-0090` was found: both degradation handlers were unreachable because Spring persistence-exception translation rewrote the exception they caught.
- Adversarial evidence: Source up/down/timeout, projection absent/stale/partial/swap, unsupported event/control version, maximum attempts/reviews/ledger rows, old UI/new API and new UI/old API, retry storm, and rollback.
- Status: PASS

## C53-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 53 stops at source-reconciled search/detail and deterministic explanation; it does not claim Chapter 54 timeline/relationship traversal, Chapter 55 saved views/bulk work, Chapter 56 insight aggregates, Phase 7 model explanation, cross-merchant intelligence, hosted identity, or new mutation authority.
- Failure sequence: Provisional timeline data is presented as canonical history, a “related” count crosses merchants, model score is fabricated, detail response becomes a write model, or a demo silently uses later endpoints/direct SQL.
- Implementation: capability and component scans, explicit `NOT_CONFIGURED`/not-available links, no V86+ migration, no bulk/insight/model packages, and a public demo limited to ordinary merchant setup plus risk-operator search/detail.
- Proof: `PaymentInvestigationScopeBoundaryTests` proves the controller declares no PUT, PATCH, or DELETE mapping and that its single POST is the search read, that PUT, PATCH, and DELETE against the detail route are refused at runtime, that no response field can name a model score, feature importance, consortium, or cross-merchant quantity, and that the public demo drives the product only through published routes and reaches no later chapter's surface. `payment_investigation_demo_tests.py` proves the demo's reconciliation path executes only SELECT with read-only argv, and that its route surface stays inside ordinary merchant setup plus risk-operator search and detail.
- Adversarial evidence: Direct SQL, hidden fixture endpoint, mock timeline, fake relation count, model-shaped data, mutation through read DTO, V86 table expectation, and later-route invocation.
- Status: PASS

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
