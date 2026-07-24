# Chapter 20 Closure Dossier

- Chapter: Chapter 20 — Deterministic Processor Catalog And Phase 2 Closure
- Chapter number: 20
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_20_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

- One immutable runtime catalog defines mapped approval, issuer decline,
  insufficient funds, suspected fraud, CVC failure, both AVS failures,
  3DS-required, and explicit unknown-card approval.
- Processor outcomes remain independent from THAAP FraudDecision truth. Every
  Chapter 20 confirm honestly retains `ALLOW` / `RULES_NOT_ENABLED`.
- Only approved authorization creates capture eligibility; automatic capture,
  manual capture/void, and full refund preserve balanced append-only accounting.
- Payment state, authorization/void/refund lineage, ledger, audit, webhook, and
  idempotency truth commit or roll back coherently inside the merchant boundary.
- Webhook delivery remains immutable, signed, SSRF-safe, at least once,
  retryable, inspectable, and independent from originating payment truth.
- A fresh loopback demo can reproduce all nine processor partitions and publish
  only sanitized, atomic, owner-only evidence.
- The canonical Phase 2 V1 simulator boundary is complete. Disputes remain
  Phase 4, partial/fee accounting retains its entry trigger, and payout
  settlement remains outside V1.

## Failure inventory

| Boundary | Hostile sequence |
| --- | --- |
| Catalog | Runtime mapping, documentation, test expectations, or unknown default drift independently |
| Fraud/processor | Processor `SUSPECTED_FRAUD` is presented as a THAAP `BLOCK`, or a non-`ALLOW` decision reaches the processor |
| Capture | Decline/3DS creates capture eligibility, automatic capture lacks ledger truth, or manual capture duplicates |
| Cancel/void | Pre-authorization cancel invents processor evidence, approved cancel omits void, or capture and cancel both win |
| Refund | Refund precedes capture, duplicates/over-refunds, lacks reversal entries, or rewrites original fraud lineage |
| Transaction | Ledger, audit, webhook, or lineage persistence fails after status mutation and leaves partial truth |
| Concurrency | Capture/cancel, refund/refund, worker/worker, or endpoint/worker transactions commit incompatible outcomes |
| Tenant | Merchant A substitutes Merchant B payment, attempt, refund, ledger, endpoint, event, delivery, or cursor IDs |
| Privacy | Demo, audit, webhook, log, or report emits submitted card values, PII, API/signing secrets, fingerprints, or idempotency material, including credential forwarding through an HTTP redirect |
| Webhook | Retry changes bytes, DNS reaches a prohibited target, disable loses before dispatch, or duplicate delivery applies two merchant effects |
| Migration | V1-to-V9 or supported legacy upgrade invents lifecycle, refund, event, or attempt evidence |
| Operations | Demo relies on stale rows, readiness skips executable tests, or source documents make contradictory Phase 2 claims |

## Boundary family evidence

| Family | Partitions | Proof |
| --- | --- | --- |
| Processor catalog | Eight mapped outcomes plus unmapped default approval; status, network, code, amounts, CVC, both AVS fields, 3DS, and fraud action | `SimulatorProcessorCatalogTests`, `PaymentIntentConfirmContractTests`, and fresh Chapter 20 runner |
| Lifecycle | Approved/declined/action-required crossed with automatic/manual capture and capture/cancel/refund attempts | Capture, cancel/void, refund, lifecycle, rollback, and fresh Chapter 17–18 proof |
| Concurrency | Capture/cancel, refund/refund, webhook claim/disable, retry-finalize/disable, route/disable, and rotate/disable orderings | Forced PostgreSQL suites with lock-wait evidence and durable postconditions |
| Report privacy | Nine card values, API key, signing secret, buyer identity, and payment-method/intent/confirm idempotency keys | `ProcessorOutcomeCatalogDemoTests.test_sensitive_report_scan_rejects_card_credentials_and_idempotency` plus fresh report scan |
| Demo origin/output | Loopback IPv4/IPv6 versus remote, path, user-info, query, and non-HTTP origins; 301/302/303/307/308 rejection before a second request; atomic success versus no report on failure | Chapter 20 Python origin, real-server redirect, and atomic-write tests |
| Webhook protocol | Authenticated bytes, identity, grammar, size, timeout, DNS, retry, stale worker, disable, duplicate, and restart partitions | Chapter 19 socket/security/concurrency/callback suites rerun in full readiness |
| Migration | Clean schema plus supported/invalid V5–V9 parent/child state partitions on H2 and PostgreSQL | `DatabaseMigrationTests` and `PostgreSqlMigrationTests` |
| Source status | Phase 2 implementation versus Phase 4 disputes, trigger-based partial/fee accounting, Phase 5 outbox, and post-V1 payouts | README/master-plan search, debt checker, and closure checker |

The fresh hostile pass found one pre-closure weakness: the first report scan
covered only confirm idempotency keys and did not explicitly include the
one-time webhook signing secret. The full sensitive-output family now covers
all three generated keys per fixture and the exact returned secret. Focused
tests and the final fresh demo passed after that correction.

The post-closure merge review found `ER-0021`: Python's default redirect handler
preserved custom credential headers while following a loopback response to a
different origin. The runner now installs a fail-closed redirect handler, and
real loopback servers prove every supported 30x status creates no request at
the redirect target.

## Enforcement boundaries

- `SimulatorProcessorCatalog` owns immutable safe-metadata mapping;
  `SimulatorProcessorAdapter` only creates request-specific authorization
  evidence from the resolved outcome.
- PaymentIntent state guards, approved Authorization lookup, row locks,
  append-only AuthorizationVoid/Refund lineage, and ledger constraints enforce
  money lifecycle truth.
- `IdempotencyService` shares the mutation transaction with response completion;
  injected persistence failures prove rollback at fraud, ledger, audit, and
  webhook boundaries.
- API-key-derived MerchantContext, scoped repositories, composite foreign keys,
  and tenant-bound secret AAD enforce isolation.
- Unique FraudDecision/FeatureSnapshot constraints bind one immutable pair to
  each successful attempt before processor execution.
- Chapter 19 event, signing, transport, lease, endpoint-lock, and callback
  boundaries enforce webhook truth.
- The Chapter 20 runner accepts only loopback origins, rejects every redirect
  before a follow-up request can inherit credentials, fails closed on every
  mismatch, performs read-only local audit reconciliation, scans sensitive
  values, fsyncs a temporary owner-only file, and atomically replaces the report
  only after every invariant passes.

## Exact proof

- Focused Java:
  `mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository
  -Dtest=SimulatorProcessorCatalogTests,PaymentIntentConfirmContractTests test`
  passed 21 tests.
- Full backend:
  `mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test`
  passed 228 tests with zero failures, errors, or skips.
- Repository Python:
  `python3 -m unittest discover -s scripts -p '*_tests.py'`
  passed 49 tests.
- Catalog Python:
  `python3 -m unittest scripts/processor_outcome_catalog_demo_tests.py`
  passed five tests covering catalog enumeration, origin partitions, all 30x
  redirect statuses, sensitive values, and atomic owner-only output.
- Existing forced PostgreSQL suites cover one capture/cancel winner, one
  refund winner, exclusive webhook claims, and all endpoint/worker orderings.
- Matrix: 15 entries across all eight required lenses are `PASS`.

## Migration evidence

- No Chapter 20 schema change exists.
- Full H2 and PostgreSQL suites reran clean V1-to-V9 migration, Hibernate
  validation, Chapter 17 state compatibility, Chapter 18 capture-lineage
  backfill/invalid-refund rejection, and Chapter 19 legacy event/attempt
  boundaries.
- Three isolated fresh PostgreSQL 16 databases reached V9 and Hibernate
  validation for lifecycle, webhook, and final Chapter 20 runtime evidence.

## Demo evidence

- Chapter 17: automatic approval reached `CAPTURED` with one balanced ledger
  pair; manual approval reached `CANCELED` with
  `RECORDED/MERCHANT_CANCELED` void evidence and no manual ledger rows.
- Chapter 18: automatic capture reached one durable `RECORDED` Refund and four
  ledger rows containing the capture pair plus exact reversal pair.
- Chapter 19: the callback returned `503` then `204`; two attempts retained one
  immutable event/payload, signature verification passed, exactly one merchant
  effect committed, and payment/ledger truth remained unchanged.
- Chapter 20 final run reproduced nine processor partitions, nine immutable
  FraudDecision/FeatureSnapshot pairs, 27 confirm audit actions, nine
  `payment_attempt.created` deliveries, and zero manual-catalog ledger entries.
  The atomic report is under `build/reports/processor-catalog/` and contains no
  submitted card, PII, credential, signing-secret, idempotency, or fingerprint
  material.
- After the `ER-0021` fix, a fresh isolated PostgreSQL V1-to-V9 run repeated the
  same nine-outcome reconciliation through the redirect-rejecting client and
  wrote an owner-only `0600` report.

## Operational signals

- Confirm logs expose bounded action, reason, authorization status, and response
  code with request correlation.
- Safe audit actions distinguish payment confirm, fraud completion, approved,
  declined, and action-required authorization.
- Webhook metrics/logs retain bounded outcome, status, error cause, latency,
  retry, exhaustion, disable, recovery, and due-backlog dimensions.
- Actuator health, V9 migration/Hibernate validation, loopback demo failure
  messages, and readiness gates provide startup and release evidence.
- No Chapter 20 metric adds raw card, credential, merchant, endpoint, or URL
  dimensions.

## Known debt

- `TD-0001`: trusted synthetic origin before shared scenario traffic or ML
  datasets.
- `TD-0002`: resumable generator orchestration before scheduled/shared
  dataset-scale runs.
- `TD-0003`: signing-secret overlap before shared non-local rotation playbooks.
- `TD-0004`: webhook fairness/rate controls before shared external volume.
- `TD-0005`: endpoint-creation idempotency before public/shared provisioning.
- All five are P2 and remain outside the frozen local V1 Phase 2 promise under
  their explicit triggers. Chapter 20 accepted no new debt.

## Custom infrastructure decision

ADR-0009 remains accepted for Chapter 19's narrow pinned-address socket
transport and contains the required identity, authenticated-byte, limit,
timeout, retry, persistence, and operations boundary table. Chapter 20 adds no
custom transport, protocol, queue, or cryptography.

## Escaped-review assessment

- `ER-0001` through `ER-0020` remain resolved and their Chapter 19 proof is
  rerun by full readiness.
- `ER-0021` records the post-closure redirect credential-forwarding finding.
  It is resolved by fail-closed client behavior plus real-server proof across
  301, 302, 303, 307, and 308.
- Chapter 20's earlier sanitizer-family weakness was found during the
  temporally separate hostile pass before its first completion claim and was
  fixed with expanded table-driven proof.
- No unresolved P0/P1 or frozen-promise P2 finding exists.

## Source-of-truth reconciliation

- Root README states that Chapter 20 and the canonical Phase 2 boundary are
  complete.
- The implementation master plan marks Chapter 20 complete, removes stale
  webhook work, and assigns disputes, partial/fee accounting, outbox, and
  payout settlement to their real boundaries.
- The service and script READMEs document the catalog and exact demo command.
- Payment-flow architecture and the processor concept define implemented versus
  deferred 3DS behavior and processor-versus-fraud truth.
- The daily note, deep dive, weekly review, matrix, dossier, debt register, and
  escaped-review register agree; no new debt or escape was added.

## Merge recommendation

`MERGE`. Every frozen Chapter 20 and canonical Phase 2 promise has production
enforcement, automated proof, fresh PostgreSQL runtime evidence, reconciled
source status, and no unresolved blocking finding.
