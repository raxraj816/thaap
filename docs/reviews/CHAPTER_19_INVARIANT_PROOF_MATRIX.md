# Chapter 19 Invariant Proof Matrix

- Chapter: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Matrix provenance: RETROACTIVE_RECOVERY
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

## C19-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Webhook execution and retry never rewrite or roll back committed payment, fraud, authorization, refund, ledger, audit, or idempotency truth.
- Failure sequence: A callback returns failure, retries, and later succeeds after the originating payment transaction has committed.
- Implementation: WebhookDeliveryService persists event routing inside the domain transaction while WebhookDeliveryExecutor claims, sends, and finalizes only after that transaction.
- Proof: WebhookDeliveryExecutionContractTests.signedFailureRetrySuccessPreservesOneEventAndPaymentTruth and payment rollback contract tests.
- Adversarial evidence: A real callback returns 503 then 204 while the test reconciles stable PaymentIntent and ledger state across both attempts.
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
- Failure sequence: A future webhook event type or legacy backfill value contains a quote, backslash, or control character; manual payload assembly emits malformed JSON or injected fields; or a future payload expansion exceeds the signed fixture/database operating boundary and THAAP signs bytes a merchant cannot safely parse, store, or verify.
- Implementation: JacksonConfiguration registers only the Java time module required by THAAP APIs; WebhookEventEnvelopeFactory and V9__harden_webhook_delivery_execution construct typed envelope records and serialize them with Jackson before computing payload SHA-256; runtime payload creation rejects serialized bodies larger than 64 KiB measured as UTF-8 bytes before hash/persist/sign.
- Proof: JacksonConfigurationTests.objectMapperUsesExplicitJavaTimeModuleWithStableIsoInstants, WebhookSecurityAndRetryTests.eventEnvelopeUsesOnlyCanonicalAllowlistedFieldsAndMatchingHash, WebhookSecurityAndRetryTests.eventEnvelopeRejectsPayloadLargerThanSignedFixtureBoundary, and DatabaseMigrationTests.chapter19MigrationBackfillsImmutableEventsAndRequiresLegacySecretRotation parse the exact signed/stored payload bytes as JSON, verify the SHA-256 matches those bytes, and prove oversized UTF-8 payloads fail closed.
- Adversarial evidence: Runtime and legacy backfill paths no longer splice string values into JSON syntax; the tests would fail if payload bytes were malformed, reordered outside the public contract, hashed differently from the persisted body, serialized with unstable Java-time behavior, or allowed past the 64 KiB signed-body boundary.
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
- Failure sequence: Nanosecond application time is serialized while TIMESTAMP(6) truncates persisted time; migration invents a recoverable legacy secret or mismatched event lineage; or a V8 `PENDING` row with a non-null `next_attempt_at` reaches the V9 retry-time constraint without being translated to `RETRY_SCHEDULED`.
- Implementation: WebhookEventEnvelopeFactory normalizes to microseconds before serialization and persistence; V9 uses composite lineage constraints, honest REQUIRES_SECRET_ROTATION backfill, and explicit normalization of every legacy delivery-state representation before installing V9 constraints.
- Proof: WebhookSecurityAndRetryTests.eventEnvelopeNormalizesTimestampToDatabasePrecisionBeforeSigning plus H2 and PostgreSQL Chapter 19 upgrade tests covering immediate pending, scheduled pending, succeeded, and failed legacy delivery representations.
- Adversarial evidence: A nanosecond input produces one canonical microsecond timestamp in payload and columns; a schema-valid V8 scheduled row migrates without constraint failure or changed retry time; legacy migration preserves truth without fabricating key material.
- Status: PASS

## C19-API-001

- Lens: API_PRODUCT
- Invariant: Merchant integration guidance produces a verifiable, replay-safe consumer and delivery reads remain safe, bounded, stable, and tenant scoped.
- Failure sequence: A merchant follows the README or fixture, deduplicates before signature verification, trusts an unauthenticated header, loses a page boundary, or receives secret/internal fields.
- Implementation: The service README and fixture require verify-then-parse signed body id plus header agreement; APIs use paired stable cursors and allowlisted response DTOs.
- Proof: webhook_callback_fixture_tests.py, WebhookEndpointOperationsTests, and cursor traversal contract tests.
- Adversarial evidence: Changed header identity, malformed signed body id, partial cursor, cross-tenant reads, and unsafe response fields are rejected.
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
- Failure sequence: An untrusted callback sends a numeric `600` or `999`, which parses as an integer, reaches finalization, violates the attempt-table constraint, rolls back, and is later misclassified as lease uncertainty instead of following retry policy; or it sends `100 Continue` before a final `204`.
- Implementation: SocketWebhookHttpTransport validates status-line grammar and range, consumes bounded informational responses, and returns only the final response status to delivery classification.
- Proof: WebhookSecurityAndRetryTests.socketTransportRejectsOutOfRangeNumericStatusAsTransportFailure and socketTransportConsumesInformationalResponseBeforeFinalStatus.
- Adversarial evidence: Raw socket fixtures emit `999` and `100` followed by `204`; the first becomes a classified transport error that finalizes normally, while the second succeeds on the final status without duplicate attempt truth.
- Status: PASS

## C19-API-004

- Lens: API_PRODUCT
- Invariant: THAAP records a callback acknowledgement only after receiving a structurally complete, bounded final HTTP response head; a final status line without its terminating header boundary is not a successful delivery.
- Failure sequence: A callback writes `HTTP/1.1 204 No Content\r\n` and disconnects or stalls before the empty line that completes the response head; THAAP reads the numeric status and incorrectly records `SUCCEEDED`.
- Implementation: SocketWebhookHttpTransport consumes every informational and final response header section through a shared 32 KiB bound and returns the final status only after reading the empty line that terminates the final response head.
- Proof: WebhookSecurityAndRetryTests.socketTransportConsumesInformationalResponseBeforeFinalStatus, socketTransportRejectsTruncatedFinalResponseHead, and socketTransportBoundsFinalResponseHeaders.
- Adversarial evidence: A complete raw-socket `204` succeeds; the same status line without its terminating empty line and a final response head beyond the configured parser bound both become classified post-send transport failures rather than false `SUCCEEDED` truth.
- Status: PASS

## C19-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish success, retry, exhaustion, lease uncertainty, disable, latency, queue age, and actual due backlog without high-cardinality or sensitive tags.
- Failure sequence: Recovery or bulk disable bypasses normal finalization, or metrics/logs expose merchant, endpoint, URL, resource, payload, or secret values.
- Implementation: WebhookMetrics records bounded enums and timers across normal and recovery paths; executor/finalization logs use bounded identifiers and omit protocol secrets.
- Proof: WebhookDeliveryExecutorUnitTests.dueGaugeReportsRemainingEligibleBacklog, successfulFinalizationLogIncludesBoundedLatencyField, and endpoint/contract metric assertions.
- Adversarial evidence: Unknown lease recovery and bulk terminal transitions are counted while tag and log scans reject forbidden dimensions and content.
- Status: PASS

## C19-OBS-002

- Lens: OBSERVABILITY
- Invariant: Attempt evidence distinguishes a request known not to have been sent from an attempt whose send outcome is genuinely unknown.
- Failure sequence: Secret loading, target validation, endpoint disable, or another preparation step fails before HTTP; the claim expires or is closed; recovery or dispatch preparation incorrectly changes `http_request_sent` from false to true and records false merchant-facing evidence.
- Implementation: The attempt persists an explicit dispatch-start marker in the same locked transaction that renews the claim immediately before HTTP; recovery maps an unmarked attempt to a no-send retryable failure, disabled pre-dispatch closure remains known no-send, and only a marked attempt can become `OUTCOME_UNKNOWN`.
- Proof: WebhookDeliveryExecutionContractTests.expiredPreDispatchLeaseRecordsNoSendFailure, expiredPostDispatchLeaseRecordsOutcomeUnknown, and disablingEndpointAfterClaimButBeforeDispatchPreventsHttpSend.
- Adversarial evidence: Otherwise identical claims differ only by the durable dispatch marker and produce distinct, truthful outcomes; a disabled pre-dispatch claim remains terminal known-no-send without creating duplicate attempt numbers or emitting HTTP.
- Status: PASS

## C19-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Slow, failing, crashing, or disabled endpoints consume bounded resources and converge to explainable durable state.
- Failure sequence: DNS resolution stalls before lease renewal, a target trickles a status line, serial batch claims expire before send, attempt five lease expires, runtime infrastructure fails unexpectedly, or endpoint disable occurs before or after dispatch starts.
- Implementation: A bounded target-resolution deadline, just-in-time one-row claims, durable pre-dispatch marking plus lease renewal, endpoint-active validation at the final no-bytes boundary, total wall-clock socket deadline, deterministic five-attempt policy, lease recovery, and classified safe terminal outcomes bound the worker.
- Proof: WebhookSecurityAndRetryTests target-resolution and socket deadline tests, WebhookDeliveryExecutorUnitTests ordering/recovery tests, WebhookDeliveryExecutionContractTests pre/post-dispatch lease, pre-dispatch disable, and fifth-attempt/disable tests, and WebhookDeliveryPostgreSqlConcurrencyTests.disabledEndpointStillRecoversExpiredInFlightAttemptOnPostgreSql.
- Adversarial evidence: A resolver that never returns is terminated within the configured preparation budget; trickle traffic exceeds per-read timing yet hits the total deadline; pre-send failure remains distinguishable from uncertain dispatch; endpoint disable before dispatch emits no HTTP; endpoint disable after dispatch does not strand an expired delivery; attempt six is never created.
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
