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
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

- A committed payment/refund mutation and its immutable webhook event share one
  transaction. Routing fails closed when no domain transaction exists; outbound
  HTTP never changes payment, fraud, ledger, audit, or idempotency truth.
- Merchant-owned endpoint, event, delivery, attempt, secret, and read paths are
  tenant scoped at API, cryptographic-AAD, repository, and database boundaries.
- The exact persisted payload bytes and timestamp are signed and reused across
  retries; merchants verify before parsing and atomically deduplicate on signed
  body ID. The executable reference consumer proves one effect across restart
  and concurrent duplicate receipt.
- SSRF policy validates every DNS answer and transport connects only to one
  approved address while preserving original Host and TLS identity.
- Delivery evidence distinguishes known no-send, completed request write, and
  genuinely unknown post-dispatch outcome.
- Endpoint disable wins until the durable dispatch marker exists, while expired
  already-dispatched work remains recoverable.
- Retry is bounded to five attempts and every terminal/retry/uncertain outcome
  is durable, inspectable, and observable without sensitive/high-cardinality
  telemetry.
- V8 aggregate execution claims without attempt evidence fail migration before
  V9 schema truth is created; pristine disabled-endpoint work becomes terminal
  rather than stranded.
- Signature time is minted after blocking preparation; response status and
  field grammar are validated; request targets retain exact URI meaning through
  RFC ASCII serialization; and exact payload, timeout-conversion, and cumulative
  response-head byte limits are enforced at runtime boundaries.
- Unsupported legacy execution states and event types fail before V9 creates
  new schema truth, and non-trivial readiness always executes both test suites.

## Failure inventory

| Boundary | Hostile sequence |
| --- | --- |
| Transaction | A caller attempts routing without a domain transaction, or payment mutation commits or rolls back while event/delivery/audit/ledger persistence fails |
| Duplicate/retry | Callback fails, times out, crashes after dispatch, merchant effect commits before acknowledgement, concurrent duplicates arrive, the consumer restarts, or delivery repeats attempt five |
| Stale worker | Lease expires before dispatch or after partial/complete request write |
| Concurrency | Rotate/disable and route/disable execute in both endpoint-lock orderings |
| Tenant | Merchant A substitutes Merchant B endpoint or delivery identifiers |
| Secret/privacy | Database reader, logs, metrics, API, URL, payload, or reference fixture attempts disclosure |
| Protocol | Header/body ID disagree, a Unicode request target is silently substituted, payload exceeds 64 KiB, status is outside `100..599`, a response field has no colon/invalid token/control value/obsolete folding, informational response precedes final, or the final head truncates or crosses the cumulative 32 KiB byte budget |
| DNS/network | Timeout, saturation, interruption, unknown host, empty answer, generic failure, prohibited answer, or DNS rebinding |
| Migration | V8 claims execution without attempt evidence, contains an unsupported event type, or an inactive endpoint owns pristine queued work |
| Operator | Endpoint disabled before dispatch, after dispatch, or while recovery is due; global execution disabled while due work accumulates |
| Time | Dispatch preparation waits longer than merchant signature tolerance before HTTP, or a positive sub-millisecond socket timeout truncates to Java's zero/unbounded representation |
| Telemetry race | A claim changes a pre-counted queued row while bulk disable waits |

## Boundary family evidence

| Family | Partitions | Proof |
| --- | --- | --- |
| Status line | HTTP/1.0 and HTTP/1.1; required spaces; empty/non-empty reason; two/three/four-digit and non-digit code; in/out-of-range status; control-bearing reason | `socketTransportRejectsEveryMalformedStatusLineGrammarPartition`, malformed-version/range/reason tests, and valid empty-reason raw sockets |
| Response field name | Every byte value against the ASCII HTTP-token set; missing colon; whitespace and delimiter characters; obsolete folding | `responseHeaderGrammarClassifiesEveryOctetForTheAsciiContract`, raw-socket malformed-field tests, and integrated durable-state rejection |
| Response field value | Every byte value against tab/visible-ASCII policy; controls, DEL, and non-ASCII octets | The same 256-value property proof plus control and non-ASCII raw-socket regressions |
| Response framing/limits | Bare/truncated sections, exact CRLF completion, line bound, cumulative N/N+1 head size, repeated informational sections | Truncated, exact-bound, oversized, and shared-budget raw-socket tests |
| Request target | Unicode path/query characters, existing percent escapes, empty/non-empty path, and ASCII wire serialization | `socketTransportPercentEncodesUnicodeRequestTargetWithoutChangingExistingEscapes` captures the exact request line from a real socket |
| Timeout duration conversion | DNS-resolution, connect, and request consumers crossed with null, negative, zero, fractional millisecond, exact 1 ms, configured maximum, maximum plus fraction/1 ms, and conversion overflow | Shared `WebhookDurationValidator` plus `webhookTimeoutConfigurationRejectsEveryUnrepresentableOrUnboundedDuration` |
| Endpoint concurrency | Routing/disable, rotation/disable, finalization/disable, and claim/disable in both forced lock orderings | Eight PostgreSQL tests with lock ownership/wait evidence and durable postconditions |
| Migration state | Active/inactive parent endpoint crossed with pristine/unsupported executed child state and canonical/unknown legacy event type | H2 and PostgreSQL V8-to-V9 upgrade tests with post-failure no-DDL metadata assertions |
| Readiness execution | Trivial/non-trivial invocation crossed with explicit/omitted `--run-tests` | `CommitReadinessCheckTests` proves non-trivial readiness launches Maven and Python without the optional flag |
| Consumer idempotency | First receipt/identical duplicate/conflicting payload crossed with sequential, concurrent, and post-restart execution | `webhook_callback_fixture_tests.py` proves one durable SQLite effect, conflict rejection, and sixteen-way duplicate serialization |
| Worker operating mode | Enabled processing crossed with disabled observe-only polling | `WebhookDeliverySchedulerUnitTests` proves disabled mode refreshes backlog without invoking claim/send work |

## Enforcement boundaries

- V9 composite foreign keys, check constraints, immutable event rows, and
  migration normalization enforce lineage and state representation.
- Mandatory transaction propagation on event routing prevents standalone
  webhook truth from committing outside its payment/refund mutation.
- Merchant-scoped controllers/repositories plus encryption AAD enforce tenant
  isolation.
- Endpoint-before-delivery row locking, claim tokens, attempt numbers, leases,
  and `dispatch_started_at` enforce concurrency and uncertainty truth.
- `WebhookEventEnvelopeFactory`, `WebhookSigner`, and bounded payload validation
  enforce exact authenticated bytes.
- `WebhookHostResolver`, `WebhookTargetPolicy`, and pinned-address
  `SocketWebhookHttpTransport` enforce DNS/SSRF and protocol behavior.
- Request-target construction uses the URI ASCII representation, preserving
  merchant path/query meaning without ASCII replacement.
- Socket timeout conversion rejects every source duration that cannot remain a
  positive bounded integer millisecond value at the Java API boundary.
- `WebhookMetrics` and bounded structured logs expose due backlog, latency,
  retry, exhaustion, disable, and lease recovery without secret or tenant-cardinality
  leakage.
- Disabled scheduler polls refresh the due gauge from durable state without
  claiming or sending work.
- The callback fixture's SQLite uniqueness transaction binds authenticated
  event ID to payload hash before acknowledgement, providing executable
  merchant-side crash/restart and concurrency proof.
- Finalization returns the committed delivery status, attempt outcome, and
  bounded error causes from the transaction itself; metrics do not depend on a
  second post-commit database read.
- V9's execution-state and event-type preflight guards reject unsupported
  evidence before DDL; inactive legacy endpoint queues are normalized to
  terminal state.
- Bulk disable records the affected count from five bounded event-type updates,
  and signatures are minted from a post-preparation clock read.

## Exact proof

- Full backend: `mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test`
  passed 225 tests with zero failures and zero skipped.
- Repository Python: `python3 -m unittest discover -s scripts -p '*_tests.py'`
  passed 44 tests, including live duplicate acknowledgement plus callback
  restart/concurrency/payload-conflict,
  non-trivial readiness execution, provenance, boundary-family, immutable
  escaped-review, severity, closure-dossier, and source-status contradiction
  cases.
- Readiness negative proof: omitting `--closure-dossier` from non-trivial
  readiness exits non-zero with an explicit missing-artifact error.
- Production-database concurrency:
  `WebhookDeliveryPostgreSqlConcurrencyTests` passed eight tests, including four
  forced endpoint-lock orderings and the claim/disable metric race with observed
  PostgreSQL lock waits.
- Protocol/DNS/security:
  `WebhookSecurityAndRetryTests` proves exact signatures, final-head
  completeness/bounds, exact HTTP/1.0 and HTTP/1.1 status grammar/range,
  exhaustive ASCII response-field name/value partitions, raw-socket rejection
  of missing-colon/invalid-token/control/obsolete-folding fields,
  malformed-version rejection, DNS taxonomy, SSRF pinning, total deadlines,
  exact socket-duration conversion domains, exact 64 KiB request and cumulative
  32 KiB response-head boundaries, and sensitive-data boundaries.
- Exact-wire request-target proof percent-encodes Unicode path/query characters
  without double-encoding existing escapes.
- Integrated executor/database proof sends a malformed `204` response head and
  asserts durable delivery state is `RETRY_SCHEDULED`, the attempt is
  `RETRYABLE_FAILURE` with `httpRequestSent=true`, and no response status is
  recorded as acknowledgement truth.
- Observability proof distinguishes HTTP rejection, resolver/transport failure,
  lease expiry, and endpoint disable through bounded error-code metrics while
  excluding tenant, endpoint, URL, resource, payload, and secret dimensions.
- Executor proof advances time 301 seconds across preparation and verifies the
  transmitted signature uses the post-wait timestamp; local signing failure
  remains known-no-send.
- Callback-store proof restarts against the same SQLite state, forces sixteen
  concurrent insert attempts to one winner, and rejects one authenticated ID
  paired with a different payload hash.
- Scheduler proof shows disabled mode refreshes due backlog and never calls the
  processing boundary.
- Matrix: 23 entries across all eight required lenses are `PASS`.
- Integrated readiness: the non-trivial command naming this matrix and dossier
  passed debt, escaped-review, invariant, closure, learning, 225 backend-test,
  and 44 Python-test gates without requiring a separate `--run-tests` flag.

## Migration evidence

- H2 and PostgreSQL upgrade tests migrate pristine V8 queued state, exhaust
  queued work for inactive legacy endpoints, and fail fast when scheduled,
  succeeded, or failed aggregate state lacks attempt evidence.
- Both databases reject unknown legacy event types before creating
  `webhook_events` or adding endpoint operational-state columns.
- Because V8 stored no event identity, V9 intentionally maps each legacy
  delivery to one synthetic event instead of inventing sibling fan-out
  lineage. Post-V9 events carry authoritative one-event-to-many-delivery
  lineage.
- A fresh PostgreSQL 16 schema migrated through V9 and passed Hibernate
  validation.
- A local volume containing an earlier uncommitted V9 draft failed validation
  because `dispatch_started_at` was absent. It was preserved rather than
  silently repaired or deleted; final proof used an isolated fresh database.

## Demo evidence

The public API demo against fresh PostgreSQL created a payment and signed
webhook, observed callback `503` then `204`, and reconciled two attempts against
one immutable event/payload. The callback applied exactly one durable business
effect. Payment status and balanced ledger truth remained unchanged across
external failure and retry.

## Operational signals

- Metrics expose bounded outcome/status/error enums, retry/exhaustion, disable,
  recovery, latency, queue age, and actual due backlog in enabled and disabled
  execution modes.
- Structured logs carry bounded event/delivery/attempt IDs and latency while
  excluding URL, payload, signature, secret, PAN, CVV, PII, API key, and
  idempotency material.
- Endpoint disable is the merchant kill switch until dispatch starts.
- Resolver capacity, queue, deadline, request deadline, timeout conversion,
  payload size, response-head syntax/size, and attempt count are bounded.
- Bulk-disable metrics count committed affected rows rather than pre-update
  observations, and signature age excludes database-lock wait time.

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
- `ER-0006` `IMPLEMENTATION_DEFECT`: failure metrics collapsed distinct
  operator causes; resolved with bounded error-code telemetry across normal,
  recovery, preparation, and bulk-disable paths.
- `ER-0007` `MISSING_FAILURE_SEQUENCE`: malformed HTTP-version tokens could
  become acknowledgement truth; resolved with exact supported-version grammar
  and a hostile raw-socket test.
- `ER-0008` `IMPLEMENTATION_DEFECT`: legacy aggregate execution state survived
  without attempts; resolved by V9 preflight rejection.
- `ER-0009` `MISSING_FAILURE_SEQUENCE`: inactive legacy endpoint work could
  remain stranded; resolved by terminal migration normalization.
- `ER-0010` `MISSING_FAILURE_SEQUENCE`: signature time preceded lock wait;
  resolved by post-preparation signing and known-no-send signing failure.
- `ER-0011` `WEAK_PROOF`: protocol limits were not checked at exact runtime
  byte boundaries; resolved with layered request limits and N/N+1 head tests.
- `ER-0012` `MISSING_FAILURE_SEQUENCE`: pre-counted bulk-disable metrics raced
  claims; resolved with affected-row telemetry and forced PostgreSQL proof.
- `ER-0013` `MISSING_FAILURE_SEQUENCE`: arbitrary CRLF-terminated lines could
  survive between a valid status and final empty line; resolved with exhaustive
  ASCII field grammar classification and hostile raw-socket proof.
- `ER-0014` `IMPLEMENTATION_DEFECT`: positive sub-millisecond socket durations
  truncated to zero; resolved with exact source/target conversion-domain proof.
- `ER-0015` `MISSING_FAILURE_SEQUENCE`: a status line could omit the required
  separator before its empty reason phrase; the new family expansion caught it
  and raw sockets now prove every separator/code-shape partition.
- `ER-0016` `IMPLEMENTATION_DEFECT`: ASCII encoding silently substituted
  Unicode request-target characters; resolved with URI ASCII serialization and
  exact socket capture.
- `ER-0017` `MISSING_FAILURE_SEQUENCE`: unknown legacy event types failed after
  DDL began; resolved with full-domain preflight and no-DDL proof on H2 and
  PostgreSQL.
- `ER-0018` `WEAK_PROOF`: non-trivial readiness could skip tests; resolved by
  making the deep-dive flag imply test execution and behavior-testing both
  commands.
- `ER-0019` `DOCUMENTATION_OVERCLAIM`: the callback fixture was described as
  replay-safe without durable deduplication; resolved with an atomic SQLite
  event-ID/payload-hash effect and restart/concurrency/conflict proof.
- `ER-0020` `MISSING_FAILURE_SEQUENCE`: disabled execution left due telemetry
  false-zero; resolved with observe-only disabled polls and no-processing proof.

The repeated pattern is not “more edge cases.” It is failure-sequence and proof
selection occurring too late and then closing individual examples instead of
their input families. The process correction makes pre-implementation
provenance, failure inventory, boundary-family proof, closure dossier,
custom-infrastructure review, severity blocking, and escaped-review tracking
executable readiness inputs.

## Source-of-truth reconciliation

- `README.md`: Chapter 19 complete, with honest V8 fail-fast behavior, fresh
  signatures, exact protocol limits, and race-correct telemetry.
- `apps/payments-api/README.md`: exact merchant integration, migration, local
  transport, retry, and secret contracts.
- `docs/IMPLEMENTATION_MASTER_PLAN.md`: Chapter 19 complete after the
  2026-07-02 final pre-merge recovery pass; 23 invariants and current evidence.
- Architecture and webhook concept docs: immutable event/attempt lineage,
  pinned targets, protocol bounds, observability, and recovery agree.
- Matrix: `RETROACTIVE_RECOVERY`, frozen, verified, fresh hostile pass.
- Debt register: `TD-0003` through `TD-0005` own the only Chapter 19 deferrals.
- Daily and weekly notes: reopened findings, fixes, proof, and process failure
  are recorded.

## Merge recommendation

`MERGE`. All Chapter 19 frozen promises are traced and proven, all twenty escaped
findings are resolved with product and process evidence, the custom transport is
bounded by accepted ADR-0009, and the only accepted deferrals are `TD-0003`,
`TD-0004`, and `TD-0005`.
