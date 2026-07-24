# Chapter 28 Invariant Proof Matrix

- Chapter: Chapter 28 — Payment Authentication Completion
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

This matrix was drafted on 2026-07-13 after Chapter 27 closure and before any
Chapter 28 production code or migration. Names below are obligations until the
implementation and real-boundary proof reconcile them.

## C28-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Fraud REQUEST_3DS and processor REQUIRES_ACTION each create one source-bound challenge for the same PaymentAttempt and authorize no new funds before successful completion.
- Failure sequence: A pending challenge carries approved amount, uses another attempt, or loses whether fraud or processor requested it.
- Implementation: V30 and `AuthenticationChallengeService` store source-distinct payment, decision, authorization, expiry, and credential lineage; V31 validates the semantic edges.
- Proof: `AuthenticationChallengeContractTests` proves fraud and processor sources with zero approved funds and zero ledger value before completion; the live demo reconciles both cohorts.
- Adversarial evidence: Source substitution, wrong attempt/intent/merchant/decision/authorization, and premature capture.
- Status: PASS

## C28-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Successful completion continues processor work exactly once without fraud re-evaluation and cannot enter a second challenge loop.
- Failure sequence: Completion creates another decision/attempt, calls the processor twice, or accepts a repeated REQUIRES_ACTION result as a new challenge.
- Implementation: Completion locks and continues the same attempt, sets `authenticated=true` at the processor boundary, never re-runs fraud, and rejects a repeated `REQUIRES_ACTION` result.
- Proof: Contract and simulator catalog tests prove one attempt/decision/authorization, exact replay, approval/decline outcomes, and rollback of the hostile repeated-challenge fixture.
- Adversarial evidence: Same/distinct keys, duplicate completion, rollback/retry, and hostile repeated step-up response.
- Status: PASS

## C28-PAY-003

- Lens: PAYMENT_FRAUD
- Invariant: Authentication success, failure, expiry, and cancellation produce truthful attempt/intent/authentication states; success honors AUTOMATIC versus MANUAL capture.
- Failure sequence: Failure authorizes funds, expiry remains completable, cancellation leaves a usable challenge, or manual capture auto-captures.
- Implementation: Closed challenge, attempt, intent, authorization, and capture transitions execute under PaymentIntent-first locking.
- Proof: H2 and PostgreSQL contracts cover success, failure, exact expiry, cancellation, automatic capture, manual capture, authenticated approval, and authenticated decline with exact row/ledger reconciliation.
- Adversarial evidence: Before/at/after expiry, all processor outcomes after success, both capture methods, and cancel ordering.
- Status: PASS

## C28-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Challenge creation, read, completion, secret identity, processor continuation, authorization, ledger, audit, and webhook lineage remain within one merchant.
- Failure sequence: A challenge ID or completion credential from one tenant mutates another tenant's attempt or money truth.
- Implementation: Tenant-scoped repositories, copied merchant lineage, composite foreign keys, and V31 semantic triggers bind every edge.
- Proof: Foreign-tenant API completion and PostgreSQL merchant/attempt/decision/authorization/source substitutions are missing-equivalent or constraint-rejected without side effects.
- Adversarial evidence: Foreign merchant, attempt, challenge, decision, authorization, secret, and idempotency values.
- Status: PASS

## C28-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Completion requires the one-time simulator secret issued for that challenge; challenge ID, merchant API key, or risk-operator credential alone is insufficient.
- Failure sequence: A guessed/replayed/foreign/rotated secret completes authentication or disclosure occurs on a normal read.
- Implementation: Reveal-once credentials use strict grammar, SHA-256 plus purpose-HMAC identity, constant-time verification, and AES-GCM protected bounded creation replay.
- Proof: Contract, cipher, idempotency, and readiness tests cover pending and terminal secret checks, wrong/foreign values, authenticated replay, AAD/ciphertext/nonce substitution, rotation, and missing keys.
- Adversarial evidence: Prefix-only, Unicode, whitespace, overlong, old-key, ciphertext swap, nonce swap, and key-version substitution.
- Status: PASS

## C28-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Challenge surfaces contain no PAN, CVC, buyer PII, API/risk keys, raw completion secret after reveal, arbitrary callback payload, or unbounded log/metric value.
- Failure sequence: Secret or payment data leaks through database plaintext, response, audit, webhook, log, error, or metric.
- Implementation: Safe DTOs, protected replay envelopes, hashes/HMACs, closed enums, strict Jackson handling, and bounded exception logging expose only safe references.
- Proof: Contract scans prove the raw secret is absent from challenge, audit, webhook, and idempotency plaintext and from normal reads; API error contracts reject malformed/unknown input without database detail.
- Adversarial evidence: Unknown fields, nested/free text, malformed JSON/media, constraint failures, and adapter exceptions.
- Status: PASS

## C28-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Challenge terminal state, payment/attempt, processor authorization, capture ledger, audit, webhook, and idempotency completion commit atomically or roll back together.
- Failure sequence: A consumed credential survives without payment truth, money survives without challenge success, or a rollback is cached complete.
- Implementation: PaymentIntent-first transactions, deterministic processor identity, flushed enforcement boundaries, and after-completion metrics cover every local write.
- Proof: PostgreSQL forced failures at challenge, attempt, intent, authorization, ledger, audit, webhook, and idempotency families roll back all local truth and permit a clean retry.
- Adversarial evidence: Challenge, payment, authorization, ledger, audit, webhook, idempotency, and processor ambiguity positions.
- Status: PASS

## C28-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Duplicate completion, completion/cancellation, and completion/expiry serialize to one terminal challenge/payment result.
- Failure sequence: Two completions authorize/capture twice, cancellation and success both win, or expiry races into accepted late completion.
- Implementation: PaymentIntent, attempt, and challenge pessimistic locks plus `now < expiresAt` revalidation serialize every terminal path.
- Proof: PostgreSQL tests force and observe completion/cancel in both arrival orders, competing completions, and expiry-first completion, then assert one terminal result and reconciled money truth.
- Adversarial evidence: Same/distinct keys, same/different result bodies, before/at/after expiry, restart, and retry.
- Status: PASS

## C28-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Authentication evidence is append-only, source-distinct, point-in-time lineage; it never rewrites FraudDecision, feature snapshot, rule result, or review override history.
- Failure sequence: Completion changes REQUEST_3DS/REVIEW to ALLOW or attaches authentication evidence to another decision.
- Implementation: Copied semantic keys plus V31 insert/update/delete triggers bind source to its original decision or authorization and make terminal evidence append-only.
- Proof: Fresh/retained migration tests and PostgreSQL mutation tests reject source swap, terminal update, and delete while preserving Chapters 26–27 history.
- Adversarial evidence: Fraud versus processor source swaps, wrong decision/action, wrong authorization status, and update/delete.
- Status: PASS

## C28-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Authentication timestamps and result codes distinguish requested, attempted, succeeded, failed, expired, and canceled truth at microsecond precision.
- Failure sequence: Rounding changes expiry eligibility, result time predates request, or terminal state lacks the matching timestamp/result.
- Implementation: Microsecond `TIMESTAMP WITH TIME ZONE` fields and closed V30/V31 terminal-shape constraints encode request, expiry, and result time.
- Proof: Exact-expiry H2 proof, PostgreSQL expiry race proof, migration precision checks, and terminal mutation rejection cover the meaningful time partitions.
- Adversarial evidence: Null/multiple terminal timestamps, future request, result before request, and precision truncation.
- Status: PASS

## C28-API-001

- Lens: API_PRODUCT
- Invariant: Confirm/review responses expose a safe `nextAction`; completion is a strict idempotent endpoint with stable errors and no false claim of real ACS/network behavior.
- Failure sequence: Pending action is undiscoverable, replay bytes drift, arbitrary callback fields are accepted, or simulator completion is marketed as network authentication.
- Implementation: Additive `nextAction` and strict completion DTOs expose source, challenge ID, expiry, local path, and reveal-once simulator secret without claiming real ACS behavior.
- Proof: Controller/error/idempotency contracts plus the live fraud- and processor-source demos prove exact same-key and authenticated distinct-key terminal replay.
- Adversarial evidence: Unknown/missing/changed fields, wrong method/media, malformed IDs/times, and cross-source completion.
- Status: PASS

## C28-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish challenge creation/completion source, result, commit/rollback, expiry, and denial with closed low-cardinality dimensions and no secret/resource values.
- Failure sequence: Authentication failures are invisible, rolled-back success looks committed, or secrets/IDs become metric tags or log content.
- Implementation: Authentication metrics use closed source/result/reason/transaction dimensions and emit commit-sensitive counters only after transaction completion.
- Proof: `AuthenticationMetricsTests` proves committed, rolled-back, and denial partitions with exact bounded tags; durable and log contracts prohibit raw secret values.
- Adversarial evidence: Wrong secret, expired completion, constraint rollback, processor error, and concurrent loser.
- Status: PASS

## C28-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Startup fails closed when active or replayable challenge key versions cannot be hashed/decrypted, while configured rotation preserves bounded old-key completion and replay.
- Failure sequence: Missing key starts green, old ciphertext becomes unreadable without warning, or an old secret remains valid outside its boundary.
- Implementation: Startup inventories unexpired pending HMAC versions and active protected replay envelopes against configured current/previous keyrings.
- Proof: Readiness and cipher tests prove current/previous rotation, missing-key failure, malformed envelope failure, exact decrypt, and authenticated substitution failure.
- Adversarial evidence: Empty/malformed/duplicate version config, ciphertext corruption, old-only database, and partial rotation.
- Status: PASS

## C28-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V29 upgrades install challenge semantics without rewriting Chapters 26–27 history; public fraud- and processor-source demos reconcile durable truth.
- Failure sequence: V30 changes old rows, weakens triggers, skips a source/capture branch, or closes on unit tests alone.
- Implementation: Forward-only V30 adds the challenge contract and V31 protects history without rewriting earlier chapter rows.
- Proof: Fresh V1→V31, retained V29→V30 test cohort, retained live V30→V31 upgrade, Hibernate validation, 404 backend tests, 86 Python tests, and both live demos pass.
- Adversarial evidence: Old approval/fraud rows, malformed seed data, checksum edits, skipped pending rows, and closure overclaim.
- Status: PASS

## Closure review record

- Plan-to-implementation-to-proof traceability: COMPLETE on 2026-07-13.
- Temporally later hostile pass: COMPLETE after the initial green suite; it found and fixed terminal-secret validation, append-only terminal history, authenticated decline lineage, and repeated-challenge rollback before this completion claim.
- Boundary review: COMPLETE across H2 contracts, PostgreSQL constraints/triggers/forced waits, key/cipher exact-byte tests, and live retained infrastructure.
- Escaped findings: NONE. All findings occurred before the first Chapter 28 completion claim.
