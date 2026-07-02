# Chapter 19 Invariant Proof Matrix

- Chapter: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Matrix provenance: RETROACTIVE_RECOVERY
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

## C19-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Webhook execution and retry never rewrite or roll back committed payment, fraud, authorization, refund, ledger, audit, or idempotency truth.
- Failure sequence: A callback returns failure, retries, and later succeeds after the originating payment transaction has committed.
- Implementation: WebhookDeliveryService.createDeliveriesInCurrentTransaction uses MANDATORY propagation so event routing can only join an existing domain transaction, while WebhookDeliveryExecutor claims, sends, and finalizes only after that transaction.
- Proof: WebhookDeliveryExecutionContractTests.routingRequiresExistingDomainTransaction, signedFailureRetrySuccessPreservesOneEventAndPaymentTruth, and payment rollback contract tests.
- Adversarial evidence: A direct out-of-transaction routing call fails before event or delivery persistence; a real callback then returns 503 followed by 204 while the retry test reconciles stable PaymentIntent and ledger state across both attempts.
- Status: PASS

## C19-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: A merchant cannot create, route, inspect, rotate, disable, claim, or decrypt another merchant's webhook state.
- Failure sequence: Merchant A substitutes Merchant B endpoint or delivery identifiers at API, repository, encryption, and database-lineage boundaries.
- Implementation: Merchant-scoped controller/service queries, tenant-bound encryption AAD, and composite merchant foreign keys constrain endpoint, event, delivery, and attempt ownership.
- Proof: WebhookEndpointOperationsTests.createFilterRotateDisableAndAuditStayTenantScopedAndSecretSafe, MerchantTenantIsolationTests.webhookDeliveriesAreVisibleOnlyToOwningMerchant, and DatabaseMigrationTests webhook constraints.
- Adversarial evidence: Cross-merchant IDs fail at both application and database boundaries without revealing endpoint existence or secret material.
- Status: PASS

## C19-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Only the exact persisted payload bytes and send timestamp are authenticated; merchant deduplication uses the signed body id and rejects a disagreeing event-id header.
- Failure sequence: An attacker changes only THAAP-Event-Id while preserving the timestamp, body, and valid MAC to induce deduplication under an unauthenticated identity.
- Implementation: WebhookSigner authenticates timestamp plus exact body; the merchant contract and callback fixture parse body id only after verification and constant-time compare it with THAAP-Event-Id.
- Proof: WebhookSecurityAndRetryTests.signerBindsTimestampAndExactPayloadBytes and webhook_callback_fixture_tests.py header-mismatch tests.
- Adversarial evidence: A valid signature paired with a changed event-id header is rejected before event processing.
- Status: PASS

## C19-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Callback delivery cannot disclose signing keys or sensitive payment data and cannot become an SSRF path.
- Failure sequence: A malicious endpoint, database reader, URL, redirect, DNS result, payload field, log field, or metric tag attempts to expose secrets or reach a prohibited network.
- Implementation: AES-256-GCM key wrapping, reveal-once APIs, strict payload allowlist, explicit address classification, pinned approved address transport, no redirects, discarded bodies, and bounded telemetry fields.
- Proof: WebhookSecurityAndRetryTests encryption, payload, target-policy, redirect, keyring, and startup-fail-closed tests plus WebhookEndpointOperationsTests secret scans.
- Adversarial evidence: Ciphertext tampering, wrong AAD, special IPv4/IPv6 ranges, URL confusion, unavailable key versions, and unsafe payload values fail closed before HTTP.
- Status: PASS

## C19-SEC-003

- Lens: SECURITY_PRIVACY
- Invariant: Webhook signing-secret encryption cannot run with absent or known-weak configured key material.
- Failure sequence: An operator accidentally configures a Base64-encoded 256-bit all-zero key; length-only validation accepts it, encrypts merchant signing secrets under predictable key material, and creates database-readable forgeability risk.
- Implementation: WebhookSecretCipher rejects blank execution config through startup validation and rejects decoded all-zero 256-bit keys during key parsing for both current and decryption-keyring entries.
- Proof: WebhookSecurityAndRetryTests.enabledExecutionFailsClosedWithoutEncryptionKey and allZeroWebhookEncryptionKeyFailsClosed.
- Adversarial evidence: A blank key leaves execution unconfigured and fails startup when delivery is enabled; an explicitly 32-byte all-zero key fails constructor validation before any endpoint secret can be encrypted.
- Status: PASS

## C19-SEC-004

- Lens: SECURITY_PRIVACY
- Invariant: Signed webhook event payloads are serialized through a JSON encoder with explicit mapper behavior and a bounded byte representation, not assembled through string concatenation, so future event/resource values cannot malform, inject, or oversized-write merchant-verifiable JSON.
- Failure sequence: A future webhook event type or legacy backfill value contains a quote, backslash, or control character; manual payload assembly emits malformed JSON or injected fields; or an oversized persisted body with a matching hash bypasses creation-time validation and reaches signing or socket connection.
- Implementation: JacksonConfiguration registers only the Java time module required by THAAP APIs; WebhookEventEnvelopeFactory and V9__harden_webhook_delivery_execution construct typed envelope records and serialize them with Jackson before computing payload SHA-256; creation, executor integrity validation, and socket transport independently reject serialized bodies larger than 64 KiB measured as UTF-8.
- Proof: JacksonConfigurationTests.objectMapperUsesExplicitJavaTimeModuleWithStableIsoInstants; WebhookSecurityAndRetryTests event-envelope, creation-size, and socketTransportRejectsOversizedPayloadBeforeConnecting tests; WebhookDeliveryExecutorUnitTests.oversizedPersistedPayloadFailsTerminallyBeforeSecretOrHttp; and the H2/PostgreSQL migration tests.
- Adversarial evidence: Runtime and legacy backfill paths no longer splice string values into JSON syntax; a payload whose hash is internally consistent but whose byte length exceeds the contract is terminal before secret access and independently rejected by direct transport before connection.
- Status: PASS

## C19-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Endpoint disable is monotonic; concurrent retry finalization cannot leave retry-scheduled or claimable work behind a disabled endpoint.
- Failure sequence: A delivery row is locked, retry finalization reads an active endpoint and waits, disable commits its bulk update, then finalization writes RETRY_SCHEDULED.
- Implementation: WebhookDeliveryFinalizationService acquires an endpoint share lock before the delivery write lock; disable takes the endpoint write lock before exhausting unclaimed deliveries.
- Proof: WebhookDeliveryPostgreSqlConcurrencyTests.concurrentRetryFinalizationAndDisableCannotLeaveRetryScheduled.
- Adversarial evidence: The PostgreSQL test holds the delivery lock, proves the finalizer owns the endpoint share lock with a NOWAIT conflict, starts disable, then releases the exact dangerous ordering and asserts terminal non-claimable state.
- Status: PASS

## C19-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent workers, endpoint rotation, event routing, lease recovery, and stale finalizers preserve one coherent attempt and monotonic endpoint state.
- Failure sequence: Workers race for one row, rotation races disable, event insertion races the bulk kill switch, or an expired worker finalizes after a replacement claim.
- Implementation: Ordered delivery-only SKIP LOCKED claims, unique attempt numbers, claim tokens, endpoint-before-delivery lock ordering where both are required, shared routing locks, locked attempt mutation reads, and locked token validation serialize conflicting transitions.
- Proof: WebhookDeliveryPostgreSqlConcurrencyTests.concurrentWorkersCreateOneClaimAndOneAttemptNumberWinner, forcedRotationBeforeDisableEndsDisabled, forcedDisableBeforeRotationRejectsReactivation, forcedRoutingBeforeDisableExhaustsTheCommittedDelivery, forcedDisableBeforeRoutingCreatesNoEventOrDelivery, and WebhookDeliveryExecutionContractTests expired-lease/stale-finalizer methods.
- Adversarial evidence: Each endpoint-transition and routing/disable ordering first proves the owner holds a conflicting PostgreSQL endpoint lock, starts the competing transaction, observes that exact backend in `pg_stat_activity` with `wait_event_type = 'Lock'`, and only then releases the owner; durable postconditions prove disable remains monotonic and no queued work is stranded.
- Status: PASS

## C19-CON-003

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Endpoint disable stops every claimed delivery that has not been durably marked dispatch-started; no HTTP request is emitted after the kill switch wins that boundary.
- Failure sequence: A worker claims a delivery and obtains signing material, the merchant disables the endpoint before dispatch preparation, and the worker then attempts to proceed to HTTP.
- Implementation: WebhookDeliveryClaimService.prepareForDispatch acquires the endpoint share lock before the delivery write lock, checks the endpoint is still ACTIVE, and exhausts a disabled claim as ENDPOINT_DISABLED with http_request_sent=false before any transport call.
- Proof: WebhookDeliveryExecutionContractTests.disablingEndpointAfterClaimButBeforeDispatchPreventsHttpSend plus WebhookDeliveryExecutorUnitTests.staleClaimAfterPreparationNeverPerformsHttp.
- Adversarial evidence: The contract test deliberately mocks signing-secret load to simulate material already being available before disable, then proves the real dispatch gate emits no transport request, leaves dispatch_started_at null, and records terminal no-send evidence.
- Status: PASS

## C19-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Event identity, resource lineage, payload bytes, hash, and timestamp remain immutable and database-representable across retries and migration.
- Failure sequence: Nanosecond application time is serialized while TIMESTAMP(6) truncates persisted time; migration invents a recoverable legacy secret or sibling lineage; a V8 row claims retry/success/failure without attempt evidence; or an inactive legacy endpoint leaves a PENDING row permanently unclaimable.
- Implementation: WebhookEventEnvelopeFactory normalizes to microseconds before serialization and persistence; because V8 stored no event identity, V9 preserves every pristine legacy delivery as its own synthetic event, rejects every row that claims prior execution without authoritative attempt evidence, marks active legacy endpoints REQUIRES_SECRET_ROTATION, and exhausts pristine queued rows for inactive endpoints as ENDPOINT_DISABLED.
- Proof: WebhookSecurityAndRetryTests.eventEnvelopeNormalizesTimestampToDatabasePrecisionBeforeSigning plus H2 and PostgreSQL Chapter 19 upgrade tests covering pristine active and inactive endpoint work and fail-fast scheduled/succeeded/failed representations.
- Adversarial evidence: A nanosecond input produces one canonical microsecond timestamp in payload and columns; unsupported aggregate execution state fails before V9 schema creation; inactive endpoint work becomes terminal rather than stranded; no secret material, attempt history, or historical fan-out is fabricated.
- Status: PASS

## C19-API-001

- Lens: API_PRODUCT
- Invariant: Merchant integration guidance produces a verifiable, replay-safe consumer and delivery reads remain safe, bounded, stable, and tenant scoped.
- Failure sequence: A merchant follows the README or fixture, deduplicates before signature verification, trusts an unauthenticated header, applies the same business effect after a crash/restart or concurrent duplicate, accepts event-ID reuse with changed bytes, loses a page boundary, or receives secret/internal fields.
- Implementation: The service README and fixture require verify-then-parse signed body id plus header agreement; the fixture atomically persists that authenticated ID and payload hash with its representative business effect before acknowledgement; APIs use paired stable cursors and allowlisted response DTOs.
- Proof: webhook_callback_fixture_tests.py live HTTP duplicate plus durable restart/concurrency/payload-conflict tests, the public demo's exactly-one-effect assertion, WebhookEndpointOperationsTests, and cursor traversal contract tests.
- Adversarial evidence: Changed header identity, malformed signed body id, a live `204/204` duplicate exchange, a restart after the first effect, sixteen concurrent duplicates, conflicting bytes under one event ID, partial cursor, cross-tenant reads, and unsafe response fields are rejected or converge to one effect.
- Status: PASS

## C19-API-002

- Lens: API_PRODUCT
- Invariant: HTTP Host represents the URI authority, not the selected DNS address family, and IPv6 literals remain RFC-shaped.
- Failure sequence: A domain resolves to IPv6 and transport incorrectly emits Host as a bracketed domain, causing rejection or routing to the wrong virtual host.
- Implementation: SocketWebhookHttpTransport derives Host exclusively from the original URI hostname and brackets only colon-containing IPv6 literals.
- Proof: WebhookSecurityAndRetryTests.hostHeaderBracketsOnlyIpv6LiteralsNotHostnamesResolvedToIpv6.
- Adversarial evidence: The boundary test pairs a normal domain with an IPv6 selected address and separately verifies an IPv6-literal URI.
- Status: PASS

## C19-API-003

- Lens: API_PRODUCT
- Invariant: The transport accepts only a final HTTP response status in the protocol and persistence range `100..599`; informational responses do not become delivery outcomes.
- Failure sequence: An untrusted callback sends a numeric `600` or `999`, a malformed `HTTP/not-http 204` protocol token, missing/tab/doubled separators, wrong-width or non-digit status code, a control-bearing reason phrase, or `100 Continue` before a final `204`; THAAP accepts non-HTTP acknowledgement truth or reaches a persistence constraint instead of following retry policy.
- Implementation: SocketWebhookHttpTransport accepts only HTTP/1.0 or HTTP/1.1 text status lines with the required single-space separators, exactly three ASCII status digits, and a control-safe optionally empty reason phrase, validates the `100..599` range, consumes bounded informational responses, and returns only the final response status to delivery classification.
- Proof: WebhookSecurityAndRetryTests.socketTransportRejectsOutOfRangeNumericStatusAsTransportFailure, socketTransportRejectsMalformedHttpVersionAsTransportFailure, socketTransportRejectsEveryMalformedStatusLineGrammarPartition, socketTransportRejectsControlBearingReasonPhrase, and socketTransportConsumesInformationalResponseBeforeFinalStatus.
- Adversarial evidence: Raw socket fixtures emit `999`, `HTTP/not-http 204`, six separator/code-shape violations, a control-bearing reason phrase, and `100` followed by `204`; malformed numeric/version/separator/code/reason responses become classified post-send transport failures, while the valid informational exchange succeeds only on the final status.
- Status: PASS

## C19-API-004

- Lens: API_PRODUCT
- Invariant: THAAP records a callback acknowledgement only after receiving a syntactically valid, structurally complete, bounded final HTTP response head; a final status line followed by malformed fields or without its terminating header boundary is not a successful delivery.
- Failure sequence: A callback writes `HTTP/1.1 204 No Content\r\n` and either disconnects before the empty line or inserts a missing-colon, whitespace-name, invalid-token, obsolete-folded, or control-bearing header line; THAAP reads the numeric status and incorrectly records `SUCCEEDED`.
- Implementation: SocketWebhookHttpTransport consumes status lines, validates every non-empty field line as an ASCII HTTP token name plus colon and control-safe value, requires the final empty-line boundary, and counts all status lines, fields, CRLF framing, informational sections, and the final section through one cumulative exact-byte 32 KiB budget.
- Proof: WebhookSecurityAndRetryTests.responseHeaderGrammarClassifiesEveryOctetForTheAsciiContract, socketTransportRejectsMalformedHeaderFieldsBeforeAcknowledgingSuccess, socketTransportConsumesInformationalResponseBeforeFinalStatus, socketTransportRejectsTruncatedFinalResponseHead, socketTransportBoundsFinalResponseHeaders, and socketTransportUsesOneResponseHeadBudgetAcrossInformationalAndFinalSections plus WebhookDeliveryExecutionContractTests.malformedSuccessfulResponseHeadCannotBecomeDurableSuccess.
- Adversarial evidence: Generative character-partition proof classifies every byte value for the ASCII field-name/value contract; raw sockets prove five malformed ASCII field families, a non-ASCII field octet, and a truncated `204` become post-send transport failures; the integrated executor/database proof shows malformed `204` state becomes retryable `TRANSPORT_ERROR`, never `SUCCEEDED`; an exact 32768-byte valid head succeeds while 32769 bytes fail.
- Status: PASS

## C19-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish success, retry, exhaustion, lease uncertainty, disable, latency, queue age, and actual due backlog without high-cardinality or sensitive tags.
- Failure sequence: Recovery or bulk disable bypasses normal finalization; a claim changes a pre-counted queued row to DELIVERING while disable waits and terminal metrics overcount; execution is intentionally disabled while pending work accumulates and the due gauge remains false-zero; distinct failure causes collapse; or telemetry exposes sensitive/high-cardinality values.
- Implementation: WebhookMetrics records bounded outcome, status, and error-code enums plus timers; finalization returns committed classification directly; bulk disable performs one bounded update per canonical event type and records only actual affected-row counts after database predicate re-evaluation; disabled scheduler polls refresh due backlog without invoking claim/send work.
- Proof: WebhookDeliveryExecutorUnitTests due/log/failure assertions, WebhookDeliverySchedulerUnitTests disabled/enabled mode tests, WebhookDeliveryExecutionContractTests, WebhookEndpointOperationsTests, and WebhookDeliveryPostgreSqlConcurrencyTests.concurrentClaimDoesNotInflateBulkDisableMetrics.
- Adversarial evidence: The PostgreSQL disable backend is observed waiting on a row changed concurrently to DELIVERING, then updates zero rows and emits zero premature terminal/failure counts; a fixed-time disabled poll updates backlog while never processing a delivery; normal causes remain distinguishable without forbidden tags.
- Status: PASS

## C19-OBS-002

- Lens: OBSERVABILITY
- Invariant: Attempt evidence distinguishes a request known not to have been sent from an attempt whose send outcome is genuinely unknown.
- Failure sequence: Secret loading, target validation, endpoint disable, signing after preparation, or another pre-HTTP step fails; recovery or finalization records false send evidence.
- Implementation: The attempt persists an explicit dispatch-start marker in the same locked transaction that renews the claim; recovery maps an unmarked attempt to no-send, disabled pre-dispatch closure remains no-send, a signing failure after the marker is explicitly finalized with http_request_sent=false, and only unresolved marked work becomes OUTCOME_UNKNOWN.
- Proof: WebhookDeliveryExecutionContractTests.expiredPreDispatchLeaseRecordsNoSendFailure, expiredPostDispatchLeaseRecordsOutcomeUnknown, and disablingEndpointAfterClaimButBeforeDispatchPreventsHttpSend plus WebhookDeliveryExecutorUnitTests.signingFailureAfterPreparationFinalizesAsKnownNoSend.
- Adversarial evidence: Otherwise identical claims differ by the durable marker and actual local knowledge; signing failure after preparation is not mislabeled unknown and no transport call occurs.
- Status: PASS

## C19-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Slow, failing, crashing, or disabled endpoints consume bounded resources and converge to explainable durable state.
- Failure sequence: DNS resolution stalls before lease renewal, dispatch preparation waits longer than merchant signature tolerance, a target trickles bytes, serial claims expire, attempt five lease expires, runtime infrastructure fails, or endpoint disable occurs around dispatch.
- Implementation: Bounded resolution, just-in-time claiming, durable preparation plus lease renewal, a fresh post-preparation signature timestamp, total socket deadline, deterministic retries, lease recovery, and classified safe outcomes bound the worker.
- Proof: WebhookSecurityAndRetryTests resolution/deadline/limit tests, WebhookDeliveryExecutorUnitTests.signatureTimestampIsMintedAfterDispatchPreparationCompletes and ordering/recovery tests, WebhookDeliveryExecutionContractTests lease/disable tests, and PostgreSQL recovery/concurrency tests.
- Adversarial evidence: A 301-second preparation delay still signs with the post-wait clock; resolver and trickle sequences remain bounded; no pre-send failure becomes false send truth; disable and fifth-attempt recovery converge without attempt six.
- Status: PASS

## C19-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Transient resolver infrastructure exhaustion or deadline failure is retryable no-send evidence, not terminal target-policy denial.
- Failure sequence: Resolver worker capacity is saturated or platform DNS exceeds the application deadline before any HTTP is attempted; the executor misclassifies that local infrastructure failure as TARGET_NOT_ALLOWED and permanently exhausts an otherwise valid endpoint.
- Implementation: WebhookHostResolver throws WebhookTargetResolutionException with bounded retryable error codes for capacity, interruption, and deadline failures; WebhookDeliveryExecutor catches it before target-policy violations and finalizes a retryable attempt with http_request_sent=false and no transport call.
- Proof: WebhookSecurityAndRetryTests.targetResolutionDeadlineBoundsNonReturningResolver and WebhookDeliveryExecutorUnitTests.resolverCapacityFailureRetriesWithoutTerminalTargetDenialOrHttp.
- Adversarial evidence: A non-canceling resolver proves deadline and capacity exceptions carry retryable codes; the executor unit test proves resolver capacity does not call HTTP and does not invoke terminal TARGET_NOT_ALLOWED finalization.
- Status: PASS

## C19-OPS-003

- Lens: OPERATIONS_RESILIENCE
- Invariant: DNS and resolver outcomes use an exhaustive classification boundary: only an explicitly prohibited target is terminal, while timeout, saturation, interruption, unknown host, empty answer, and resolver infrastructure failure remain retryable known-no-send evidence.
- Failure sequence: DNS temporarily returns NXDOMAIN/UnknownHostException, an empty answer, or an unexpected resolver failure; THAAP converts it to WebhookTargetPolicyException and permanently exhausts a safe endpoint as TARGET_NOT_ALLOWED on attempt one.
- Implementation: WebhookHostResolver maps timeout, saturation, interruption, unknown host, and unexpected resolver failure to WebhookTargetResolutionException; WebhookTargetPolicy maps empty address answers to the same retryable boundary and reserves WebhookTargetPolicyException for URL, scheme, allowlist, and prohibited-address decisions.
- Proof: WebhookSecurityAndRetryTests.targetResolutionDeadlineBoundsNonReturningResolver, emptyDnsAnswerIsRetryableResolutionFailureNotTerminalTargetDenial, and unknownHostAndResolverFailuresRemainRetryableKnownNoSendOutcomes plus WebhookDeliveryExecutorUnitTests.resolverCapacityFailureRetriesWithoutTerminalTargetDenialOrHttp.
- Adversarial evidence: Timeout, saturated capacity, NXDOMAIN/unknown host, empty answer, and injected resolver failure all carry bounded retryable codes; private or non-allowlisted targets remain terminal policy failures; the executor records the retry path with `http_request_sent=false` and no transport interaction.
- Status: PASS

## C19-OPS-004

- Lens: OPERATIONS_RESILIENCE
- Invariant: Every accepted DNS-resolution, connect, and request timeout remains a positive bounded whole-millisecond value after conversion from configuration `Duration` to the integer/long milliseconds consumed by Java future, socket, and deadline APIs.
- Failure sequence: An operator configures a positive sub-millisecond timeout; validation accepts the source duration, conversion truncates it to zero, Java treats the socket timeout as unbounded while the deadline thread sleeps for zero milliseconds, and the documented bounded-worker semantics become representation-dependent.
- Implementation: WebhookDurationValidator centralizes conversion for SocketWebhookHttpTransport and WebhookHostResolver, rejecting null, negative, zero, fractional-millisecond, configured-maximum violations, and conversion-overflow durations before a resolver worker or socket can start.
- Proof: WebhookSecurityAndRetryTests.webhookTimeoutConfigurationRejectsEveryUnrepresentableOrUnboundedDuration.
- Adversarial evidence: The test exercises null, zero, one nanosecond, 999999 nanoseconds, 1.000001 milliseconds, negative one millisecond, exact one millisecond, exact socket maximum, socket maximum plus one nanosecond/millisecond, resolver maximum, resolver maximum plus one millisecond, and Duration-to-millisecond overflow; both socket parameters and the resolver constructor share the validator.
- Status: PASS

## C19-API-005

- Lens: API_PRODUCT
- Invariant: The outbound HTTP request target represents the merchant-configured URI path and query exactly after RFC ASCII serialization; Unicode characters are percent-encoded and existing escapes are not rewritten.
- Failure sequence: A merchant configures `/café?q=naïve`; the transport concatenates Unicode `URI` components and encodes the request head as US-ASCII, substitution turns characters into `?`, and the signed callback reaches a different server route.
- Implementation: SocketWebhookHttpTransport derives the request path and query from `URI.toASCIIString()` before constructing the ASCII HTTP/1.1 request line.
- Proof: WebhookSecurityAndRetryTests.socketTransportPercentEncodesUnicodeRequestTargetWithoutChangingExistingEscapes.
- Adversarial evidence: A real socket captures `POST /caf%C3%A9/already%20encoded?q=na%C3%AFve&literal=%2F HTTP/1.1`; UTF-8 characters are encoded by octet while existing `%20` and `%2F` representations remain stable.
- Status: PASS

## C19-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: V9 rejects every unsupported legacy webhook event type before creating any new schema or lineage truth.
- Failure sequence: A V8 row contains an unconstrained unknown event type; execution-state preflight passes, V9 creates tables and mutates endpoints, and only then resource-type mapping fails, leaving partial H2 DDL and an unretryable failed migration.
- Implementation: V9 preflights the complete legacy event-type domain against the five canonical Chapter 19 types immediately after execution-state validation and before its first DDL statement.
- Proof: DatabaseMigrationTests.v9MigrationRejectsUnsupportedLegacyEventTypesBeforeCreatingSchemaTruth and PostgreSqlMigrationTests.v9MigrationRejectsUnsupportedLegacyEventTypesBeforeDdlOnPostgreSql.
- Adversarial evidence: Both databases reject `legacy.unknown`; post-failure metadata proves neither `webhook_events` nor `webhook_endpoints.operational_status` exists, so no V9 product truth preceded rejection.
- Status: PASS

## C19-OPS-005

- Lens: OPERATIONS_RESILIENCE
- Invariant: Every non-trivial commit-readiness invocation executes both backend and repository Python suites before it can return success.
- Failure sequence: A caller supplies the required matrix, dossier, and deep-dive flag but omits optional `--run-tests`; structural checks pass while broken product code is never executed.
- Implementation: commit-readiness-check.sh makes `--deep-dive-required` imply `run_tests=true` while retaining optional test execution for explicitly trivial work.
- Proof: CommitReadinessCheckTests.test_non_trivial_readiness_always_runs_backend_and_python_tests.
- Adversarial evidence: The behavior test invokes the real readiness shell in an isolated repository fixture without `--run-tests` and asserts that both the Maven and Python commands ran before success.
- Status: PASS
