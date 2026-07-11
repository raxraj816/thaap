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
- Resolution evidence: V18 and the JPA mapping now make `(definition ID, code, version, value type)` one identity. `databaseBindsEveryDeclaredValueTypeToTheReferencedDefinitionType` crosses every seeded definition with LONG, STRING, BOOLEAN, and DECIMAL, accepts only its required type, and rejects all incompatible types on PostgreSQL while the full 301-test backend suite passes without skips.
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
