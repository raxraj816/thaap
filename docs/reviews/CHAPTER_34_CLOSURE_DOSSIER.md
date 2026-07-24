# Chapter 34 Closure Dossier

- Chapter: Chapter 34 — Card-Testing Incidents And Rate Limits
- Chapter number: 34
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_34_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created before Chapter 34 production code or migrations. It
now recommends `MERGE`: durable suspected-incident truth, exact Redis
enforcement, ambiguity semantics, privacy, migration, and live abuse workflow
all pass at their real PostgreSQL/Redis/API boundaries.

## Observable promises

- Suspected card-testing incidents derive only from persisted same-tenant
  attempt/decision/control evidence and never from Redis keys alone.
- Only an active RISK_MANAGER resolves with a closed mitigation code; resolution
  is append-only and never mutates merchant policy automatically.
- Confirm and payment-method creation enforce application-owned merchant,
  credential, and client-IP limits using only domain-separated HMAC material.
- Definite exceedance is 429; dependency ambiguity is 503; neither is a fraud
  score, label, or network-confirmed attack claim.

## Failure inventory

The matrix covers durable evidence eligibility, aggregation/dedup/concurrency,
resolution authorization, all limit dimensions, retry non-consumption, HMAC
rotation, script grammar/reload/ambiguity, memory/TTL, transaction rollback,
migration, telemetry, privacy, and product vocabulary.

## Boundary family evidence

Four PostgreSQL+Redis API tests cover incident opening/resolution, concurrent
detectors, no policy mutation, append-only evidence, confirm and method limits,
replay/conflict behavior, exact 429, and injected 503. Eight script/service
tests cover exact bytes and grammar, real-Redis below/at/above, TTL/cardinality,
concurrency, script flush, raw identity rejection, HMAC rotation, and startup
configuration validation.

## Enforcement boundaries

Composite PostgreSQL foreign keys and V48 triggers own durable incident truth.
Merchant advisory transaction locks and incident row locks own detector and
resolution serialization. Fenced idempotency precedes route guards. The exact
Redis Lua operation owns atomic fixed-window membership; typed exceptions own
429/503 truth; local audit/webhook and after-completion metrics own side effects.

## Exact proof

Targeted Chapter 34 proof passes 12/12 across API, service, exact-protocol, and
real-Redis tests. The Lua SHA-1 is
`afe84373b967e1cc728b0671b680fa5ecf36bc15`; malformed outputs fail closed.
Full phase-wide regression counts are recorded by Chapter 35.

## Migration evidence

Fresh V1-to-V48 and retained V46-to-V48 tests pass with Hibernate validation.
Incident, evidence, and resolution tables begin empty; historical review and
decision rows and webhook consent are unchanged; V47/V48 install the expected
lineage, lifecycle, append-only, and deferred-resolution protections. The
current binary started on the retained local V48 volume.

## Demo evidence

`scripts/demo-card-testing-incidents-rate-limits.py` passed against retained V48.
Five durable attempts (three declines, two velocity reviews) opened one
suspected incident; manager resolution created one immutable mitigation and
zero policy versions. The 21st credential-bound method request returned 429
with `Retry-After`; Redis exposed 15 HMAC-shaped keys and no raw identity.

## Operational signals

Rate-limit metrics distinguish operation, dimension, allowed/retry/exceeded,
and dependency-unavailable outcomes. Incident metrics distinguish detect/open/
update/resolve after transaction completion. Responses and bounded logs separate
definite abuse-control exceedance from dependency ambiguity without identities.

## Known debt

None accepted. No Chapter 34 deferral exists outside the canonical registry.

## Custom infrastructure decision

`PASS` under accepted ADR-0010. The exact Lua boundary has purpose-HMACed
identity/member inputs, Redis server time, one atomic operation, stable retry,
bounded window/limit/TTL/cardinality, closed eight-field output, explicit 503
ambiguity, no local fallback, exact hash/version tests, and a replacement
trigger if the three narrow Redis scripts expand.

## Escaped-review assessment

`COMPLETE`. No prior Chapter 34 completion claim existed. Hostile review found
and fixed PostgreSQL `Instant` binding, public decline-state expectation,
current/previous HMAC enforcement, startup window validation, and missing
concurrent-detector proof before closure. No `ER-NNNN` entry is required.

## Source-of-truth reconciliation

Chapter 34 is complete. Chapter 35 now owns integrated Phase 4 regression,
all-demo execution, and final repository/learning/status reconciliation.

## Merge recommendation

`MERGE`. Every matrix row is `PASS`; PostgreSQL, Redis, migration, ambiguity,
privacy, concurrency, and live demo proof pass with no blocking debt/finding.
