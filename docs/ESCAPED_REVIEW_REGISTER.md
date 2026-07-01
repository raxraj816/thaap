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
