# Chapter 54 Invariant Proof Matrix

- Chapter: Chapter 54 — Canonical Timeline And Safe Related-Payment Pivots
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

This matrix was drafted and frozen on 2026-08-02 after founder confirmation
and before Chapter 54 production code, V86–V87, runtime configuration, fixtures,
UI, or demo changes. Chapter 54 remains dependency-blocked until Chapter 53 is
merge-ready. Planned boundaries and tests are proof obligations only; every row
remains `PENDING` until implementation and both review passes complete.

Chapter 54 owns safe presentation of the existing canonical payment stream and
same-merchant relationship navigation. It does not make webhook attempts or
audit entries part of stream order, accept raw correlation input, authorize a
mutation, expose cross-merchant intelligence, or implement later saved/bulk or
insight workflows.

## C54-TIM-001

- Lens: PAYMENT_FRAUD
- Invariant: The investigation timeline presents each supported canonical PaymentIntent-stream fact exactly once in immutable `streamVersion` order with safe event-specific meaning; occurrence/recording timestamps remain displayed evidence and never replace stream identity or rewrite the original payment/fraud decision.
- Failure sequence: Same-time events collapse, out-of-order business timestamps reorder facts, one event appears twice or is skipped, webhook retries look like repeated payment events, or a later label/dispute changes the meaning of an earlier decision.
- Implementation: `PaymentTimelineService` reads the canonical stream by tenant/payment/version and maps each catalogue type/version through a closed `TimelineEntryMapper`; webhook transport and audit remain separate collections.
- Proof: Planned `PaymentTimelineContractTests`, exact catalogue mapper inventory, same-time/out-of-order fixtures, source-to-entry reconciliation, and public multi-stage payment demo.
- Adversarial evidence: All supported payment/review/risk/money/dispute event families; same microsecond; occurrence before/after recording; stream gap/duplicate; later label/refund/chargeback; repeated webhook attempt; and unknown type/version.
- Status: PENDING

## C54-MAP-001

- Lens: SECURITY_PRIVACY
- Invariant: Every timeline entry is built from an event-type/version allowlist and exposes only safe title/category/facts/source links; raw payload/record bytes, digests, request/trace IDs, actor secrets, raw metadata, correlation values, note text, webhook URL/payload, and arbitrary event-provided navigation never reach API, browser, logs, metrics, fixtures, or screenshots.
- Failure sequence: A generic JSON viewer exposes a newly added payload field, hostile payload text becomes a link or HTML, an unknown version falls back to raw JSON, or an exception prints exact event bytes.
- Implementation: typed mappers with no broad map/entity serialization, server-owned route allowlist, escaped display strings, explicit unsupported-version section failure, and prohibited-value canaries across all sinks.
- Proof: Planned `PaymentTimelinePrivacyTests`, mapper field inventory, hostile payload/link fixtures, response/DOM/log/metric canary scans, and source scans rejecting raw-byte or arbitrary-map response paths.
- Adversarial evidence: PAN/CVV/PII/IP/device/session/HMAC/key/idempotency/request/trace/webhook/note/SQL canaries; absolute/protocol-relative/backslash/encoded URLs; Unicode/control/oversized text; unknown version; and mapper exception.
- Status: PENDING

## C54-CUR-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The first timeline page freezes the authorized seed payment's observed `maximumStreamVersion`; every signed cursor binds tenant, payment, maximum version, direction, page size, and last `(streamVersion,eventId)`, so concurrent appends cause neither loss nor duplication inside the frozen traversal and appear only after refresh.
- Failure sequence: An append between pages shifts the window, a cursor from another tenant/payment/query continues successfully, same-version corruption becomes ambiguous, reverse traversal skips a boundary, or cursor tampering broadens the result.
- Implementation: the existing signed cursor primitive encodes the closed timeline identity; repository queries use strict version/event anchors and `<= maximumStreamVersion`; composite stream uniqueness remains authoritative.
- Proof: Planned `PaymentTimelineCursorTests`, property/table cursor grammar tests, and `PaymentTimelineAppendPostgreSqlTests` forcing append-before/after page reads with exact union/order/no-duplicate postconditions.
- Adversarial evidence: Empty/one/full/next page; page size min/max/max+1; forward/reverse; append before/between/after; same timestamp; malformed/truncated/oversized/expired/tampered cursor; foreign tenant/payment; and stream corruption.
- Status: PENDING

## C54-REL-001

- Lens: PAYMENT_FRAUD
- Invariant: Related-payment results begin from an authorized seed and use exactly seven reason codes: direct same-merchant customer lineage plus six persisted HMAC-backed card/email/phone/IP/device/session families; results never become fraud labels, risk scores, decision inputs, or proof that two people are identical.
- Failure sequence: A shared email is called confirmed fraud, a relation changes a decision, customer and email meanings collapse, absent anchors produce an authoritative zero, or a result from outside the requested time/origin boundary appears.
- Implementation: `RelatedPaymentService` unions a tenant-qualified customer query with V86 anchor matches, deduplicates by payment, reports closed reason sets/counts and coverage, and returns source/projection summaries without invoking decision or mutation services.
- Proof: Planned `RelatedPaymentContractTests`, seven-reason table tests, multi-reason deduplication, source-non-interference fingerprints, and a multi-payment analyst demo.
- Adversarial evidence: Each reason alone/together; same customer with changed card/device; shared email/phone; same HMAC under distinct reason domains; no identity; partial anchors; live/synthetic origin; seed included/excluded; and later decision/label differences.
- Status: PENDING

## C54-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Timeline, webhook activity, direct-customer pivot, anchor lookup, result counts, coverage, cursors, and links are merchant-qualified from the live risk-operator context and authorized seed payment; identical HMACs or copied IDs across merchants reveal no existence, timing, cardinality, or relationship.
- Failure sequence: Anchor index lookup omits merchant prefix, a direct customer query follows a bare UUID, a foreign seed or cursor returns a distinguishable zero/error, or cached related results survive an actor/tenant switch.
- Implementation: composite foreign keys and indexes beginning with merchant, merchant-qualified repositories, seed-first authorization, not-found-equivalent errors, tenant/payment/query-bound cursors, and tenant/operator/payment-qualified UI cache keys cleared on reconnect.
- Proof: Planned `PaymentRelationshipTenantIsolationTests`, V86/V87 direct PostgreSQL substitution tests, equal-HMAC/equal-shaped two-merchant fixtures, timing/count equivalence checks, and browser actor-switch tests.
- Adversarial evidence: Foreign seed/payment/attempt/customer/method/risk-context/source/event/cursor; identical digest/reason/time; absent versus foreign; disabled actor; role change; cached first/next page; and direct invalid insert.
- Status: PENDING

## C54-HMC-001

- Lens: SECURITY_PRIVACY
- Invariant: The related API accepts no raw or HMAC identity and returns no customer ID, correlation digest, key material/version detail useful for probing, or raw email/phone/IP/device/session/card value; it exposes only bounded reason codes, matched-reason count, safe payment/decision summary, origin, times, and honest aggregate coverage.
- Failure sequence: A query parameter becomes a raw identity oracle, response/debug data exposes a digest, key-version gaps reveal rotation history per person, errors/timing distinguish foreign matches, or logs/metrics contain correlation material.
- Implementation: seed-derived lookup only, closed range/reason/page inputs, safe aggregate coverage enum, constant-shape not-found/zero/partial handling where practical, redacted DTOs, and bounded metrics without identity dimensions.
- Proof: Planned `RelatedPaymentPrivacyTests`, API grammar negatives, prohibited-data canaries across database/API/log/metric/DOM, equal-HMAC cross-tenant timing/count tests, and response schema inventory.
- Adversarial evidence: Raw/HMAC query/body/header attempts for all six identities; malformed/oversized reason/range/cursor; key current/retained/missing; zero match versus foreign match; multiple matches; sensitive exception; and browser copy/link behavior.
- Status: PENDING

## C54-LIN-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each anchor binds merchant, payment, attempt, relation type, persisted HMAC, key version/family, observed time, traffic origin, and exact source lineage; `SAME_CUSTOMER` retains direct source-FK meaning, active/retained aliases remain distinguishable internally, and coverage reports missing source/key/alias/backfill truth without fabrication.
- Failure sequence: Current key version labels an old digest, a card HMAC is compared under an email domain, alias source points to another attempt, observed time uses query time, synthetic origin becomes live, or migration invents a digest from unavailable raw data.
- Implementation: V86 typed anchor rows and source derivation, explicit `correlation-v1` provenance for stable persisted correlation HMACs, versioned risk-context aliases, source/reason domain checks, and immutable coverage calculation.
- Proof: Planned `PaymentRelationshipLineageTests`, retained key/version fixtures, domain-separation tables, source-to-anchor reconciliation, exact timestamp/origin tests, and no-raw-rehash migration assertions.
- Adversarial evidence: Stable/rotating key families; current/retained/missing/duplicate alias; card/email/phone/IP/device/session domain substitution; source present/foreign/missing; before/exact/after microsecond; live/synthetic; and ambiguous retained row.
- Status: PENDING

## C54-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V86–V87 migrate fresh V1 and realistic retained V85 PostgreSQL safely, backfill only persisted safe correlation lineage, preserve all source/domain/event/projection truth, reject malformed/cross-tenant/ambiguous history before unsafe completion, install immutable guards and bounded indexes, and converge on repeated startup.
- Failure sequence: Backfill re-derives absent PII, duplicate aliases abort after partial unsafe DDL, source trigger accepts a foreign polymorphic ID, ordinary update/delete mutates history, query indexes omit tenant, or retained code reads half-installed anchors.
- Implementation: capability-named forward migrations with preflight constraints, tenant-qualified FKs, V87 source constraint trigger and update/delete guards, closed type/key/time/origin checks, deterministic set-based safe backfill, and readiness validation.
- Proof: Planned `PaymentRelationshipMigrationPostgreSqlTests` for fresh V1→V87 and retained V85→V87, malformed-history partitions, exact source row fingerprints, mutation rejection, index/query-plan assertions, and repeated startup.
- Adversarial evidence: Empty/populated V85; missing/duplicate/cross-tenant source; malformed type/HMAC/key/time/origin; mixed key aliases; interrupted/repeated migration; old/new application order; direct UPDATE/DELETE; and maximum retained volume.
- Status: PENDING

## C54-API-001

- Lens: API_PRODUCT
- Invariant: Timeline, related-payment, and webhook-activity APIs use separate bounded cursors and explicit section/coverage status; related lookup defaults to seven aligned days, rejects more than 90, returns at most 50 payments per page, freezes `asOf`, and never silently truncates or broadens a request.
- Failure sequence: A timeline cursor is accepted by related lookup, unaligned/future/inverted range broadens, 50/51 behavior loses continuation, partial coverage appears as empty, or one transport failure erases canonical history.
- Implementation: typed request/response/cursor records, endpoint-specific cursor purposes/digests, UTC-aligned range validation, section-local dependency mapping, and explicit continuation/coverage metadata.
- Proof: Planned `PaymentInvestigationNavigationApiTests`, table/property range/page/cursor tests, method/media/auth/status contracts, and independent timeline/related/webhook failure tests.
- Adversarial evidence: Range absent/zero/seven/90/over-90/inverted/future/unaligned; page 1/50/51; cursor cross-endpoint/tenant/payment/range/reason/direction; 400/401/403/404/409/429/503; and partial dependency combinations.
- Status: PENDING

## C54-UI-001

- Lens: API_PRODUCT
- Invariant: The existing investigation route renders canonical timeline, separately labelled webhook activity, and safe related-payment pivots with keyboard/focus/accessibility support, source/coverage warnings, lossless continuation, and safe navigation; credentials, cursors, raw identities, and server-provided URLs never enter routable or persistent browser state.
- Failure sequence: Timeline and webhook attempts flatten together, infinite scroll hides loss, clicking hostile facts navigates externally, related pivot leaks a correlation value, a stale response replaces a newly selected payment, or partial failure blanks the page.
- Implementation: typed section components, explicit evidence labels, user-controlled pagination, allowlisted internal UUID routes, tenant/operator/payment query keys, abort/stale-response handling, memory-only credentials, and semantic status/focus restoration.
- Proof: Planned `paymentTimelineAndRelations.test.tsx`, browser pagination/routing/accessibility tests, hostile link/identity fixtures, cache/race tests, storage/history inspection, and production build.
- Adversarial evidence: Empty/full/multipage timeline; append and refresh; webhook failure; zero/partial/full relation coverage; rapid pivot/back; actor change; viewer/analyst/manager; keyboard-only; hostile link; and section 401/403/404/409/429/503.
- Status: PENDING

## C54-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded metrics and safe logs expose timeline/relationship/webhook operation outcome, closed section/coverage state, latency/result-size bucket, cursor rejection, and query timeout without tenant, actor, payment, event, source, cursor, reason combination, HMAC, key version, request, URL, credential, payload, message, or exception text as dimensions.
- Failure sequence: One payment/event/digest creates a time series, foreign and absent outcomes become distinguishable tags, a raw cursor is logged, partial coverage increments complete success, or a stuck query is invisible.
- Implementation: closed operation/outcome/coverage enums, request-ID-only safe diagnostics, transaction/outcome-aware recording, bounded query timers, and readiness for schema/guard/coverage health.
- Proof: Planned `PaymentInvestigationNavigationObservabilityTests`, cardinality and canary assertions, commit/rollback/timeout tests, and readiness matrices for anchor/event/transport availability.
- Adversarial evidence: Success/empty/partial/unavailable/timeout/tamper; high-volume IDs/digests/cursors; database/event mapper exception; missing trigger/index; source corruption; and client cancellation.
- Status: PENDING

## C54-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 54 owns only canonical timeline, separately labelled webhook activity, same-merchant pivots, V86–V87, and their UI; it does not add raw-identifier search, cross-merchant counts/graphs, fraud features/scores, saved views, bulk actions, insights, model behavior, or source mutation authority.
- Failure sequence: A relationship becomes a feature, network counts leak, a mapper replays a command, a demo writes anchors directly, or later UI/API routes are presented as Chapter 54 proof.
- Implementation: dependency/route/migration/source scans, read-only services, explicit Phase 55/56/7/8 ownership links, and public demos using ordinary source APIs plus read navigation only.
- Proof: Planned scope scans, source non-interference fingerprints, route inventory, demo safety tests, and absence of later capability packages/tables/endpoints.
- Adversarial evidence: Direct SQL/hidden fixtures, raw identity query, foreign merchant graph, decision-service dependency, bulk/insight endpoint, model-shaped field, and anchor-as-authorization attempt.
- Status: PENDING

## Frozen Relationship Representation

| Reason | Lookup authority | V86 anchor |
|---|---|---|
| `SAME_CUSTOMER` | existing tenant-qualified customer FK | no |
| `SAME_CARD_FINGERPRINT` | persisted stable correlation HMAC | yes |
| `SAME_EMAIL` | persisted stable correlation HMAC | yes |
| `SAME_PHONE` | persisted stable correlation HMAC | yes |
| `SAME_IP` | versioned risk-context HMAC/aliases | yes |
| `SAME_DEVICE` | versioned risk-context HMAC/aliases | yes |
| `SAME_SESSION` | versioned risk-context HMAC/aliases | yes |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 54 obligations |
|---|---|
| Timeline semantics/mappers | C54-TIM-001 and C54-MAP-001 |
| Append-safe pagination | C54-CUR-001 |
| Related-payment meaning | C54-REL-001 |
| Tenant/privacy | C54-TEN-001 and C54-HMC-001 |
| Identity lineage/key rotation | C54-LIN-001 |
| Migration/history | C54-MIG-001 |
| API/browser product | C54-API-001 and C54-UI-001 |
| Observability/operations/scope | C54-OBS-001 and C54-SCP-001 |
