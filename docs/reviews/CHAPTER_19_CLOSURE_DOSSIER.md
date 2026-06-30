# Chapter 19 Closure Dossier

- Chapter: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Chapter number: 19
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_19_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: RETROACTIVE_RECOVERY
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

- A committed payment/refund mutation and its immutable webhook event share one
  transaction; outbound HTTP never changes payment, fraud, ledger, audit, or
  idempotency truth.
- Merchant-owned endpoint, event, delivery, attempt, secret, and read paths are
  tenant scoped at API, cryptographic-AAD, repository, and database boundaries.
- The exact persisted payload bytes and timestamp are signed and reused across
  retries; merchants verify before parsing and deduplicate on signed body ID.
- SSRF policy validates every DNS answer and transport connects only to one
  approved address while preserving original Host and TLS identity.
- Delivery evidence distinguishes known no-send, completed request write, and
  genuinely unknown post-dispatch outcome.
- Endpoint disable wins until the durable dispatch marker exists, while expired
  already-dispatched work remains recoverable.
- Retry is bounded to five attempts and every terminal/retry/uncertain outcome
  is durable, inspectable, and observable without sensitive/high-cardinality
  telemetry.

## Failure inventory

| Boundary | Hostile sequence |
| --- | --- |
| Transaction | Payment mutation commits or rolls back while event/delivery/audit/ledger persistence fails |
| Duplicate/retry | Callback fails, times out, crashes after dispatch, or repeats attempt five |
| Stale worker | Lease expires before dispatch or after partial/complete request write |
| Concurrency | Rotate/disable and route/disable execute in both endpoint-lock orderings |
| Tenant | Merchant A substitutes Merchant B endpoint or delivery identifiers |
| Secret/privacy | Database reader, logs, metrics, API, URL, payload, or reference fixture attempts disclosure |
| Protocol | Header/body ID disagree, status is outside `100..599`, informational response precedes final, final head truncates or exceeds 32 KiB |
| DNS/network | Timeout, saturation, interruption, unknown host, empty answer, generic failure, prohibited answer, or DNS rebinding |
| Migration | V8 pending/scheduled/succeeded/failed rows upgrade without invented secret or false lineage |
| Operator | Endpoint disabled before dispatch, after dispatch, or while recovery is due |

## Enforcement boundaries

- V9 composite foreign keys, check constraints, immutable event rows, and
  migration normalization enforce lineage and state representation.
- Merchant-scoped controllers/repositories plus encryption AAD enforce tenant
  isolation.
- Endpoint-before-delivery row locking, claim tokens, attempt numbers, leases,
  and `dispatch_started_at` enforce concurrency and uncertainty truth.
- `WebhookEventEnvelopeFactory`, `WebhookSigner`, and bounded payload validation
  enforce exact authenticated bytes.
- `WebhookHostResolver`, `WebhookTargetPolicy`, and pinned-address
  `SocketWebhookHttpTransport` enforce DNS/SSRF and protocol behavior.
- `WebhookMetrics` and bounded structured logs expose due backlog, latency,
  retry, exhaustion, disable, and lease recovery without secret or tenant-cardinality
  leakage.

## Exact proof

- Full backend: `mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test`
  passed 203 tests with zero failures and zero skipped.
- Repository Python: `python3 -m unittest discover -s scripts -p '*_tests.py'`
  passed 36 tests, including provenance, immutable escaped-review, severity,
  closure-dossier, and source-status contradiction cases.
- Readiness negative proof: omitting `--closure-dossier` from non-trivial
  readiness exits non-zero with an explicit missing-artifact error.
- Production-database concurrency:
  `WebhookDeliveryPostgreSqlConcurrencyTests` passed seven tests, including four
  forced endpoint-lock orderings with observed PostgreSQL lock waits.
- Protocol/DNS/security:
  `WebhookSecurityAndRetryTests` proves exact signatures, final-head
  completeness/bounds, status grammar/range, DNS taxonomy, SSRF pinning, total
  deadlines, and sensitive-data boundaries.
- Matrix: 19 entries across all eight required lenses are `PASS`.
- Integrated readiness: the non-trivial command naming this matrix and dossier
  passed debt, escaped-review, invariant, closure, learning, 203 backend-test,
  and 36 Python-test gates.

## Migration evidence

- H2 and PostgreSQL upgrade tests migrate realistic V8 immediate-pending,
  scheduled-pending, succeeded, and failed delivery states through V9.
- A fresh PostgreSQL 16 schema migrated through V9 and passed Hibernate
  validation.
- A local volume containing an earlier uncommitted V9 draft failed validation
  because `dispatch_started_at` was absent. It was preserved rather than
  silently repaired or deleted; final proof used an isolated fresh database.

## Demo evidence

The public API demo against fresh PostgreSQL created a payment and signed
webhook, observed callback `503` then `204`, and reconciled two attempts against
one immutable event/payload. Payment status and balanced ledger truth remained
unchanged across external failure and retry.

## Operational signals

- Metrics expose bounded outcome/error enums, retry/exhaustion, disable,
  recovery, latency, queue age, and actual due backlog.
- Structured logs carry bounded event/delivery/attempt IDs and latency while
  excluding URL, payload, signature, secret, PAN, CVV, PII, API key, and
  idempotency material.
- Endpoint disable is the merchant kill switch until dispatch starts.
- Resolver capacity, queue, deadline, request deadline, payload size, response
  head, and attempt count are bounded.

## Known debt

- `TD-0003`: old/new signing-secret overlap and expiry before shared non-local
  merchant onboarding.
- `TD-0004`: per-merchant/per-endpoint fairness and rate controls before shared
  external delivery volume.
- `TD-0005`: endpoint-creation idempotency before public/shared onboarding.
- No DNS classification, response-completeness, endpoint-disable, tenant,
  payment/fraud-lineage, or attempt-evidence debt is accepted.

## Custom infrastructure decision

ADR-0009 accepts the narrow socket transport only for validated-address pinning
with original HTTP/TLS identity. Its boundary table covers destination identity,
authority, TLS identity, authenticated bytes, response completion, redirects,
limits, retry truth, persistence, and operations. Shared non-local egress,
mTLS, proxying, HTTP/2, or multi-region needs trigger replacement review rather
than expansion into a general client.

## Escaped-review assessment

- `ER-0001` `WEAK_PROOF`: timing-only concurrency proof.
- `ER-0002` and `ER-0003` `MISSING_FAILURE_SEQUENCE`: final response completion
  and complete DNS outcome taxonomy.
- `ER-0004` `IMPLEMENTATION_DEFECT`: disabled response lost subscription truth.
- `ER-0005` `DOCUMENTATION_OVERCLAIM`: retroactive matrix and structurally weak
  closure gate; resolved by the provenance, escaped-review, dossier, severity,
  and source-reconciliation checks.

The repeated pattern is not “more edge cases.” It is failure-sequence and proof
selection occurring too late. The process correction makes pre-implementation
provenance, failure inventory, closure dossier, custom-infrastructure review,
severity blocking, and escaped-review tracking executable readiness inputs.

## Source-of-truth reconciliation

- `README.md`: Chapter 19 complete, with bounded final response and exhaustive
  DNS behavior.
- `apps/payments-api/README.md`: exact merchant integration, migration, local
  transport, retry, and secret contracts.
- `docs/IMPLEMENTATION_MASTER_PLAN.md`: Chapter 19 complete after the
  2026-06-30 hostile recovery pass; 19 invariants and current evidence.
- Architecture and webhook concept docs: immutable event/attempt lineage,
  pinned targets, protocol bounds, observability, and recovery agree.
- Matrix: `RETROACTIVE_RECOVERY`, frozen, verified, fresh hostile pass.
- Debt register: `TD-0003` through `TD-0005` own the only Chapter 19 deferrals.
- Daily and weekly notes: reopened findings, fixes, proof, and process failure
  are recorded.

## Merge recommendation

`MERGE`. All Chapter 19 frozen promises are traced and proven, all five escaped
findings are resolved with product and process evidence, the custom transport is
bounded by accepted ADR-0009, and the only accepted deferrals are `TD-0003`,
`TD-0004`, and `TD-0005`.
