# Chapter 50 Invariant Proof Matrix

- Chapter: Chapter 50 — Projection Rebuild, Reconciliation, And Activation
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

Verification update (2026-08-01): V72–V73, V75, V80–V81 and Spring Batch
implement immutable cutoffs/bootstrap facts, fenced 100-item chunks and catch-
up cycles, five-family reconciliation, atomic activation, cancellation, and
compatible rollback. H2 and real-PostgreSQL rebuild suites passed, including
101-input boundaries, legacy truth without fabricated events, and rejection of
same-count positive aggregate corruption.

Reopened verification update (2026-08-01): `ER-0075` exposed the untested
production score-unit partition. V82 plus the new H2/PostgreSQL score-bearing
rebuild contract prove 500 basis points succeeds, 10001 is rejected, and the
shadow generation reconciles before the public activate/rollback journey.

This matrix freezes Chapter 50 before production, migration, API, worker, or
test changes. Every proof below is planned evidence, not a completion claim.
The rebuild cutoff is an immutable manifest of each included tenant-qualified
stream and its maximum visible stream version plus the exact bootstrap-fact
manifest digest. A database sequence, event timestamp, Kafka offset, or wall
clock is never treated as the rebuild authority.

## C50-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Projection rebuild, catch-up, reconciliation, activation, cancellation, and rollback only derive read models; they never call a processor, send a webhook, create or change a PaymentIntent, PaymentAttempt, Authorization, Refund, Chargeback, FraudDecision, FraudLabel, review, control, incident, or balanced ledger group.
- Failure sequence: A replay handler reuses a domain orchestration service, a retry re-enters a money mutation, or activation repairs a mismatch by changing source truth.
- Implementation: projection-only handler boundary will accept canonical domain-event or immutable bootstrap-fact input and a shadow generation identifier, use projection repositories only, and prohibit source-domain repository dependencies; reconciliation reads source state without locking or mutating it.
- Proof: `ProjectionRebuildPostgreSqlTests` will snapshot source table counts, identities, terminal states, and ledger group digests before rebuild/cancel/activate/rollback and assert byte-equivalent source truth afterward; mocks will assert zero processor and webhook calls.
- Adversarial evidence: Approved capture, refund, chargeback, fraud label, open review, active control, duplicate event, failed chunk, canceled rebuild, rejected reconciliation, activation, and rollback partitions are exercised.
- Status: PASS

## C50-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Only a live `DATA_OPERATOR` may control rebuilds, every generation/rebuild/cutoff/checkpoint/reconciliation row is tenant-qualified where it contains tenant data, and copied projection names, generation IDs, rebuild IDs, or rollback targets never disclose or mutate another tenant's state.
- Failure sequence: A valid operator substitutes a foreign rebuild ID, a merchant risk credential calls an internal data route, a disabled operator passes an early check then acts, or a global rebuild response exposes tenant identifiers or counts without authorization.
- Implementation: platform-operator resolver and service transaction will lock and revalidate the live operator before the projection definition and rebuild resource; composite tenant foreign keys and safe not-found behavior will bind every tenant-owned relation.
- Proof: `ProjectionRebuildControlTests` and `ProjectionRebuildPostgreSqlTests` will cover `DATA_OPERATOR`, `PLATFORM_ADMIN`, merchant `VIEWER`/`ANALYST`/`RISK_MANAGER`, disabled credentials, same-shaped foreign IDs, and disable-after-precheck interleavings.
- Adversarial evidence: Same versus foreign tenant, existing versus absent resource, enabled versus disabled operator, data versus platform credential, list/detail/mutation route, and precheck-before-disable versus disable-before-lock partitions are forced.
- Status: PASS

## C50-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Rebuild APIs, events, logs, metrics, reconciliation evidence, and errors expose only bounded safe metadata; they never disclose canonical event payloads, bootstrap bytes, credentials, note plaintext, PAN/CVV, contact data, raw device/session/IP values, idempotency material, exception text, or unrestricted tenant identifiers.
- Failure sequence: A corrupt event reaches an exception message, a mismatch report copies payload bytes, a metric tags a tenant/rebuild/event ID, or a platform credential appears in an audit or response.
- Implementation: closed reason/invariant enums, count-and-digest reconciliation records, bounded DTOs, sanitizer checks, and closed metric tags will keep payload resolution inside the worker and exclude raw values from operator surfaces.
- Proof: `ProjectionRebuildPrivacyContractTests` seeds a merchant-name, contact and PAN canary into the source rows a rebuild reads, runs the full start/reconcile/activate lifecycle, and asserts no canary appears in any serialised control-plane view, in any row of `projection_rebuilds`, `projection_rebuild_events`, `projection_generations` or `projection_reconciliations`, or in the message or reason of a forbidden-role refusal and an unreconciled-activation refusal. `theRebuildFixtureActuallyCarriesTheCanariesItClaimsToScanFor` asserts the fixture is not empty, because every other assertion would pass equally well against a clean scan of nothing.
- Adversarial evidence: Invalid digest, unsupported event version, malformed bootstrap fact, SQL failure, lease loss, reconciliation mismatch, foreign resource, and operator error partitions are scanned.
- Status: PASS

## C50-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: A rebuild uses a 60-second lease and monotonically fenced ownership; it commits exactly 100 inputs per full Spring Batch chunk, resumes only after the last committed checkpoint, and a stale worker cannot write, complete, reconcile, cancel, or activate after lease takeover.
- Failure sequence: Worker A pauses after reading a chunk, its lease expires, worker B takes over and commits, then worker A resumes and overwrites progress or marks the generation complete.
- Implementation: `projection_rebuilds` lease owner/token/epoch, generation-qualified chunk writes, compare-and-update checkpoint, Spring Batch execution metadata, and append-only `projection_rebuild_events` will fence every state transition and preserve takeover evidence.
- Proof: `ProjectionRebuildLeasePostgreSqlConcurrencyTests` forces both claim orders behind a row lock the test holds and releases, so each schedule is exercised deliberately rather than sampled; a lease takeover at `beforeChunkLease`, the one instant a worker intends to write and holds no lock on the rebuild row; a crash between chunk evidence and its checkpoint; a crash after the checkpoint statement and before the chunk transaction commits; and expiry-driven recovery. It asserts chunk-commit progression 100 then 101, 99 inputs as exactly one partial chunk, that a stale worker can neither commit a chunk nor finish a build, that a stale worker's failure handler cannot mark a rebuild it no longer owns, that resumption reapplies nothing (one projection row per stream, each at exactly its own version), and the full `projection_rebuild_events` history including `LEASE_RECOVERED` carrying `EXPIRED_LEASE`. The seam this row needed is `ProjectionRebuildExecutionProbe`, added with it, mirroring `OfflineReplayDatasetExecutionProbe`. Expiry is reached by ageing the lease rather than by driving a clock to the 60-second boundary, and fencing is asserted on the epoch: the boundary is a wall-clock comparison, and the clock-based form of the equivalent replay test was flaky for exactly that reason.
- Adversarial evidence: Chunk sizes 99, 100 and 101 are covered here and 0, 1 and 2 by `ProjectionRebuildControlTests`; crash before checkpoint, after the checkpoint statement, and after a durable chunk commit; active, taken-over and expired lease; current and stale epoch, with the same failure path asserted to terminate the rebuild on a current epoch and to leave it untouched on a stale one.
- Status: PASS

## C50-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Readers remain on one complete active generation while a shadow builds; reconciliation and activation serialize on the projection definition, validate the shadow against one consistent PostgreSQL snapshot, and rollback can select only a retained, reconciled, handler-compatible generation.
- Failure sequence: A live event commits while reconciliation is running, activation exposes a partial generation, two operators activate different generations, cancel wins after activation, or rollback selects an incompatible generation.
- Implementation: active-generation pointer, shadow lifecycle state machine, definition-first lock order, snapshot-visible stream-position comparison, compatibility version, terminal cancellation fence, and retained-generation eligibility constraint will make the switch atomic.
- Proof: `ProjectionGenerationActivationPostgreSqlConcurrencyTests` holds the activation transaction open at the switch boundary through `ProjectionRebuildExecutionProbe.beforeGenerationActivation`, with the definition row locked and the switch unwritten, and forces cancel-versus-activation, rollback-versus-activation, activation-versus-activation, a live write committed before the activation snapshot, and a live write racing the switch. The contending operation is asserted to actually wait — a one-second timeout on its future — because without that the test would pass just as well if the two never overlapped. Readers outside the transaction are asserted to observe exactly one active generation throughout and never the uncommitted switch. Exactly one activation succeeds, the prior generation retires once, and one `ACTIVATED` event is appended. A write racing the switch is refused with `ProjectionGenerationUnavailableException`, is not quarantined, lands in neither generation, and lands in the newly active one on redelivery — the refusal is the invariant holding, since quarantining a valid event because a switch happened underneath it would convert a transient race into permanent operator work. Two operators activating different generations of one definition is unreachable rather than untested: `start` refuses a second rebuild while one is unfinished and RECONCILED counts as unfinished, so a definition never holds two activatable generations.
- Adversarial evidence: RECONCILED, ACTIVE and RETIRED generations under forced ordering; matching versus ahead stream positions through the before-snapshot and racing live writes; current versus foreign rollback targets, where a retained generation of the other projection definition is refused. BUILDING and CATCHING_UP are exercised by `ProjectionRebuildControlTests`, CANCELED by its cancellation fence, and FAILED by `ProjectionRebuildLeasePostgreSqlConcurrencyTests`. An incompatible-handler rollback target is asserted to be unconstructible rather than merely refused: `protect_projection_generation` makes a generation's handler version immutable and a generation is created carrying its definition's version, so a retained target cannot drift from the definition that owns it.
- Status: PASS

## C50-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each rebuild permanently binds the projection/handler version, immutable per-tenant/per-stream maximum-version cutoff manifest, bootstrap-fact manifest digest, origin policy, creation time, and canonical cutoff digest; rebuild membership never depends on a global event sequence, event time, Kafka offset, or later database visibility.
- Failure sequence: Two streams commit in opposite sequence order, an event timestamp collides, a later event has an older occurrence time, or bootstrap facts change while a resumed rebuild silently selects a different cohort.
- Implementation: cutoff manifest rows and digest will enumerate every included `(merchant, stream type, stream ID, max stream version)` and the exact bootstrap manifest before work begins; readers will join to that immutable manifest and reject digest or coverage drift.
- Proof: `ProjectionRebuildPostgreSqlTests` will create cross-stream commit inversions, equal timestamps, delayed events, before/exact/after stream versions, and changed bootstrap bytes, then compare repeated and resumed membership/digests.
- Adversarial evidence: Empty and populated tenant, bootstrap-only, event-only, mixed input, same timestamp, inverted surrogate sequence, late old-occurrence event, missing stream, extra stream, and digest mismatch partitions are covered.
- Status: PASS

## C50-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Reconciliation records complete evidence for input count, stream/version coverage, projection row count, canonical content digest, and declared payment-risk/merchant-risk business invariants; activation is impossible unless every required invariant passes and the shadow catches up to the activation snapshot.
- Failure sequence: Row counts match while amounts differ, a stream is missing but another has an extra row, bootstrap digest disagrees, or a live successor is visible to activation but absent from the shadow.
- Implementation: immutable reconciliation results and aggregate decision will retain expected/actual counts and digests by closed invariant family; activation will re-read and verify the latest successful evidence under the definition lock.
- Proof: `ProjectionRebuildPostgreSqlTests` will independently corrupt count, stream position, content, amount, label coverage, origin, and bootstrap evidence and assert rejection without changing the active pointer.
- Adversarial evidence: Exact match, compensating count mismatch, same-count content mismatch, missing/extra stream, one-version gap, amount imbalance, future label, origin drift, stale reconciliation, and catch-up lag are partitioned.
- Status: PASS

## C50-API-001

- Lens: API_PRODUCT
- Invariant: The local/internal control contract is limited to `POST /internal/data/projections/{name}/rebuilds`, bounded rebuild status under that collection, `POST /internal/data/projections/{name}/rebuilds/{rebuildId}/cancel`, `/reconcile`, `/activate`, and `POST /internal/data/projections/{name}/rollback`; all mutations are actor-bound and idempotent and never expose raw event or bootstrap content.
- Failure sequence: Retry after an ambiguous response creates a second active rebuild, a changed body reuses an idempotency key, activate succeeds before reconcile, or arbitrary projection/topic/offset/SQL parameters reach the service.
- Implementation: closed projection-name registry, validated route DTOs, platform-scoped idempotency identity, live operator revalidation, stable rebuild identity, bounded cursor status responses, and state-machine guards will enforce the contract.
- Proof: `ProjectionRebuildControlTests` will cover exact replay, conflicting replay, missing/oversized key, unsupported projection, invalid cutoff request, legal and illegal transitions, bounded pagination, and safe response schemas.
- Adversarial evidence: First request, committed-response loss, exact retry, changed retry, concurrent retry, missing resource, foreign resource, wrong role, stale reconciliation, and invalid rollback partitions are included.
- Status: PASS

## C50-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish rebuild queued/running/resumed/canceled/failed/reconciled/activated/rolled-back states, lease recovery, progress, duration, and reconciliation mismatch family through bounded metrics, health, audit, and append-only events without high-cardinality or sensitive labels.
- Failure sequence: A worker stalls with health still green, rollback success is emitted before commit, tenant/event/rebuild IDs become metric tags, or every mismatch collapses into one unactionable failure.
- Implementation: `projection_rebuild_events`, after-commit metrics, checkpoint-age/lease health, closed outcome and invariant-family tags, safe structured logs, and audited human mutations will separate operational states.
- Proof: `ProjectionRebuildObservabilityTests` asserts that all nine lifecycle states an operator must distinguish hold their own series, that no signal is visible before commit and a rolled-back activation is never reported as having happened, that lease recovery is distinguishable from a fresh claim and is not deferred to a transaction that already committed, that job duration is timed separately for completion and failure, that reconciliation mismatch is attributed to its family, and that no meter carries tenant, generation, rebuild, actor or checkpoint identity. `ProjectionRebuildMetrics.lease` and `.jobDuration` were added with this suite because production did not emit the lease-recovery and duration signals the row promised.
- Adversarial evidence: Committed versus rolled-back transitions, active versus stalled worker, known mismatch families, unknown exception classes, multiple tenants/rebuilds, and secret-shaped canaries are covered.
- Status: PASS

## C50-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Rebuild execution is restart-safe, bounded, cancelable, and recoverable; fresh and retained PostgreSQL migrations preserve the active generation, append-only rebuild evidence, and prior source rows, while worker-disabled or schema-incompatible configurations fail readiness without deleting work.
- Failure sequence: Process loss strands a RUNNING rebuild forever, Spring Batch restart rereads committed work, disk/dependency pressure causes unbounded retries, a retained migration rewrites active data, or worker enablement lacks required database configuration.
- Implementation: durable queue scan, expired-lease recovery, Spring Batch restart identity, 100-row chunks, 60-second lease, bounded executor/queue, explicit worker kill switch, compatibility readiness, and forward-only schema protections will govern recovery.
- Proof: ProjectionRebuildRestartIntegrationTests, fresh/retained `PostgreSqlMigrationTests`, and readiness tests will stop/restart the application between chunk and activation boundaries and verify resumption, no duplicate effect, and retained active service. The ProjectionRebuildRestartIntegrationTests suite this row promised was never written; the unbuilt assertions are registered as TD-0007.
- Adversarial evidence: Fresh schema, realistic V64-plus-analytics upgrade, queued/running/expired/canceled jobs, database outage, worker disabled, incompatible handler, storage/backlog threshold, and configuration rollback are covered.
- Status: PARTIAL

### Frozen Chapter 50 Boundary Table

| Boundary family | Valid partitions | Invalid partitions |
|---|---|---|
| Cutoff identity | immutable per-tenant/per-stream maximum versions plus bootstrap manifest digest | global sequence/time/offset cutoff, mutable query membership, missing bootstrap digest |
| Worker ownership | current 60-second lease owner and epoch, 100-input chunk, committed checkpoint | expired/stale owner, partial checkpoint, duplicate active worker, unbounded chunk |
| Generation visibility | complete active generation or isolated shadow | partial shadow read, in-place truncate/rebuild, incompatible rollback target |
| Reconciliation | all count/stream/content/business checks current and passing | stale, partial, compensating, digest, coverage, or catch-up mismatch |
| Authorization | live DATA_OPERATOR and platform-scoped idempotency | merchant role, bootstrap token, disabled operator, foreign identifier |
| Side effects | projection rows/checkpoints/rebuild evidence only | processor, webhook, payment, fraud, control, label, or ledger mutation |
