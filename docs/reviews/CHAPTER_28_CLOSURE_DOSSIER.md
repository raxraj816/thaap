# Chapter 28 Closure Dossier

- Chapter: Chapter 28 — Payment Authentication Completion
- Chapter number: 28
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_28_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created before Chapter 28 production code and reconciled only
after every frozen authentication invariant passed at its real boundary.

## Observable promises

- Both fraud and processor step-up create source-bound pending challenges and no
  new approved funds.
- One-time simulator completion succeeds, fails, expires, or cancels exactly
  once and never creates a second challenge loop.
- Success continues the same attempt without fraud re-evaluation and honors
  automatic/manual capture.
- Challenge secrets are reveal-once, tenant-scoped, rotated, and absent from
  normal reads and durable plaintext.

## Failure inventory

The matrix freezes source, actor/credential, payment, processor, capture,
tenant, privacy, replay, rollback, concurrency, expiry precision, migration,
readiness, observability, and premature-closure sequences.

## Boundary family evidence

Both sources; pending/success/failure/expiry/cancel; automatic/manual capture;
approved/declined/repeated-challenge processor outcomes; pending/terminal secret
checks; current/previous/missing key versions; completion/cancel and duplicate
arrival orders; rollback positions; and tenant/lineage substitutions pass. The
later hostile pass reopened the terminal-history, terminal-secret, authenticated
decline, and repeated-challenge families and re-proved their meaningful
partitions before closure.

## Enforcement boundaries

PaymentIntent-first PostgreSQL row locks serialize terminal actions. V30
constraints and V31 semantic/history triggers enforce source and terminal
lineage. Exact `now < expiresAt`, purpose-HMAC plus SHA-256 identity, AES-GCM
replay with merchant/record AAD, deterministic processor operation identity,
and one Spring transaction enforce the remaining promises.

## Exact proof

- 404 backend tests passed with zero failures, errors, or skips.
- 86 Python safety tests passed.
- `AuthenticationChallengeContractTests` covers public state, secret, source,
  capture, decline, and second-loop contracts.
- `AuthenticationChallengePostgreSqlTests` covers observed waits, arrival
  orders, rollback families, and history enforcement on PostgreSQL 16.
- cipher, key-readiness, metric, migration, API error, idempotency, and
  simulator catalog tests cover their real enforcement boundaries.

## Migration evidence

Fresh V1→V31 and retained V29→V31 automated cohorts pass with Chapter 26–27
review, resolution, label, authorization, capture, and fraud history preserved.
The retained local PostgreSQL database also upgraded from V30 to V31 before the
live demo, proving the forward history-protection migration on real Chapter 28
rows.

## Demo evidence

`scripts/demo-authentication-challenge.py` passed against the retained local
PostgreSQL and Redis stack. Fraud source produced one authenticated automatic
capture and two ledger entries; processor source completed the existing
authorization to manual `REQUIRES_CAPTURE` with zero ledger entries. Both
cohorts retained one attempt, one decision, exact same-key creation/completion
replay, and authenticated distinct-key terminal replay.

## Operational signals

Closed source/result/reason/transaction counters distinguish creation,
terminal outcomes, denials, commit, and rollback without resource identifiers.
Startup fails when a pending challenge HMAC version or active protected replay
key is absent. Bounded error logs carry request ID, path, and exception type,
never database detail or the raw completion secret.

## Known debt

None accepted. Any real deferral requires the next immutable `TD-NNNN` with a
named owner slice, DRI, trigger, and done evidence.

## Custom infrastructure decision

`NOT_APPLICABLE`. Use Spring/JPA transactions, PostgreSQL, standard crypto,
existing idempotency, simulator, ledger, audit, and webhook infrastructure.

## Escaped-review assessment

`NONE`. The later hostile pass happened before the first completion claim and
fixed all findings in-slice, so no escaped-review entry is warranted.

## Source-of-truth reconciliation

The matrix is `VERIFIED`, every row is `PASS`, and this dossier recommends
`MERGE`. Roadmap and README status advance to Chapter 29 in the same closure
change. Phase 4 remains open through Chapter 35.

## Merge recommendation

`MERGE`. Every row is `PASS`; both live demos, retained migration, complete
suites, traceability review, temporally later hostile review, and boundary
review pass with no blocking debt or escaped finding.
