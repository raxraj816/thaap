# Chapter 29 Closure Dossier

- Chapter: Chapter 29 — Safe RiskContext And Real-Time Feature Extraction
- Chapter number: 29
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_29_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created before Chapter 29 production code and reconciled only
after every frozen RiskContext, privacy, lineage, compatibility, and real-time
feature invariant passed at its real boundary.

## Observable promises

- Confirm accepts an optional constrained RiskContext without changing existing
  rules_v1 or payment outcomes in Chapter 29.
- Raw IP, user-agent, device/session, buyer, card, and credential values exist
  only in request memory; persistence retains purpose-HMACed or closed safe data.
- Every derived context feature is point-in-time, typed, provenance-bearing,
  tenant-bound, and reproducible from immutable lineage.
- Existing clients, historical decisions, and exact idempotent replays remain
  backward compatible.

## Failure inventory

The matrix freezes grammar, privacy, purpose separation, key rotation,
provenance, timestamp, tenant substitution, typed feature, snapshot parity,
rollback, concurrency, migration, telemetry, and product-language failures.

## Boundary family evidence

Absent/full/partial context, IPv4/IPv6 and invalid input partitions, four HMAC
purposes, current/previous/missing keys, typed present/missing features, all
four fraud actions, rollback/retry, same/distinct idempotency keys and context
bodies, raw-canary surfaces, and fresh/retained cohorts pass. The later hostile
pass reopened numeric coercion and deferred-trigger event semantics and proved
their valid/invalid partitions after the fixes.

## Enforcement boundaries

Strict Jackson/Bean Validation and literal IP parsing enforce input grammar.
Purpose-separated versioned HMACs enforce privacy identity. V32–V34 constraints,
composite foreign keys, immutable history triggers, and deferred final-row
lineage checks enforce durable truth. Existing PaymentIntent/idempotency locks
and one confirm transaction enforce atomicity; the typed feature lineage remains
the explanation boundary.

## Exact proof

- 432 backend tests pass with zero failures, errors, or skips.
- 89 Python safety tests pass.
- `RiskContextContractTests` covers exact HMAC vectors, privacy,
  input grammar, full/partial/absent extraction, replay, and all-action parity.
- `RiskContextPostgreSqlTests` covers forced observed waits,
  rollback/retry, same-key body conflict, one-winner lineage, and history guards.
- migration, catalogue, feature, key-readiness, metric, strict-JSON, tenant, and
  earlier payment/fraud suites cover their real enforcement boundaries.

## Migration evidence

Fresh V1→V34 and retained V31→V34 automated cohorts pass. The retained cohort
preserves Chapters 22–28 review/decision rows byte-for-byte, creates zero
synthetic context, installs 17 immutable definitions and all three context
triggers. The retained local database also upgraded V31→V34 and passed Hibernate
validation before the live demo.

## Demo evidence

`scripts/demo-risk-context.py` passed against retained local
PostgreSQL and Redis. Paired absent/full cohorts produced ALLOW/AUTHORIZED,
BLOCK/BLOCKED, REQUEST_3DS/REQUIRES_ACTION, and REVIEW/IN_REVIEW identically.
Every confirm replay was byte-exact; context/feature/decision lineage was
complete; raw device canaries appeared in zero context, snapshot, audit,
webhook, or idempotency rows.

## Operational signals

Closed presence/transaction counters distinguish committed and rolled-back
present/absent context. Closed rejection and readiness causes distinguish
invalid IP, time/control input, and missing keys. Exact meter tests prohibit
resource/identity dimensions; bounded logs and canary scans contain no raw
context values.

## Known debt

None accepted. Any real deferral requires the next immutable `TD-NNNN` with a
named owner slice, DRI, trigger, and done evidence.

## Custom infrastructure decision

`NOT_APPLICABLE`. The implementation uses Jackson/Bean Validation, Spring/JPA
transactions, PostgreSQL, the existing versioned HMAC component, and existing
feature lineage.

## Escaped-review assessment

`NONE`. The later hostile pass occurred before the first completion claim and
fixed both findings in-slice, so no escaped-review entry is required.

## Source-of-truth reconciliation

The matrix is `VERIFIED`, every row is `PASS`, and this dossier recommends
`MERGE`. Roadmap and READMEs advance to Chapter 30 in the same closure change.
Phase 4 remains open through Chapter 35.

## Merge recommendation

`MERGE`. Every frozen row passes at its enforcement boundary; full suites,
fresh/retained migrations, live all-action proof, traceability, later hostile
review, and boundary review pass with no blocking debt or escaped finding.
