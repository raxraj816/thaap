# Chapter 30 Invariant Proof Matrix

- Chapter: Chapter 30 — Redis Velocity And Typed Fraud Lists
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

This matrix was frozen on 2026-07-13 after Chapter 29 closure and before any
Chapter 30 production code, migration, Redis script, or list API. ADR-0010 is
accepted; its exact-byte script boundary remains an implementation obligation.

The 2026-07-25 escaped-review remediation for ER-0052 and ER-0053 reopened the
rotation and compensation families. Current code evaluates retained-version
windows and uses insertion-only token ownership plus atomic compare-delete;
real Redis tests re-close both families without changing the original matrix
provenance.

## C30-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Velocity and negative-list candidates participate in complete deterministic aggregation without suppressing a stronger base rule or creating processor/money truth before the final action.
- Failure sequence: Redis/list evidence converts BLOCK to REVIEW/ALLOW, calls the processor before aggregation, or becomes an ALLOW override.
- Implementation: `FraudActionAggregator` consumes base, velocity, and list candidates with fixed precedence `BLOCK > REVIEW > REQUEST_3DS > ALLOW`; list actions exclude ALLOW and Redis uncertainty supplies a REVIEW floor before any processor call.
- Proof: `VelocityDecisionAggregationTests` covers all 36 base-action × velocity-state × list-action combinations and all six list insertion orders; public confirm contracts prove BLOCK creates no authorization or ledger truth.
- Adversarial evidence: Stronger/weaker/equal candidates, multiple matches, corrupt/missing control evidence, and dependency failure.
- Status: PASS

## C30-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: The current logical attempt is excluded from its own velocity count on first execution, same-key retry, transaction retry, and compensation failure.
- Failure sequence: A payment counts itself, retry increments twice, or rollback makes the same attempt appear as prior traffic.
- Implementation: `VelocityIdentityService` derives stable purpose-HMAC members from merchant/intent/logical attempt; `velocity_v1.lua` atomically prunes, excludes the member, counts prior attempts, adds with NX, and expires the key.
- Proof: Real-Redis integration tests cover first execution, same-member retry, distinct attempts, rollback compensation, abandoned members, TTL expiry, and same-attempt database retry.
- Adversarial evidence: Same/different key, same/different body, member already present, cleanup success/failure, and clock/window boundaries.
- Status: PASS

## C30-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Redis keys/members, fraud lists/entries, evaluation rows, APIs, audit, and webhook evidence are tenant-bound; cross-merchant values are missing-equivalent.
- Failure sequence: One merchant's velocity or list entry affects, reveals, activates, or deactivates another merchant's control.
- Implementation: Merchant-scoped purpose-HMAC domains, composite PostgreSQL keys, tenant repositories, operator ownership checks, and semantic triggers bind every control/evaluation surface.
- Proof: Identity and API contracts use the same raw identity across merchants without cross-match; `FraudListPostgreSqlTests` rejects direct list/entry/decision/operator tenant substitutions and the Redis suite proves merchant-separated keys.
- Adversarial evidence: Same raw identity across merchants, foreign IDs/credentials, copied digest, and activation race.
- Status: PASS

## C30-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Raw card fingerprint, IP, email, customer, device, credential, and generic-list values never appear in Redis, PostgreSQL, response, error, log, metric, audit, or webhook surfaces.
- Failure sequence: A raw/high-cardinality input becomes a key/member/tag, list payload, database error, or public explanation.
- Implementation: Purpose-separated versioned HMAC identities protect high-cardinality values; BIN/country use closed canonical grammars; generic namespaces are allowlisted; DTOs and telemetry expose closed safe fields only.
- Proof: Contract and live-demo canaries scan Redis keys/members, list/evaluation/customer/risk/idempotency/audit/webhook database surfaces, HTTP bodies, captured logs, and metrics with zero raw matches.
- Adversarial evidence: Unicode, controls, delimiters, wildcard-looking values, malformed script results, SQL failures, and operator denial.
- Status: PASS

## C30-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Only active RISK_MANAGER operators may create/activate/deactivate typed negative-list truth, and status/role/tenant are revalidated under the mutation lock.
- Failure sequence: Analyst, disabled, foreign, or concurrently disabled operator changes active controls after passing an early check.
- Implementation: Existing risk-operator credentials are revalidated for tenant, active status, and `RISK_MANAGER` role while holding the merchant/operator mutation locks; immutable lifecycle evidence records the actor and role.
- Proof: Public contracts reject analyst, disabled, missing, malformed, and foreign credentials; PostgreSQL forced disable-first and mutation-first orders prove no stale authorization window.
- Adversarial evidence: Missing/malformed/rotated credential, stale lifecycle epoch, self-replay, and foreign list ID.
- Status: PASS

## C30-API-001

- Lens: API_PRODUCT
- Invariant: Fraud-list APIs accept only closed type/action/reason/namespace/effective-time grammar and expose stable idempotent lifecycle responses without raw identities.
- Failure sequence: Free-form types/actions, ALLOW, ambiguous normalization, invalid intervals, unknown fields, or changed-body replay become active controls.
- Implementation: Strict DTOs/enums, per-type canonicalizers, bounded effective intervals, immutable versioned entries, strict Jackson, and fenced idempotency enforce the public grammar.
- Proof: `FraudListContractTests` table-drives every identity type, invalid namespace/value/time partitions, action/reason closure, unknown fields, exact replay, and changed-body conflict.
- Adversarial evidence: Empty/oversize/confusable values, email/domain edge cases, IPv4/IPv6 forms, BIN/country bounds, duplicate keys, and media/method errors.
- Status: PASS

## C30-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Fraud lists, entries, lifecycle events, key versions, creators, effective intervals, and matched decision evidence are immutable, versioned, and point-in-time reproducible.
- Failure sequence: Deactivation deletes history, an entry changes value/action, or a historical explanation resolves against current list state.
- Implementation: V38–V40 add list/version/entry/lifecycle/evaluation tables, composite semantic keys, parent-row activation serialization, and append-only PostgreSQL triggers.
- Proof: Migration and PostgreSQL tests cover point-in-time before/at/after evaluation, historical reads after deactivation, direct update/delete rejection, wrong-parent substitutions, and a forced two-transaction activation race with exactly one active version.
- Adversarial evidence: Backdated/future intervals, overlapping versions, wrong key version, entry reuse, and decision/list substitution.
- Status: PASS

## C30-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Every Redis velocity observation records script version, Redis server time, window/count/status/freshness/provenance; unavailable or malformed results never become zero.
- Failure sequence: Host time is mislabeled as Redis time, malformed counts overflow/truncate, or timeout fabricates fresh zero evidence.
- Implementation: Typed velocity results and immutable observation/feature lineage record `AVAILABLE` or `UNAVAILABLE`, script version/SHA, Redis server time, window, count, freshness, and provenance.
- Proof: `VelocityScriptContractTests`, Redis integration tests, and PostgreSQL lineage tests cover exact return grammar, numeric boundaries, millisecond precision, snapshot reconciliation, malformed results, and unavailable REVIEW evidence.
- Adversarial evidence: Negative/huge/fractional numbers, reordered/extra/missing fields, wrong version, and stale time.
- Status: PASS

## C30-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The versioned Lua operation atomically prunes, detects the current member, counts others, adds once, applies TTL, and returns one coherent result.
- Failure sequence: Client-side command interleaving exposes partial prune/add/expire state or duplicate members.
- Implementation: Repository-owned immutable observation and compensation Lua scripts execute through EVALSHA boundaries and are verified against SHA-1 `10bde6dc25904bcbce276ecc99f44e6bbaeeb53b` / `f333eb65957b7b3beb279a3c79699ac0de758fab` and versions `velocity_v1` / `velocity_compensation_v1`.
- Proof: Real Redis concurrent clients, exact-byte/SHA tests, first/retry/window/TTL fixtures, command postconditions, and SCRIPT FLUSH reload prove atomic behavior.
- Adversarial evidence: Script flush/reload, wrong SHA/version, eviction/restart, simultaneous members, and memory/TTL bounds.
- Status: PASS

## C30-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Database rollback performs bounded best-effort Redis compensation; leaked members expire and can only conservatively increase another attempt's candidate.
- Failure sequence: Cleanup removes another attempt, blocks rollback indefinitely, or a leaked member weakens a later decision.
- Implementation: Transaction synchronization submits the exact insertion owner token after rollback; the compensation script atomically compare-deletes only matching ownership, with bounded Redis timeout, closed outcome metrics, and finite TTL safety net.
- Proof: Coordinator and real PostgreSQL rollback tests prove successful cleanup and clean retry; real Redis interleavings prove retry rollback cannot delete an existing member and stale cleanup cannot delete a newer insertion; cleanup failure remains bounded/observable and leaked evidence can only raise risk until expiry.
- Adversarial evidence: Timeout before/after apply, process death, Redis unavailable during cleanup, and member expiry rollover.
- Status: PASS

## C30-CON-003

- Lens: TRANSACTION_CONCURRENCY
- Invariant: List activation/deactivation and confirm serialize so a decision observes exactly one committed point-in-time list state and persists it.
- Failure sequence: Confirm mixes entry rows across versions or records a match from a rolled-back activation.
- Implementation: Confirm acquires a shared merchant risk-control lock; list lifecycle mutation acquires the exclusive merchant lock; point-in-time evaluation persists immutable matched/non-matched evidence.
- Proof: `FraudListPostgreSqlTests` forces confirm-first and deactivation-first orders, two concurrent deactivations, operator-disable races, and version-activation races with exact lock-wait and decision postconditions.
- Adversarial evidence: Same entry in competing versions, operator disable, rollback, and repeated activation.
- Status: PASS

## C30-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish Redis available/unavailable/malformed/version/timeout, cleanup success/failure, list lifecycle, and evaluation outcomes using closed low-cardinality dimensions.
- Failure sequence: Ambiguity is invisible, rollback looks committed, or key/member/list/value identifiers enter metric tags or logs.
- Implementation: Counters and timers emit after transaction completion with closed operation/result/cause/transaction/script-version dimensions; safe logs contain identifiers and closed outcomes only.
- Proof: `FraudListMetricsTests`, velocity metrics tests, and captured-log canary contracts prove exact committed/rolled-back tags, operation/result families, bounded cardinality, and no raw identity values.
- Adversarial evidence: Raw identity canaries, network exceptions, script grammar failures, rollback, and foreign-tenant denial.
- Status: PASS

## C30-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Startup/readiness fails closed for wrong script bytes/hash/version or unavailable retained HMAC versions; Redis loss yields REVIEW-floor decisions rather than silent weakening.
- Failure sequence: A different script starts green, retired key makes active entries unusable, or outage maps to ALLOW/zero.
- Implementation: Script checksum/version verification, Redis readiness, retained HMAC-key inventory, and the conservative evaluation adapter reject invalid startup state and map runtime ambiguity to REVIEW.
- Proof: Readiness/key-readiness/script-contract tests cover missing keys, wrong bytes/SHA/version, SCRIPT FLUSH, and Redis loss; the live demo pauses Redis, observes REVIEW/readiness degradation, unpauses it, and proves current-binary recovery.
- Adversarial evidence: Partial rollout, SCRIPT FLUSH, read-only replica, malformed config, old-only database, and timeout ambiguity.
- Status: PASS

## C30-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Redis memory/latency are bounded by finite dimensions, member limits, TTL, command timeout, and explicit replacement triggers from ADR-0010.
- Failure sequence: Unbounded keys/members or script runtime exhausts Redis or stalls confirm while database locks are held.
- Implementation: Four allowlisted dimensions, fixed windows, finite TTL/member bounds, exact result grammar, and one-second command timeout bound the Redis surface and synchronous wait.
- Proof: Configuration/boundary tests reject unknown dimensions and invalid windows/limits; real Redis tests inspect TTL/member behavior and bounded timeouts; PostgreSQL lock observation proves the intended control-lock order.
- Adversarial evidence: High-cardinality generic namespaces, maximum members, huge windows, slow script, eviction policy, and Redis restart.
- Status: PASS

## C30-OPS-003

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V34 upgrades add fraud-list/control evidence without rewriting Chapters 22–29 payment, decision, context, review, authentication, label, or idempotent response bytes.
- Failure sequence: Migration mutates old decisions/features, fabricates active entries, weakens immutable triggers, or current binary cannot read retained history.
- Implementation: V35–V40 are forward-only and additive; list/control state starts empty and historical Chapter 22–29 records are not rewritten.
- Proof: Fresh V1→V40 and retained V34/V39→V40 migrations, Hibernate validation, 541/541 Java tests, 94/94 Python tests, real Redis/PostgreSQL fixtures, and the public available/unavailable/list demo all pass.
- Adversarial evidence: Every historical action/context cohort, pending/terminal authentication, review labels, malformed seeds, and checksum changes.
- Status: PASS
