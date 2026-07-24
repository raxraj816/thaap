# Chapter 32 Invariant Proof Matrix

- Chapter: Chapter 32 — Chargebacks, Dispute Offsets, And Evidence Packets
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

This matrix was frozen on 2026-07-14 after Chapter 31 closure and before any
Chapter 32 production code, migration, chargeback API, ledger, label, webhook,
or evidence-packet change. V1 is explicitly merchant-ingested simulator truth;
it does not claim acquirer, card-network, or independently verified dispute
truth.

The `Implementation` and `Proof` sentences inside each frozen row preserve the
pre-implementation plan verbatim as provenance. The verification supplement
below records what was actually built and executed; it is the evidence used to
move every row to `PASS`.

## C32-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: A chargeback is valid only against a same-tenant captured automatic-payment cohort, uses the original capture currency, and is capped at the exact remaining captured value after final refund and prior dispute allocations.
- Failure sequence: Uncaptured, manual-authorization, failed, foreign, wrong-currency, zero, partial, over-capture, already-disputed, or fabricated payment lineage creates dispute or money truth.
- Implementation: Planned strict merchant-authenticated ingestion, PaymentIntent-first locking, capture/authorization lineage validation, and exact remaining-liability arithmetic using integer minor units.
- Proof: Planned public and direct-SQL captured/refunded/disputed/state × amount/currency/tenant matrices including zero, one, ceiling, ceiling-plus-one, and numeric overflow.
- Adversarial evidence: Missing IDs, foreign IDs, stale payment state, retained legacy capture, multiple refunds, duplicate external event, and malformed amount/currency.
- Status: PASS

## C32-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Full refund first yields `OFFSET_BY_REFUND`, zero new merchant debit, and leaves the PaymentIntent `REFUNDED`; chargeback first allocates the remaining full liability, moves it to `DISPUTED`, and makes a later full refund invalid.
- Failure sequence: The merchant is debited after a full refund, both refund and chargeback allocate the same captured value, or a zero allocation changes payment state to DISPUTED.
- Implementation: Planned one immutable allocation result per chargeback under the PaymentIntent lock, explicit zero/non-zero states, and refund eligibility calculated from the same allocation boundary.
- Proof: Planned captured, fully refunded, and disputed public cohorts plus refund-first/chargeback-first forced PostgreSQL interleavings with exact final payment/refund/chargeback/allocation/ledger postconditions.
- Adversarial evidence: Same-millisecond operations, different idempotency keys, rollback after allocation, retry after loser response, and existing final offsets.
- Status: PASS

## C32-PAY-003

- Lens: PAYMENT_FRAUD
- Invariant: Fraud reason codes create exactly one immutable FRAUD label with explicit chargeback source, required fraud type, evidence time, and later-or-equal availability time; non-fraud dispute reasons create no fraud label.
- Failure sequence: Every dispute becomes fraud, a fraud chargeback lacks type, a non-fraud reason poisons training labels, or `available_at` predates ingestion and leaks future knowledge.
- Implementation: Planned closed reason taxonomy mapped to nullable fraud type, source-qualified uniqueness, append-only FraudLabel linkage, and server-assigned availability time.
- Proof: Planned every reason × label/no-label partition, duplicate/out-of-order ingestion, evidence/availability boundaries, dataset-time assertions, and unchanged original FraudDecision/FeatureSnapshot.
- Adversarial evidence: Client-supplied availability/source/type, contradictory prior labels, absent decision, retry, and retained label cohorts.
- Status: PASS

## C32-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Chargeback, external identity, payment/attempt/authorization/refund/capture lineage, allocation, ledger, label, evidence packet, audit, webhook, and reads remain bound to one merchant; foreign identifiers are missing-equivalent.
- Failure sequence: One merchant ingests or reads another merchant's dispute, links foreign payment evidence, or uses the same external event key to collide across tenants.
- Implementation: Planned merchant-qualified API lookup, composite same-tenant foreign keys, copied tenant columns, and tenant-scoped external-event uniqueness.
- Proof: Planned pairwise API and direct-SQL substitutions across every linked resource plus same external key in two merchants.
- Adversarial evidence: Valid foreign credential, copied UUID, same payment-shaped rows, foreign evidence packet route, and ledger source substitution.
- Status: PASS

## C32-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: V1 ingestion and evidence creation require a valid active merchant API credential and record `SIMULATOR_MERCHANT_INGESTED`; no operator credential or payload can impersonate an acquirer/network source.
- Failure sequence: Missing, disabled, foreign, or risk-operator credential creates dispute truth, or caller-supplied source attestation is accepted.
- Implementation: Planned existing merchant tenant boundary, server-owned source enum, strict DTOs, and audit actor/source mapping.
- Proof: Planned credential kind/status/tenant matrix, disabled-key behavior, unknown source fields, audit/webhook source assertions, and exact replay.
- Adversarial evidence: Merchant ID header substitution, operator key in merchant header, unknown JSON, source string injection, and concurrent disable where supported.
- Status: PASS

## C32-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Evidence packets accept only bounded allowlisted structured facts and safe existing references; no arbitrary JSON, free text, uploaded document, raw PAN/CVV, credential, identity HMAC, address, email, phone, IP, or idempotency key is persisted or emitted.
- Failure sequence: A merchant turns evidence into an unclassified document/PII store or a rejected value leaks through errors, logs, metrics, audit, webhook, or replay bytes.
- Implementation: Planned closed evidence codes and booleans/enums/timestamps/reference IDs, strict JSON, size/count limits, safe response/event DTOs, and no generic map/blob column.
- Proof: Planned full valid field grammar, unknown/nested/free-text/oversize/Unicode/control payloads, unique canary scans across HTTP/PostgreSQL/logs/metrics/audit/webhook/idempotency, and tenant-safe reference checks.
- Adversarial evidence: JSON object/array smuggling, duplicate keys, long strings, raw card/email/IP canaries, and exception text.
- Status: PASS

## C32-API-001

- Lens: API_PRODUCT
- Invariant: `POST /chargebacks`, `GET /chargebacks/{id}`, and `POST /chargebacks/{id}/evidence_packets` use strict contracts, required fenced idempotency for mutations, byte-exact replay, and missing-equivalent tenant reads.
- Failure sequence: Missing key, changed-body replay, unsupported media/method, unknown field, duplicate external event under a new idempotency key, or foreign read produces extra side effects or disclosure.
- Implementation: Planned existing `IdempotencyService`, strict request records/enums, merchant-qualified repositories, and external-event deduplication independent of request-key replay.
- Proof: Planned same-key exact replay, changed-body conflict, same external key same/different bytes and keys, method/media/JSON partitions, not-found equivalence, and terminal replay.
- Adversarial evidence: Empty/oversize key, path/body ID mismatch, duplicate JSON key, stale reservation, and concurrent duplicates.
- Status: PASS

## C32-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Chargebacks, offset allocations, evidence packets, and their payment/authorization/refund/decision/label/source/timestamp references are append-only and point-in-time reproducible.
- Failure sequence: Updating reason, amount, deadline, allocation, source, or evidence rewrites historical financial, analyst, or training truth.
- Implementation: Planned additive V43/V44 tables, append-only PostgreSQL triggers, copied immutable fields, semantic same-tenant constraints, and no mutable status column without an event row.
- Proof: Planned direct UPDATE/DELETE rejection, historical reads after later refund/packet creation, semantic substitution rejection, and migration/entity validation.
- Adversarial evidence: Actor/source rewrite, due-date rewrite, allocation relink, label relink, forged created time, and delete cascade attempt.
- Status: PASS

## C32-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Evidence occurrence, merchant ingestion, system availability, chargeback receipt, and evidence due times are distinct server-validated concepts; training eligibility uses `available_at`, never the earlier evidence or received time.
- Failure sequence: Backdated client evidence becomes available to historical training, due time precedes receipt, or an out-of-order event rewrites an earlier availability timestamp.
- Implementation: Planned explicit timestamp columns, server-owned `available_at`, bounded received/evidence/due ordering grammar, and immutable label linkage.
- Proof: Planned before/at/after timestamp table, client clock extremes, duplicate older/newer events, and point-in-time label query assertions.
- Adversarial evidence: Far-future/past timestamps, precision truncation, timezone offsets, equal boundaries, and retry after server time advances.
- Status: PASS

## C32-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Refund and chargeback allocation lock PaymentIntent before refund/chargeback/allocation/ledger rows, so exactly one competing operation owns each captured minor unit without deadlock or double accounting.
- Failure sequence: Both operations read CAPTURED, both commit full liability, ledger posts twice, or opposite lock order deadlocks.
- Implementation: Planned reuse of the PaymentIntent pessimistic lock as the first money-state lock, query final allocations while held, and flush all local truth in one transaction.
- Proof: Planned deterministic refund-first and chargeback-first forced PostgreSQL interleavings, repeated races, lock-order evidence, and exact balanced final postconditions.
- Adversarial evidence: Different keys, rollback of the winner, retry of loser, audit/webhook failure, and already-present zero allocation.
- Status: PASS

## C32-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Tenant/source/external-event deduplication has one committed winner; concurrent identical events converge to byte-consistent truth and conflicting bytes cannot create a second chargeback, label, allocation, ledger group, audit, webhook, or packet.
- Failure sequence: Check-then-insert races duplicate financial truth or an out-of-order conflicting event silently reuses prior identity.
- Implementation: Planned database unique identity plus locked lookup and canonical request fingerprint stored with the immutable chargeback.
- Proof: Planned forced identical/conflicting concurrent requests at PostgreSQL, same/different idempotency keys, one-winner counts, and exact replay/conflict responses.
- Adversarial evidence: Same external key across tenants, timestamp-only change, JSON field order, failed winner, and later retry.
- Status: PASS

## C32-LED-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Every non-zero dispute allocation posts exactly one balanced ledger group for the allocation amount with original PaymentIntent/capture and Chargeback source lineage; zero allocations post no ledger rows.
- Failure sequence: Unbalanced debit/credit, wrong amount/currency/account/direction, orphan source, duplicate group, or zero-value rows commit.
- Implementation: Planned extension of the mature `LedgerService` and closed account/source enums, integer minor units, and flush/balance validation before audit/webhook/idempotency completion.
- Proof: Planned zero/non-zero/ceiling tables, exact account-direction-source assertions, direct-SQL semantic guards, and failure injection for each ledger row/flush boundary.
- Adversarial evidence: Currency mismatch, integer overflow, duplicate source group, partial insert, source substitution, and retry.
- Status: PASS

## C32-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Chargeback, allocation, payment transition, ledger, optional label, audit, webhook event/delivery, and idempotent response commit atomically or leave no local Chapter 32 truth.
- Failure sequence: Any boundary throws after an earlier write and a retry observes partial money, fraud, notification, or replay state.
- Implementation: Planned one Spring transaction after fenced reservation, explicit flush ordering, existing local webhook transaction, and after-completion metrics.
- Proof: Planned forced failures before/after every repository save/flush, label, payment update, both ledger entries, audit, webhook event/delivery, and idempotency completion.
- Adversarial evidence: Constraint violation, serializer failure, duplicate event, rollback-only transaction, and same-key retry after failure.
- Status: PASS

## C32-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish ingestion, deduplication, reason class, allocation result, evidence deadline/result, label creation, commit/rollback, and authorization denial using closed low-cardinality dimensions without identifiers or evidence values.
- Failure sequence: Rolled-back debit looks committed, zero offset is invisible, deadline breach is hidden, or merchant/payment/external-event/evidence/credential/idempotency data enters tags or unsafe logs.
- Implementation: Planned after-completion counters/timers with closed operation/result/reason_class/allocation/status/transaction dimensions and bounded safe log fields.
- Proof: Planned exact meter/tag/cardinality tests, commit/rollback fixtures, captured logs, and unique sensitive canary scans.
- Adversarial evidence: Duplicate, foreign denial, overflow, overdue evidence, rollback, and hostile evidence payload.
- Status: PASS

## C32-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Evidence packets can be created only before or exactly at their immutable due time, expose explicit `DRAFT` truth, and never claim submission, representment, or dispute outcome in Chapter 32.
- Failure sequence: Overdue evidence is accepted, equal boundary is inconsistently handled, or the product claims evidence was sent to a network/acquirer.
- Implementation: Planned server-clock deadline comparison under chargeback lock, closed packet status/source language, and no submission/outcome API.
- Proof: Planned before/at/after due-time fixtures, restart/precision behavior, duplicate packet policy, public vocabulary assertions, and zero external calls.
- Adversarial evidence: Client-supplied status/outcome/submitted time, timezone offset, nanosecond boundary, expired retry, and clock movement.
- Status: PASS

## C32-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V42 upgrades add empty-by-default dispute truth without changing Chapters 1–31 payment, refund, ledger, fraud, review, authentication, context, control, audit, webhook, or idempotent response bytes.
- Failure sequence: Migration fabricates chargebacks/labels, mutates terminal payment/refund history, weakens prior immutability, changes webhook consent, or current binary cannot read retained V42 rows.
- Implementation: Planned forward-only additive V43/V44 migrations and explicit opt-in for new webhook event types.
- Proof: Planned fresh V1→current, retained V42→current byte comparisons, Hibernate validation, full suites, current-binary retained startup, and no fabricated rows/subscriptions.
- Adversarial evidence: Captured/refunded/review/authentication/control cohorts, malformed retained seed, missing historical key, checksum change, and legacy event subscriptions.
- Status: PASS

## C32-PROD-001

- Lens: API_PRODUCT
- Invariant: Public language consistently describes chargebacks and evidence as merchant-ingested simulator records, distinguishes financial allocation from fraud classification, and shows zero-offset/refund state without implying network-confirmed liability or representment.
- Failure sequence: A response, audit, webhook, demo, README, or UI-shaped DTO calls simulator input verified network truth, equates dispute with fraud, or hides the zero-liability cohort.
- Implementation: Planned explicit source attestation, closed reason/allocation/status fields, safe resource lineage, and public captured plus fully-refunded demos.
- Proof: Planned response/webhook/audit vocabulary contracts, fraud/non-fraud and zero/non-zero demos, no-external-call assertions, and source-of-truth reconciliation.
- Adversarial evidence: Fraud reason with zero allocation, non-fraud non-zero dispute, full refund, duplicate event, and evidence packet creation.
- Status: PASS

## Verification evidence supplement

### Payment, fraud, tenant, and API boundaries

- `ChargebackAndDisputeContractTests` runs on Testcontainers PostgreSQL and
  proves all eight reason codes, one/ceiling/ceiling-plus-one and zero amount
  behavior, currency and automatic-capture eligibility, captured/refunded/
  disputed terminal state, same-key byte replay, changed-body conflict,
  independent external-event convergence, strict machine-key and unknown-field
  rejection, missing-equivalent foreign reads, append-only history, and exact
  simulator source language.
- The fraud reason table proves the exact mapping to
  `STOLEN_PAYMENT_CREDENTIAL`, `ACCOUNT_TAKEOVER`, `CARD_TESTING`, and
  `FIRST_PARTY_MISUSE`; every non-fraud reason proves zero label rows. Label
  `available_at` is the server-owned chargeback availability instant and never
  the earlier evidence instant.
- V43 supplies composite merchant-qualified foreign keys for payment,
  attempt, authorization, refund, decision, chargeback, label, evidence, and
  ledger lineage. V44 rejects cross-lineage, state/status, allocation,
  authorization-derived evidence, and fraud-type substitutions at PostgreSQL.

### Transaction, concurrency, and ledger boundaries

- Deterministic PostgreSQL forced orderings hold the PaymentIntent lock across
  an uncommitted outer transaction. Refund-first makes the chargeback query
  visibly wait, then commits one `OFFSET_BY_REFUND` allocation, zero chargeback
  ledger rows, and a `REFUNDED` intent. Chargeback-first makes refund visibly
  wait, then commits one balanced two-entry allocation and rejects the refund
  against `DISPUTED`.
- A separate forced merchant-lock ordering proves simultaneous identical
  external events converge to byte-identical response truth with exactly one
  chargeback, allocation, ledger group, fraud label, and audit row.
- Injected allocation, ledger, label, webhook-routing, and audit failures prove
  rollback of chargeback, allocation, payment state, ledger, label, webhook,
  audit, and idempotency reservation. Existing idempotency and ledger suites
  retain their reservation/completion and balanced-group boundary proof.

### Evidence, privacy, telemetry, migration, and product boundaries

- `ChargebackServiceDeadlineTests` uses a fixed microsecond clock to prove the
  exact due instant succeeds and one microsecond after fails. PostgreSQL
  contract tests independently prove before/after behavior, one DRAFT packet,
  and AVS/CVC/3DS derivation from immutable authorization truth.
- Evidence accepts three booleans only; strict Jackson rejects unknown source,
  notes, nested/free-text expansion, and the external event key accepts only a
  128-character bounded machine grammar. Response, audit, webhook, and metric
  shapes contain no raw PAN/CVV, contact data, IP, credential, or idempotency
  key fields.
- `ChargebackMetrics` records only closed reason class, allocation, result, and
  transaction tags after completion. Contract proof enumerates the tag keys
  and distinguishes expired rolled-back evidence from committed creation.
- Fresh V1-to-V44 migration and retained V42-to-V44 migration both pass with
  Hibernate validation. The retained cohort proves empty new tables, unchanged
  Chapters 1-31 review/decision rows, unchanged webhook consent, all six new
  semantic/history triggers, and no fabricated dispute truth.
- `demo-chargeback-lifecycle.py` passed against the retained local PostgreSQL
  volume after V42-to-V44 startup. Its public API and read-only reconciliation
  proved `DISPUTED|2|1` for the captured fraud cohort and `REFUNDED|0|0` for
  the fully refunded non-fraud cohort, plus byte replay, independent dedup, and
  bounded pre-deadline DRAFT evidence.

### Review disposition

- Plan-to-implementation traceability: PASS.
- Temporally separate hostile sequence review: PASS. It re-opened and hardened
  machine-key grammar, automatic-capture eligibility, single allocated dispute,
  reason-to-type mapping, authorization-derived evidence, append-only SQL,
  transaction rollback, retained migration, and forced lock ordering.
- Custom infrastructure: none. Spring transactions/idempotency, PostgreSQL,
  strict Jackson validation, the existing ledger, audit, webhook, and label
  components are reused.
- Debt and escaped findings: none. All findings occurred before the first
  completion claim and were fixed with executable proof.
