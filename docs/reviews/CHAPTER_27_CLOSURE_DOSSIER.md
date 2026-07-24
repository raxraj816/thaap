# Chapter 27 Closure Dossier

- Chapter: Chapter 27 — Pre-Authorization Review Approval
- Chapter number: 27
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_27_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

APPROVE preserves the immutable REVIEW decision and resumes the same reviewed
PaymentAttempt. The override is flushed before processor work. The simulator
operation identity derives from that attempt, so transaction rollback and retry
address the same external operation. Processor approval, decline, and pending
3DS reuse confirm semantics; automatic capture writes the balanced ledger and
manual capture remains `REQUIRES_CAPTURE`. Confirmed legitimate creates one
`LEGITIMATE` label while inconclusive approval creates none.

Chapter 27 intentionally stops at truthful `REQUIRES_ACTION`. Challenge object
creation and completion remain Chapter 28 work and are not claimed here.

## Failure inventory

The frozen matrix covered actor state, assignment, tenant substitution, all
processor fixtures, both capture methods, both classification partitions,
same/distinct keys, cancellation and competing approvals, local rollback,
migration preservation, privacy, and public reconciliation. The boundary-family
pass enumerated these partitions rather than closing only observed examples.

## Boundary family evidence

Every actor/assignment state, deterministic processor outcome, capture method,
classification partition, replay identity, forced arrival order, rollback
position, tenant edge, and migration cohort named in the frozen matrix has an
exact contract, PostgreSQL test, retained migration test, or public demo mapping.

The hostile pass revisited the most failure-prone sequences after implementation:

- automatic-capture approval versus merchant cancellation in both lock orders;
- two differently classified approvals waiting on the same PaymentIntent lock;
- operator disable committed while approval waits on the actor lock;
- failure while flushing override evidence before processor invocation;
- failure after authorization, capture ledger, audit, webhook, and idempotency
  completion writes;
- processor decline, which truthfully reopens payment-method collection, versus
  automatic approval, which ends in captured funds;
- same-attempt retry identity after transaction rollback.

No P0/P1, frozen-promise P2, undocumented deferral, or escaped finding remains.

## Enforcement boundaries

- `ReviewOperationsService` locks active actor, PaymentIntent, PaymentAttempt,
  and review lineage; validates assignment; then flushes the immutable override
  before invoking the processor.
- `SimulatorProcessorAdapter` derives `auth_sim_<attempt UUID>` as the stable
  simulator operation identity.
- Authorization uniqueness, label source constraints, the PostgreSQL lineage
  trigger, ledger constraints, audit, webhook, and idempotency completion all
  remain inside the operation transaction.
- V29 permits only the closed APPROVE/classification/reason combinations and
  distinguishes `FRAUD` from `LEGITIMATE` label shape without updating rows.

## Exact proof

- `ReviewApprovalContractTests`: 11 cases. Ten processor/capture
  combinations plus the explicit legitimate-label partition; all pass.
- `ReviewResolutionPostgreSqlConcurrencyTests`: forced approval/cancel ordering,
  competing approval, disable-before-approval, and six rollback positions; all
  pass on PostgreSQL 16 with observed lock waits and exact postconditions.
- `SimulatorProcessorCatalogTests`: deterministic same-attempt and
  distinct-attempt processor identities pass.
- Existing Chapter 26 actor/tenant/privacy/API regression proof remains green
  because approval is additive on the same locked endpoint.
- The complete backend and Python suites are the final regression gate; their
  counts are recorded in the learning trail after the closure run.

## Migration evidence

Fresh V1→V29 migration and Hibernate validation pass on H2 and PostgreSQL.
The retained migration cohort starts at V25, records pre-Chapter-26 OPEN and
CANCELED review rows plus explicit webhook consent, verifies equality at V28,
then migrates to V29 and verifies the same retained values again. PostgreSQL's
installed constraint definition contains only the new closed approval and
legitimate-label vocabulary. No row-update migration exists.

## Demo evidence

`scripts/demo-review-approval.py` passed against the current binary
and retained local PostgreSQL upgraded from V28 to V29:

```text
payment=CAPTURED attempt=AUTHORIZED review=RESOLVED decision=REVIEW
resolution=1 labels=0 authorizations=3 ledger_entries=2
processor_reference=auth_sim_<same reviewed attempt UUID>
same_key_replay=exact distinct_key_terminal_replay=exact
```

The contract matrix separately covers manual capture, all six decline fixtures,
processor 3DS, default approval, and confirmed-legitimate labeling.

## Operational signals

Chapter 27 retains the bounded `thaap.risk.operations` transaction outcome
signal and Chapter 26 bounded error logging. Responses, audit, and webhooks add
only closed enums and identifiers. Processor/card data is neither accepted from
the analyst nor copied into the approval audit state. Constraint-failure logs
were checked for PostgreSQL failing-row disclosure.

## Known debt

None. Chapter 28 ownership of challenge completion is a frozen scope boundary,
not a hidden Chapter 27 defect. No new `TD-NNNN` is required.

## Escaped-review assessment

`COMPLETE`. Chapter 27 had no earlier completion claim, so every finding found
during implementation and the later hostile pass was fixed in-slice. No new
`ER-NNNN` entry is required.

## Custom infrastructure decision

`NOT_APPLICABLE`. The slice reuses Spring/JPA transactions, PostgreSQL,
idempotency, the simulator adapter, ledger, audit, and webhook infrastructure.

## Source-of-truth reconciliation

The matrix is `FROZEN`/`VERIFIED` with every row `PASS`. The Phase 4 detailed
plan, master plan, README, learning notes, and this dossier identify Chapter 27
as complete and Chapter 28 as the active implementation slice. Phase 4 remains
open through Chapter 35.

## Merge recommendation

`MERGE`. Chapter 27's frozen promises have implementation and real-boundary
proof, its public demo passes, and it leaves no blocking debt or finding.
