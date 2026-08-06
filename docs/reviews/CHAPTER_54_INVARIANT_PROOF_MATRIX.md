# Chapter 54 Invariant Proof Matrix

- Chapter: Chapter 54 — Canonical Timeline And Safe Related-Payment Pivots
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

This matrix was drafted and frozen on 2026-08-02 after founder confirmation
and before Chapter 54 production code, V86–V87, runtime configuration, fixtures,
UI, or demo changes. No row was added, removed, or reworded after implementation
began; only the `Proof` and `Status` lines were completed with delivered
evidence.

Every row is now `PASS`. Chapter 54 is proven by thirteen named artifacts:
twelve backend test classes and one browser test file. The proof build promoted
two previously unproven durable guards to explicit tests rather than working
around them, corrected two of its own over-broad claims against real system
behavior, and registered `TD-0006` for the navigation page envelope naming
split. The traceability pass walked every frozen promise to its delivered
artifact; the hostile pass was run separately against the failure inventory.

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
- Proof: `PaymentTimelineContractTests` (6). The stream is reconciled row-for-row against `domain_events` rather than against a fixture constant, so a held third attempt or a skipped append fails the test. Every entry carries a closed category, `titleCode == "timeline." + eventType`, and `eventVersion == 1`. A later attempt is proven not to reorder or rewrite the earlier decision by joining `fraud_decisions` through `payment_attempts`. Duplicate stream versions are refused, webhook transport is a separate collection under `authority == "WEBHOOK_TRANSPORT_EVIDENCE"`, and an unconfirmed intent returns exactly its one creation fact.
- Adversarial evidence: All supported payment/review/risk/money/dispute event families; same microsecond; occurrence before/after recording; stream gap/duplicate; later label/refund/chargeback; repeated webhook attempt; and unknown type/version.
- Status: PASS

## C54-MAP-001

- Lens: SECURITY_PRIVACY
- Invariant: Every timeline entry is built from an event-type/version allowlist and exposes only safe title/category/facts/source links; raw payload/record bytes, digests, request/trace IDs, actor secrets, raw metadata, correlation values, note text, webhook URL/payload, and arbitrary event-provided navigation never reach API, browser, logs, metrics, fixtures, or screenshots.
- Failure sequence: A generic JSON viewer exposes a newly added payload field, hostile payload text becomes a link or HTML, an unknown version falls back to raw JSON, or an exception prints exact event bytes.
- Implementation: typed mappers with no broad map/entity serialization, server-owned route allowlist, escaped display strings, explicit unsupported-version section failure, and prohibited-value canaries across all sinks.
- Proof: `PaymentTimelinePrivacyTests` (7). Reflection over `PaymentTimelineService.safeFacts`, `category`, and `presentationFields` proves every `DomainStreamType.PAYMENT` event type has a presentation mapping, so a newly appended type cannot blank the timeline. An unrecognised payload field, an `eventVersion == 2`, and a structured payload value each fail closed with `PublicApiException`. Known-but-hidden reference fields are dropped rather than rendered, and canaries sourced from real customer and credential rows appear nowhere in the rendered timeline or webhook activity.
- Adversarial evidence: PAN/CVV/PII/IP/device/session/HMAC/key/idempotency/request/trace/webhook/note/SQL canaries; absolute/protocol-relative/backslash/encoded URLs; Unicode/control/oversized text; unknown version; and mapper exception.
- Status: PASS

## C54-CUR-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The first timeline page freezes the authorized seed payment's observed `maximumStreamVersion`; every signed cursor binds tenant, payment, maximum version, direction, page size, and last `(streamVersion,eventId)`, so concurrent appends cause neither loss nor duplication inside the frozen traversal and appear only after refresh.
- Failure sequence: An append between pages shifts the window, a cursor from another tenant/payment/query continues successfully, same-version corruption becomes ambiguous, reverse traversal skips a boundary, or cursor tampering broadens the result.
- Implementation: the existing signed cursor primitive encodes the closed timeline identity; repository queries use strict version/event anchors and `<= maximumStreamVersion`; composite stream uniqueness remains authoritative.
- Proof: `PaymentTimelineCursorTests` (7) and `PaymentTimelineAppendPostgreSqlTests` (7). Paging at `limit=1` equals paging at `limit=50` with no gap or repeat; the page-one ceiling is held by every later page. A cursor minted for another payment, another tenant, or the webhook domain is refused, as is every single-character edit of a valid cursor, an oversized cursor, and a non-Base64 cursor. On PostgreSQL a genuine `POST /payment_intents/{id}/cancel` append is proven not to leak into an already-issued page while a fresh page sees it. Two connections racing for one stream version leave exactly one row; versions are exactly `1..n`; and every stored payload still hashes to the digest recorded beside it.
- Adversarial evidence: Empty/one/full/next page; page size min/max/max+1; forward/reverse; append before/between/after; same timestamp; malformed/truncated/oversized/expired/tampered cursor; foreign tenant/payment; and stream corruption.
- Status: PASS

## C54-REL-001

- Lens: PAYMENT_FRAUD
- Invariant: Related-payment results begin from an authorized seed and use exactly seven reason codes: direct same-merchant customer lineage plus six persisted HMAC-backed card/email/phone/IP/device/session families; results never become fraud labels, risk scores, decision inputs, or proof that two people are identical.
- Failure sequence: A shared email is called confirmed fraud, a relation changes a decision, customer and email meanings collapse, absent anchors produce an authoritative zero, or a result from outside the requested time/origin boundary appears.
- Implementation: `RelatedPaymentService` unions a tenant-qualified customer query with V86 anchor matches, deduplicates by payment, reports closed reason sets/counts and coverage, and returns source/projection summaries without invoking decision or mutation services.
- Proof: `RelatedPaymentContractTests` (7). Two payments sharing a contact and card relate; a third with a different contact and the alternate card does not; the seed excludes itself; `scope == "SAME_MERCHANT_SERVER_DERIVED"`. Every reason is in the frozen seven-value vocabulary, `matchedReasonCount == reasons.size()`, and `firstObservedAt <= lastObservedAt`. Coverage partitions the vocabulary with no overlap or omission and `PARTIAL` carries `reasonCode == "seed_identity_types_unavailable"`. The window is proven to satisfy `from < to <= asOf` within 90 days, and inverted, oversized, future-facing, and empty windows are all refused.
- Adversarial evidence: Each reason alone/together; same customer with changed card/device; shared email/phone; same HMAC under distinct reason domains; no identity; partial anchors; live/synthetic origin; seed included/excluded; and later decision/label differences.
- Status: PASS

## C54-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Timeline, webhook activity, direct-customer pivot, anchor lookup, result counts, coverage, cursors, and links are merchant-qualified from the live risk-operator context and authorized seed payment; identical HMACs or copied IDs across merchants reveal no existence, timing, cardinality, or relationship.
- Failure sequence: Anchor index lookup omits merchant prefix, a direct customer query follows a bare UUID, a foreign seed or cursor returns a distinguishable zero/error, or cached related results survive an actor/tenant switch.
- Implementation: composite foreign keys and indexes beginning with merchant, merchant-qualified repositories, seed-first authorization, not-found-equivalent errors, tenant/payment/query-bound cursors, and tenant/operator/payment-qualified UI cache keys cleared on reconnect.
- Proof: `PaymentRelationshipTenantIsolationTests` (5). Two merchants holding byte-identical digests, proven non-vacuous by `COUNT(DISTINCT relation_identity_hmac) == 1`, are never related to each other. A foreign payment is byte-indistinguishable from an absent one across all four investigation routes in both status and scrubbed body. Coverage exposes exactly the tenant's own anchor types plus the always-derivable `SAME_CUSTOMER`. A related cursor minted in one tenant is refused in the other, and no anchor row's `merchant_id` disagrees with its intent's.
- Adversarial evidence: Foreign seed/payment/attempt/customer/method/risk-context/source/event/cursor; identical digest/reason/time; absent versus foreign; disabled actor; role change; cached first/next page; and direct invalid insert.
- Status: PASS

## C54-HMC-001

- Lens: SECURITY_PRIVACY
- Invariant: The related API accepts no raw or HMAC identity and returns no customer ID, correlation digest, key material/version detail useful for probing, or raw email/phone/IP/device/session/card value; it exposes only bounded reason codes, matched-reason count, safe payment/decision summary, origin, times, and honest aggregate coverage.
- Failure sequence: A query parameter becomes a raw identity oracle, response/debug data exposes a digest, key-version gaps reveal rotation history per person, errors/timing distinguish foreign matches, or logs/metrics contain correlation material.
- Implementation: seed-derived lookup only, closed range/reason/page inputs, safe aggregate coverage enum, constant-shape not-found/zero/partial handling where practical, redacted DTOs, and bounded metrics without identity dimensions.
- Proof: `RelatedPaymentPrivacyTests` (6). No identity digest sourced from a real anchor row, and no plaintext email, phone, PAN, customer identifier, or payment-method identifier, crosses any of the four investigation routes. The relationship is published only as a reason code. The stored digest is proven not to be an unkeyed SHA-256 of the plaintext and to be `hmacSha256Lowercase("customer.email", email)`. The same value under three domains yields three distinct digests, and the domain boundary is proven unshiftable, which establishes the NUL delimiter. Every stored digest matches `[0-9a-f]{64}` and therefore leaks nothing about input length.
- Adversarial evidence: Raw/HMAC query/body/header attempts for all six identities; malformed/oversized reason/range/cursor; key current/retained/missing; zero match versus foreign match; multiple matches; sensitive exception; and browser copy/link behavior.
- Status: PASS

## C54-LIN-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each anchor binds merchant, payment, attempt, relation type, persisted HMAC, key version/family, observed time, traffic origin, and exact source lineage; `SAME_CUSTOMER` retains direct source-FK meaning, active/retained aliases remain distinguishable internally, and coverage reports missing source/key/alias/backfill truth without fabrication.
- Failure sequence: Current key version labels an old digest, a card HMAC is compared under an email domain, alias source points to another attempt, observed time uses query time, synthetic origin becomes live, or migration invents a digest from unavailable raw data.
- Implementation: V86 typed anchor rows and source derivation, explicit `correlation-v1` provenance for stable persisted correlation HMACs, versioned risk-context aliases, source/reason domain checks, and immutable coverage calculation.
- Proof: `PaymentRelationshipLineageTests` (7). Customer anchor digests match `customers.email_hash` and `phone_hash` of the named `source_id`; the card anchor digest matches `payment_methods.card_fingerprint`. Anchors follow the attempt, proven non-vacuously against a confirmed payment, and carry the attempt's `traffic_origin`. Anchors are unique per `(payment_attempt_id, relation_type, digest, identity_key_version)` because each attempt is its own observation; the read path is separately proven to report a sibling exactly once with a deduplicated reason list. `observed_at <= created_at`, `identity_key_version` is non-blank, and no anchor names a nonexistent customer, payment method, or attempt.
- Adversarial evidence: Stable/rotating key families; current/retained/missing/duplicate alias; card/email/phone/IP/device/session domain substitution; source present/foreign/missing; before/exact/after microsecond; live/synthetic; and ambiguous retained row.
- Status: PASS

## C54-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V86–V87 migrate fresh V1 and realistic retained V85 PostgreSQL safely, backfill only persisted safe correlation lineage, preserve all source/domain/event/projection truth, reject malformed/cross-tenant/ambiguous history before unsafe completion, install immutable guards and bounded indexes, and converge on repeated startup.
- Failure sequence: Backfill re-derives absent PII, duplicate aliases abort after partial unsafe DDL, source trigger accepts a foreign polymorphic ID, ordinary update/delete mutates history, query indexes omit tenant, or retained code reads half-installed anchors.
- Implementation: capability-named forward migrations with preflight constraints, tenant-qualified FKs, V87 source constraint trigger and update/delete guards, closed type/key/time/origin checks, deterministic set-based safe backfill, and readiness validation.
- Proof: `PaymentRelationshipMigrationPostgreSqlTests` (9) against real PostgreSQL 16. `trg_payment_investigation_relation_append_only` refuses both UPDATE and DELETE with `Investigation relation anchors are append-only`. The deferred constraint trigger refuses a well-formed anchor whose digest disagrees with its named source. The closed relation vocabulary and the relation-to-source pairing are both enforced. Digest length, whitespace padding, and blank key version are refused. The alias uniqueness constraint refuses a repeated observation for one attempt. Both protection triggers and both lookup indexes are asserted present, and every surviving anchor is reconciled against its source row.
- Adversarial evidence: Empty/populated V85; missing/duplicate/cross-tenant source; malformed type/HMAC/key/time/origin; mixed key aliases; interrupted/repeated migration; old/new application order; direct UPDATE/DELETE; and maximum retained volume.
- Status: PASS

## C54-API-001

- Lens: API_PRODUCT
- Invariant: Timeline, related-payment, and webhook-activity APIs use separate bounded cursors and explicit section/coverage status; related lookup defaults to seven aligned days, rejects more than 90, returns at most 50 payments per page, freezes `asOf`, and never silently truncates or broadens a request.
- Failure sequence: A timeline cursor is accepted by related lookup, unaligned/future/inverted range broadens, 50/51 behavior loses continuation, partial coverage appears as empty, or one transport failure erases canonical history.
- Implementation: typed request/response/cursor records, endpoint-specific cursor purposes/digests, UTC-aligned range validation, section-local dependency mapping, and explicit continuation/coverage metadata.
- Proof: `PaymentInvestigationNavigationApiTests` (9). All three navigation routes publish a cursor-paged collection with a `nextCursor` field present even when null, and each declares its own consistency or authority boundary. Every route refuses an unauthenticated reader, admits a VIEWER because navigation is read-only evidence, answers an unknown payment with `payment_investigation_not_found` plus a recovery action and a request identifier, refuses `limit` values of `0`, `-1`, `51`, and `abc`, honours the upper bound rather than merely accepting it, returns JSON with `X-Request-Id`, and refuses a malformed identifier before any lookup.
- Adversarial evidence: Range absent/zero/seven/90/over-90/inverted/future/unaligned; page 1/50/51; cursor cross-endpoint/tenant/payment/range/reason/direction; 400/401/403/404/409/429/503; and partial dependency combinations.
- Status: PASS

## C54-UI-001

- Lens: API_PRODUCT
- Invariant: The existing investigation route renders canonical timeline, separately labelled webhook activity, and safe related-payment pivots with keyboard/focus/accessibility support, source/coverage warnings, lossless continuation, and safe navigation; credentials, cursors, raw identities, and server-provided URLs never enter routable or persistent browser state.
- Failure sequence: Timeline and webhook attempts flatten together, infinite scroll hides loss, clicking hostile facts navigates externally, related pivot leaks a correlation value, a stale response replaces a newly selected payment, or partial failure blanks the page.
- Implementation: typed section components, explicit evidence labels, user-controlled pagination, allowlisted internal UUID routes, tenant/operator/payment query keys, abort/stale-response handling, memory-only credentials, and semantic status/focus restoration.
- Proof: `apps/analyst-dashboard-ui/src/paymentTimelineAndRelations.test.tsx` (7). The timeline renders in the server's canonical stream-version order and is never renumbered. Loading later stream versions appends rather than replaces, so no event is dropped or repeated. Partial relation coverage is stated plainly instead of being shown as a complete picture. A related payment renders with its reasons and pivots through in-app navigation. A server regression that echoed a correlation digest is proven not to reach the DOM, and the operator credential never reaches storage, cookies, the URL, or the page. A failed pivot names itself without blanking the timeline that did load, and both sections pass an axe-core scan.
- Adversarial evidence: Empty/full/multipage timeline; append and refresh; webhook failure; zero/partial/full relation coverage; rapid pivot/back; actor change; viewer/analyst/manager; keyboard-only; hostile link; and section 401/403/404/409/429/503.
- Status: PASS

## C54-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded metrics and safe logs expose timeline/relationship/webhook operation outcome, closed section/coverage state, latency/result-size bucket, cursor rejection, and query timeout without tenant, actor, payment, event, source, cursor, reason combination, HMAC, key version, request, URL, credential, payload, message, or exception text as dimensions.
- Failure sequence: One payment/event/digest creates a time series, foreign and absent outcomes become distinguishable tags, a raw cursor is logged, partial coverage increments complete success, or a stuck query is invisible.
- Implementation: closed operation/outcome/coverage enums, request-ID-only safe diagnostics, transaction/outcome-aware recording, bounded query timers, and readiness for schema/guard/coverage health.
- Proof: `PaymentInvestigationNavigationObservabilityTests` (6). Each of the three routes records latency under its own operation tag, so a timeline regression cannot hide behind healthy related-payment traffic. A missing payment is recorded as `NOT_FOUND` rather than `SUCCEEDED`, an invalid cursor as `INVALID` rather than `FAILED`, and an unauthenticated read as refused, all asserted as before-and-after deltas because the meter registry is shared. Result size is published as a bounded bucket. No meter under `thaap.decision_intelligence` carries a tenant, payment, credential, or identity-digest tag value.
- Adversarial evidence: Success/empty/partial/unavailable/timeout/tamper; high-volume IDs/digests/cursors; database/event mapper exception; missing trigger/index; source corruption; and client cancellation.
- Status: PASS

## C54-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 54 owns only canonical timeline, separately labelled webhook activity, same-merchant pivots, V86–V87, and their UI; it does not add raw-identifier search, cross-merchant counts/graphs, fraud features/scores, saved views, bulk actions, insights, model behavior, or source mutation authority.
- Failure sequence: A relationship becomes a feature, network counts leak, a mapper replays a command, a demo writes anchors directly, or later UI/API routes are presented as Chapter 54 proof.
- Implementation: dependency/route/migration/source scans, read-only services, explicit Phase 55/56/7/8 ownership links, and public demos using ordinary source APIs plus read navigation only.
- Proof: `PaymentInvestigationNavigationScopeBoundaryTests` (5). No navigation route accepts POST, PUT, PATCH, or DELETE. Reading all three routes leaves a database fingerprint over events, anchors, attempts, and ledger entries completely unchanged, so investigating never alters what is investigated. No navigation response publishes a model, network, consortium, saved-view, bulk, or insight field. Source scans prove neither risk decisioning nor authorization reads `payment_investigation_relation_anchors`, so correlation never becomes silent policy. The public demo mutates only over HTTP and its single database channel is guarded read-only.
- Adversarial evidence: Direct SQL/hidden fixtures, raw identity query, foreign merchant graph, decision-service dependency, bulk/insight endpoint, model-shaped field, and anchor-as-authorization attempt.
- Status: PASS

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
