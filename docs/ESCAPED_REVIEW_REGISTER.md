# Escaped Review Register

This is THAAP's canonical record of defects that survived an earlier review or
completion claim. IDs are immutable and never reused. The register measures the
review system, not individual performance.

Allowed root-cause categories are `MISSING_PROMISE`,
`MISSING_FAILURE_SEQUENCE`, `WEAK_PROOF`, `IMPLEMENTATION_DEFECT`, and
`DOCUMENTATION_OVERCLAIM`.

## ER-0001

- Status: RESOLVED
- Severity: P1
- Chapter: 19
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier Chapter 19 concurrency review
- Invariant: Endpoint disable, rotation, and routing must have one coherent winner under every dangerous database ordering.
- Finding: Simultaneous thread starts exercised races but did not force both endpoint-lock orderings or prove that the competing PostgreSQL backend was blocked on the intended lock.
- Why prior review missed it: The review treated scheduler competition as concurrency evidence instead of requiring a deterministic interleaving and lock-wait observation.
- Resolution evidence: Four PostgreSQL tests force rotation-before-disable, disable-before-rotation, routing-before-disable, and disable-before-routing; each records the competing backend PID, observes `pg_stat_activity.wait_event_type = 'Lock'`, then asserts durable postconditions.
- Process correction: The invariant process and checker metadata now require a pre-implementation failure inventory, traceability review, and real-boundary proof; concurrency policy explicitly rejects simultaneous-start-only evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30

## ER-0002

- Status: RESOLVED
- Severity: P1
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier Chapter 19 protocol review
- Invariant: A callback is acknowledged only after a structurally complete, bounded final HTTP response head.
- Finding: The transport could accept a final `2xx` status line before receiving the empty-line boundary that completes the final response head.
- Why prior review missed it: The failure inventory covered informational responses and invalid status numbers but omitted final-status truncation and oversized final headers.
- Resolution evidence: `socketTransportRejectsTruncatedFinalResponseHead` and `socketTransportBoundsFinalResponseHeaders` use hostile raw sockets and prove both sequences become classified transport failures.
- Process correction: Protocol review now requires a cross-product boundary table and the accepted custom-infrastructure ADR records response-completion and parser-limit obligations.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30

## ER-0003

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier Chapter 19 DNS review
- Invariant: Only an explicitly prohibited target is terminal; transient or failed resolution remains retryable known-no-send evidence.
- Finding: Unknown host, empty DNS answers, and unexpected resolver failures could be collapsed into terminal target denial even though no unsafe address had been proven.
- Why prior review missed it: Tests covered resolver timeout and capacity but not the complete resolver-outcome taxonomy.
- Resolution evidence: DNS boundary tests now cover timeout, saturation, interruption, unknown host, empty answer, generic failure, and prohibited addresses with distinct retryable/terminal outcomes.
- Process correction: Failure inventories must enumerate dependency result classes, not only timeout; Chapter 19 matrix entry C19-OPS-003 makes the taxonomy executable.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30

## ER-0004

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier Chapter 19 API review
- Invariant: Disabling an endpoint changes status without falsifying its durable event subscriptions.
- Finding: The disable response returned an empty `enabledEvents` set although subscriptions remained stored.
- Why prior review missed it: The endpoint state transition was tested, but the response projection was not compared with durable subscription truth.
- Resolution evidence: `WebhookEndpointOperationsTests` now asserts the disabled response preserves the configured subscriptions, and `WebhookEndpointService` returns the stored set.
- Process correction: Closure now requires observable promises and source-of-truth reconciliation, and API/product matrix evidence must compare response projections with durable state.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30

## ER-0005

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier Chapter 19 completion review
- Invariant: A chapter completion claim must identify matrix provenance, reconcile all source-of-truth documents, and remain blocked by unresolved severity.
- Finding: The Chapter 19 matrix was reconstructed after implementation and marked verified too eagerly; the old readiness gate checked matrix shape but had no closure dossier, escaped-review measure, custom-infrastructure decision, or severity-aware block.
- Why prior review missed it: Repository policy conflated structural matrix validation with semantic chapter closure and retained key closure checks only in prose and chat.
- Resolution evidence: Sixteen focused checker tests and all 36 repository Python tests pass; an executable negative readiness run exits non-zero when `--closure-dossier` is omitted, and the completed Chapter 19 dossier passes the integrated closure checker.
- Process correction: `AGENTS.md`, the invariant process, the closure dossier, ADR-0009, and executable readiness scripts now form one quality operating system.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30

## ER-0006

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 observability reconciliation
- Invariant: Operators must distinguish bounded webhook failure causes, including endpoint disable, lease expiry, target denial, secret failure, transport failure, and HTTP rejection, without tenant or endpoint cardinality.
- Finding: Existing metrics exposed attempt outcome and terminal status but no error-code dimension, so distinct failure causes collapsed into the same RETRYABLE_FAILURE or EXHAUSTED series while the matrix and closure dossier claimed bounded outcome/error enums.
- Why prior review missed it: Tests proved that recovery and bulk-disable transitions incremented counters and that sensitive tags were absent, but never compared two different failure causes to prove operational distinguishability.
- Resolution evidence: WebhookMetrics now emits thaap.webhook.failures with canonical event.type and bounded error.code tags; finalization returns its committed classification without a post-commit reread; executor finalization, lease recovery, dispatch preparation, and bulk disable all record the effective durable cause, and focused tests prove HTTP_NON_SUCCESS, TRANSPORT_ERROR, TARGET_NOT_ALLOWED, LEASE_EXPIRED, and ENDPOINT_DISABLED without high-cardinality tags.
- Process correction: Observability proof must assert both absence of forbidden dimensions and presence of the bounded dimensions required to distinguish every promised operator outcome.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0007

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 custom-protocol review
- Invariant: A webhook callback acknowledgement requires a syntactically valid supported HTTP status line, an in-range final status, and a complete bounded response head.
- Finding: SocketWebhookHttpTransport accepted any protocol token beginning with HTTP/, so a malformed response such as HTTP/not-http 204 could be recorded as successful despite the matrix claiming status-line grammar validation.
- Why prior review missed it: Hostile protocol tests covered numeric range, informational responses, truncation, oversized heads, and deadlines but omitted malformed HTTP-version tokens and reason-phrase controls.
- Resolution evidence: SocketWebhookHttpTransport now accepts only HTTP/1.0 or HTTP/1.1 text status lines with three ASCII digits and a bounded control-safe reason phrase; socketTransportRejectsMalformedHttpVersionAsTransportFailure and socketTransportRejectsControlBearingReasonPhrase prove both malformed responses remain post-send transport failures.
- Process correction: Custom-protocol test inventories must vary every parsed grammar component, not only numeric values, framing boundaries, and size limits.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0008

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 migration and attempt-lineage review
- Invariant: A delivery with prior execution state cannot survive migration without authoritative append-only attempt evidence for every claimed attempt number and terminal success.
- Finding: V9 preserved schema-valid V8 scheduled, succeeded, and failed aggregate delivery states while creating zero attempt rows, so merchant reads could show success without a successful attempt or begin at attempt two with attempt one absent.
- Why prior review missed it: Migration tests asserted aggregate status normalization and even asserted zero attempts, but did not compare that result with the frozen successful-attempt and complete-attempt-history promises.
- Resolution evidence: V9 now rejects every V8 row that claims execution through status, attempt count, retry time, last-attempt time, delivered time, or last error; H2 and PostgreSQL hostile upgrade tests prove three unsupported representations fail before V9 creates new schema truth.
- Process correction: Migration review must reconcile every preserved aggregate counter and terminal state with its required child evidence; an upgrade test that passes is not proof when its postconditions contradict runtime invariants.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0009

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 legacy endpoint-state review
- Invariant: A disabled endpoint cannot retain nonterminal queued work that is neither claimable nor terminally explained.
- Finding: V9 mapped inactive V8 endpoints to DISABLED but left their pristine PENDING deliveries unchanged; the claim predicate requires ACTIVE, so those rows became permanently stranded.
- Why prior review missed it: Upgrade fixtures covered only active legacy endpoints and secret rotation, not the cross-product of endpoint operational state and queued delivery state.
- Resolution evidence: V9 now exhausts pristine deliveries owned by disabled legacy endpoints with ENDPOINT_DISABLED while leaving active legacy endpoint work queued for secret rotation; both H2 and PostgreSQL upgrade tests prove the two postconditions.
- Process correction: State-machine migrations must test the cross-product of parent eligibility and child work state, including proof that every migrated nonterminal row remains reachable by a worker.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0010

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 signature-freshness review
- Invariant: The webhook signature timestamp is minted after potentially blocking dispatch preparation and immediately before bounded transport work.
- Finding: The executor signed before acquiring dispatch-preparation locks, so a long endpoint or delivery lock wait could make the signature older than the documented merchant tolerance before HTTP began.
- Why prior review missed it: Signature tests verified exact bytes and retry freshness but never advanced the clock across a blocked preparation boundary.
- Resolution evidence: WebhookDeliveryExecutor now prepares first, rereads the clock, and signs immediately before transport; signatureTimestampIsMintedAfterDispatchPreparationCompletes advances time by 301 seconds across preparation, and signingFailureAfterPreparationFinalizesAsKnownNoSend proves a local signing failure remains durable false-send evidence.
- Process correction: Time-bound credential and signature tests must place clock advancement across every preceding lock, queue, resolver, and retry boundary rather than checking only static cryptographic vectors.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0011

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 protocol-limit review
- Invariant: The 64 KiB request and 32 KiB cumulative response-head limits are enforced at the execution and socket boundaries with exact byte accounting.
- Finding: Payload size was checked only during normal envelope construction, while the socket response budget excluded status lines and could consume beyond the declared head limit before rejection.
- Why prior review missed it: Tests used obviously oversized values and never exercised exact limit, limit-plus-one, persisted oversized payload, or cumulative informational/final head accounting.
- Resolution evidence: The executor terminally rejects oversized persisted payloads before secret access, the transport independently rejects oversized direct requests before connect, and raw-socket tests accept exactly 32768 response-head bytes while rejecting 32769 with status lines, headers, CRLF framing, and informational responses sharing one budget.
- Process correction: Every numeric protocol limit requires executable N-minus-one, N, and N-plus-one accounting at the real consumer boundary, including framing bytes and repeated protocol sections.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0012

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Final Chapter 19 disable-observability review
- Invariant: Bulk-disable terminal metrics count only delivery rows actually changed by the disable transaction.
- Finding: Disable counted queued rows before its bulk update; a concurrent claim could move a counted row to DELIVERING while the update waited, causing terminal and ENDPOINT_DISABLED metrics to overcount and later double-count recovery.
- Why prior review missed it: Existing metrics proof covered sequential bulk disable and absence of sensitive tags but did not force claim-before-update predicate re-evaluation.
- Resolution evidence: Disable now executes one bounded bulk update per canonical event type and records each actual update count; concurrentClaimDoesNotInflateBulkDisableMetrics proves the disable backend waits on the claimed delivery lock, updates zero rows after predicate re-evaluation, and emits zero premature terminal/failure counts.
- Process correction: Metrics derived from state transitions must be tested under the same forced interleavings as the state change and must use affected-row truth rather than a pre-update observation.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0013

- Status: RESOLVED
- Severity: P1
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Post-closure Chapter 19 final merge review
- Invariant: A callback acknowledgement requires every non-empty line in the bounded final response head to be a syntactically valid HTTP field, not merely a CRLF-terminated string.
- Finding: `SocketWebhookHttpTransport` validated the status line and final empty-line boundary but discarded arbitrary intervening lines without validating field-name tokens, the colon separator, obsolete folding, or control-bearing field values; `HTTP/1.1 204 No Content\r\nnot-a-header\r\n\r\n` could therefore become durable `SUCCEEDED` truth.
- Why prior review missed it: Protocol review expanded malformed status grammar and byte framing but continued selecting isolated examples instead of enumerating every parsed response-head grammar component and its complete character partitions.
- Resolution evidence: `SocketWebhookHttpTransport` now requires an ASCII HTTP token field name, an exact colon separator, and control-safe field values for every informational and final header line. `responseHeaderGrammarClassifiesEveryOctetForTheAsciiContract` exhaustively classifies all 256 input-octet values; `socketTransportRejectsMalformedHeaderFieldsBeforeAcknowledgingSuccess` proves missing-colon, whitespace-name, invalid-token, control-value, obsolete-folding, and non-ASCII responses fail at the raw-socket boundary; `malformedSuccessfulResponseHeadCannotBecomeDurableSuccess` proves the real executor/database state becomes retryable `TRANSPORT_ERROR`, never `SUCCEEDED`.
- Process correction: The invariant process and executable matrix/dossier gates now require `Boundary family review: PASS`; an escaped defect must reopen its whole grammar, numeric, concurrency, dependency, migration, or authorization family and add generative/property proof plus a real-boundary regression.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0014

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: Post-closure Chapter 19 final merge review
- Invariant: Every accepted socket timeout must retain a positive bounded value when converted to the millisecond integer consumed by Java socket and deadline APIs.
- Finding: Positive durations below one millisecond passed `Duration` validation but truncated to zero; Java interprets a zero connect/read timeout as unbounded while the deadline thread sleeps zero milliseconds, contradicting the bounded-worker promise and allowing configuration representation to change runtime semantics.
- Why prior review missed it: Timeout tests exercised runtime expiry and slow trickle behavior but did not enumerate negative, zero, sub-millisecond, exact-minimum, maximum, over-range, and conversion-overflow configuration partitions.
- Resolution evidence: `WebhookDurationValidator` now centralizes DNS-resolution, connect, and request conversion and rejects null, negative, zero, fractional-millisecond, configured-maximum, and conversion-overflow durations before asynchronous resolution or socket construction. `webhookTimeoutConfigurationRejectsEveryUnrepresentableOrUnboundedDuration` proves all invalid partitions and the exact one-millisecond/socket-maximum/resolver-maximum boundaries.
- Process correction: The new boundary-family gate requires representation-conversion reviews to enumerate the source domain, target domain, truncation points, exact boundaries, and overflow behavior rather than relying on runtime happy-path timeout tests.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0015

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-01
- Escaped gate: ER-0013 boundary-family expansion before Chapter 19 re-closure
- Invariant: An accepted HTTP/1.0 or HTTP/1.1 status line contains the required single-space separators around the three-digit status code, including the separator before an optionally empty reason phrase.
- Finding: The status parser accepted `HTTP/1.1 204\r\n\r\n` because it allowed a two-part split, even though the documented exact status grammar requires the post-code space before the possibly empty reason phrase.
- Why prior review missed it: Earlier status tests varied version tokens, numeric range, reason controls, and informational responses independently but never enumerated the complete separator, code-width, and code-character partitions.
- Resolution evidence: `SocketWebhookHttpTransport` now requires exactly three status-line parts. `socketTransportRejectsEveryMalformedStatusLineGrammarPartition` sends missing post-code space, tab separator, doubled separator, two-digit, four-digit, and non-digit code variants through real sockets and proves none can become acknowledgement truth.
- Process correction: The newly required boundary-family expansion caught this adjacent status-grammar defect before another completion claim; protocol review now enumerates every grammar component and partition rather than treating previous regression examples as exhaustive.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-01

## ER-0016

- Status: RESOLVED
- Severity: P1
- Chapter: 19
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Final Chapter 19 pre-merge review after ER-0013 boundary-family recovery
- Invariant: The outbound HTTP request target represents the merchant-configured URI path and query exactly after RFC ASCII serialization; transport encoding must never substitute characters and route a signed callback to a different resource.
- Finding: `SocketWebhookHttpTransport` concatenated `URI.getRawPath()` and `getRawQuery()` into a Java string and encoded the complete request head with `US_ASCII`. Java replaced non-ASCII path/query characters with `?`, so `/café?q=naïve` became `/caf??q=na?ve` on the wire.
- Why prior review missed it: Protocol review exhaustively classified response grammar but did not inventory the request-line representation boundary from Unicode Java `URI` characters to ASCII HTTP wire bytes.
- Resolution evidence: The transport now derives path and query from `URI.toASCIIString()` before constructing the request line. `socketTransportPercentEncodesUnicodeRequestTargetWithoutChangingExistingEscapes` captures the real socket request line and proves UTF-8 characters become their percent-encoded octets while existing `%20` and `%2F` escapes remain unchanged.
- Process correction: Custom HTTP protocol reviews must cover both request and response grammars, including every character-set conversion, percent-encoding boundary, and exact-wire identity claim.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0017

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Final Chapter 19 pre-merge migration review
- Invariant: Every unsupported V8 webhook representation is rejected before V9 creates tables, columns, subscriptions, or partially migrated lineage.
- Finding: The V9 preflight rejected unsupported execution state but did not reject an unknown legacy `event_type`. Such a row reached resource-type mapping only after DDL had begun; PostgreSQL rolled the transaction back, while H2 retained partial schema and a failed Flyway record that blocked retry.
- Why prior review missed it: Migration proof crossed endpoint state with execution state but did not enumerate the complete legacy event-type domain even though V8 had no event-type check constraint.
- Resolution evidence: V9 now counts and rejects every event type outside the five canonical Chapter 19 values before its first DDL statement. `v9MigrationRejectsUnsupportedLegacyEventTypesBeforeCreatingSchemaTruth` and `v9MigrationRejectsUnsupportedLegacyEventTypesBeforeDdlOnPostgreSql` prove the failure and the absence of both `webhook_events` and `operational_status` after rejection.
- Process correction: Migration boundary-family review must enumerate every unconstrained legacy enum/string domain and prove unsupported values fail before the first non-transactional or transactional schema mutation.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0018

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Final Chapter 19 commit-readiness review
- Invariant: Non-trivial commit readiness always executes the backend and repository Python suites; a caller cannot receive a green readiness result from structural artifacts alone.
- Finding: `--deep-dive-required` still left test execution behind the optional `--run-tests` flag. The canonical non-trivial readiness command could therefore pass without executing Maven or Python tests even though the closure dossier described those suites as integrated gates.
- Why prior review missed it: Negative readiness tests covered missing matrix/dossier inputs and structural contradictions but never observed whether the non-trivial invocation actually launched both test commands.
- Resolution evidence: `commit-readiness-check.sh` now makes `--deep-dive-required` imply test execution. `CommitReadinessCheckTests.test_non_trivial_readiness_always_runs_backend_and_python_tests` runs the real shell script in an isolated fixture and proves both commands are invoked.
- Process correction: Executable quality gates require behavior tests for promised side effects, not only parser/checker unit tests and successful manual invocations.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0019

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Final Chapter 19 post-readiness merge review
- Invariant: The executable merchant callback reference applies one durable business effect per authenticated signed-body event ID across duplicate delivery, concurrent receipt, and process restart.
- Finding: The callback fixture verified the signature and authenticated event ID but incremented in-memory state and appended evidence for every valid retry. It had no durable uniqueness boundary, even though the matrix described the executable consumer as replay-safe.
- Why prior review missed it: Fixture tests proved signature ordering, UUID parsing, and header agreement but never modeled merchant-side state, crash-after-side-effect recovery, concurrent duplicates, or restart.
- Resolution evidence: `DurableEventStore` now atomically inserts the authenticated event ID and payload hash into SQLite before returning success, treats an existing identical row as an acknowledged duplicate, and rejects event-ID reuse with different bytes. Callback fixture tests prove one effect across restart, reject conflicting payload reuse, force sixteen concurrent duplicates to one winner, and run the real HTTP fixture to observe `204/204` with effect flags `true/false`; the public demo asserts `503 -> 204` applies exactly one durable effect.
- Process correction: Any reference consumer called idempotent or replay-safe must include durable side-effect state and prove duplicate, concurrent, payload-conflict, and crash/restart partitions; parser-only tests cannot certify consumer idempotency.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0020

- Status: RESOLVED
- Severity: P2
- Chapter: 19
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Final Chapter 19 post-readiness merge review
- Invariant: `thaap.webhook.due` reports the actual database backlog in both enabled and intentionally disabled execution modes without causing disabled workers to claim or send work.
- Finding: The scheduler returned immediately when execution was disabled, so the default safe mode left the due gauge at its initial zero even while active endpoints accumulated pending deliveries.
- Why prior review missed it: Observability tests exercised the executor after an enabled processing pass and the claim/disable metric race, but the failure inventory did not cross backlog observation with the worker-disabled operating mode.
- Resolution evidence: `WebhookDeliveryScheduler` now refreshes the due count through `WebhookDeliveryExecutor.refreshDueDeliveries` when execution is disabled and still invokes no claim/send path. `WebhookDeliverySchedulerUnitTests` proves both disabled observe-only behavior and enabled processing behavior at a fixed poll time.
- Process correction: Operational-signal proof must cross every supported operating mode, especially fail-safe disabled, degraded, and recovery modes, with tests showing both metric truth and absence of prohibited side effects.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0021

- Status: RESOLVED
- Severity: P1
- Chapter: 20
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-02
- Escaped gate: Post-closure Chapter 20 final merge review
- Invariant: The loopback-only Chapter 20 demo never creates a follow-up request after an HTTP redirect, so API keys and the admin bootstrap token cannot leave the validated origin through redirect handling.
- Finding: `urllib.request.urlopen` followed redirects automatically and copied custom request headers to the redirected request; a loopback response could therefore forward `X-Api-Key` and `X-Admin-Bootstrap-Token` to a different origin despite the initial URL validation.
- Why prior review missed it: The origin tests validated only the caller-supplied URL string and never crossed that boundary with the HTTP client's effective-destination behavior or a real redirecting server.
- Resolution evidence: `ApiClient` now uses a dedicated `RejectRedirectHandler` that raises before a redirected request is created. `test_api_client_rejects_every_redirect_without_forwarding_credentials` sends credential-bearing requests through real loopback servers for 301, 302, 303, 307, and 308 and proves the redirect target receives zero requests. A post-fix fresh PostgreSQL V1-to-V9 Chapter 20 run also reconciles all nine outcomes and publishes a sanitized owner-only report through the hardened client.
- Process correction: Any client described as loopback-only or origin-restricted must inventory redirects as a separate effective-destination boundary and prove with real servers that authentication headers and bodies are never forwarded.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-02

## ER-0022

- Status: RESOLVED
- Severity: P1
- Chapter: 19
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-04
- Escaped gate: Chapter 21 live upgrade and synthetic-run recovery review
- Invariant: Every supported already-migrated database must start under the current webhook attempt entity without discarding or inventing dispatch evidence.
- Finding: A previously applied V9 database had `http_request_sent` but no `dispatch_started_at`; clean-schema tests passed because the mutable Java V9 migration now creates the newer shape, while Hibernate validation rejected the real upgraded database.
- Why prior review missed it: Migration proof recreated historical version 9 from the current Java migration implementation instead of retaining a fixture for the schema shape that had actually shipped. Java migration content changes were therefore invisible to Flyway history and to clean-database verification.
- Resolution evidence: V13 additively creates the missing column, makes tri-state send evidence nullable, backfills known-send rows from their authoritative attempt start timestamp, and installs the current dispatch-truth constraint. `v13RepairsPreviouslyAppliedWebhookAttemptSchemaWithoutLosingSendTruth` reconstructs the shipped shape in PostgreSQL, migrates it, and proves preserved send evidence and nullable tri-state semantics. The real local V9-to-V13 database then passed Flyway and Hibernate validation before the Chapter 21 public-API crash/resume demo.
- Process correction: Every Java migration schema change now requires a new additive migration; upgrade tests must include immutable shipped-schema fixtures rather than rebuilding every historical state from mutable migration source.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-04

## ER-0023

- Status: RESOLVED
- Severity: P1
- Chapter: 16
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-04
- Escaped gate: Chapter 21 final hostile effective-destination review
- Invariant: The loopback-only synthetic scenario generator never creates a follow-up request after an HTTP redirect, so merchant API keys, the admin bootstrap token, and the scenario-runner credential cannot leave the validated origin.
- Finding: The generator validated only its initial loopback base URL and then used `urllib.request.urlopen`, whose default redirect handling could create a request to another origin carrying privileged request headers.
- Why prior review missed it: Chapter 16 proved caller-supplied origin validation but did not inventory the HTTP client's effective destination. Chapter 20 later fixed the same boundary in its separate demo client, but that process correction was not applied repository-wide to the older generator.
- Resolution evidence: The generator `ApiClient` now owns a fail-closed `RejectRedirectHandler`. `test_api_client_rejects_every_redirect_without_forwarding_credentials` sends API, admin, and scenario-runner credentials through real source and target loopback servers for 301, 302, 303, 307, and 308 and proves the target receives zero requests.
- Process correction: Every credential-bearing, origin-restricted repository client must pair initial-origin validation with an explicit redirect policy and real-server effective-destination regression proof; fixing one client does not close sibling clients.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-04

## ER-0024

- Status: RESOLVED
- Severity: P1
- Chapter: 16
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Phase 2.5 end-to-end security review
- Invariant: Credential-bearing loopback clients communicate directly with the validated loopback origin and cannot delegate API, admin, or scenario-runner credentials to an environment-configured proxy.
- Finding: Both Python clients rejected redirects but still inherited `HTTP_PROXY` and related environment configuration through the default opener, so the effective network destination could differ from the validated loopback origin.
- Why prior review missed it: The effective-destination inventory covered caller input and redirects but omitted proxy resolution. Existing real-server tests proved only that redirect targets received nothing; they did not install a hostile proxy and force proxy selection.
- Resolution evidence: Both clients now build an opener with an explicit empty `ProxyHandler` before the rejecting redirect handler. `test_api_client_ignores_environment_proxy_for_loopback_credentials` in both Python suites forces `proxy_bypass` false, installs real direct and proxy servers, and proves the direct server receives the request while the proxy receives zero requests.
- Process correction: Origin-restricted client reviews must enumerate caller URL, redirect, environment proxy, DNS resolution, and connection target as separate effective-destination boundaries and prove each credential-bearing path.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0025

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Phase 2.5 end-to-end secret-lifecycle review
- Invariant: A previous webhook signing-secret generation is usable only when the exact signature timestamp is strictly before its expiry.
- Finding: The executor loaded a still-valid previous secret and could wait across its expiry before generating the signature, allowing a delivery to be signed after the advertised verification window had closed.
- Why prior review missed it: Before/at/after expiry tests exercised secret loading at one instant but did not force the dispatch path to cross expiry between authorization and signature timestamp capture.
- Resolution evidence: `WebhookEndpointSecretService.signForDelivery` now validates generation state at the exact signature timestamp, decrypts, and signs while holding the endpoint row lock. Exact-expiry proof produces no signer or transport call; `ER-0037` later strengthened the boundary with a forced current-to-previous rotation wait, and `ER-0038` corrected the stale outcome from terminal to retryable no-send convergence.
- Process correction: Time-bounded credential proof must bind authorization to the exact use instant and include forced clock movement or a boundary timestamp between load and cryptographic use.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0026

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Phase 2.5 end-to-end key-rotation review
- Invariant: Startup fails closed if any ciphertext the running product may still decrypt references an unavailable webhook key-encryption-key version.
- Finding: Startup validation inspected only active endpoints' current signing-secret ciphertext and ignored unexpired previous-secret ciphertext plus unexpired completed endpoint-creation replay ciphertext.
- Why prior review missed it: The key inventory was derived from the primary endpoint column rather than from every product path that can request decryption after startup.
- Resolution evidence: `findRequiredSecretKeyVersions` now unions active current ciphertext, active unexpired previous ciphertext, and unexpired generation-matching completed creation replay ciphertext. `requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext` proves inclusion and expiry removal, while startup security tests prove every returned missing version blocks application startup.
- Process correction: KEK readiness checks must be derived from a complete decryptability inventory covering primary, overlap, replay, recovery, and migration-held ciphertext—not a single entity field.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0027

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Phase 2.5 end-to-end crash-recovery review
- Invariant: A process death after durable endpoint-create reservation cannot permanently strand an idempotency key, and recovery cannot steal an active owner's reservation.
- Finding: Endpoint creation reserved an `IN_PROGRESS` row in `REQUIRES_NEW`, but the row had no lease owner or expiry; a process death before completion left every retry returning conflict forever.
- Why prior review missed it: Rollback tests covered ordinary exceptions in the surviving process but omitted fail-stop process death after the independent reservation committed.
- Resolution evidence: V14 adds owner tokens and lease expiries, backfills historical `IN_PROGRESS` rows as immediately reclaimable, and constrains state/lease consistency. H2 tests prove active ownership cannot be stolen and expired ownership is reclaimed. `expiredCreationReservationReclaimSerializesAndReplaysOneEndpoint` forces PostgreSQL row-lock waiting and proves two contenders converge to one endpoint and one replayed secret. `v14MakesPreviouslyStrandedEndpointCreationReservationsReclaimable` proves the shipped upgrade path.
- Process correction: Every independently committed `IN_PROGRESS` protocol must define owner identity, expiry, reclaim semantics, fencing, and a process-death test at the commit boundary.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0028

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Phase 2.5 end-to-end generator-recovery review
- Invariant: Abandoning an ambiguous generator run erases credentials while retaining sanitized tenant/resource references needed for operator reconciliation and cleanup.
- Finding: Abandonment cleared every checkpoint response, including the only durable copy of safe merchant and resource identifiers, so the required cleanup disposition had no actionable resource inventory.
- Why prior review missed it: Redaction proof checked secret removal and terminal state but did not separately freeze the operator promise that sanitized cleanup references survive.
- Resolution evidence: The generator now persists safe IDs in a separate `generator_resources` table before failpoint/checkpoint transitions and backfills references from older completed checkpoints. The ambiguous non-idempotent abandonment test proves merchant identity survives, API keys do not, and all secret-bearing checkpoint responses are cleared.
- Process correction: Recovery journals must classify fields as secret-bearing payload, durable safe reference, or derived summary; erasure tests must prove both confidentiality and retained operability.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0029

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Final Phase 2.5 pre-merge review
- Invariant: A durable generator step's retry-safety classification is immutable and authoritative across process and binary restart.
- Finding: `GeneratorRunStore.execute_step` read the persisted `retry_safe` value but decided from the new caller argument and overwrote the durable value. A previously unsafe `STARTED` merchant/customer step could therefore be reclassified as safe and repeated.
- Why prior review missed it: Restart tests reused the same caller classification and never crossed durable state with binary/configuration drift or an adversarial reclassification.
- Resolution evidence: `GeneratorRunStore.execute_step` now validates the stored classification before any state transition or operation, decides unsafe recovery from that durable value, and never overwrites `retry_safe` on conflict. The SQLite schema constrains the field to `0|1`. `test_persisted_retry_classification_cannot_be_rewritten_on_resume` proves both unsafe-to-safe and safe-to-unsafe drift fail closed without invoking the operation or changing the stored value.
- Process correction: Durable workflow metadata must be treated as protocol state; restart proof must mutate every caller-supplied classification that could contradict the persisted record.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0030

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Final Phase 2.5 pre-merge review
- Invariant: Merchant and customer creation converge to one server object when the generator dies after the API commits but before the local resource/checkpoint write.
- Finding: Merchant/customer creation was non-idempotent. The failpoint ran only after `generator_resources` persistence, so a death immediately after the remote commit could lose both the one-time merchant API key and the resource ID; safe resume could only stop ambiguously, not reconcile the committed object.
- Why prior review missed it: The hostile test injected failure after the client-side resource journal rather than at the earlier distributed commit-to-local-checkpoint boundary.
- Resolution evidence: V15 adds fenced fail-stop leases to general idempotency records and a secret-safe `merchant_creation_records` state machine. Merchant retries bind an HMACed global key to an HMACed request, return the same merchant and deterministically derived API key without persisting either plaintext, and reject changed requests, live leases, blank keys, and stale ownership. Customer retries use the existing merchant-scoped atomic mutation/response transaction. The generator sends stable domain-separated keys and injects its failpoint immediately after the remote response, before all local persistence. H2 API/lease/privacy contracts, deterministic PostgreSQL row-lock waits for both creation paths, V14-to-V15 historical migration proof, and exact-window Python merchant/customer crash-resume proof converge to one server object and restored local resource lineage.
- Process correction: Client restart proof for remote mutations must inject failure immediately after the server response/commit and before every client-side persistence action; local journaling cannot certify server convergence by itself.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0031

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Post-closure Senior Staff payment-idempotency review
- Invariant: An active payment mutation owns and locks its idempotency reservation before any processor, fraud, ledger, webhook, or other mutation work begins, so lease expiry cannot admit a second execution.
- Finding: `IdempotencyService` invoked the mutation supplier before acquiring the reservation row lock. A duplicate request could reclaim an expired lease and invoke the supplier concurrently; the stale database transaction would roll back at completion, but a future external processor authorization could not be rolled back.
- Why prior review missed it: The PostgreSQL test wrapped a completed service call in an outer transaction and proved that a duplicate waited on the already-completed record. It never expired the lease while the first supplier was blocked, so it did not prove the dangerous in-flight ordering.
- Resolution evidence: `IdempotencyService` now locks and validates the reservation owner at the beginning of the operation transaction, before invoking the supplier. `expiredCustomerLeaseCannotBeStolenWhileMutationOwnsTheReservationLock` uses a 100-millisecond lease, blocks the first supplier after the lock is held, proves the lease expires, observes the second caller waiting in PostgreSQL, then proves one supplier execution, one customer, and replay of the first response.
- Process correction: Fenced-lease proof must expire the lease while the protected side effect is still blocked and verify lock acquisition precedes the first irreversible or externally visible operation; holding a lock after service completion is not evidence for in-flight exclusion.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0032

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Post-closure Senior Staff secret-rotation review
- Invariant: Rotating the sensitive-value HMAC pepper during the merchant replay window cannot make an existing external idempotency key appear new, mint a second merchant, or change the replayed API key.
- Finding: Merchant creation derived both its lookup identity and replayed API key only from the currently configured pepper. After rotation, the same external key produced a different database lookup and could create a duplicate merchant with a different API key.
- Why prior review missed it: Privacy tests proved plaintext absence and deterministic replay under one static secret, but the secret-compromise inventory did not rotate the HMAC root between remote commit and retry or consider mixed-version rolling deployment.
- Resolution evidence: V16 adds `derivation_key_version` plus `merchant_creation_identities`, whose unique HMAC aliases bind every configured current/previous key version to one creation record. Replay derives the API key with the record's original version and promotes the new alias. Startup fails closed when an unexpired/in-progress record requires an unavailable version. `merchantCreationReplaySurvivesPepperRotationAndPromotesTheNewIdentityAlias` proves one merchant, the identical API key, two aliases, and missing-previous-key rejection; `rollingPepperVersionsShareOneDatabaseIdentityAndReplayOneMerchant` forces mixed-version PostgreSQL lock waiting and replay; `v16BackfillsVersionedMerchantCreationIdentityWithoutLosingReplayLineage` proves upgrade lineage and database uniqueness.
- Process correction: Deterministic secret-derived identifiers require a rotation grammar covering current, previous, rolling mixed-version writers, database uniqueness, replay derivation, retention, and fail-closed startup—not only same-key cryptographic determinism.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0033

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Post-closure Senior Staff generator-ownership review
- Invariant: A live generator process retains exclusive ownership across every bounded network wait, including verification reads outside checkpointed mutation steps.
- Finding: The generator renewed its 90-second lease around checkpointed steps but performed four sequential 30-second-timeout verification reads and a ledger read without renewal. A slow but successful API could let another process acquire the run while the first remained active.
- Why prior review missed it: Ownership tests covered a stationary active lease and step transitions; they did not accumulate multiple successful network waits outside `execute_step` until the lease boundary was crossed.
- Resolution evidence: `ApiClient` now invokes an owner heartbeat immediately before and after every request, `run_generator` wires it to `GeneratorRunStore.heartbeat`, and startup rejects a run lease that does not exceed one request timeout. `test_api_client_renews_run_ownership_across_slow_sequential_requests` uses a real delayed HTTP server and a one-second lease, runs three calls for longer than the original lease, and proves a second store still cannot acquire the run.
- Process correction: Workflow lease inventories must include validation, polling, and read-only network waits—not only mutation steps—and proof must make cumulative successful latency exceed the original lease while testing a real competing owner.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0034

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Post-closure end-to-end HMAC consumer review
- Invariant: Routine replay-key rotation preserves every live idempotency binding, while long-lived fraud and PII correlation identities do not silently change as a side effect.
- Finding: ER-0032 repaired merchant creation only. Generic idempotency request hashes still used only the current pepper, so a customer, payment-method, PaymentIntent, or confirm retry after rotation conflicted instead of replaying. Webhook endpoint key and request identities also used only the current pepper, so a reused external key appeared new after rotation and could bind a second URL. Card fingerprints plus customer email/phone pseudonyms shared the rotating replay pepper, splitting fraud and data lineage.
- Why prior review missed it: The secret-rotation pass started from the observed merchant duplicate rather than inventorying every persisted output of `SensitiveValueHasher`; the merchant-specific proof was then promoted into a system-wide rotation claim.
- Resolution evidence: V17 stores the original request-HMAC key version on generic and endpoint-creation replay records, backfills historical rows as `v1`, and adds tenant-scoped current/previous webhook identity aliases with composite lineage constraints. Generic, merchant, and webhook startup inventories retain every key required by an unexpired replay window and release expired versions. Rotation contracts prove same-request replay, changed-request conflict, alias promotion, missing-live-key startup failure, expired-key reuse after retirement, deterministic current/previous order, and duplicate pepper material rejection. A forced PostgreSQL rolling-v1/v2 interleaving proves one endpoint/record with two aliases. `CorrelationValueHasher` moves PAN, email, and phone correlation to the separately managed `THAAP_CORRELATION_PEPPER`; the contract test proves those digests remain stable while replay HMACs rotate. Clean and V16-to-V17 migrations, 265 Java tests, and 67 repository Python tests pass with zero skips.
- Process correction: A cryptographic-key rotation review must inventory every producer, persisted column, lookup path, uniqueness boundary, retention rule, and downstream fraud/ML consumer before any member of the boundary family can return to PASS.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-05

## ER-0035

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-05
- Escaped gate: Post-closure generator cleanup-lineage review
- Invariant: Every remotely created generator resource is represented by an explicit durable type and safe identifier so interruption or abandonment has a complete cleanup inventory.
- Finding: Resource classification inferred type from step-name suffixes. The `tenant_isolation.verifier_merchant` step did not match `.merchant`, so its committed merchant never entered `generator_resources`.
- Why prior review missed it: Tests enumerated scenario merchant/customer names but did not compare the complete execution plan's creation steps with the durable resource ledger.
- Resolution evidence: `generator_steps.resource_type` is now explicit immutable protocol state, every production resource-creation call declares its type, and resource recording consumes that metadata instead of step-name suffixes. The additive SQLite upgrade backfills both completed and interrupted legacy rows, explicitly maps `tenant_isolation.verifier_merchant`, and reconstructs safe references from completed responses. Tests reject resource-type drift before operation execution, reconcile child resources to merchant lineage, prove the verifier merchant is present, and scan retained state for secret absence. The complete 67-test repository suite passes.
- Process correction: Durable lineage must use explicit typed operation metadata, and tests must reconcile every resource-producing plan step against the resulting ledger instead of sampling known naming conventions.
- Primary DRI: ML/Data Platform
- Last reviewed: 2026-07-05

## ER-0036

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Post-closure merchant replay lifecycle review
- Invariant: Reusing an expired merchant-creation idempotency key starts a distinct lifecycle epoch while replay within the live window returns the original merchant and API key.
- Finding: The API key derivation used only the idempotency-key hash and request hash. With an unchanged pepper, deleting an expired creation record and repeating the same request regenerated the original merchant's API key and collided with the permanent unique merchant API-key hash.
- Why prior review missed it: Expired-key reuse proof rotated the pepper at the same time, which changed the derived API key and masked the same-pepper partition.
- Resolution evidence: API-key derivation now includes the immutable creation-record UUID as a lifecycle epoch while retaining the record's original HMAC key version for live replay. Expired-record deletion flushes and clears the persistence context before the alias is recreated, preventing a deleted alias from being updated at commit. `expiredMerchantCreationStartsNewCredentialEpochWithoutPepperRotation` proves that identical external key, request, and pepper create a different merchant and API key only after expiry, while the existing live replay and rotation tests still return the original credential.
- Process correction: Deterministic replay proof must distinguish replay identity from lifecycle epoch and test same-key/same-request reuse both with and without key rotation.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-11

## ER-0037

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Post-closure cryptographic-use interleaving review
- Invariant: A webhook signature is produced only while its bound generation is current or strictly inside its previous-generation grace window.
- Finding: Authorization returned an unbounded use window while a generation was current, then released the endpoint lock before timestamp capture and signing. A concurrent rotation could move that generation to previous and let it expire before the worker signed with it.
- Why prior review missed it: The expiry test started with an already-previous generation and moved the clock between load and authorization; it never rotated a current generation after authorization but before cryptographic use.
- Resolution evidence: `WebhookEndpointSecretService.signForDelivery` now owns one transaction that locks the tenant-scoped endpoint row, validates the claim-bound generation at the exact signature timestamp, decrypts only that generation, and invokes the signer before releasing the row lock. `rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed` blocks inside the real signer, observes PostgreSQL rotation waiting on the endpoint lock, verifies the produced signature with the original secret, and proves rotation completes only afterward; the before/at/after expiry contract rejects the previous generation exactly at expiry.
- Process correction: Exact-use proof must force every state transition that can occur after authorization and before use, including current-to-previous rotation under the real endpoint lock.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-11

## ER-0038

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Post-closure webhook recovery-semantics review
- Invariant: Expiry of a claim-bound previous signing generation never discards a delivery that an active endpoint can retry with its current generation.
- Finding: Every `WebhookSecretException` finalized terminally. A stale previous generation therefore exhausted the delivery instead of scheduling a no-send retry whose next claim could bind the current generation.
- Why prior review missed it: The exact-expiry test asserted fail-closed cryptographic behavior but did not separately prove the at-least-once delivery recovery outcome.
- Resolution evidence: Stale or expired claim-bound generations now raise `WebhookStaleSecretGenerationException`, which the executor maps to retryable no-send `SIGNING_SECRET_UNAVAILABLE`; signing-engine failures remain known-no-send failures and unexpected database/runtime failures remain lease-recoverable. `expiredClaimGenerationRetriesAndNextClaimBindsCurrentSecret` rotates after claim, reaches exact expiry, proves zero transport calls and a retry schedule, then proves the next claim binds the current generation. Executor unit tests separately prove stale-generation, signing-failure, and unexpected-runtime partitions.
- Process correction: Error families must be partitioned by recovery semantics—permanent integrity/configuration failure versus stale snapshot that can converge on current durable state.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-11

## ER-0039

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Post-closure startup-readiness review
- Invariant: Startup inventories every KEK required by any enabled webhook administration or delivery path, independent of scheduler execution mode.
- Finding: `WebhookExecutionConfiguration` returned before persisted KEK inventory when delivery execution was disabled, even though endpoint replay and rotation APIs remained available and could request those ciphertexts.
- Why prior review missed it: Readiness proof exercised only execution-enabled startup and coupled worker configuration with general encrypted-state availability.
- Resolution evidence: `WebhookExecutionConfiguration.validate` inventories every persisted required webhook KEK before applying the delivery-worker enable flag; only worker-specific URL/timeout/lease checks remain conditional. `disabledExecutionStillFailsClosedWhenAdministrationNeedsUnavailableKey` starts validation with execution disabled and proves an unavailable persisted key version still aborts readiness, while enabled/disabled scheduler behavior remains covered independently.
- Process correction: Startup dependency inventories must be derived from reachable product capabilities, not from one background worker's enable flag.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-11

## ER-0040

- Status: RESOLVED
- Severity: P2
- Chapter: 21
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Post-closure endpoint-creation fencing review
- Invariant: A live endpoint-creation owner locks and validates its reservation before endpoint, subscription, or audit mutation, so lease expiry cannot admit a competing worker.
- Finding: Endpoint creation acquired the reservation lock only after creating endpoint, subscription, and audit state. A healthy operation delayed beyond the lease could be reclaimed and lose ownership during mutation.
- Why prior review missed it: The PostgreSQL test held an outer transaction after the service had completed; it proved post-completion waiting rather than lease expiry while protected work was still blocked.
- Resolution evidence: `WebhookEndpointService.createOnce` now locks and validates the reservation owner at operation-transaction entry, before secret generation, endpoint insertion, subscription persistence, or audit. `liveEndpointCreationLockSurvivesLeaseExpiryBeforeFirstMutationCompletes` blocks the first worker on a real PostgreSQL endpoint insert after reservation lock acquisition, advances time beyond the lease, observes the second worker waiting on the reservation row, and proves both callers converge to one endpoint and one replayed secret after the blocker rolls back.
- Process correction: Fenced-lease tests must expire ownership while the first worker is blocked immediately after acquiring the real reservation lock and before the first protected mutation.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-11

## ER-0041

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Five-slice transition-table recovery review
- Invariant: A previous webhook signing-secret generation is validated against the actual cryptographic-use instant after every database-lock wait.
- Finding: The executor captured `signatureTime` before calling `signForDelivery`. The service then waited for the endpoint row lock and validated the generation using that earlier timestamp. If rotation held the lock until after previous-secret expiry, the worker could acquire the lock after expiry and still sign using a timestamp captured before expiry.
- Why prior review missed it: The forced rotation test blocked rotation behind an active signer, but no test reversed the order by blocking a previous-generation signer behind the endpoint lock while wall time crossed expiry. The review asked whether rotation could occur after authorization, but did not ask what could change while acquiring the authorization lock itself.
- Resolution evidence: `WebhookEndpointSecretService.signForDelivery` now acquires the tenant-scoped endpoint row lock before capturing `clock.instant()`, treats the executor's preparation time only as a lower bound, then validates, decrypts, and signs before releasing the lock. `previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry` holds the endpoint lock, starts a previous-generation signer, observes its PostgreSQL lock wait, advances the service clock to exact expiry, and proves zero signer calls after acquisition.
- Process correction: Every time-based protocol transition table must distinguish check time, lock-acquisition time, use time, and persisted timestamp; exact-use proof must force elapsed time across every lock wait in both orderings.
- Primary DRI: Payments Core
- Last reviewed: 2026-07-11

## ER-0042

- Status: RESOLVED
- Severity: P1
- Chapter: 21
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-11
- Escaped gate: Five-slice transition-table recovery review
- Invariant: An active endpoint never loses its first real current-generation send opportunity solely because an internal claim-bound generation became stale before HTTP.
- Finding: Stale-generation handling called the generic retry finalizer. On attempt five, the retry policy exhausted the delivery even though the stale snapshot produced no signature and no HTTP request, so the promised next claim could never bind the current generation.
- Why prior review missed it: The recovery contract exercised stale generation only on attempt one. The error-classification review separated retryable from terminal errors but did not cross each known-no-send class with the exact retry ceiling.
- Resolution evidence: Attempts below the ceiling retain retryable known-no-send stale-generation evidence. On the final allowed attempt, `WebhookDeliveryExecutor` catches only `WebhookStaleSecretGenerationException` and rebinds current endpoint secret state before transport in the same claim; permanent secret failures retain their prior classification. `finalAttemptStaleGenerationRebindsCurrentSecretBeforeHttp` proves control flow and `finalAttemptStaleGenerationUsesCurrentSecretBeforeFirstHttpSend` verifies the emitted HMAC with the rotated current secret and a successful durable delivery.
- Process correction: Every retry/error transition table must cross each failure class with first, intermediate, and final retry positions and state whether internal pre-send failures consume the merchant-visible send budget.
- Primary DRI: Payments Core
- Last reviewed: 2026-07-11

## ER-0043

- Status: RESOLVED
- Severity: P1
- Chapter: 22
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-12
- Escaped gate: Post-closure Senior Staff feature-type lineage review
- Invariant: Every immutable FeatureValue must declare the same value type as its referenced immutable FeatureDefinition.
- Finding: The composite definition foreign key bound definition ID, code, and version but omitted `value_type`, so direct PostgreSQL writes could attach a LONG value to a STRING definition and make the corrupt row immutable.
- Why prior review missed it: Database tests crossed definition ID with feature code and crossed declared type with populated columns, but never crossed a valid definition triple with each incompatible declared value type.
- Resolution evidence: The originally shipped V18 bytes remain unchanged; forward V22 and the JPA mapping make `(definition ID, code, version, value type)` one identity. `databaseBindsEveryDeclaredValueTypeToTheReferencedDefinitionType` crosses every seeded definition with LONG, STRING, BOOLEAN, and DECIMAL, accepts only its required type, and rejects all incompatible types on PostgreSQL while the full suite passes without skips.
- Process correction: Composite lineage reviews must compare every copied semantic discriminator with the referenced source row; typed-column shape alone cannot certify definition/type agreement.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-12

## ER-0044

- Status: RESOLVED
- Severity: P1
- Chapter: 22
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-12
- Escaped gate: Post-closure Senior Staff decision-input review
- Invariant: The immutable FeatureSnapshot and typed FeatureValues must be the exact governed values available to the fraud decision engine.
- Finding: Confirm materialized the governed seven-feature set but passed the engine a separately reconstructed request containing BIN, last4, and resource IDs absent from the snapshot while omitting the governed traffic-origin feature.
- Why prior review missed it: Tests reconciled materialized rows with the snapshot and separately verified allow-only engine behavior, but never asserted object-level equality between the engine input and persisted decision evidence.
- Resolution evidence: `DecisionFeatureContext` admits only the exact seven-code typed catalogue and is now the sole `ConfirmDecisionEngine` input. `MaterializedFeatureSet` rejects context/value disagreement. Unit partitions reject missing, extra, wrong-type, and mutation attempts, while `PaymentIntentConfirmProcessorAdapterCallTests` captures the real engine argument and proves exact equality with the persisted snapshot and seven typed rows plus absence of prohibited fields.
- Process correction: Decision-lineage review must trace one feature object from materialization through evaluation and persistence; equal-looking reconstructions are not acceptable evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-12

## ER-0045

- Status: RESOLVED
- Severity: P2
- Chapter: 22
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-12
- Escaped gate: Post-closure Senior Staff transaction-boundary review
- Invariant: A real SQL failure while flushing decision lineage occurs before processor authorization and rolls back attempt, decision, snapshot, values, and idempotency ownership so one coherent retry can succeed.
- Finding: Rollback tests threw from mocked repository `save` calls, PostgreSQL constraint tests ran outside confirm, and the forced interleaving proved that flush blocks the processor but never proved the failure-and-retry path at PostgreSQL.
- Why prior review missed it: The review combined mock position coverage, isolated database constraints, and a successful blocked flush into one transaction claim without forcing a constraint failure through the real confirm transaction.
- Resolution evidence: `realFeatureFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry` corrupts one otherwise valid materialized row so PostgreSQL rejects it at the explicit `featureValueRepository.flush()`. It proves zero processor calls; zero attempt, decision, snapshot, value, and authorization rows; deletion of the failed confirm reservation; unchanged intent state; and one successful same-key retry with one complete lineage and completed replay record.
- Process correction: Transaction proof must inject failure at the actual deferred SQL boundary; a mock before SQL and a successful database wait cannot be composed into failure evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-12

## ER-0046

- Status: RESOLVED
- Severity: P1
- Chapter: 22
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-13
- Escaped gate: Phase 3 retained-database public demo startup
- Invariant: An already-applied Chapter 22 database must validate and migrate forward without editing the checksum or semantics of V18 in place.
- Finding: ER-0043 repaired the definition/value type foreign key by editing V18 after the retained local database had already applied the original V18. Flyway therefore rejects startup on checksum validation before any later repair migration can run.
- Why prior review missed it: Fresh-schema and V17-to-current tests both resolved V18 from the final source tree; neither test recreated the actually shipped original V18 checksum and then upgraded through the post-closure repair.
- Resolution evidence: V18 is restored to the exact shipped checksum `1512291140`; V22 forward-migrates the three-column definition identity to the required four-column type-bound identity. `fraudLineageUpgradePreservesHistoricalTruthAndBindsFraudActionLineage` stops a real PostgreSQL schema at V19, asserts the shipped checksum and historical bytes, then migrates through V24 and proves immutable catalogues, legacy preservation, semantic foreign keys, append-only review history, and selective webhook-subscription preservation. The retained local V19 database validated without repair, migrated through V22, passed Hibernate/readiness, and completed the all-action demo; it later migrated V22→V24 successfully before correctly failing unrelated webhook-key readiness when that historical key was intentionally unavailable.
- Process correction: Once any migration has been applied by a supported demo or environment, every later repair must use a new forward migration; upgrade proof must start from the exact shipped migration bytes/checksum, not only an older target resolved from the current source tree.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-13

## ER-0047

- Status: RESOLVED
- Severity: P1
- Chapter: 25
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-13
- Escaped gate: Post-closure Senior Staff webhook-consent review
- Invariant: A migration may not subscribe an existing merchant endpoint to newly introduced fraud or review event types without durable evidence of explicit wildcard consent.
- Finding: V20 added four new fraud subscriptions to every endpoint and V23 removed them only from endpoints with fewer than all five legacy subscriptions. Because the old schema stored the same five rows for an explicitly selected full set and the omitted-field default, an exact-five endpoint was silently expanded without distinguishable consent.
- Why prior review missed it: Upgrade proof covered a selective one-event endpoint but omitted the equally valid exact-all legacy partition and treated row-count equality as wildcard intent without a durable subscription-mode field.
- Resolution evidence: Forward-only V25 removes the four V20-added event rows from every legacy endpoint whose old and new subscriptions have different creation cohorts. `PostgreSqlMigrationTests.fraudLineageUpgradePreservesHistoricalTruthAndBindsFraudActionLineage` proves an explicit one-event endpoint remains at one, an exact-five legacy endpoint is repaired from nine at V24 to five at V25, and a post-Phase-3 endpoint whose nine rows share one cohort remains at nine. Fresh H2/PostgreSQL migration and Hibernate validation also pass through V25.
- Process correction: Every subscription expansion must enumerate explicit subset, explicit full set, omitted default, and durable wildcard representations; absence of a wildcard discriminator requires conservative no-expansion migration behavior.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-13

## ER-0048

- Status: RESOLVED
- Severity: P1
- Chapter: 23
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-13
- Escaped gate: Post-closure Senior Staff transaction-boundary review
- Invariant: A real PostgreSQL rule-result constraint failure at flush must occur before processor authorization and roll back every confirm artifact while permitting one coherent same-key retry.
- Finding: Rule-result rollback tests failed mocked repository save calls, while the only real confirm-path PostgreSQL failure corrupted a FeatureValue. Direct rule semantic constraint tests did not execute through confirm, idempotency ownership, or the processor boundary.
- Why prior review missed it: The closure composed mock position coverage, isolated SQL rejection, and successful rule-table lock waits into a claim about deferred rule-result flush failure.
- Resolution evidence: `FeatureLineagePostgreSqlConcurrencyTests.realRuleResultFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry` corrupts copied action semantics so `fk_rule_results_rule_semantics` fails at the production `ruleExecutionResultRepository.flush()` boundary. It proves zero processor calls and zero attempt/decision/snapshot/value/result/authorization rows, reservation deletion, restored intent state, then one coherent same-key retry with exactly one complete lineage and processor call.
- Process correction: Each new deferred SQL family requires at least one constraint failure through the production transaction, not evidence borrowed from an adjacent persistence family.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-13

## ER-0049

- Status: RESOLVED
- Severity: P2
- Chapter: 24
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-13
- Escaped gate: Post-closure Senior Staff observability review
- Invariant: Decision and review-cancellation success or rollback must emit bounded, contract-named operational signals only after the transaction outcome is known.
- Finding: Engine failure used `fraud_rule_decision_failures_total` instead of the frozen `fraud_decision_failures_total` family, decision/review rollback after evaluation had no outcome observer, and cancellation exposed only a success counter.
- Why prior review missed it: The metric test invoked failure recording but asserted neither its meter name nor count, and happy-path after-commit tests were treated as proof of rollback observability.
- Resolution evidence: `FraudDecisionMetrics` now uses the frozen `fraud_decision_failures_total{stage,cause,feature_code}` family and transaction-completion observers for decision and cancellation success/rollback. Cancellation rollback also emits `fraud_review_case_cancellation_failures_total{status,traffic_origin,cause}`. `FraudDecisionMetricsTests` proves exact commit/rollback meter names, tags, and counts; `PaymentIntentConfirmContractTests.cancelReviewWebhookFailureRollsBackAllStateAndEmitsBoundedFailureMetrics` proves the real cancellation/webhook rollback retains open payment/review truth and releases idempotency ownership while emitting only bounded failure signals.
- Process correction: Metric proof must assert exact names, complete tag sets, timing relative to commit, and every promised rollback partition.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-13

## ER-0050

- Status: RESOLVED
- Severity: P2
- Chapter: 24
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-13
- Escaped gate: Post-closure Senior Staff API boundary-family review
- Invariant: A PASS pagination claim must enumerate limits, empty and repeated pages, equal sort timestamps, malformed/missing/foreign cursors, status filters, and unsupported mutation methods at the public API boundary.
- Finding: C24-API-001 claimed all named partitions, but tests covered only ordinary pagination, a foreign cursor, 0/101 limits, and a one-row PostgreSQL cursor query.
- Why prior review missed it: The proof review matched broad test names to matrix prose without tracing each enumerated partition to an executable assertion.
- Resolution evidence: `PaymentIntentConfirmContractTests.reviewListIsBoundedStableAndTenantScoped` now proves empty and status-filtered reads, limits 0/1/100, repeated-page equality, malformed/missing/foreign cursors, and unsupported POST as bounded 405; the 101 upper bound remains covered by the existing validation assertion. `FeatureLineagePostgreSqlConcurrencyTests.reviewCursorPaginationUsesStablePostgreSqlOrderingForEqualTimestamps` forces three same-merchant rows to one timestamp and proves 2+1 keyset pages with no skips or duplicates on PostgreSQL. The 405 repair is enforced centrally by `GlobalApiExceptionHandler` with an `Allow` header.
- Process correction: Every matrix partition must map to a named assertion or be explicitly rejected as unreachable with enforcement evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-13

## ER-0051

- Status: RESOLVED
- Severity: P2
- Chapter: Cross-chapter 17-34 artifact naming
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: NO
- Date found: 2026-07-25
- Escaped gate: Completed Chapter 17-25 reviews and Phase 4 pre-closure artifact review
- Invariant: Durable executable artifacts must be named for the payment, fraud, security, data, or operational capability they implement or prove; chapter identifiers belong only to planning, learning, and chapter-governance artifacts.
- Finding: Fifty production migration, Java test, demo, and demo-test artifact paths used temporary chapter identifiers, and the same leakage appeared in executable class names, test methods, fixture namespaces, database functions, runtime messages, and report names. Two chapter-closure validator scripts were correctly chapter-named because chapter governance is their actual responsibility.
- Why prior review missed it: Implementation traceability was optimized by copying chapter identifiers into executable names, an explicit founder warning was not converted into a durable repository rule, and readiness had no automated boundary distinguishing chapter-governance artifacts from product executables.
- Resolution evidence: All fifty inappropriate artifact paths and their executable symbols/references now use capability names; the clean Maven suite passes 612 tests with zero failures, errors, skips, or stale chapter-named compiled classes; all 113 repository Python tests pass; Flyway 11.14.1 repair and validation accept the renamed V27 migration in the exact local Docker database; fresh migrations create capability-named append-only functions; and `executable_artifact_naming_check_tests.py` proves path, class, fixture, immutable-migration, and governance exceptions before `executable-artifact-naming-check.py` passes the repository.
- Process correction: `AGENTS.md` now defines the durable artifact naming boundary, the commit checklist and scripts guide require the naming check, and `commit-readiness-check.sh` runs the tested checker automatically with exact allowlists only for chapter-governance tools and immutable applied migration evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0052

- Status: RESOLVED
- Severity: P1
- Chapter: 30
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure Senior Staff HMAC-rotation review
- Invariant: Velocity observations inside an active decision window remain visible and conservatively counted across a configured sensitive-value HMAC rotation.
- Finding: Velocity Redis keys and logical-attempt members were derived only with the current pepper, so rotation abandoned every still-live previous-version window and could weaken REVIEW to ALLOW.
- Why prior review missed it: Rotation proof checked key readiness and HMAC-only representation but never populated a real Redis window with the previous key and evaluated it with the rolling keyring.
- Resolution evidence: `VelocityIdentityFactory` now produces current and retained-version key/member pairs, and `VelocityRiskCoordinator` dual-writes while taking the maximum compatible window count so the current attempt is not double-counted. `RedisVelocityScriptIntegrationTests.rollingKeyringKeepsPreviousWindowVisibleWithoutDoubleCountingCurrentAttempt` places three attempts in a real Redis previous-version window, evaluates through a rolling keyring, and proves REVIEW with count three plus one current member in both versioned windows.
- Process correction: Every ephemeral HMAC identity review must cross key rotation with the full retention window and prove the decision postcondition, not only startup key availability.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0053

- Status: RESOLVED
- Severity: P1
- Chapter: 30
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure Senior Staff rollback/retry review
- Invariant: Compensation for a rolled-back velocity observation cannot delete evidence reserved by a newer retry of the same logical payment attempt.
- Finding: After-completion compensation unconditionally removed a member stable across retries, allowing an older rollback callback to delete a newer retry after the PostgreSQL lock was released.
- Why prior review missed it: Tests invoked rollback callbacks directly but never forced a retry to reserve the same logical member between database rollback and compensation.
- Resolution evidence: `velocity_v1.lua` creates a random reservation owner only when it inserts a new member; exact-byte `velocity_compensation_v1.lua` atomically compares that token before `ZREM`. Real Redis tests prove an old callback returns `STALE` without deleting a newer insertion and a retry rollback cannot delete an already-existing logical attempt. Exact SHA and every closed compensation result/malformed partition are contract-tested.
- Process correction: Every rollback compensation proof must cross resource reuse by the immediate retry and demonstrate that an old cleanup token cannot remove new ownership.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0054

- Status: RESOLVED
- Severity: P1
- Chapter: 31
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure Senior Staff fraud-list rotation review
- Invariant: Active IP, device, and session fraud-list identities continue matching newly materialized RiskContext identities throughout retained-key rotation.
- Finding: Fraud-list evaluation applied an old outer HMAC to a newly rotated inner RiskContext HMAC, which can never reproduce the stored old-inner and old-outer digest.
- Why prior review missed it: Tests covered list-key readiness and normalized values independently but omitted the cross-product of inner RiskContext version and outer fraud-list entry version.
- Resolution evidence: V49 adds immutable retained-version IP/device/session aliases to `risk_contexts`; `RiskContextService` derives every inner identity before raw inputs are discarded, and `FraudListIdentityService` selects the inner alias matching the entry's outer HMAC version. `RiskContextIdentityRotationTests` proves alias persistence and `FraudListIdentityRotationTests` proves old-version IP, device, and session entries all match under a rolling keyring.
- Process correction: Nested pseudonymization designs must document and test every versioned layer together; outer-key availability cannot certify inner-identity compatibility.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0055

- Status: RESOLVED
- Severity: P1
- Chapter: 26
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure Senior Staff authorization-before-replay review
- Invariant: A risk-operator mutation replay is authorized for the same authenticated actor and credential lifecycle before any stored success is returned.
- Finding: Risk controllers converted operator context to merchant-only idempotency identity, so another operator could replay a privileged response before service-level role and actor checks executed.
- Why prior review missed it: Authorization tests covered fresh mutations and idempotency tests covered same-actor replay, but the two families were not crossed with a lower-role second operator.
- Resolution evidence: V49 scopes each idempotency row and uniqueness boundary by merchant, principal type, principal ID, credential prefix, and key; all risk-operator controllers pass authenticated `RiskOperatorContext` directly to `IdempotencyService`. `completedMutationReplayIsBoundToTheOriginalOperatorAndCredentialLifecycle` proves exact same-actor replay while a second lower-role actor using the identical key/body receives missing-equivalent denial and cannot consume the first actor's stored success. Fresh H2/PostgreSQL and retained V48-to-V49 migration tests prove schema, constraints, and backfill.
- Process correction: Every authenticated idempotency boundary must prove authorization before replay for same actor, different actor, disabled credential, and lower-role partitions.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0056

- Status: RESOLVED
- Severity: P2
- Chapter: 33
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure Senior Staff post-authorization lineage review
- Invariant: A signal response reports only the review action and review identifier caused by that exact immutable source signal.
- Finding: Duplicate creation and GET selected any post-authorization review for the payment, allowing a prior no-action signal to later report another signal's review and changed action.
- Why prior review missed it: Tests proved concurrent duplicates of one review signal but never replayed or read an earlier no-action signal after a later signal opened a review on the same payment.
- Resolution evidence: POST duplicate and GET response assembly now consult review state only for `REVIEW` recommendations and derive `REVIEW_OPENED` versus `REVIEW_ALREADY_OPEN` from the exact `source_post_authorization_signal_id`. `laterReviewCannotRewriteEarlierNoActionSignalResponseLineage` proves a prior `NO_ACTION` signal remains `actionTaken=NONE` with no review ID after a later signal opens a review for the same payment.
- Process correction: Point-in-time response proof must cross multiple same-resource source events and verify every returned lineage identifier against the exact source row.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0057

- Status: RESOLVED
- Severity: P2
- Chapter: 35
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-25
- Escaped gate: Post-closure exact-evidence reconciliation
- Invariant: Every source-of-truth document names the exact test count produced by the current repository discovery run.
- Finding: The closure dossier, matrix, READMEs, detailed plan, and master plan claimed 107 Python tests after the executable naming gate increased discovery to 113.
- Why prior review missed it: The readiness gate executed the suite but did not compare its discovered count with exact numeric claims across completion documents.
- Resolution evidence: Current completion sources are reconciled to the final clean Maven and repository discovery outputs: 629 Java tests and 114 Python tests, with zero failures, errors, or skips. Historical dated evidence remains unchanged where it accurately records the suite at that earlier review point.
- Process correction: Exact test-count claims require an automated repository-wide reconciliation or must be generated from the same completed test invocation.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-25

## ER-0058

- Status: RESOLVED
- Severity: P2
- Chapter: 39
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Chapter 43 integrated public-demo reconciliation
- Invariant: The governed-controls public demo must either satisfy the production-safe simulation activation population/coverage gate or explicitly configure and document a local-only evidence threshold without weakening the runtime default or bypassing any other activation check.
- Finding: `demo-governed-controls.py` builds a deliberate one-row cohort, while `RiskSimulationActivationGate` defaults to 100 eligible rows and 50% label coverage. Chapter 39 claimed the public demo passed against the current binary, but no durable public instruction named the local-only `1`/`0` threshold configuration, so a documented/default retained runtime correctly returned `409` with `INSUFFICIENT_ELIGIBLE_POPULATION` and `INSUFFICIENT_LABEL_COVERAGE`.
- Why prior review missed it: The earlier live shell carried reduced Spring properties used by focused activation proof, and closure recorded the successful output without reconstructing the runtime from public documentation and production defaults.
- Resolution evidence: `application.yml` now exposes explicit `THAAP_SIMULATION_GATE_MINIMUM_ELIGIBLE_POPULATION` and `THAAP_SIMULATION_GATE_MINIMUM_LABEL_COVERAGE_PERCENT` variables while retaining defaults `100` and `50`. `scripts/README.md` and the payments API README document the local-only `1`/`0` proof configuration, warn against shared use, and state that exact successful-run, candidate, freshness, tenant, creator, and dual-control checks remain enforced. Chapter 43 restarts the retained V64 API with only those evidence thresholds reduced and reruns the complete governed-rule/exception/rollback demo plus the activation contracts.
- Process correction: Every public demo closure claim must be reproduced from a clean runtime whose complete non-default configuration is present in durable user-facing instructions; successful output from an inherited developer shell is not documentation proof.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0059

- Status: RESOLVED
- Severity: P1
- Chapter: 43R
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Phase 4.5 post-closure recovery audit
- Invariant: An integrated Phase 4.5 completion claim must execute and pass every inherited Chapter 36–43 closure contract, not only the Chapter 43 matrix and dossier supplied to the readiness gate.
- Finding: Phase 4.5 was declared complete although the closure validator rejected Chapter 36's README completion marker and the exact failure-inventory, boundary-evidence, and migration-evidence sections in the Chapter 38 and 39 dossiers.
- Why prior review missed it: The integrated readiness invocation validated only the supplied Chapter 43 artifacts and did not aggregate the inherited chapter closure contracts.
- Resolution evidence: Chapter 36's README marker and Chapter 38/39 dossier sections are repaired. `phase-closure-check.py` independently validates Chapters 36–43 and 43R; its negative/positive unit tests and the final aggregate invocation pass.
- Process correction: Integrated Phase 4.5 readiness now automatically enumerates every inherited matrix/dossier pair when Chapter 43 or 43R is supplied.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0060

- Status: RESOLVED
- Severity: P1
- Chapter: 43R
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Phase 4.5 product-surface traceability audit
- Invariant: The shipped cockpit must expose the promised merchant monitoring summary and cohort lineage plus endpoint verification and actionable idempotency guidance.
- Finding: The backend capabilities existed, but the cockpit omitted monitoring summary/cohort drill-down, webhook endpoint verification, and concrete retry-key guidance.
- Why prior review missed it: Earlier closure treated backend API and isolated UI slices as sufficient without tracing every approved product promise through the integrated cockpit.
- Resolution evidence: The cockpit now renders all nine monitoring metrics with exact evidence and cohort drill-down, starts merchant-authorized endpoint verification, and gives actionable stable-key retry guidance. Thirteen UI tests/build, all seven public clients, and the isolated V64 browser pass succeed.
- Process correction: The Chapter 43R matrix/dossier maps every approved product promise to a public route, visible control, real enforcement boundary, and exact proof.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0061

- Status: RESOLVED
- Severity: P1
- Chapter: 43R
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Phase 4.5 hostile browser-proof audit
- Invariant: Browser proof must cover role visibility, tenant substitution, stale conflicts, exact idempotent retry, and loading, empty, partial, denied, and dependency-failure states without secret disclosure.
- Finding: Seven component tests and a happy-path browser walkthrough did not prove the complete hostile browser boundary family.
- Why prior review missed it: The earlier proof sampled successful workflows and accessibility but did not enumerate the browser failure and authorization partitions frozen by the plan.
- Resolution evidence: UI contracts prove authentication/denied/stale/dependency states, exact idempotent retry, partial/empty/loading behavior, endpoint authority, and role visibility. Live browser proof verifies manager monitoring/cohort, VIEWER read-only rendering, and credential clearing on reload; tenant substitution remains covered by backend two-tenant contracts.
- Process correction: The recovery matrix freezes the browser boundary table before implementation, and the dossier requires component plus retained-stack browser evidence before closure.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0062

- Status: RESOLVED
- Severity: P1
- Chapter: 43R
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Developer scenario mutation-contract audit
- Invariant: Every developer scenario mutation must require a generic actor-scoped idempotency key in addition to lease owner and lease epoch protection.
- Finding: Scenario creation required `Idempotency-Key`, but claim, step completion, and fail mutations did not pass through the generic idempotency boundary.
- Why prior review missed it: Lease/version replay semantics were incorrectly treated as a substitute for transport-level request deduplication.
- Resolution evidence: Claim, complete, and fail authorize the runner before actor-scoped `IdempotencyService` execution; UI/Python clients send stable operation keys. Missing-key, exact-replay, changed-body, missing-runner, lease/event, PostgreSQL concurrency, full regression, and public demo proof pass.
- Process correction: The Chapter 43R mutation inventory checks transport idempotency and domain lease/version fencing as independent required layers.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0063

- Status: RESOLVED
- Severity: P2
- Chapter: 43R
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Frontend dependency and architecture reconciliation
- Invariant: The approved frontend architecture must match the shipped dependency graph, or an explicit evidence-backed recovery decision must replace the unimplemented promise.
- Finding: The plan named TanStack Query, Tailwind CSS, and Recharts while the cockpit package shipped only React, Vite, TypeScript, and handwritten CSS.
- Why prior review missed it: Architecture prose was not reconciled against `package.json` and the product's actual visualization needs.
- Resolution evidence: `@tanstack/react-query` now owns bounded non-secret session/monitoring server state with explicit invalidation and disconnect clearing. The detailed plan and cockpit README record the evidence-table/handwritten-CSS decision and reject Tailwind/Recharts until a frozen visualization promise justifies them.
- Process correction: Chapter 43R closure reconciles the plan, package manifest, runtime use, production build, and dependency rationale as one UI evidence boundary.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0064

- Status: RESOLVED
- Severity: P1
- Chapter: 36
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff point-in-time monitoring review
- Invariant: A monitoring result and its drill-down must observe only evidence available at the declared `asOf`, so appending later evidence cannot rewrite an earlier report.
- Finding: `ENUMERATION_RATIO` treated every incident evidence link as visible without requiring `linked_at <= asOf`; the summary and cohort could therefore change for an unchanged historical window after a later card-testing link was appended.
- Why prior review missed it: Point-in-time tests covered late chargebacks and labels but generalized that evidence to the incident-link family without enumerating its distinct observed-versus-linked availability boundary.
- Resolution evidence: Summary and cohort SQL now require both authorization creation and incident association availability at `asOf`. `MerchantRiskMonitoringPostgreSqlTests` passed 7/7 on PostgreSQL 16.14, including the before/exact/after `linked_at` family. Its semantic-drift fixture now uses a deliberately privileged, test-only trigger suspension to inject corruption; ordinary taxonomy UPDATE/DELETE remains rejected by the separate migration contract. The complete backend suite then passed 759/759 with no skips.
- Process correction: Every point-in-time review must inventory and test the availability column for each joined evidence table rather than borrowing proof from another late-evidence family.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0065

- Status: RESOLVED
- Severity: P1
- Chapter: 38
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff counterfactual-decision review
- Invariant: Historical simulation must preserve the complete decision-time production control stack and replace or suppress only the exact candidate family being evaluated.
- Finding: The simulation baseline loaded legacy rule, fraud-list, and velocity evidence but omitted merchant-policy and authored-rule evaluations, so policy, rule, and safety-exception reports could calculate a counterfactual action that production never would have produced.
- Why prior review missed it: The simulation proof used a single isolated candidate and source inspection, then treated the presence of generic control evidence as proof of mixed-control reconstruction.
- Resolution evidence: The processor now reconstructs legacy, policy, and authored-rule evidence; authored candidates replace the immutable `rule_code` family, while safety exceptions suppress only an exact historically evaluated source version and preserve its recorded effective action. `HistoricalRiskSimulationPostgreSqlTests` passed 8/8 on PostgreSQL 16.14, including mixed-control reconstruction and injected chunk/finalization restart edges; the complete backend suite then passed 759/759 with no skips.
- Process correction: Counterfactual proof must use cross-family fixtures and explicitly show which historical control is preserved, replaced, or suppressed for every candidate type.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0066

- Status: RESOLVED
- Severity: P1
- Chapter: 42
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff webhook lifecycle review
- Invariant: A webhook verification challenge may be dispatched only when its locked projection is still pending, unexpired, and bound to the current active endpoint generation.
- Finding: `markDispatchStarted` silently returned for a terminal challenge while its caller continued to sign and send, allowing expiry or recovery to win the state transition yet lose the external-side-effect race.
- Why prior review missed it: Existing tests forced secret rotation and standalone expiry but omitted expiry/recovery winning specifically between the pre-dispatch clock check and the dispatch-state lock.
- Resolution evidence: Verification now signs the exact endpoint generation and then acquires a locked dispatch permit that revalidates pending, expiry, endpoint status, URL, generation, and challenge identity before any transport call. `WebhookDeliveryPostgreSqlConcurrencyTests.terminalVerificationWinnerAfterSigningRevokesDispatchBeforeAnyNetworkSideEffect` passed on PostgreSQL 16.14 and proves a post-sign terminal winner leaves `dispatch_started_at` null and makes zero transport calls; the complete backend suite then passed 759/759 with no skips.
- Process correction: Every external-I/O preparation method must return an explicit permit/outcome, and hostile tests must force every terminal state between precheck, lock, signature, and socket dispatch.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0067

- Status: RESOLVED
- Severity: P1
- Chapter: 40
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff dispute authorization review
- Invariant: A dispute mutation revalidates and locks the live operator and credential before locking or changing dispute state.
- Finding: Evidence revision and submission trusted the request-time `RiskOperatorContext` and locked only the workflow, so a concurrent operator disable could commit while the stale request still appended evidence or submitted a packet.
- Why prior review missed it: Tests rejected actors already disabled before request dispatch but did not force disable after argument resolution and before the dispute mutation lock.
- Resolution evidence: Evidence revision, submission, and completed replay now lock and revalidate the live operator and credential before the workflow. `ChargebackAndDisputeContractTests` passed 19/19 on PostgreSQL 16.14, including disable-before-submit and completed revise/submit replay-after-disable; the complete backend suite then passed 759/759 with no skips.
- Process correction: Authorization matrices must distinguish already-disabled requests from disable-during-mutation and require a shared database lock in every state-changing operator path.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0068

- Status: RESOLVED
- Severity: P1
- Chapter: 42
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff webhook authorization review
- Invariant: Operator redelivery revalidates and locks the live operator and credential before endpoint and delivery state can advance to a new generation.
- Finding: Operator redelivery checked only the role captured during argument resolution, allowing a concurrent disable to commit while the stale request still scheduled external webhook work.
- Why prior review missed it: Replay tests covered an actor disabled before the replay request, while concurrency tests forced endpoint disable rather than operator disable after authentication.
- Resolution evidence: Redelivery execution and completed replay lock and revalidate the live operator and credential before endpoint and delivery state. `forcedOperatorDisableBeforeRedeliveryRejectsAuthenticatedInFlightAndFutureRequests` passed on PostgreSQL and proves disable wins with no new generation, request, attempt, or audit side effect.
- Process correction: Endpoint-state races and actor-state races are separate authorization families and must each have deterministic real-database proof.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0069

- Status: RESOLVED
- Severity: P1
- Chapter: 37
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff protected-note review
- Invariant: Every authorized decryption and return of analyst note plaintext leaves metadata-only access evidence without copying the plaintext into any sink.
- Finding: `ReviewNoteService.list` authorized and decrypted note content but recorded no access audit, despite the detailed plan explicitly promising access-audited notes.
- Why prior review missed it: The contract scanned audit rows for forbidden plaintext but never asserted that a successful plaintext read created a required access record.
- Resolution evidence: `ReviewNoteService.list` records `review_note.accessed` with request ID and count-only metadata in the authorized read transaction. Workspace contracts prove audit presence, denied-read absence, redacted-content counts, and plaintext absence; the retained PostgreSQL redaction/read ordering also passes.
- Process correction: Privacy proof must assert both absence of sensitive bytes and presence of every required access-control event.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0070

- Status: RESOLVED
- Severity: P1
- Chapter: 41
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 Senior Staff scenario-report lineage review
- Invariant: A scenario's terminal report and digest are functions only of that run's immutable specification and verified durable results.
- Finding: `originBreakdown` counted every payment attempt owned by the merchant, so unrelated historical or concurrent traffic changed the run report and digest.
- Why prior review missed it: The live demo intentionally created one merchant reference attempt and treated the expected mixed total as reconciliation proof without testing a second unrelated attempt or an identical run under different tenant history.
- Resolution evidence: Origin breakdown now traverses only the target run's completed `CONFIRM_PAYMENT_INTENT` step resources to their attempts. The scenario contract injects unrelated same-tenant traffic before and after finalization and proves both the owned breakdown and canonical report digest remain unchanged.
- Process correction: Report-determinism tests must inject unrelated same-tenant rows before and during finalization and prove they cannot change run-owned bytes.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0071

- Status: RESOLVED
- Severity: P1
- Chapter: 42
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 authorization source-of-truth reconciliation
- Invariant: The shipped webhook role boundary must match the founder-confirmed Phase 4.5 authorization matrix across backend, UI, README, tests, matrix, and dossier.
- Finding: The phase plan and cockpit restricted inspection/redelivery to `RISK_MANAGER`, while the Chapter 42 service, tests, matrix, and dossier granted `ANALYST`, creating an unreviewed privilege expansion and contradictory product truth.
- Why prior review missed it: Chapter-local proof was internally consistent, but the integrated review compared artifact presence rather than row-by-row authorization semantics against the authoritative phase matrix.
- Resolution evidence: Webhook dead-letter inspection and redelivery now require a live `RISK_MANAGER` in the service and cockpit; VIEWER and ANALYST are denied, and controller/service tests, the phase matrix, Chapter 42 matrix/dossier, service README, and demo agree on the same actor/action cells.
- Process correction: Integrated closure must mechanically or manually compare every actor/action cell across the master matrix, controllers, services, UI affordances, and chapter-local proof.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0072

- Status: RESOLVED
- Severity: P2
- Chapter: 43R
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 local cockpit integration review
- Invariant: Every public API route invoked by the advertised local Vite cockpit reaches the payments API through the documented same-origin development boundary.
- Finding: Endpoint verification called `/webhook_endpoints/...` while Vite proxied neither `/webhook_endpoints` nor `/webhook_deliveries`, producing a local Vite 404 that mocked fetch tests could not reveal.
- Why prior review missed it: UI component tests replaced `fetch`, and the retained browser proof did not execute endpoint verification through the actual Vite proxy.
- Resolution evidence: Vite now proxies both `/webhook_endpoints` and `/webhook_deliveries`; the configuration test inventories those routes, all 20 UI tests pass, and the production build succeeds.
- Process correction: Browser closure must exercise every client route through the real development proxy or validate the route-to-proxy inventory exhaustively.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0073

- Status: RESOLVED
- Severity: P2
- Chapter: 43R
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-07-27
- Escaped gate: Post-Phase-4.5 cockpit resilience review
- Invariant: After successful authentication, failure of one independent workspace dependency is explicit but does not discard usable monitoring, review, or operational slices that loaded successfully.
- Finding: The initial `Promise.all` made monitoring, queue, searches, controls, and disputes one all-or-nothing connection, so one optional 503 returned the user to the entry surface and discarded every successful result.
- Why prior review missed it: Failure tests made every request return the same 403 or 503 and verified classification, but never tested one failed auxiliary dependency alongside successful core slices.
- Resolution evidence: Authentication is a hard first gate, while independent workspace dependencies settle separately with per-slice state and bounded retry. UI contracts prove a saved-search 503 and dispute 403 preserve successful monitoring/review slices, an auxiliary 401 fails closed, credentials remain memory-only, and all 20 UI tests plus the production build pass.
- Process correction: Client failure inventories must include mixed dependency outcomes, not only all-success and all-failure partitions.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-07-27

## ER-0074

- Status: RESOLVED
- Severity: P1
- Chapter: 49
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 live PostgreSQL analytics demo after Chapter 49 verification
- Invariant: Every frozen analytics route executes on PostgreSQL with explicit timestamp result and parameter types, including freshness, ranges, and opaque cursor continuation.
- Finding: Merchant summary returned HTTP 500 because Spring JDBC could neither infer `Instant` query-parameter types nor map `timestamptz` scalar results directly to `Instant`; the H2 contracts and PostgreSQL `EXPLAIN` proof did not execute the public query path.
- Why prior review missed it: Production-dialect proof stopped at the range query plan and consumer postconditions, while endpoint behavior was exercised only against H2 and mocked browser responses.
- Resolution evidence: `RiskAnalyticsService` now binds range/cursor instants as `Timestamp` and maps freshness scalars through `JdbcTime`. `ProjectionConsumerKafkaPostgreSqlTests.PostgreSqlRangeQueryUsesTenantGenerationIntervalIndex` executes summary, time series, freshness, and second-page cursor paths on PostgreSQL 16.14; the live public demo passed the same summary/time-series routes.
- Process correction: Every JDBC-backed public analytics route must execute at least one value-bearing and one continuation/range partition on the production database; a query plan alone cannot certify driver conversion boundaries.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0075

- Status: RESOLVED
- Severity: P1
- Chapter: 49
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 live full-stream projection and shadow-rebuild demo after Chapters 49–50 verification
- Invariant: Canonical fraud-score units and bounds are identical across producer, event schema, live projection, shadow rebuild, migration constraints, and analyst output.
- Finding: The producer intentionally emitted `riskScore=500` basis points for a 5% score, but V70 restricted projection scores to `0..100`; live consumption quarantined the decision and a shadow rebuild failed before its first chunk.
- Why prior review missed it: Projection and rebuild fixtures used only intent-created events or synthetic scores within 100, so no real confirm-flow fraud decision crossed the migration constraint during production-database proof. The demo initially accepted stream version 1 instead of requiring the complete five-event stream.
- Resolution evidence: Forward migration V82 aligns the projection constraint to the canonical `0..10000` basis-point domain. `basisPointRiskScoreRebuildsWithoutWeakeningProjectionBounds` passes on H2 and PostgreSQL with 500 accepted, 10001 rejected, full shadow build, and reconciliation; the public demo now waits for stream version 5 and passed with 10/10 applied events.
- Process correction: Numeric representation reviews must state unit, scale, valid partitions, and constraint bounds end to end; rebuild fixtures must include a production-shaped confirm stream rather than only the first event.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0076

- Status: RESOLVED
- Severity: P2
- Chapter: 52
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 public demo execution after integrated closure documentation
- Invariant: The advertised public demo uses the shipped API/database contracts and fails unless every durable event-to-dataset hop is present.
- Finding: The client expected replay fields `id`/`COMPLETED` instead of `datasetId`/`SUCCEEDED`, joined publication attempts through a nonexistent column, queried inbox status `COMPLETED` instead of `APPLIED`, and asserted only reconciliation/dataset counts, allowing a zero inbox count to appear in a nominal pass.
- Why prior review missed it: Five safety tests covered loopback, subprocess, mutation, and broker restoration boundaries but never ran the client against the real API and schema before the dossier recommended merge.
- Resolution evidence: The client now validates `datasetId`, waits for `SUCCEEDED`, uses the tenant-qualified attempt-to-event join, requires durable tuple `10|>=10|10|5|1`, and has seven unit tests. The fresh V1→V82 live run passed Kafka outage/restart, FRESH analytics, rebuild/reconcile/activate/rollback, dataset success, unchanged source fingerprint, and exact durable evidence `10|10|10|5|1`.
- Process correction: A public-demo completion claim requires one fresh live execution before matrix verification, plus contract tests for response identifiers, lifecycle values, schema joins, and every asserted tuple field.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0077

- Status: RESOLVED
- Severity: P2
- Chapter: 49
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end analyst-output review after Chapter 49 verification
- Invariant: A fraud score has one canonical basis-point representation in storage and APIs and is rendered to a human as a percentage with an explicit unit.
- Finding: The analytics API correctly returned basis points, but the analyst cockpit rendered the raw integer, so a score of `8700` appeared as `8700` instead of `87.00%`.
- Why prior review missed it: The score-unit family stopped at producer, migration, projection, and response JSON; browser contracts asserted presence without asserting the human-visible conversion and label.
- Resolution evidence: The cockpit now renders `riskScoreBasisPoints / 100` with two decimal places and a percent sign. `App.test.tsx` proves `8700` renders as `87.00%`, while the API type keeps the basis-point name and the 22-test UI suite and production build pass.
- Process correction: Every numeric representation review must close both machine and human boundaries with unit-bearing names, conversion rules, and exact rendered-value assertions.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0078

- Status: RESOLVED
- Severity: P1
- Chapter: 51
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end replay-lineage review after Chapters 49 and 51 verification
- Invariant: Every projected and replayed fraud result is traceable to the exact canonical event, fraud decision, feature snapshot, payment attempt, and payment stream; timestamps are not identities.
- Finding: Fraud events carried decision and snapshot identifiers only inside payload bytes, projections did not retain them, and replay membership joined decisions by equal timestamps. Two decisions at the same database timestamp could select the wrong lineage, while a copied event could be linked to a decision from another payment stream.
- Why prior review missed it: Fixtures happened to use distinct timestamps and same-payment lineage, so count/digest reconciliation looked correct without an exact relational identity or hostile timestamp-collision/cross-payment partition.
- Resolution evidence: V83 adds immutable `domain_event_source_lineage`, exact projection identifiers, and tenant-composite foreign keys; V84 enforces event type and immutability on PostgreSQL; V85 binds the event stream through the decision's payment attempt to the exact feature snapshot. Publication writes the source link in the source transaction, projection and replay use the link, analytics expose the identifiers, and H2/PostgreSQL tests reject missing, mutated, non-fraud, and cross-payment links. `OfflineReplayDatasetIntegrationTests` proves two same-time decisions include only the exact linked identity.
- Process correction: Event/replay traceability reviews must model timestamp collision and same-tenant cross-resource substitution; identity claims require immutable relational keys and production-database constraints, never equality of occurrence times.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0079

- Status: RESOLVED
- Severity: P1
- Chapter: 48
- Category: MISSING_FAILURE_SEQUENCE
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end hostile event-payload review after Chapter 48 verification
- Invariant: Canonical event validation rejects every semantic numeric, timestamp, and identifier value outside the consumer and persistence domains before a projection handler can narrow or parse it.
- Finding: Schema validation accepted generic JSON integers and strings, while projection code used narrowing `intValue()` and direct timestamp/UUID parsing. Oversized numbers could wrap and malformed semantic values could escape the intended poison-event quarantine boundary.
- Why prior review missed it: The schema family checked JSON shape and required fields but did not enumerate integer-width, non-negative, risk-score, microsecond-timestamp, and canonical-UUID partitions at the representation boundary.
- Resolution evidence: `DomainEventFieldType`, the event catalogue, and `CanonicalEventCodec` now enforce safe integer widths, positive attempt numbers, `0..10000` basis points, exact database-microsecond instants, and canonical UUIDs. Projection parsing fails safely, missing exact lineage quarantines, and `CanonicalEventCodecTests.rejectsEverySemanticNumberTimestampAndIdentityBoundaryPartition` plus projection quarantine contracts pass.
- Process correction: Canonical protocol reviews must freeze semantic value domains separately from JSON token types and prove below/min/exact/max/above, malformed, precision, and canonical-representation partitions.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0080

- Status: RESOLVED
- Severity: P2
- Chapter: 49
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end analytics collection review after Chapter 49 verification
- Invariant: Every bounded analytics collection is losslessly continuable with an opaque tenant- and query-bound cursor; neither API nor UI silently discards rows.
- Finding: Payment search was paginated, but merchant summary, time series, and projection-generation history used fixed limits without continuation; the cockpit also sliced time-series results to the last 12 points.
- Why prior review missed it: Cursor proof covered the named payment-search route and a PostgreSQL second page, but did not inventory every collection or the UI's post-fetch truncation.
- Resolution evidence: Summary, time-series, and projection-status routes now use keyset pagination with signed cursors, `limit + 1`, and stable order tuples; cursor context binds tenant, route, range, interval, policy, and generations. The cockpit exposes load-more actions and no longer slices points. Analytics contracts traverse every page without gaps/duplicates and the UI test verifies all four continuation requests.
- Process correction: API closure must enumerate every response collection and every client-side transformation; a maximum page size is not a bounded/lossless contract unless continuation and terminal-page behavior are proven.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0081

- Status: RESOLVED
- Severity: P2
- Chapter: 49
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end analytics range review after Chapter 49 verification
- Invariant: Hourly and daily analytics ranges are UTC-aligned half-open intervals, explicit future bounds fail, and omitted defaults remain valid at every instant.
- Finding: Arbitrary `from`/`to` instants were accepted, producing partial buckets that looked complete; after adding alignment, a naive default `to=now` would itself be unaligned or future-rejected.
- Why prior review missed it: Tests covered range size and SQL execution but did not partition alignment, half-open boundaries, explicit versus server-default time, or the interval rollover edge.
- Resolution evidence: Range validation requires UTC hour/day boundaries, rejects explicit future `to`, enforces positive intervals of at most 90 days, and creates an omitted `to` at the next server-owned interval boundary. `ProjectionConsumerAnalyticsContractTests.analyticsRangesAndEveryCollectionCursorAreClosedAndLossless` proves unaligned/future rejection and valid default/hour/day boundaries.
- Process correction: Time-range reviews must freeze zone, granularity, inclusivity, alignment, explicit/default ownership, rollover, and future-clock policy as separate partitions.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0082

- Status: RESOLVED
- Severity: P1
- Chapter: 52
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-08-01
- Escaped gate: Phase 5 end-to-end source-of-truth review after integrated closure
- Invariant: The Phase 5 closure gate must reject completion when any mandatory architecture or product source still describes the phase as incomplete or contradicts the implemented ownership boundary.
- Finding: The integrated gate checked selected READMEs/plans but omitted the data/events architecture source, which still described Phase 5 as future work while other sources declared completion.
- Why prior review missed it: Governance proof verified artifact presence and a narrow stale-claim list instead of treating architecture as a mandatory completion source with case-insensitive contradiction checks.
- Resolution evidence: `phase-closure-check.py` now requires the data/events architecture document and rejects stale claims case-insensitively; its test suite deletes and corrupts that source to prove fail-closed behavior. The architecture now records completed Phase 5 ownership, exact source lineage, and current event names.
- Process correction: A completion gate must enumerate all normative source-of-truth documents and test omission plus case/wording variants of stale status, not rely on a hand-checked subset.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-01

## ER-0083

- Status: RESOLVED
- Severity: P0
- Chapter: 57
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: Chapter 57 public error contract implementation review
- Invariant: An unexpected server failure must return the reviewed public error shape without emitting database, driver, or row content to any sink.
- Finding: `GlobalApiExceptionHandler.handleUnexpected` passed the throwable as the trailing slf4j argument, so the full stack trace was logged. PostgreSQL constraint violations carry `Failing row contains (...)` detail, which placed cardholder-adjacent row values and internal schema into application logs.
- Why prior review missed it: The privacy proof asserted only on the HTTP response body. The existing log canaries lived in unrelated concurrency tests, so the handler change was reviewed as a logging improvement rather than as a privacy boundary change.
- Resolution evidence: The handler now logs only `requestId`, path, and exception simple name. `ApiErrorContractTests.unexpectedErrorsUseStandardShapeWithoutLeakingDetails` and twelve `ReviewResolutionPostgreSqlConcurrencyTests` parameterizations assert `CapturedOutput` never contains `Failing row contains`.
- Process correction: Any change to an exception handler is reviewed as a privacy boundary change and must carry a log-sink canary, not only a response-body assertion.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0084

- Status: RESOLVED
- Severity: P0
- Chapter: 57
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: `scripts/public-api-error-contract-check.py`
- Invariant: The `error` field is a stable machine-readable failure class that names a domain condition and is never derived from human message text.
- Finding: The catalogue was generated mechanically from message strings. Twenty-three codes carried a truncated hash suffix such as `..._e6e895eedf`, one hundred eighty-five codes were whole English sentences, and several were truncated at a Java string-concatenation boundary, producing codes and safe messages that could not be a compatibility contract.
- Why prior review missed it: The gate asserted only that at least one hundred descriptors existed and that codes were unique. Cardinality and uniqueness cannot detect message-derived naming.
- Resolution evidence: The catalogue was curated to 271 reviewed domain codes. The gate now rejects duplicate codes, a constant that does not mirror `upper(code)`, a hash-truncated suffix, and codes exceeding seven tokens. `PublicApiErrorDescriptorTests.catalogueConstantsMirrorTheirStableCodeAndCarryCompleteSafeMessages` proves the same properties in the running JVM and additionally rejects safe messages truncated at a concatenation boundary.
- Process correction: A gate over a public contract must assert the shape of every entry, not the population size. Contract identifiers may never be produced from prose by an automated refactor without an explicit human curation pass.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0085

- Status: RESOLVED
- Severity: P1
- Chapter: 57
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: Chapter 57 published contract review
- Invariant: Every stable error code a client may receive is discoverable in the published OpenAPI contract.
- Finding: `ApiErrorResponse.error` was published as an undescribed `string`. The contract asserted in prose that clients should classify using the error field while publishing nothing that told a client which values exist, so no generated SDK or merchant integration could enumerate them.
- Why prior review missed it: The contract gate validated paths, operation identifiers, version headers, and security schemes, and treated schema presence as sufficient without checking that the documented compatibility surface was actually populated.
- Resolution evidence: `PublicOpenApiConfiguration.publishStableErrorCatalogue` injects all 271 codes and 37 recovery actions from `PublicApiErrorDescriptor` as `x-extensible-enum`, documents `message` as non-contractual, and is proven by `VersionedPublicContractTests.publishedContractEnumeratesEveryStableErrorCodeSoClientsNeverParseMessages`. A closed `enum` was rejected because it would make every future code addition a breaking change for strictly validating clients.
- Process correction: A field described as a compatibility contract must publish its permitted values, and the value set must be derived from the enforcing code so it cannot drift.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0086

- Status: RESOLVED
- Severity: P1
- Chapter: 57
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: `VersionedPublicContractTests.servedArtifactIsByteExactAndRuntimeContractExcludesInternalSurfaces`
- Invariant: The committed public OpenAPI artifact describes the API that actually runs.
- Finding: The byte-exactness test compared the served artifact to the committed artifact, both of which are the same classpath file. Nothing compared either to the springdoc contract produced by the running application, so any server-side contract change could ship with a stale published artifact.
- Why prior review missed it: Byte-exact serving was accepted as artifact correctness. The review did not ask which two independent sources the equality actually related.
- Resolution evidence: `VersionedPublicContractTests.committedArtifactDescribesTheRunningApiAndNotAStaleSnapshot` compares the parsed runtime contract to the committed artifact and fails closed on drift. It also writes the runtime contract to `apps/payments-api/target/openapi/runtime-public-contract.json`, and `scripts/generate-openapi-contract.py --source` freezes the artifact from that same JVM, so regeneration and verification share one source.
- Process correction: An equality proof must name the two independent sources it relates. Comparing an artifact to itself is not evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0087

- Status: RESOLVED
- Severity: P1
- Chapter: 58
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-03
- Escaped gate: `scripts/generate-python-sdk.sh`
- Invariant: A generated SDK is never published with an unresolved generator token.
- Finding: The guard was written as `if rg -n 'ERROR_TO_EXAMPLE_VALUE' ...; then`. Where `rg` is not installed the shell returns 127, the condition evaluates false, and `set -e` does not apply inside an `if` condition, so the guard silently never executed and the script still reported success.
- Why prior review missed it: The script was reviewed for the correctness of the condition it expresses rather than for its behavior when the tool it depends on is absent.
- Resolution evidence: The guard now uses `grep` and branches on the exact exit status: `0` fails as a found generator token, `1` is clean, and any other status fails as a broken scan. Regeneration was re-run and the guard executes.
- Process correction: A shell gate must distinguish "checked and clean" from "could not check". Any gate whose failure mode is silence is treated as absent.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0088

- Status: RESOLVED
- Severity: P1
- Chapter: 56
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: `ReviewBulkPostgreSqlConcurrencyTests.forcedRowLockInterleavingLetsOnlyTheCommittedLiveLeaseWin`
- Invariant: Under a forced row-lock interleaving, only the committed live lease wins and the contending worker observes it.
- Finding: The test wrote `started_at` and `updated_at` with PostgreSQL `CURRENT_TIMESTAMP` while the rows under test are written by the application `Clock` bean. Under Testcontainers the database runs on a separate virtual-machine clock, so a sub-millisecond skew intermittently produced `started_at < created_at` and failed `ck_review_bulk_action_requests_time`. The concurrency invariant therefore had a non-deterministic proof.
- Why prior review missed it: The review focused on the forced interleaving and lock-wait observation and did not ask which clock each timestamp column came from. The failure only surfaces when the two clocks drift.
- Resolution evidence: The test injects the application `Clock` and binds `Timestamp.from(clock.instant())`, matching the single clock source used by `ReviewBulkActionStore`. Five consecutive runs pass. Production was never affected because the store derives `created_at`, `started_at`, and `updated_at` from one `clock.instant()` call per operation.
- Process correction: Any assertion touching a database ordering constraint must use a single clock source. Mixing `CURRENT_TIMESTAMP` with application-written timestamps under an ordering constraint is treated as a defect even when the suite is green.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0089

- Status: RESOLVED
- Severity: P1
- Chapter: 53
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: absent proof for `GET /risk/workspace/payments/{id}/webhook-activity`
- Invariant: Every investigation route named by the Chapter 53 promises returns authoritative evidence rather than an unhandled server error.
- Finding: `PaymentTimelineService.webhookActivity` selected `last_error`, a column dropped by `V9__harden_webhook_delivery_execution` and replaced by the closed enum column `last_error_code`. Every call to the webhook-activity route raised `BadSqlGrammarException` and returned HTTP 500. The route had never been invoked by any test, so the schema drift survived a full green suite and a chapter review.
- Why prior review missed it: The review treated the route as covered because the service class and its DTO existed and compiled. Compilation cannot detect a dropped column referenced from a string literal, and no proof exercised the route end to end.
- Resolution evidence: The query now selects `last_error_code`, which is the closed diagnostic enum the DTO field `safeFailureCode` was always intended to carry, and binds the keyset cursor instant as `Timestamp.from(afterTime)`. `PaymentInvestigationSourceReconciliationTests.readingDetailSearchTimelineAndRelatedLeavesEveryAuthoritativeRowByteIdentical` and `PaymentInvestigationTenantIsolationTests.everyPaymentScopedRouteTreatsAForeignIdExactlyLikeAnAbsentId` now invoke the route directly, and `PaymentInvestigationReadinessTests.sourceOnlyRoutesKeepWorkingWhileTheProjectionIsDown` asserts it returns 200 during a projection outage.
- Process correction: Every route named in an invariant matrix must be invoked by at least one proof before the matrix row can leave `PENDING`. A route with no executing test is treated as unimplemented regardless of whether its handler compiles.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0090

- Status: RESOLVED
- Severity: P1
- Chapter: 53
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: absent projection-outage proof for `PaymentInvestigationService.analytics`
- Invariant: A derived read-model outage degrades only the projection-backed section and never removes authoritative source detail.
- Finding: `ProjectionDefinitionStore` is annotated `@Repository`, so Spring persistence-exception translation rewrote the `IllegalStateException` it threw into `InvalidDataAccessApiUsageException`. Both graceful-degradation handlers, in `PaymentInvestigationService.analytics` and `RelatedPaymentService`, caught `IllegalStateException` and were therefore unreachable dead code. A disabled or handler-incompatible projection made `GET /risk/workspace/payments/{id}` return HTTP 500, which is precisely the failure sequence C53-OPS-001 was written to prevent.
- Why prior review missed it: The review read the catch block and accepted the stated intent. No test ever ran the investigation surface with the projection unavailable, so the degradation branch was never executed and its unreachability was invisible.
- Resolution evidence: New `ProjectionGenerationUnavailableException` extends `RuntimeException` rather than `IllegalStateException`, so persistence-exception translation leaves it intact; `ProjectionDefinitionStore.requireActive` throws it and both degradation sites catch it. `PaymentInvestigationReadinessTests.aDisabledProjectionStillLeavesAuthoritativeSourceDetailFullyReadable` proves the detail route stays 200 with `analyticsContext` reported `UNAVAILABLE` and reason `active_projection_unavailable`, and `aDisabledProjectionMakesSearchFailLoudlyRatherThanReturnAnEmptyPage` proves the projection-only route refuses rather than reporting a falsely empty result set. The full backend suite passes at 929 tests.
- Process correction: A degradation handler is not proven by reading its catch clause. Every declared degradation path must be executed by a test that makes the dependency genuinely unavailable, and any handler catching a framework-translated exception type must be verified against the type that actually escapes the proxy.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0091

- Status: RESOLVED
- Severity: P1
- Chapter: 54
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: Chapter 54 proof asserted the refusal status and code for forged timeline evidence but never asserted that the published recovery contract was truthful.
- Invariant: A stable public error code classifies its failure exactly, and `retryable` is true only when replaying the identical request can succeed without operator intervention.
- Finding: `TIMELINE_INTEGRITY_UNAVAILABLE` was published as `503 SERVICE_UNAVAILABLE`, `retryable=true`, message "Canonical timeline evidence is temporarily unavailable", recovery `retry_the_safe_read_after_retry_after`. Every one of its four raise sites in `PaymentTimelineService` is deterministic and permanent. The most severe of them, the `map` identity check, fires when a stored row disagrees with its own signed record, which is tamper detection on an append-only store that `PaymentTimelineAppendPostgreSqlTests` had already proven cannot be repaired or deleted. The contract therefore instructed every client and SDK to retry forever against evidence that can never become readable, and buried a forgery signal under a transient-error class that no operator pages on.
- Why prior review missed it: The Chapter 54 hostile pass exercised the boundary correctly and asserted `503` and `timeline_integrity_unavailable`, then treated matching the shipped descriptor as proof. It never asked whether the descriptor itself was honest, so the test encoded the defect as the expected answer. The failure sequence "retryable is true for a permanent failure" belonged to the error-contract family, which no Chapter 54 row owned.
- Resolution evidence: The two operationally distinct causes are now separate codes. `TIMELINE_EVIDENCE_INTEGRITY_FAILED` (`500`, `retryable=false`, recovery `contact_support_with_the_request_id`) covers the signed-record identity mismatch and logs at ERROR with the event and stream identity so the tamper signal is visible in operations. `TIMELINE_INTEGRITY_UNAVAILABLE` retains `503`/`retryable=true` for the genuinely transient case, a stored event this build has no reviewed presentation mapper for, which a later deployment fixes without the caller changing anything. The change is additive to the published catalogue. `PaymentTimelineAppendPostgreSqlTests.aRowWhoseDatabaseIdentityDisagreesWithItsSignedRecordIsRefusedNotRendered` now asserts `500`, the new code, and `retryable=false`. `PublicApiErrorCatalogueTests.twoDistinctOperationalCausesNeverShareOneCode` pins the split.
- Process correction: The boundary family was reopened rather than the single observed descriptor. `PublicApiErrorCatalogueTests` now proves the retry contract for every descriptor in the catalogue: no `503` may be non-retryable, no non-transient status may be retryable, every retryable code must name its safe replay, no permanent code may invite an unchanged retry, and no `5xx` may blame the caller. A proof that asserts an error code must assert the recovery semantics that code publishes, because matching the shipped value only proves the code did not change.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0092

- Status: RESOLVED
- Severity: P1
- Chapter: 57
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: `ApiErrorContractTests` and `scripts/public-api-error-contract-check.py` both certified the error contract, but each only inspected the catalogue in isolation. Neither compared the codes the API actually emits on the wire against the codes the API publishes to clients.
- Invariant: Every stable code a public response can emit is published in `/developer/error-catalog` and the OpenAPI error vocabulary, with identical status and retry semantics on both sides.
- Finding: `GlobalApiExceptionHandler` carried a second, undocumented error vocabulary written as bare string literals, together with its own `retryable` and `recoveryAction` rules derived from HTTP status. Four emitted codes existed nowhere in `PublicApiErrorDescriptor`, so they appeared in neither the published catalogue nor the OpenAPI `x-extensible-enum`: `method_not_allowed`, `unsupported_media_type`, `client_error`, `server_error`. Worse, the two vocabularies disagreed where they overlapped. A `404` was published with recovery `verify_the_tenant_scoped_identifier` but emitted with `correct_the_request_before_retry`, and the handler's transient recovery hints (`retry_with_same_idempotency_key_after_retry_after`, `wait_for_retry_after_then_retry_with_same_idempotency_key`) were spellings the catalogue never contained. An integrator branching on the documented contract would have built handling for strings the runtime never sends, and received strings the documentation never mentions.
- Why prior review missed it: The existing proofs asserted properties of the catalogue (uniqueness, constant-to-code agreement, minimum size, message safety) and properties of individual responses (status, code, envelope shape), but nothing joined the two sets. The generic framework failure families were also the least interesting to write a domain test for, which is exactly why they were the ones that drifted. The `x-extensible-enum` decision to keep the published list open, taken so that adding a code stays non-breaking, removed the last place a schema validator would have noticed the omission.
- Resolution evidence: `method_not_allowed`, `unsupported_media_type`, `client_error`, and `server_error` are now first-class descriptors. `PublicApiErrorDescriptor.byCode` resolves any emitted code back to its catalogue entry, and `GlobalApiExceptionHandler` reads `retryable` and `recoveryAction` from that entry instead of re-deriving them; the parallel status-based rules are deleted. Emitting a code that is not in the catalogue now fails loudly rather than shipping an undocumented contract. The regenerated `openapi/thaap-public-api-2026-08-01.yaml` carries the completed vocabulary, and the contract drift gate in `VersionedPublicContractTests` proved the staleness before the artifact was refreshed.
- Process correction: `ApiErrorCompatibilityTests` closes the join that was missing. `everyEmittedCodeIsPublishedInTheCatalogueClientsAreToldToBranchOn` walks eleven real failure families end to end and requires each emitted code to appear in `/developer/error-catalog`; `theCatalogueAgreesWithTheEnvelopeOnStatusAndRetrySemantics` requires status, `retryable`, and `recoveryAction` to be identical on the wire and in the documentation. The general lesson is that a contract proof must test the join between what the system does and what the system says, because each side can be internally consistent while the pair is a lie.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0093

- Status: RESOLVED
- Severity: P2
- Chapter: 57
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-03
- Escaped gate: `VersionedPublicContractTests` asserted the security block of the sandbox scenario operations, but it asserted the value the generator happened to emit rather than the credential the runtime actually demands.
- Invariant: A published security requirement states exactly the credentials an operation enforces, so a generated client neither withholds a credential the API needs nor supplies one it does not.
- Finding: Every `/developer/scenario-runs` operation published `security: [{merchantApi: [], scenarioRunner: []}]`. A single OpenAPI requirement object listing two schemes means both are mandatory. The runtime disagrees: `MerchantContextArgumentResolver` requires only `X-Api-Key`, and treats `X-Thaap-Scenario-Runner-Token` as optional, validating it only when present in order to mark the call as synthetic traffic. The contract therefore told every generated SDK and strict client that a merchant key alone could not open the sandbox, which is the first surface a new integrator touches. The mismatch was an over-declaration rather than a privilege hole, but a contract that misstates its credential boundary in either direction cannot be trusted in the direction that matters.
- Why prior review missed it: The security declarations are derived from path prefixes in `PublicOpenApiConfiguration.security`, while enforcement is derived from handler parameter types resolved by an argument resolver. Nothing joined the two, and the existing assertion was written by reading the generated document, so it recorded the generator's opinion as the expected answer.
- Resolution evidence: The scenario operations now publish `security: [{merchantApi: []}]`, and every merchant-authenticated operation documents `X-Thaap-Scenario-Runner-Token` as an optional header whose description states that it never replaces the merchant credential. The committed and bundled `openapi/thaap-public-api-2026-08-01.yaml` were regenerated, and `VersionedPublicContractTests` now asserts the corrected requirement together with the optional parameter.
- Process correction: `OpenApiSecurityContractTests.everyDeclaredCredentialIsTheCredentialTheHandlerActuallyEnforces` derives the enforced credential for all 133 public operations from `RequestMappingHandlerMapping` handler signatures and requires the published document to match exactly, so the path-prefix heuristic can no longer drift from enforcement without failing. `noOperationOutsideTheDocumentationSurfaceIsPublishedAsOpen` and `merchantAndRiskOperatorCredentialsAreNeverInterchangeable` close the remaining partitions of the same family.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-03

## ER-0094

- Status: RESOLVED
- Severity: P2
- Chapter: 55
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-05
- Escaped gate: every chapter's PostgreSQL proofs were run per-class or per-chapter, never as one whole-suite pass, so the suite's aggregate resource cost was never a measured quantity.
- Invariant: Adding a proof must not make an unrelated proof fail. Test infrastructure is part of the product's evidence chain, and evidence that only holds when run in isolation is not evidence.
- Finding: Every `*PostgreSqlTests` class in the repository declares its own `@Container` static PostgreSQL container, so each class costs the suite one Docker container and one Spring application context. Adding the two new Chapter 55 PostgreSQL classes took the full suite from 25 contexts / 26 containers to 27 / 28, and at that point `DomainEventPostgreSqlTests` — a Chapter 51 class untouched by the change — began failing all four of its tests with `No qualifying bean of type DatabaseCleaner`. The context logged a successful startup, so the failure presented as a nonsensical missing-bean error rather than as resource exhaustion. Running the same three classes together passed; only the full-suite aggregate reproduced it.
- Why prior review missed it: The per-class container pattern was inherited and never questioned, because at 25 classes it still worked. No gate measured contexts or containers, and no chapter ran `-Dtest='*PostgreSql*'` as a single pass, so the suite's headroom was invisible until it was gone. Each chapter's proofs were green in isolation, which is precisely the condition under which an aggregate limit stays hidden.
- Resolution evidence: `ReviewBulkPostgreSqlTestBase` now owns one manually started singleton container for the whole `bulk` package, and all three review-bulk PostgreSQL classes extend it. The container is deliberately not a `@Container` field: that annotation starts and stops the container per test class, so the second subclass would receive a fresh container on a new port while the cached Spring context still addressed the stopped one — a failure mode observed and rejected during this fix. `@EnabledIf` preserves the previous skip-without-Docker behaviour. The review-bulk PostgreSQL proofs now cost one context and one container instead of three of each, and the full `*PostgreSql*` suite is green again.
- Process correction: A new PostgreSQL proof class in an area that already has one must extend that area's shared base rather than declare a fresh container. More generally, a chapter's PostgreSQL evidence is not complete until the whole `*PostgreSql*` suite has been run in one pass, because per-class runs cannot observe an aggregate resource limit. The lesson is that "each proof passes" and "the proofs pass together" are different claims, and only the second one is the one that ships.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-05

## ER-0095

- Status: RESOLVED
- Severity: P2
- Chapter: 55
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-05
- Escaped gate: the `ER-0094` process correction was written narrowly enough that it did not bind the next class that triggered the same failure.
- Invariant: Adding a proof must not make an unrelated proof fail. A correction for a recurring failure has to name the condition that causes it, not the one place it was last observed.
- Finding: `ER-0094` corrected the per-class container pattern by adding `ReviewBulkPostgreSqlTestBase` and instructing that "a new PostgreSQL proof class in an area that already has one must extend that area's shared base." `ReviewBulkAssignmentMigrationPostgreSqlTests` was then added under `com.thaap.payments.database`, an area whose only existing class — `DecisionIntelligencePostgreSqlMigrationTests` — declared its own `@Container` and had no shared base. The instruction was followed to the letter and the defect recurred anyway: the full suite went red with `OfflineReplayDatasetIntegrationTests` failing all four tests on `No qualifying bean of type DatabaseCleaner`, the same nonsensical missing-bean presentation of container/context exhaustion that `ER-0094` diagnosed.
- Why prior review missed it: The correction was phrased around "an area that already has a shared base," which is a property of the codebase's current state rather than of the risk. An area with two container-declaring classes and no base was exactly the gap the wording left open. The pure-Flyway migration classes also looked exempt because they start no Spring context — but they still cost a whole database each, and the binding constraint is the Docker host, not the context cache.
- Resolution evidence: `MigrationPostgreSqlTestBase` now owns one manually started singleton container for the `database` package and carries the shared `database()`, `migrate()`, and `drop()` helpers; both `DecisionIntelligencePostgreSqlMigrationTests` and `ReviewBulkAssignmentMigrationPostgreSqlTests` extend it, so the two Chapter 55 migration proofs added zero containers to the suite. Subclasses work inside their own generated schema, so sharing one database is safe. The full backend suite is green at 1152 tests with zero failures.
- Process correction: The rule is now stated as a property of the risk rather than of the current tree: no test class may declare its own PostgreSQL `@Container`; every PostgreSQL proof extends a package-level shared base, and a package that lacks one gets a base created as part of the change that adds its second such class. The wider lesson is that a process correction which names a location rather than a condition will be obeyed and still fail, so a correction should be written so that following it prevents the recurrence rather than merely describing the fix that resolved the original instance.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-05

## ER-0096

- Status: RESOLVED
- Severity: P1
- Chapter: 58
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-06
- Escaped gate: the SDK regeneration check exists and is correct, but it had never been executed, so a stale generated client passed every gate that did run.
- Invariant: The committed Python SDK is generated from the exact committed OpenAPI contract. A client generated from an older contract silently disagrees with the server about which credentials exist and which fields are returned.
- Finding: `scripts/python-sdk-regeneration-check.sh` had never been run in any environment, because it requires Docker and `C58-GEN-001` recorded its byte-stability claim as asserted-by-script rather than demonstrated. Its first execution failed. The committed client carried contract digest `8ca1ba94…` while `openapi/thaap-public-api-2026-08-01.yaml` hashes to `97c0d31e…`: the SDK had been generated from a contract predating the Chapter 53–56 endpoints. It was missing the `DrillThrough` model, and it still modelled `X-Thaap-Scenario-Runner-Token` as a `scenarioRunner` security scheme that the current contract does not attach to any operation. The contract now models the token as an explicit optional header parameter on all 42 merchant operations, which matches `MerchantContextArgumentResolver`, where the token is what flips `TrafficOrigin` from `MERCHANT_API` to `SYNTHETIC_SCENARIO`. The consequence was not a broken call: `Credentials(scenario_runner_token=…)` was accepted and the header was never sent, so the sandbox quickstart's generated payments would have been recorded as live merchant traffic and entered live-only fraud metrics. Nothing would have failed.
- Why prior review missed it: Section 15 of the Phase 6 plan states that a chapter adding a public endpoint after Chapter 57 owns the additive regeneration. Chapters 53–56 added endpoints and regenerated the contract but not the client, and no gate compared the two because the only gate that would have was itself unrun. The matrix row honestly recorded "asserted by the script but not yet demonstrated by a recorded run," which is precisely the state in which a claim looks proven in a summary and is not.
- Resolution evidence: The SDK was regenerated from the committed contract; two independent generations produced byte-identical trees and `scripts/python-sdk-regeneration-check.sh` reports `Python SDK regeneration PASS`. `Credentials` now rejects a scenario token without a merchant key and rejects holding merchant and risk-operator authority in one client. A client constructed with a runner token attaches the qualifier to every request it makes, because forgetting it per call fails silently rather than loudly. `tests/test_payment_quickstart_contract.py::test_scenario_traffic_is_tagged_synthetic_and_live_traffic_is_not` asserts the two origins, and `tests/test_secure_client.py::test_scenario_qualifier_is_absent_unless_the_session_is_synthetic` asserts the qualifier never appears on a live session.
- Process correction: A gate that has never been executed does not count as evidence, and a matrix row must not reach `PASS` on a script's assertion alone. Any row whose proof is a script now requires a recorded run in the closure dossier before it closes. The regeneration check runs before Chapter 59 closure and on every later contract change, so the additive-regeneration obligation is enforced by execution rather than by a paragraph in a plan.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-06

## ER-0097

- Status: RESOLVED
- Severity: P1
- Chapter: 58
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-06
- Escaped gate: the secret-hygiene proof asserted on the exception's message and `__cause__` but never on `__context__`, so the leak sat one attribute away from every assertion.
- Invariant: The SDK never places a credential, raw response body, or request URL into an exception, log, or representation. `C58-SEC-001` and `C58-OBS-001` both depend on this.
- Finding: `_SafeApiClient` raised its safe `ThaapApiError` and `ThaapTransportError` with `raise … from None` inside the `except` block. That clears `__cause__` and sets `__suppress_context__`, which stops the default traceback printer — but `__context__` still referenced the underlying generated `ApiException` or urllib3 exception, whose `body` and `headers` attributes carry the raw server response and the request headers, and whose message carries the full request URL. Every assertion in the suite passed because they all read `str(error)`, `repr(error)` and `__cause__`. Any consumer that walks the exception chain directly — structured log handlers, error reporters, `traceback` helpers that ignore the suppression flag — would have published the credential and the response body into the integrator's logging system.
- Why prior review missed it: `raise … from None` reads as the idiom for "drop the chain," and the tests were written against the two attributes that idiom visibly affects. The suppression flag is a rendering hint, not a deletion, and no test asserted the distinction. This is the `WEAK_PROOF` shape of a real implementation defect: the promise was correct, the mechanism was almost correct, and the proof only covered the visible half.
- Resolution evidence: The safe error is now constructed inside the handler and raised after the `except` block completes, so no exception is being handled at the raise point and `__context__` is null. `tests/test_client_observability.py::test_the_exception_chain_never_reattaches_the_raw_response_body` and `::test_a_transport_failure_reports_a_decision_not_a_stack_of_internals` assert both `__cause__` and `__context__` are `None`, and assert the base URL, credential and server message are absent from the rendered exception.
- Process correction: A non-disclosure claim about an exception must be proven over the whole reachable object graph, not over its default rendering: `str`, `repr`, `__cause__`, `__context__`, and any attribute a reporter would serialise. The boundary family for secret hygiene is reopened to that standard, which is why the transport failure path was audited and fixed in the same change rather than only the API error path where the defect was first seen.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-06

## ER-0098

- Status: RESOLVED
- Severity: P1
- Chapter: 57
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-06
- Escaped gate: `scripts/openapi-contract-check.sh` was written but never wired into `scripts/commit-readiness-check.sh`, so it had been failing without anyone learning that it was.
- Invariant: Section 3.5 of the Phase 6 plan freezes that every published operation carries its correct credential scheme, so a client generated from the contract presents what the server actually requires.
- Finding: The contract published `security: [{merchantApi: []}]` for every `/developer/scenario-runs*` operation and attached the runner token as an optional header, on the reasoning that `MerchantContextArgumentResolver` merely accepts the token to flip `TrafficOrigin`. That reasoning holds for the 36 ordinary merchant operations and is wrong for all six scenario operations: every one of them reaches `DeveloperScenarioService.requireSynthetic`, which rejects any context whose origin is not `SYNTHETIC_SCENARIO`, and only a valid runner token produces that origin. The published contract therefore told a generated client that a merchant key alone was sufficient for six operations that return `SCENARIO_RUNNER_CREDENTIAL_REQUIRED` without the token, so a client generated from the contract would fail every documented sandbox scenario call. `openapi-contract-check.sh` asserted the correct blanket rule and was therefore red — but being unwired from `scripts/commit-readiness-check.sh`, its failure was invisible.
- Why prior review missed it: The credential decision was made once, for the family, and recorded as a considered comment in `PublicOpenApiConfiguration` explaining why the token is only a traffic-origin qualifier. That reasoning is correct for ordinary merchant operations and was generalised over a path prefix to the scenario routes, where a second guard exists one layer down in the service. No test derived the expectation from an enforcement point, and `VersionedPublicContractTests` asserted the wrong expectation confidently across four scenario pointers. The first attempt at the fix repeated the error in a third form: reading only `DeveloperScenarioController` shows `requireScenarioRunner` in three of six handlers, which suggested a three/three split until `DeveloperScenarioService.requireSynthetic` was found guarding all six. Three independent attempts inferred this boundary from a convenient surface rather than from where it is enforced.
- Resolution evidence: A new `@RequiresScenarioRunner` annotation marks each handler that rejects a merchant key presented alone, and sits beside the guard rather than in a separate list. `PublicOpenApiConfiguration` reads it from the running handler mapping to publish the `scenarioRunner` requirement; `OpenApiSecurityContractTests.credentialsOf` reads the same annotation to derive the enforced boundary it compares the contract against, so expected and actual still come from two different artifacts and the comparison retains content. All six scenario operations declare `merchantApi` AND `scenarioRunner` and no longer duplicate the token as a bare header; the other 36 merchant operations keep the optional qualifier. `openapi-contract-check.py` restores the blanket rule and additionally rejects an operation that publishes the token both ways. `VersionedPublicContractTests` asserts both groups separately with the reason recorded. The contract was regenerated, the SDK regenerated from it, and `openapi-contract-check.sh`, the Redocly lint, the compatibility check and the breaking-change negative fixture all pass.
- Process correction: A gate script that is not invoked by `scripts/commit-readiness-check.sh` is not a gate. `openapi-contract-check.py` is now wired into the readiness run alongside the compatibility check, and both are covered by `commit_readiness_check_tests.py` so removing either fails a test. The wider correction, shared with `ER-0096`: before a chapter closes, every script named as proof in its matrix must have a recorded execution, because an unrun script is indistinguishable from a passing one in a summary and is frequently red.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-06

## ER-0099

- Status: RESOLVED
- Severity: P1
- Chapter: 52
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-06
- Escaped gate: `scripts/invariant-proof-check.py` required the `Proof` field to be non-empty and never checked that anything it named existed, so a row could close on a suite that was never written.
- Invariant: A closed invariant row names proof an auditor can run. `ER-0096` and `ER-0098` established that a gate which has never been executed is not evidence; the same reasoning applies with more force to a gate that was never written, because there is nothing to execute.
- Finding: A sweep prompted by the `ER-0096`/`ER-0098` process correction found that 59 rows across seven chapters name test classes and scripts that exist nowhere in the repository. Chapters 19–35, 37–43R and 53–59 are clean; the drift is Chapter 36 and Phase 5. The mechanism is uniform: every Phase 5 matrix is `PRE_IMPLEMENTATION` with `Proof` written in future tense as an obligation, implementation consolidated each domain into one contract class plus a `*PostgreSqlTests` subclass that re-runs the same bodies against real PostgreSQL, and `Status` was then flipped to `PASS` without anyone rewriting `Proof` to name what was built. Chapter 50 planned eleven suites and shipped seven test methods; Chapter 46 planned ten and shipped 302 lines across two classes. Thirty-six of the 59 rows are naming drift — the concern is genuinely held by the consolidated suite, which is why the closure dossiers, where they name classes at all, name only real ones. The remaining 23 are not: a repository-wide search for `MeterRegistry` returns no rebuild, replay or event-foundation test, so `C50-OBS-001`, `C51-OBS-001`, `C44-OBS-001` and `C46-OBS-001` are unproven rather than proven under another name, and the same holds for rebuild privacy and restart, replay source non-interference and concurrency, risk-operations authorization, and all six Chapter 52 cross-cutting rows. The consequence was not a broken invariant: the payment, fraud, ledger and tenant correctness rows these sit beside are proven, and no suite contradicts a closed row. What was lost is the adversarial half of Phase 5's proof, and an auditor reading a `Proof` line to decide what to run would have been sent to a class that does not exist.
- Why prior review missed it: The matrix is drafted before implementation and read after it, and nothing in between forces the two readings to agree. `invariant-proof-check.py` validated the shape of a row — required fields present, `Status: PASS`, no unfinished-work marker — which is exactly the check that a well-formed obsolete claim passes. `chapter-closure-check.py` reconciles matrix against dossier metadata, not against the filesystem. The wider miss is that `ER-0096`'s correction was scoped to scripts named as proof, and was verified by executing them; twenty-three of these rows name a Java suite, which the correction's wording did not reach and its verification method could not detect, because a class that does not exist produces no failing run to notice. `ER-0098`'s correction was also weaker than recorded: it stated that removing `openapi-contract-check.py` from the readiness run would fail a test, and it would not have — the readiness tests stubbed each checker and asserted only the exit code, never that the checker was invoked.
- Resolution evidence: `scripts/invariant-proof-check.py` now resolves every backticked test class and file reference in a `Proof` field against the repository and fails closed on any that does not exist, with path references falling back to basename so a matrix may name an SDK suite the way Chapter 58 does. Backticked prose that is not an artifact — `AUDIT`, `LIMIT`, `ER-0096`, `PASS` — is not mistaken for a missing file, because a noisy gate is a gate that gets switched off. Six tests in `invariant_proof_check_tests.py` pin this, including that the repository index finds a real committed suite. All 36 naming-drift rows across Chapters 36, 44, 45, 46, 50, 51 and 52 now name the suite that actually holds them; the 23 rows with no counterpart carry an explicit `TD-0007` or `TD-0008` reference, which is the only exemption the gate accepts, and seventeen of those still did when this entry was last reviewed, six having since been proven and returned to `PASS` and which `technical-debt-check.py` holds to the register's owner, trigger and done-criteria rules. `scripts/invariant-proof-check.py` passes over 42 matrices and 511 invariants. The `ER-0098` enforcement gap is closed in the same change: `commit_readiness_check_tests.py::test_every_required_gate_script_is_actually_invoked` asserts invocation through marker files, and was confirmed to fail when a gate line is deleted and pass when it is restored. `scripts/python-sdk-package-check.py` is now wired into the readiness run and no longer reports `FAIL` on a host without `packaging`, and `scripts/python-sdk-regeneration-check.sh` no longer goes red because a gitignored `__pycache__` directory exists.
- Process correction: A `Proof` field is a promise that something exists and can be run, and it is now checked as one. A row may not close on an artifact the repository does not contain; where the artifact was planned and never built, the row must name the debt that owns it, so the gap lives in the technical debt register with an owner and a trigger instead of inside a `PASS` row. A structural weakness remains and is recorded rather than hidden: `invariant-proof-check.py` requires every row to be `PASS`, so a matrix cannot express an invariant that is held on the ordinary path and unproven adversarially. Every registered gap above is therefore still a closed row carrying a debt citation, and a reviewer who notices a thin row still has nowhere to record it short of blocking a merged phase. `docs/reviews/PHASE_5_PROOF_ARTIFACT_AUDIT.md` states the fix — a `PARTIAL` status that mandates a debt reference and is rejected at phase closure rather than chapter closure — and states why it is not made here, since it changes the meaning of all 511 invariant rows. The wider correction, and the reason this was found at all: when a process correction is written, its enforcement must be executed against the failure it claims to prevent — `ER-0098` recorded an enforcement that did not exist, and it survived because nobody deleted the gate line to watch a test fail.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-06

## ER-0100

- Status: RESOLVED
- Severity: P1
- Chapter: Cross-cutting; introduced 2026-05-31 with the first payment entities, surfaced by the Chapter 52 CI enablement
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-08
- Escaped gate: Every backend suite run for ten weeks, all of them on macOS. `Instant.now()` resolves to microseconds on macOS and to nanoseconds on Linux, so on the machine where this repository is developed the kernel has already performed the truncation the application forgot, and every assertion comparing an in-memory instant against its own database row passes for free. The gate did not fail to catch this; it was structurally incapable of observing it.
- Invariant: An instant the API returns for a persisted field must equal the instant a later read of that same field returns. PostgreSQL `timestamp(6)` stores microseconds, so an application that mints a finer instant holds a value its own database cannot store, and the two disagree from the moment the row is written.
- Finding: 53 production call sites minted time outside the application clock — `PaymentIntent`, `PaymentAttempt`, `Refund`, `LedgerEntry`, `FraudDecision`, `AuthenticationChallenge`, `WebhookEndpoint`, `AuditLog` and others set `createdAt`/`updatedAt` with a bare `Instant.now()` — and the `Clock` bean itself was `Clock.systemUTC()`, untruncated. Two further production classes, `WebhookExecutionConfiguration` and `WebhookEndpointSecretService`, bypassed the injected bean with their own `Clock.systemUTC()`. On Linux the resulting nanosecond values diverge from storage in three ways: a create response returns `.400031361Z` where every later read returns `.400031Z`; `GovernedControlService.exactInstant()` rejects the sub-microsecond value with `400`; and `CanonicalEventCodec.validateInstant()` throws `IllegalArgumentException`, which is unmapped and surfaces as `500`. The first CI run ever executed against this repository produced 46 failures and 2 errors from this single cause. The consequence in production is a public-contract violation rather than a correctness loss: no money, lineage or tenant invariant is broken, but `POST /refunds` returns a `createdAt` that a subsequent `GET` contradicts, and any consumer that stored the first value and compared it to the second would see two different instants for one immutable field.
- Why prior review missed it: The defect is invisible on the hardware every review was performed on, and no reviewer can see a difference the kernel has already erased. Worse, the codebase had been accumulating the symptom for five weeks without anyone recognising it: roughly thirty ad-hoc `.truncatedTo(ChronoUnit.MICROS)` calls were added across services from 2026-07-01 onward, each one added where a specific value was suspected to matter. On macOS every one of those calls is a no-op, so their authors had no way to learn whether any of them was load-bearing, and the scattering itself was never read as evidence of a missing invariant at the source. The precision guard added on 2026-08-01 in `CanonicalEventCodec` was correct and would have failed loudly, but it too can only fire on Linux. The repository had no Linux execution of any kind until `43b7f36` on 2026-08-07 added its first and only CI workflow; that run is what found this, on its first execution.
- Resolution evidence: `com.thaap.payments.time.DatabaseInstant` is the single definition of database precision, and `EventTimePrecision` delegates to it so the codec's guard and the application's minting cannot drift apart. The `Clock` bean is now `DatabaseInstant.tick(Clock.systemUTC())`, which fixes every injected caller at once, and the two classes that bypassed it now wrap their own clocks the same way. 315 call sites were swept to `DatabaseInstant.now()` — 53 in production, 262 in tests. `DatabaseInstantTests` pins the behaviour, including that truncation is used rather than rounding, that a ticked clock truncates even when the underlying clock offers nanoseconds, and that 5,000 consecutive readings carry no sub-microsecond digits. Verification was performed on Linux rather than reasoned about: the full suite was executed inside an `eclipse-temurin:25` container against a host whose `Instant.now()` was confirmed to return `sub-microsecond digits=387`, and reports 1384 tests, 0 failures, 0 errors, with every one of the 48 previously failing tests passing. The same suite on macOS reports 1384 tests and 0 failures, so nothing regressed. The input guards at `GovernedControlService.exactInstant()` and `CanonicalEventCodec.validateInstant()` are deliberately unchanged; they were correct throughout and were the only components reporting this defect honestly.
- Process correction: Truncating at each call site is what failed here, because a rule applied by memory at 315 places is a rule that will be forgotten at the 316th, and on macOS the omission is undetectable by test. Precision is now established at the source — the clock and one constructor-equivalent helper — so a value that never carried sub-microsecond digits cannot lose them later. Because no runtime assertion can catch a regression on a developer machine, `DatabaseInstantTests.noSourceFileMintsTimeOutsideThisClass` scans `src/main/java` and `src/test/java` and fails the build on any bare `Instant.now()` or unwrapped `Clock.systemUTC()`, naming the file and the required replacement; following `ER-0098`, this gate was verified by planting a violating file and confirming the test fails, then removing it and confirming it passes. The wider correction is about where proof runs: this repository's entire test history executed on one operating system whose clock is coarser than its deployment target, which means an entire class of defect was unobservable by construction. CI on Linux is now the authority for a green suite, and a local macOS pass is necessary but not sufficient evidence. The generalisation worth carrying: when the same defensive call starts appearing in many unrelated files, that is not diligence, it is a missing invariant at the source announcing itself, and the correct response is to ask why each site must remember rather than to add the next one.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0101

- Status: RESOLVED
- Severity: P2
- Chapter: 53 and 55; the shared-registry mutation entered with the Chapter 53 investigation observability tests and surfaced against the Chapter 55 bulk-backlog readiness gauges
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-08
- Escaped gate: The Chapter 53 observability review, which accepted a test that deletes meters from a `MeterRegistry` without asking who else holds that registry, and then the 2026-08-08 investigation of the resulting failure, which killed three hypotheses and stopped without enumerating the writers to the registry it was studying.
- Invariant: A test must not leave shared application state different from how it found it. Spring caches one application context — and therefore one `MeterRegistry` bean — per distinct test configuration and hands that same instance to every class whose configuration matches, so a registry mutation in one class is a mutation in all of them.
- Finding: `PaymentInvestigationObservabilityTests.setUp` removed every `thaap.decision_intelligence.*` meter from the injected registry before each of its tests, to give its cardinality assertions a clean slate. It declared `@ActiveProfiles("test")` and `@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")` — byte-identical to `ReviewBulkBacklogReadinessTests` and to thirty other classes — so all of them shared one context and one registry. Every other `decision_intelligence` meter is a counter or timer that `DecisionIntelligenceMetrics` re-registers on each call and so reappeared unaided, which is why the registry still held 321 meters and why the damage was invisible. The two bulk-backlog gauges were registered once, in the constructor, which runs once per context; once deleted they were gone for the life of that context. The failure was therefore position-dependent: orderings that ran the bulk class first passed, and orderings that ran the investigation class first read an absent meter and the helper's `-1.0` sentinel. Production was never at risk. There is one registry per process there, nothing removes meters from it, and the gauges have always been correct at runtime; the damage was to the suite's ability to tell the truth about itself.
- Why prior review missed it: Two separate gaps. The Chapter 53 review read the removal as local test hygiene, because nothing in the code says that the registry is shared — the sharing is implied by an annotation pair matching another file, and Spring's context cache key is invisible at the call site. The 2026-08-08 investigation then ruled out `meterRegistry.clear()` at `PaymentIntentConfirmContractTests:89` on the correct grounds that its context key differs, and generalised that result to "no test removes these meters" rather than to "that specific test does not". Having killed the clear, the search moved to Micrometer internals — weak references, cache eviction — and never asked the cheaper question of which code in the repository calls anything that removes a meter. `grep -rn 'registry::remove'` answers it in one step and names the file. The instrumentation that was added recorded the registry by `System.identityHashCode`, which cannot distinguish "this registry lost two meters" from "this is a different registry", so the evidence could not settle the question it was gathered to settle.
- Resolution evidence: The mechanism was identified by experiment, not inference. A `SimpleMeterRegistry` subclass installed as the test registry logged a stack trace on every `remove` and `clear` and stamped each instance with a monotonic sequence number rather than an identity hash, and two canary meters — a counter and a strongly referenced gauge, both registered exactly once in the same constructor — were registered alongside the two gauges. Under `-Dsurefire.runOrder=reversealphabetical` the run reproduced the original symptom exactly at 1384 tests and 4 failures, and named the cause: registry `#49` received `REMOVE name=thaap.decision_intelligence.bulk.backlog` from `PaymentInvestigationObservabilityTests.setUp`, and the same registry object later reported both canaries present and both gauges absent to `ReviewBulkBacklogReadinessTests`. The surviving canaries eliminate registry-identity confusion, weak-reference collection, and any general removal mechanism at once. `PaymentInvestigationObservabilityTests` and `PaymentIntentConfirmContractTests` now declare `@ExclusiveMeterRegistry`, which contributes a `ContextCustomizer` whose identity is the annotated class, so each gets a context — and a registry — of its own. That is the extension point Spring itself uses to isolate a class declaring bean overrides, registered the same way through `META-INF/spring.factories`. An earlier revision of this fix used an inert property with the same effect on the cache key; it was replaced because a magic string names nothing and cannot be enforced, while an annotation can be both. The isolation is defence in depth rather than what currently keeps the suite green: with the self-healing gauges in place the two classes pass together even without it, which was measured rather than assumed by removing the registration and re-running them. `MeterRegistryIsolationTests.noSharedSpringContextTestRemovesMetersFromTheRegistryItShares` scans `src/test/java` and fails the build when a `@SpringBootTest` class prunes a `MeterRegistry` without that annotation, and `theOwnershipAnnotationIsWhatSplitsTheContextAndNotSomethingElse` asserts through Spring's own `SpringFactoriesLoader` that the factory is registered at all, so the annotation cannot decay into a comment — verified by deleting the registration and watching the gate fail, and `theBacklogGaugesComeBackAfterSomethingRemovesThemFromTheRegistry` pins the self-healing behaviour added under `c339510` as behaviour rather than as history.
- Process correction: When a signal is missing, enumerate the code that can remove it before reasoning about the library that stores it. The three hypotheses that died were all about Micrometer; the answer was eleven lines of test code in this repository, reachable by one grep. Instrumentation must also be able to answer the question it is built for: identity must be recorded as a value the program controls, never as an identity hash code, which is neither unique nor stable enough to prove two observations concern one object. The isolation gate now enforces the invariant that review could not see, following `ER-0098`: it was verified by removing the property from `PaymentInvestigationObservabilityTests`, confirming the scan failed and named that file, restoring it, and confirming the scan passed. Separately, this defect existed only because the suite's execution order was arbitrary and differed between macOS and Linux, so `surefire.runOrder` is now pinned to `alphabetical` for every run, making a local result and a CI result comparable, and a dedicated CI job runs the same suite at `-Dsurefire.runOrder=random` and republishes the seed needed to replay any failing ordering. Pinning alone would have re-hidden this class of defect; pinning plus a deliberate shuffle keeps the property that found it.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0102

- Status: RESOLVED
- Severity: P2
- Chapter: 53; the investigation, timeline and related-payment test classes, found by the randomized-order CI job added under `ER-0101`
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-08
- Escaped gate: Every backend suite run in this repository's history, all of them in one arbitrary class order. Surefire's default `runOrder` is `filesystem`, so the suite had only ever executed a single ordering per platform, and an assertion that reads state another class created is correct or incorrect purely as a function of that ordering.
- Invariant: A test asserting over a shared database must either scope its query to the fixture it created or start from a known-empty database. Fifteen classes shared a cached Spring context, and therefore one database, with dozens of others while doing neither.
- Finding: The randomized-order job found two failures on its first two executions, both of the same shape. `PaymentInvestigationScopeBoundaryTests.anIdenticalCardInAnotherMerchantNeverRaisesARelatedCountOrCoverageClaim` asserted `SELECT DISTINCT card_fingerprint FROM payment_methods` equals 1 to prove its own fixture was not vacuous, but the query is unscoped and counted rows left by whichever class ran before it; it read 2 in CI at seed `84512339639`. `PaymentInvestigationNavigationScopeBoundaryTests` failed in `setUp`, because `RiskWorkspaceScenario.drainProjection` replays every event the database holds rather than only the fixture's, and a leftover event whose subject a later class had deleted is `QUARANTINED` rather than `APPLIED`. Auditing the family showed 15 of the 41 classes using `RiskWorkspaceScenario` never cleaned the database, against 26 that did; the other 13 were latent instances of the same defect that the pinned ordering would have continued to hide. No production invariant is involved: the defect is entirely in test isolation, and its cost is that a green suite meant less than it appeared to.
- Why prior review missed it: A suite that only ever runs in one order cannot distinguish an assertion that is correct from one that is merely lucky, and no reviewer reading a single class can see which other classes share its context and its database. Both facts are properties of the run, not of the file. The two failing assertions also read as careful rather than careless — one is a guard against a vacuous fixture, the other is a shared helper that replays production projection state — which is why review accepted them.
- Resolution evidence: `distinctCardFingerprints` now takes the fixture's own merchants and scopes its query with `WHERE merchant_id IN (...)`, so it measures the fixture it is asserting about rather than the table. All 15 classes that used `RiskWorkspaceScenario` without cleaning now call `databaseCleaner.clean()` in their setup, matching the 26 that already did, so no class inherits another's rows. The 15 modified classes run green together, and the full suite is green across three consecutive randomized orderings at independent seeds as well as at the pinned `alphabetical` order and at `reversealphabetical`.
- Process correction: The randomized-order CI job introduced under `ER-0101` is what found this, on its first run, which is the argument for keeping it blocking rather than advisory. Its replay contract was also corrected once measured rather than assumed: surefire shuffles the class list the directory scanner produced, and that input order is filesystem order, so one seed yields different permutations on ext4 and APFS. Replaying the CI seed on macOS produced a completely different ordering and a different failure. The workflow therefore states that a seed replays on a runner of the same platform, and gained a `workflow_dispatch` seed input so a failing ordering can be replayed in CI where it is reproducible. The generalisation: a claim about reproducibility is a claim like any other and must be executed before it is written down.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0103

- Status: RESOLVED
- Severity: P1
- Chapter: 53; `scripts/demo-payment-investigation.py`, found by running it against the containerised stack built in Chapter 60
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-08-08
- Escaped gate: None. `scripts/invariant-proof-check.py` was extended under `ER-0099` to confirm that every artifact named in a `Proof` field exists, and this script does exist. Nothing anywhere checks that a demo script named as a product promise's evidence actually completes, and the `ER-0096` correction — that a script named as proof needs a recorded execution — was recorded as a rule without a mechanism to enforce it.
- Invariant: `VP-004` and the Chapter 53 closure name `scripts/demo-payment-investigation.py` as the merchant/analyst demo that opens a risky attempt and explains every decision input, the winning control, the controls that did not win, the later analyst action, the label, and the money effect from one route. A demo cited as the evidence for a promise must run to completion.
- Finding: The script could never have completed, and stopped at its first assertion for three independent reasons. First, its source-reconciliation query selected `payment_intents.amount_minor`; that column has been `amount` since `V1` and was never renamed, so the query failed with `column "amount_minor" does not exist` on any database. Second, once that was corrected it asserted that a bounded search pages, immediately after publishing the events it searches for — but the search reads the Kafka-fed payment risk projection, and the assertion raced the consumer. The projection honestly reported `state: PARTIAL` with `appliedEventCount: 0` of 13 eligible and the search correctly returned nothing; the merchant's checkpoint was applied seven seconds after the search cutoff. Third, once that was corrected it read `webhooks["evidenceAuthority"]` from a response whose field is `authority`. Each defect hid the next, which is the signature of a script that has never been executed end to end rather than one that regressed. The product was correct throughout: every failure was the demo misreading a system that was behaving as designed.
- Why prior review missed it: The three defects are individually the kind a careful reader skims past — a plausible column name, an assertion that looks synchronous, a field name that reads correctly in English. What allowed all three to survive is structural: a demo script is reviewed as documentation and cited as evidence, and those two readings never met. `ER-0096` identified exactly this and its correction was verified by executing the scripts named in *that* chapter's matrix, which did not include this one. A rule that depends on someone remembering to apply it is a rule that holds only where it was first applied.
- Resolution evidence: The query selects `amount`; the field read is `authority`; and a new `wait_projection_current` helper polls the published freshness contract until the projection reports a state other than `PARTIAL` and the expected rows are present, bounded at sixty seconds, mirroring the existing `wait_readiness` rather than sleeping. The script now completes three consecutive times against the containerised stack, reporting `explainedAction: BLOCK`, `winningControl: BLOCK_USD_EXTREME_AMOUNT_V1`, `retainedLosingControls: 6`, `timelineCeiling: 7`, `relatedScope: SAME_MERCHANT_SERVER_DERIVED` and `sourceFingerprintStable: true`. This is the first recorded end-to-end execution of the `VP-004` demo.
- Process correction: A demo named as a product promise's evidence must be executed by a gate, not by intention. Chapter 64 owns running every `scripts/demo-*.py` against the deployed instance, and Chapter 66 wires that into `scripts/commit-readiness-check.sh` so a demo that stops asserting is a red build rather than a document nobody ran. The narrower lesson is that `ER-0096`'s correction was scoped to one chapter's scripts when its reasoning applied to all of them.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0104

- Status: RESOLVED
- Severity: P1
- Chapter: 51; `PaymentRiskProjectionHealthIndicator`, found by leaving the containerised stack idle during Chapter 60
- Category: IMPLEMENTATION_DEFECT
- Frozen promise affected: YES
- Date found: 2026-08-08
- Escaped gate: `PaymentRiskProjectionHealthIndicatorTests` asserted the age partitions exactly and correctly, and never asked whether age was the right question. Every other proof of this indicator ran inside a suite that had just produced events, which is the one condition under which the defect is invisible.
- Invariant: Readiness reports whether this instance can serve traffic. An instance that is caught up and merely quiet is serving correctly, and a readiness signal wired to a load balancer must not remove it from rotation.
- Finding: `readinessState` graded every tenant checkpoint by the wall-clock age of `last_processed_at` alone — `FRESH` within sixty seconds, `LAGGING` within five minutes, `STALE` beyond — and a single `STALE` checkpoint took the whole application `DOWN`. Because a checkpoint only advances when that merchant's events are applied, any merchant who stopped transacting for five minutes marked the entire instance unhealthy. Observed directly: with zero consumer lag on all six partitions, all 52 publications `ACKNOWLEDGED`, no quarantine and nothing pending, readiness returned `DOWN` with `consumerState: STALE` at checkpoint ages of 6 and 8 minutes. The consequence is worst exactly where it matters: with the ALB health check bound to `/actuator/health/readiness` as Phase 6.5 plans, every task leaves rotation roughly five minutes after traffic goes quiet, so a private demo — idle nearly all the time — would be permanently unavailable. It also degrades as the platform grows, since one idle merchant among many is sufficient, and eight tenant projections already existed on a stack a few minutes old.
- Why prior review missed it: The indicator was proven under load and reviewed as a lag detector, where staleness and brokenness are indistinguishable. Nothing in the repository exercised the state the system spends most of its life in — correct, caught up, and idle — because every test that touches the projection creates events first, and no deployment had ever existed for an operator to observe going quiet. This is a defect that only contact with a running environment can surface, which is the argument for Phase 6.5 preceding rather than following further product work.
- Resolution evidence: Pending work is now the gate and age only grades work that is waiting. The checkpoint query computes `pending_stream_count` per tenant by comparing `event_stream_heads.current_version` against `projection_stream_positions.last_stream_version` for the active generation — both already maintained, so no broker call is added to a health path — and a checkpoint with nothing pending is skipped regardless of age. A wedged consumer still trips the indicator, because its backlog does not drain. `pendingStreamCount` is published in the health details so an operator can distinguish a backlog from a broken query without database access. Five new tests in `PaymentRiskProjectionHealthIndicatorTests` pin the distinction: a tenant idle for thirty days is `FRESH`, a tenant with unapplied streams and a six-minute-old checkpoint is `STALE`, an idle tenant cannot mask a genuinely lagging one, a missing checkpoint is `UNAVAILABLE` only when work is waiting, and open quarantine still degrades to `PARTIAL` when every tenant is caught up. The two original age-partition tests are unchanged and still pass. Proven against the running stack: the same database whose checkpoints were `DOWN` at 8 minutes reports `UP` at 14 minutes.
- Process correction: A readiness indicator must be proven in the idle state, not only under load, because idle is the state a deployment spends most of its life in and the only state in which a false negative is permanent. The wider correction is that health semantics cannot be certified by a suite that always arrives having just produced work; Chapter 64 adds an idle readiness observation to the deployment smoke proof.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0105

- Status: RESOLVED
- Severity: P2
- Chapter: 62; `docs/PHASE_6_5_DETAILED_IMPLEMENTATION_PLAN.md` Section 13.1 and `scripts/invariant-proof-check.py`, found by reviewing the phase plan after Chapters 61 and 62 had shipped against it
- Category: WEAK_PROOF
- Frozen promise affected: NO
- Date found: 2026-08-08
- Escaped gate: The Phase 6.5 plan review that approved Section 13.1's compression of seven per-chapter matrices into one, and every readiness run since, which validated the matrices that existed and had no way to notice the matrix that could not exist.
- Invariant: An invariant-proof matrix is drafted from frozen promises and failure sequences before implementation, and its provenance says so honestly. A matrix reconstructed from the code that happens to exist may not be presented as pre-implementation work.
- Finding: Two defects, one enabling the other. Section 13.1 said Phase 6.5 uses one integrated matrix delivered at Chapter 66, and never said when that matrix is drafted; read literally it required a matrix authored after Chapters 63-65 to carry `Matrix provenance: PRE_IMPLEMENTATION`, since the only other accepted value is allowlisted to the Chapter 19 legacy recovery. Underneath it, `scripts/invariant-proof-check.py` could not represent a pre-implementation matrix at all: entry statuses were limited to `PASS` and `PARTIAL`, `Review status` had to be `VERIFIED`, the three review verdicts and the escaped-review assessment had to be final, and every artifact named in a `Proof` field had to already exist. `docs/INVARIANT_PROOF_REVIEW_PROCESS.md` meanwhile instructed authors to start a matrix at `Scope status: DRAFT` and `Review status: IN_PROGRESS` — a state its own checker rejected. The practical consequence is that pre-implementation matrices lived in uncommitted working trees and were committed only once closed, so nothing durable distinguished a matrix written first from one written afterwards. Chapters 61 and 62 then shipped with no matrix at all, correctly following 13.1 as written. No product invariant is involved; the damage is to the proof system's ability to tell the truth about its own sequence.
- Why prior review missed it: The gap is invisible from either side alone. Every committed matrix passes, because every committed matrix was committed after closure — so the checker has never been asked the question it cannot answer. And the plan's compression decision was reviewed as a documentation-volume tradeoff, which it also was; the missing word was "when", and an omission is far harder to see in review than a wrong statement. The header of the same plan still read `Frozen proof artifacts: Chapter 60-66 PRE_IMPLEMENTATION matrices`, contradicting 13.1 directly, and survived because a metadata block is read once at the top and never again.
- Resolution evidence: The checker gained a frozen-but-unproven state. `PENDING` is a legal entry status, the three review verdicts and the escaped-review assessment may be `PENDING`, and `Scope status: DRAFT` is accepted only while `Review status: IN_PROGRESS`. A `PENDING` row may name proof that does not exist yet, because naming the proof before building it is the point of drafting; the `ER-0099` obligation that a row must name an existing artifact applies from the moment the row closes. `Promise freeze` and `Failure inventory` remain mandatory in every state, since they are inputs to drafting rather than outputs of implementation. The state cannot leak into a completion claim: a matrix holding a `PENDING` row may not claim `Review status: VERIFIED`, `--reject-partial` rejects `PENDING` at phase closure alongside `PARTIAL`, and `scripts/chapter-closure-check.py` now refuses any dossier whose matrix is still `IN_PROGRESS` or still holds an unproven row. Eight new cases in `invariant_proof_check_tests.py` and two in `chapter_closure_check_tests.py` pin both directions, and the repository Python suite passes at 252, up from 243 by exactly those ten plus the readiness-gate registration of a new check. `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` was then drafted and committed on 2026-08-08 with all ten rows `PENDING`, before any Terraform, deployment, seed or runbook existed, and verified rejected by `--reject-partial`. Section 13.1 now states when the phase matrix is drafted, the contradicting header line is corrected, and both the plan and the phase matrix record that Chapters 61 and 62 have no pre-implementation matrix rather than absorbing them silently at closure.
- Process correction: A process rule and the gate that enforces it must be able to express the same states, and the way to find out is to try to execute the rule rather than to read it. `docs/INVARIANT_PROOF_REVIEW_PROCESS.md` told authors to create a matrix in a state its own checker refused, for as long as both documents have existed, and nobody noticed because everybody worked around it identically. The narrower correction is that a compression decision must say when as well as what: Section 13.1 removed six documents and did not say when the surviving one gets written, and an unstated schedule defaults to the most convenient moment, which here was the one that made the provenance false.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0106

- Status: IN_PROGRESS
- Severity: P1
- Chapter: 63; `docs/PHASE_6_5_DETAILED_IMPLEMENTATION_PLAN.md` Sections 4.2, 5 and 13, found by reviewing the phase plan before Chapter 63 started
- Category: MISSING_PROMISE
- Frozen promise affected: YES
- Date found: 2026-08-08
- Escaped gate: The Phase 6.5 plan review, which accepted "private / invite-only" as an exposure decision and did not ask what enforces it.
- Invariant: A deployment described as private must be private by mechanism. An unauthenticated request from outside the operator allowlist is refused before it reaches any THAAP surface, and no origin hostname is a way around that.
- Finding: The plan called exposure "private / invite-only" in Section 5 and "a deliberate control, not an unfinished feature" in Section 13, and named no mechanism anywhere. The only control described was authentication — a human pasting an operator key into a browser field — which is a different property. The topology is Route53 to CloudFront to ALB, which is a public URL, and the implicit control was that the hostname would not be published. That control does not exist: ACM issues publicly-trusted certificates, publicly-trusted certificates are submitted to Certificate Transparency logs, and opting out of CT logging makes the certificate untrusted in Chrome and Safari, so the hostname is public within minutes of issuance and CT streams are routinely scanned. Section 4.2 compounded it by listing WAF as a "Phase 6.5 follow-on" whose unblock condition was "before the URL is published" — but the URL is published the moment CloudFront exists. A second path was missed entirely: the WebACL attaches to CloudFront, so an ALB reachable by its own `*.elb.amazonaws.com` name bypasses the edge control completely, which also defeats the `server.forward-headers-strategy: framework` setting Chapter 62 shipped, since a caller reaching the ALB directly chooses its own `X-Forwarded-For` and with it the per-IP rate limit and the per-IP fraud velocity signal.
- Why prior review missed it: "Private" was written as a product decision and read as one, and product decisions in this plan are argued rather than mechanised — the surrounding rows in the same table are genuine tradeoffs with named triggers, which is the company the claim was keeping. The review also had no running system to test the claim against, and the failure is invisible on paper: a topology diagram showing Route53, CloudFront and an ALB looks identical whether or not a WebACL is attached, because the absent control is absent from the drawing too.
- Resolution evidence: **Partial, and deliberately left open.** The documentation half is corrected and the control itself is unbuilt, so this entry stays `IN_PROGRESS` — closing it would assert the property the finding is about. The invariant it names becomes true when `C63-SEC-001` closes, and that row is `PENDING` in `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md`, which `--reject-partial` refuses at phase closure, so Phase 6.5 cannot close while this is outstanding. What is corrected today: a new Section 5.1 states the mechanism in three layers and states plainly that until all three exist the deployment is not private and no document may describe it as such. Chapter 63's scope now delivers them: a WAF WebACL on the CloudFront distribution with default `BLOCK` and an operator IP set; an ALB that accepts only requests carrying a CloudFront-injected shared secret header held in Secrets Manager, so the origin is not reachable by its own name; and task security groups admitting the ALB security group by reference rather than by CIDR, which matters because there is no NAT Gateway and tasks run in public subnets. The Section 6 topology diagram now draws both controls inline, because a diagram that omits them is a diagram of a public system. Section 4.2 no longer defers the WAF, Section 13's honest-limits list is corrected, the Section 7 cost model carries the WAF at about seven dollars a month alongside two other categories it had omitted, and Section 17 decision 5 is amended from a policy to a mechanism with its cost stated. `C63-SEC-001` in the pre-implementation phase matrix owns the proof and requires it to be executed from an address genuinely outside the allowlist rather than from a shell inside the VPC, with the ALB-bypass case asserted separately.
- Process correction: A security posture stated as an adjective is not a control. Any claim of the form "the deployment is private/internal/restricted" must name the enforcing mechanism, the boundary it sits at, and the test that fails when it is removed — and must be written in the topology, not beside it. The specific generalisation is that hostname secrecy is never a control, because certificate issuance publishes it: any future phase that reaches for an unpublished URL as protection is repeating this finding. The wider one is that the review that finds this class of defect is the one that asks "what breaks if an attacker knows everything the design document says", which is now part of the hostile pass for any chapter that changes network exposure.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-08-08

## ER-0107

- Status: RESOLVED
- Severity: P2
- Chapter: 59; integrated Phase 6 completion governance
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-10-06
- Escaped gate: Phase 6 aggregate closure and Chapter 59 commit readiness.
- Invariant: A Phase 6 completion claim must reconcile all source-of-truth documents and inherited closure dossiers, and an aggregate closure failure must stop Chapter 59 readiness.
- Finding: The master plan retained an implementation-not-started claim split across Markdown lines, while the Chapter 55 and 56 dossier summaries still described Phase 6 as open. The aggregate checker matched literal spaces and did not scan inherited dossier prose. Chapter 59 readiness also did not invoke the Phase 6 aggregate check at all.
- Why prior review missed it: Completion markers and structural matrix validation were mistaken for semantic reconciliation; the negative fixtures did not cover line wrapping or contradictory inherited summaries, and readiness lacked an invocation assertion for Phase 6.
- Resolution evidence: The plan and inherited summaries now agree with completed Chapters 53–59. `scripts/phase-closure-check.py` normalizes whitespace before stale-claim matching and scans all inherited Phase 6 dossiers. `scripts/phase_closure_check_tests.py` exercises space, LF, CRLF, paragraph and tab boundaries, contradictory inherited dossier text, a valid repository and SDK digest drift. `scripts/commit_readiness_check_tests.py` asserts the exact `--phase-6` invocation for Chapter 59 and propagation of a nonzero aggregate exit. The focused 19-test run exited 0 on 2026-10-06; the real `scripts/phase-closure-check.py --phase-6` run exited 0. No runtime payment, fraud, tenant or migration behavior was changed.
- Process correction: Test representation boundaries in governance documents, not just happy-path status markers. For each integrated phase, test both invocation of its aggregate checker and failure propagation through readiness. Historical phase evidence remains dated rather than presented as a fresh runtime run.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-10-06

## ER-0108

- Status: RESOLVED
- Severity: P2
- Chapter: 59; source-of-truth reconciliation across Phase 5 prerequisites and Phase 7 entry
- Category: DOCUMENTATION_OVERCLAIM
- Frozen promise affected: YES
- Date found: 2026-10-06
- Escaped gate: Vision coverage and integrated source-of-truth review.
- Invariant: Vision prerequisites and debt ownership must distinguish implemented product truth, resolved proof debt and unimplemented model/hosted capabilities.
- Finding: VP-022 listed model timeout fallback as existing evidence even though the investigation explanation publishes `NOT_CONFIGURED` and VP-002 correctly assigns serving fallback to Phase 7. VP-008 called the offline dataset builder unimplemented after Chapter 51 closed it. The Phase 7 master-plan entry still called `TD-0007` and `TD-0008` open although the canonical register records their resolution. Several prerequisite descriptions also retained planning-only language after Phases 5–6 completed.
- Why prior review missed it: Future-owner rows were retained unchanged at phase closure without reconciling their prerequisite sentences against delivered capabilities; the structural checker verifies fields and ownership, not whether prose describes the current implementation.
- Resolution evidence: `docs/reviews/VISION_COVERAGE_REVIEW_2026-10-06.md` records all 23 dispositions, artifact inventory, current gaps and review limits. Chapter 51's dossier and `OfflineReplayDatasetService` establish the built replay substrate; `PaymentInvestigationService` explicitly emits `NOT_CONFIGURED`; the canonical debt register and current Phase 5 aggregate check establish resolved proof debt. Coverage now reflects these boundaries, and the Phase 7 entry agrees with the register. Aggregate Phases 4.5, 5 and 6 and `scripts/vision-coverage-check.py` each exited 0. Existing statuses and future owners remain unchanged; no trained model, hosted production or real PSP capability is invented.
- Process correction: Review both directions of capability truth: implemented prerequisites must not remain marked unbuilt, and future capabilities must not be listed as implemented evidence. Record entry-level dispositions before advancing review dates; read canonical debt state rather than copying historical deferral prose. Structural passes do not substitute for this semantic review.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-10-06
