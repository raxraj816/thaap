# ADR-0010: Use Redis As Ephemeral Risk Coordination

Date: 2026-07-13
Status: Accepted

## Context

Phase 4 needs low-latency velocity counters and API rate limits. PostgreSQL is
the durable source for payment, decision, label, dispute, and incident truth,
but repeated high-cardinality window queries are not the intended real-time
coordination boundary. Redis is already part of the local substrate.

Naively reading a counter and incrementing it after commit creates a crash gap.
Incrementing before the database transaction creates a possible phantom. A
Redis timeout may also mean “not applied,” “applied but response lost,” or
“unknown.” None may silently become zero velocity or a fabricated rate-limit
decision.

## Decision

Use Redis only for bounded ephemeral risk coordination:

- versioned sorted-set operations for velocity
- versioned fixed-window operations for rate limiting
- purpose-HMACed keys and members; no raw sensitive values
- PostgreSQL remains authoritative for durable product truth

Velocity uses a stable logical-attempt member derived from merchant,
PaymentIntent, and next attempt number. One exact-byte versioned Lua operation
prunes the window, excludes that member, counts other members, inserts it with
`ZADD NX`, creates insertion-only random ownership, sets bounded expiry, and
returns structured status. A second exact-byte operation compensates rollback
only when its owner token still matches; retry rollback cannot delete an
existing attempt and stale cleanup cannot delete a newer insertion. A leaked
member expires and can only conservatively raise another payment's risk. The
same logical retry recognizes and excludes its own member.

During HMAC rotation, velocity evaluates and writes both current and retained
version identities for the bounded overlap window and takes the maximum
compatible count. IP identities use the matching retained inner RiskContext
alias before their versioned outer velocity key is derived. This preserves old
window evidence without summing the same post-rotation attempt twice.

Velocity timeout/unavailability records unavailable feature lineage and applies
a REVIEW floor. It never becomes zero or ALLOW. Rate-limit ambiguity returns
`503`; only definite exceedance returns `429`.

Scripts are repository-owned custom infrastructure and therefore require:

- exact script bytes and immutable version/hash
- startup validation against configured version
- exhaustive return grammar and numeric-boundary tests
- real Redis fixtures for first/retry/expiry/timeout/eviction behavior
- bounded command timeout, key TTL, cardinality, and memory policy
- metrics with closed dimensions and no keys/members
- a replacement trigger if operational complexity grows beyond the three
  narrow scripts: velocity observe, velocity compensation, and rate limiting

## Boundary Table

| Boundary | Decision |
|---|---|
| Identity | purpose-HMACed tenant/dimension/logical-attempt identity |
| Authoritative truth | PostgreSQL payment/decision/label/dispute rows |
| Time | Redis server time returned and persisted with observation |
| Atomicity | one versioned Lua operation per observation/rate-limit decision; one token-guarded compensation operation |
| Retry | stable member/request identity; insertion-only owner token; idempotent same-operation replay |
| Ambiguity | REVIEW floor for risk; `503` for rate limiting |
| Rollback | atomic token-guarded member removal; stale/not-found are safe; bounded conservative expiry |
| Limits | fixed windows, TTLs, maximum dimensions, bounded result size |
| Privacy | no raw or reversible card, IP, email, device, credential, or PII data |
| Operations | readiness, script hash/version, latency/error/eviction metrics |

## Consequences

Decision latency remains bounded without pretending Redis participates in the
PostgreSQL transaction. The conservative failure policy may add review friction
or temporary availability errors, but it cannot silently weaken fraud controls
or create payment truth. Exact-byte scripts become security/product code and
must pass the same hostile boundary review as migrations and protocols.

Founder confirmation of the Phase 4 promises accepted this ADR on 2026-07-13.
After Chapter 29 closure, the Chapter 30 pre-implementation matrix froze the
exact script boundary before production code; that readiness dependency is now
satisfied, but Chapter 30 remains in progress until its real-boundary proof and
closure dossier pass.
