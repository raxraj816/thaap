# Chapter 34 Invariant Proof Matrix

- Chapter: Chapter 34 — Card-Testing Incidents And Rate Limits
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

This matrix was frozen after Chapter 33 implementation proof and before any
Chapter 34 production code or migration. Redis is ephemeral enforcement state;
PostgreSQL decision/control evidence and incident links are durable truth.

## C34-INC-001

- Lens: PAYMENT_FRAUD
- Invariant: An incident opens or updates only from tenant-qualified persisted attempt, decline, velocity, risk-context, list, or policy evidence; Redis keys alone can never create durable incident truth.
- Failure sequence: A transient counter, missing decision, foreign attempt, or fabricated aggregate opens an incident.
- Implementation: Planned evidence detector over committed PostgreSQL lineage with composite foreign keys.
- Proof: Planned eligible/ineligible evidence table, direct-SQL substitutions, and Redis-only negative test.
- Adversarial evidence: Rolled-back attempt, deleted/evicted key, foreign merchant, missing decision, and malformed evidence.
- Status: PASS

## C34-INC-002

- Lens: DATA_ML_LINEAGE
- Invariant: Open incidents aggregate bounded attempt, decline, distinct-card, BIN, and IP-HMAC evidence, deduplicate links, and serialize concurrent detection to one active merchant incident per fixed detection window.
- Failure sequence: Duplicate detection double-counts evidence, races open two incidents, or raw identifiers enter aggregates.
- Implementation: Planned merchant lock, active-window uniqueness, immutable evidence links, and server-derived counts.
- Proof: Planned duplicate/concurrent detector calls with exact rows and aggregate postconditions.
- Adversarial evidence: Same attempt twice, different BIN/IP, window boundary, losing transaction, and reordered candidates.
- Status: PASS

## C34-INC-003

- Lens: API_PRODUCT
- Invariant: Only active RISK_MANAGER may resolve an OPEN incident with a closed mitigation code; resolution is idempotent, append-only, and never mutates merchant risk policy automatically.
- Failure sequence: Analyst, disabled, foreign, stale actor, or arbitrary note resolves; mitigation silently changes policy.
- Implementation: Planned operator transaction revalidation, closed enum, incident lock, audit/webhook, and no policy service dependency.
- Proof: Planned role/status/tenant/state/code matrix, terminal replay, direct SQL immutability, and policy before/after bytes.
- Adversarial evidence: Concurrent resolutions, changed-body replay, disabled-in-flight actor, and unknown code.
- Status: PASS

## C34-RL-001

- Lens: SECURITY_PRIVACY
- Invariant: Confirm and payment-method creation enforce application-owned fixed-window limits independently for merchant, credential-prefix, and client-IP HMAC dimensions; Redis keys/members contain only versioned digests.
- Failure sequence: One dimension bypasses enforcement, tenant traffic collides, raw API prefix/IP/PAN enters Redis, or a stronger dimension is ignored.
- Implementation: Planned versioned Lua contract, domain-separated HMAC identities, and route-level guards inside fenced idempotency.
- Proof: Planned below/at/above tables for both operations and all dimensions plus Redis key/value scans.
- Adversarial evidence: IPv4/IPv6, proxy-header spoof, credential rotation, merchant collision, and key-version change.
- Status: PASS

## C34-RL-002

- Lens: API_PRODUCT
- Invariant: A definite exceedance returns `429` with bounded `Retry-After` and closed error code; Redis unavailable, malformed, version-mismatched, timed-out, or ambiguous returns `503`, never fabricated acceptance or 429.
- Failure sequence: Dependency ambiguity is presented as abusive traffic, or a definite exceedance is presented as outage/success.
- Implementation: Planned typed result/exception boundary and explicit global error mapping.
- Proof: Planned real Redis exceedance and injected connection/script/malformed/version failures with exact status/header/body.
- Adversarial evidence: Nil/partial/wrong-type result, restart, eviction, timeout, and connection loss after dispatch.
- Status: PASS

## C34-RL-003

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The same fenced request identity consumes at most one slot; exact replay does not call Redis, changed-body replay is rejected before new consumption, and distinct concurrent identities serialize atomically.
- Failure sequence: Client retry double-consumes, replay outage changes a stored response, or concurrent requests exceed the limit without a 429.
- Implementation: Planned idempotency-before-guard ordering and atomic set-if-new Lua membership.
- Proof: Planned exact replay call-count/Redis cardinality, changed-body conflict, and concurrent threshold boundary.
- Adversarial evidence: Failed winner retry, lease reclaim, same key across operations/tenants, and clock rollover.
- Status: PASS

## C34-RL-004

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fixed-window rollover is deterministic, TTL is bounded, memory is capped, script version is authenticated by exact output contract, and restart/eviction produces explicit safe behavior.
- Failure sequence: Keys survive unbounded, memory grows without limit, clock boundary miscounts, or script ambiguity silently accepts.
- Implementation: Planned Redis TIME-based window key, TTL/cap checks, fixed return grammar, and no local fallback.
- Proof: Planned real Redis TTL/cardinality/rollover, flush/restart, malformed fixture, and property boundary tests.
- Adversarial evidence: At-boundary millisecond, max members, stale key, clock skew, eviction, and script cache loss.
- Status: PASS

## C34-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Incident reads/resolution/evidence and all rate-limit dimensions are tenant-scoped; foreign and missing resources are response-equivalent.
- Failure sequence: Tenant substitution observes incident evidence, resolves another merchant's incident, or shares counters.
- Implementation: Planned merchant-qualified repositories, composite keys, context-derived identities, and missing-equivalent authorization.
- Proof: Planned two-tenant API, Redis digest separation, and direct-SQL substitution matrix.
- Adversarial evidence: Same IP/card/idempotency key across tenants, foreign operator, and mixed evidence IDs.
- Status: PASS

## C34-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Incident and rate-limit state, audit, webhook, metrics, logs, and responses contain only closed codes, bounded counts, safe UUIDs, and HMACs; no PAN, raw IP, credential, idempotency key, email, or free text is persisted or tagged.
- Failure sequence: Abuse defense becomes a credential/PII leak or high-cardinality telemetry sink.
- Implementation: Planned strict DTOs, trusted servlet remote address, domain-separated HMAC, closed metrics, and canary scans.
- Proof: Planned hostile payload/header/key and sink scans, Redis scans, and exact tag sets.
- Adversarial evidence: X-Forwarded-For spoof, Unicode/control input, long key, exception path, and duplicate JSON.
- Status: PASS

## C34-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Incident open/update/resolve, evidence links, projection, audit, webhook, and idempotent response commit atomically or leave no partial durable truth.
- Failure sequence: Late audit/webhook/idempotency failure leaves incident or resolution without notification/provenance.
- Implementation: Planned single PostgreSQL transaction with explicit flush and append-only triggers.
- Proof: Planned injected persistence/audit/webhook failures and retry with exact zero/one postconditions.
- Adversarial evidence: Unique conflict, rollback winner, serializer failure, and terminal retry.
- Status: PASS

## C34-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish allowed, exceeded, dependency-unavailable, incident opened/updated/resolved, route, dimension, and mitigation using fixed low-cardinality tags emitted with truthful transaction outcome.
- Failure sequence: Rollback appears committed, outage appears attack, or identifiers enter metrics/logs.
- Implementation: Planned after-completion incident metrics and immediate typed rate-limit metrics.
- Proof: Planned exact meter/tag/cardinality tests plus rollback and canary log capture.
- Adversarial evidence: Concurrent loser, foreign denial, Redis ambiguity, and hostile identity.
- Status: PASS

## C34-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V46 upgrades add empty incident truth, preserve all earlier Phase 4 bytes/consent, and start with current Redis script/config validation without fabricating incidents.
- Failure sequence: Migration rewrites historical controls, creates incidents from old keys, changes subscriptions, or current binary fails retained startup.
- Implementation: Planned forward-only V47/V48 and startup-bound validated configuration.
- Proof: Planned fresh/retained migration, Hibernate validation, empty tables, subscription comparison, full suites, and retained startup.
- Adversarial evidence: Historical decisions/velocity/policies/signals/refunds, absent Redis, and malformed config.
- Status: PASS

## C34-PROD-001

- Lens: API_PRODUCT
- Invariant: Public language calls the object a suspected card-testing incident derived from THAAP evidence, distinguishes rate limiting from fraud decisions, and never claims network-confirmed fraud or automatic policy mitigation.
- Failure sequence: A 429 becomes a fraud label, an incident is called confirmed attack, or resolution implies policy mutation.
- Implementation: Planned explicit source/trigger/mitigation fields and separate error/decision contracts.
- Proof: Planned vocabulary contracts and abusive-traffic live demo with unchanged policy/decision history.
- Adversarial evidence: Threshold traffic, Redis outage, resolved incident, and legitimate retry.
- Status: PASS

## Verification evidence supplement

### Durable incident and authorization boundaries

- `CardTestingIncidentAndRateLimitContractTests` runs with PostgreSQL and Redis.
  Persisted attempt/decision/authorization/context evidence opens one suspected
  incident at the configured threshold, links each attempt once, exposes only
  bounded counts/HMAC-derived context, denies ANALYST resolution, permits
  RISK_MANAGER closed-code resolution, preserves byte replay, rejects history
  deletion, and proves zero automatic risk-policy mutation.
- Concurrent detector calls execute behind a PostgreSQL advisory transaction
  lock and converge to one incident and one evidence link per durable attempt.
  Redis state alone has no incident creation route or durable foreign key.

### Exact Redis, privacy, retry, and ambiguity boundaries

- `RateLimitScriptContractTests` authenticates exact repository bytes with
  SHA-1 `afe84373b967e1cc728b0671b680fa5ecf36bc15`, version `v1`, and rejects
  malformed shape, numeric representation, version, and semantic partitions.
- `RedisRateLimitScriptIntegrationTests` proves below/at/above, same-member
  retry non-consumption, bounded hash cardinality/TTL, atomic concurrent
  threshold winners, SCRIPT FLUSH reload, and raw-key/member rejection against
  Redis 7.4. `RequestRateLimitServiceTests` proves startup window validation and
  simultaneous current/previous HMAC-key enforcement during rotation.
- API proof distinguishes definite `429 rate_limit_exceeded` with bounded
  `Retry-After` from dependency-ambiguous `503 rate_limit_unavailable`, leaves
  no payment-method side effect on 503, and proves exact idempotent replay
  bypasses new consumption while changed-body replay remains conflict truth.

### Migration, demo, operations, and review disposition

- V47/V48 fresh and retained upgrades pass with Hibernate validation, empty new
  tables, append-only/lifecycle triggers, preserved historical review rows and
  webhook consent, and no incident fabricated from Redis or retained payments.
- The public live demo passed on retained V48: five durable attempts (three
  processor declines and two velocity reviews) produced one suspected incident,
  manager resolution preserved zero policy mutations, the 21st credential-bound
  method request returned 429, Redis contained only HMAC-shaped keys, and
  readiness stayed UP.
- Plan-to-implementation traceability, fresh hostile review, and boundary-family
  review: PASS. ADR-0010 remains Accepted and its identity, time, atomicity,
  retry, ambiguity, TTL/limit, privacy, and operations table matches the exact
  Lua implementation. No blocking debt or escaped finding exists.
