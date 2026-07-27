# Chapter 40 Invariant Proof Matrix

- Chapter: Chapter 40 — Dispute Deadline And Outcome Operations
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

Post-closure recovery completed on 2026-07-27 for `ER-0067`. Dispute evidence
revision, submission, and their completed-replay authorization acquire and
revalidate the live operator and credential locks before the workflow lock.
VIEWER, ANALYST, and RISK_MANAGER retain tenant read access; only a live
RISK_MANAGER may revise evidence or submit it. The PostgreSQL contract passed
19/19, including disable-before-submit and completed replay after disable.

This matrix was drafted and frozen on 2026-07-25 after Chapter 39 closure and
before Chapter 40 schema, production code, tests, configuration, scheduler, or
UI changes. It derives from Chapter 32 chargeback/evidence truth and the Phase
4.5 deadline, immutable evidence-version, submission, expiry, simulator-outcome,
queue, and cockpit promises.

## C40-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Dispute operations never create, reverse, or reinterpret capture, refund, chargeback allocation, ledger, FraudDecision, or FraudLabel truth; an outcome is simulator operational evidence only.
- Failure sequence: Submission books money, WIN removes a chargeback/label, LOSS duplicates ledger exposure, or packet expiry changes the original decision.
- Implementation: V59 uses separate workflow/evidence/outcome/event tables; the service only reads authorization/refund evidence and never invokes ledger, allocation, decision, or label writers.
- Proof: `disputeWorkflowUsesGovernedDeadlineImmutableVersionsDualControlAndTrustedOutcome` and the live demo reconcile unchanged ledger and FraudLabel counts through `WON`.
- Adversarial evidence: READY, SUBMITTED, EXPIRED, WON, and LOST projections versus capture, allocation, refund, ledger, decision, and label counts.
- Status: PASS

## C40-TIME-001

- Lens: DATA_ML_LINEAGE
- Invariant: A versioned reason definition deterministically derives one UTC evidence deadline; packet readiness and submission use `now <= dueAt`, expiry uses `now > dueAt`, and scheduler/API races converge to one terminal state.
- Failure sequence: At-deadline submission is rejected, after-deadline submission wins, two clocks disagree, or a scheduler expires a committed submission.
- Implementation: Immutable eight-row catalogue, injected `Clock`, locked workflow projection, `isPastDeadline`, and strict `due_at < now` expiry predicate.
- Proof: `DisputeDeadlineSemanticsTests` proves before/at/after; the PostgreSQL lifecycle and forced concurrent-submission tests prove locked deadline revalidation and one submitted result.
- Adversarial evidence: One nanosecond before, exactly at, and one nanosecond after due; concurrent repeated submission behind a held workflow lock.
- Status: PASS

## C40-EVID-001

- Lens: SECURITY_PRIVACY
- Invariant: Evidence edits append a new immutable bounded version; latest-version projection is explicit; only closed booleans/authorization verification results and safe merchant text-free fields exist; no PAN, credential, customer PII, notes, or arbitrary document content is accepted.
- Failure sequence: Old packet mutates, submission reads a stale version, arbitrary text uploads PII, or history DELETE rewrites what was submitted.
- Implementation: Closed boolean/verification columns, canonical JSON SHA-256, version uniqueness, exact submitted reference, and V60 UPDATE/DELETE rejection.
- Proof: PostgreSQL contract appends versions 1/2, rejects stale version 1, submits version 2, and proves evidence/event mutation rejection; strict Jackson and safe-log suites remain green.
- Adversarial evidence: Old/latest/stale version, UPDATE, DELETE, unknown JSON, arbitrary text, PAN/credential canaries, and digest drift.
- Status: PASS

## C40-READY-001

- Lens: API_PRODUCT
- Invariant: Readiness is derived from the latest immutable evidence version and reason-specific required fields; missing requirements are returned as closed codes and submission cannot bypass them.
- Failure sequence: A draft with missing delivery/customer evidence submits, an old complete version masks a newer incomplete one, or UI readiness disagrees with the transaction.
- Implementation: V59 stores closed reason requirements; every revision derives missing codes and the locked submit path requires latest `READY` with no missing codes.
- Proof: PostgreSQL contract proves incomplete→ready latest-version behavior and stale submit rejection; UI test renders readiness and the exact submitted version.
- Adversarial evidence: Missing authentication/device, missing delivery, missing communication, missing refund history, newer incomplete version, and stale expected version.
- Status: PASS

## C40-OUT-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Simulator outcomes are deduplicated by tenant/source event key plus exact request digest, accept only `WON`/`LOST`, bind one submitted packet version, and ignore/reject duplicate-conflicting or out-of-order evidence without rewriting terminal history.
- Failure sequence: Same key changes outcome, outcome arrives before submission, WON then LOST both commit, or foreign packet ID is linked.
- Implementation: Trusted-scenario origin gate, exact request digest, immutable outcome/event, unique event/workflow keys, submitted-version FK, and terminal projection checks.
- Proof: PostgreSQL contract rejects untrusted ingestion and proves exact replay to one `WON`; schema/check/unique/tenant constraints and live reconciliation prove terminal lineage.
- Adversarial evidence: Merchant-origin forgery, pre-submit timestamp, future timestamp, same-key/different-body, repeated terminal event, and foreign workflow/evidence IDs.
- Status: PASS

## C40-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Operator mutations and completed replays lock idempotency, live actor and credential, chargeback workflow, latest evidence version, then outcome/event state; submit, expire, revise, disable, and outcome races yield one legal projection and exact event history.
- Failure sequence: A credential is disabled after authentication but revision commits behind it, completed replay bypasses current authorization, revision commits behind submission, two submissions create two events, scheduler and submit both terminalize, or outcome reads a partially submitted packet.
- Implementation: Evidence revision and submission acquire merchant-qualified operator and credential `FOR UPDATE` locks, verify active status/current role, require RISK_MANAGER, and only then lock the base workflow before projection hydration. Completed idempotency replays execute the same actor-first authorization callback. Expected evidence version, legal-state checks, unique rows, and bounded expiry transactions serialize later transitions.
- Proof: `forcedOperatorDisableBeforeEvidenceRevisionRejectsInFlightAndFutureMutations` owns the actor lock in the disable transaction, observes the revision blocked on PostgreSQL, then proves 404/no evidence/event/audit after release and 401 for the disabled credential. `forcedConcurrentSubmissionsSerializeToOneSubmittedVersionAndEvent` proves the workflow ordering and one submission/audit/event. Disable-before-submit and completed revise/submit replay-after-disable pass in the 19/19 PostgreSQL 16.14 contract.
- Adversarial evidence: Disable-first in-flight request, future disabled credential, completed replay after disable/role drift, two submit keys, held PostgreSQL actor/workflow locks, stale evidence version, revision after submit, outcome before submit, and due versus expiry predicate partitions.
- Status: PASS

## C40-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Workflow, definitions, evidence versions, events, submission, outcome, queue rows, and cockpit reads are merchant-qualified from authenticated context; copied foreign IDs are indistinguishable from absence.
- Failure sequence: Merchant submits another tenant packet, simulator outcome crosses tenant, or queue joins by bare chargeback ID.
- Implementation: Every operator/simulator query derives merchant from authenticated context; V59 uses merchant-qualified unique keys and composite FKs across workflow, evidence, outcome, event, actor, and chargeback.
- Proof: Existing risk-operator and merchant-context tenant substitution suites plus V59 PostgreSQL FK validation remain green; no body-supplied tenant field exists.
- Adversarial evidence: Foreign chargeback/workflow/evidence/outcome/operator IDs, copied event key, same UUID shape, and absent authenticated merchant.
- Status: PASS

## C40-API-001

- Lens: API_PRODUCT
- Invariant: VIEWER, ANALYST, and RISK_MANAGER can read tenant workflow queues/details; only RISK_MANAGER can append evidence versions or submit an exact version; trusted simulator outcome ingestion is authenticated separately; every mutation is idempotent and returns stable 400/401/403/404/409 states.
- Failure sequence: VIEWER cannot read an approved tenant workflow, VIEWER or ANALYST revises/submits, merchant forges simulator outcome, disable or role drift after authentication still permits mutation/replay, or unknown JSON widens evidence.
- Implementation: Typed `/risk/disputes` list/get/revise/submit and `/simulator/disputes/.../outcomes` endpoints reuse strict JSON, idempotency, tenant authentication, and stable status errors. Read authorization accepts the three frozen risk roles; mutation and replay authorization locks/revalidates the live actor plus credential first and then requires RISK_MANAGER.
- Proof: `disputeWorkflowUsesGovernedDeadlineImmutableVersionsDualControlAndTrustedOutcome` proves VIEWER list/detail, VIEWER/ANALYST evidence and submission denial, manager revision/submission, stale 409, untrusted outcome 403, exact replay, and terminal readback. The deterministic disable/revision test proves authorization stays live through commit.
- Adversarial evidence: VIEWER/ANALYST/RISK_MANAGER read and mutation matrix, missing/wrong/disabled credentials, absent idempotency key, unknown fields/enums, stale state, completed replay after disable, and replay with changed content.
- Status: PASS

## C40-OBS-001

- Lens: OBSERVABILITY
- Invariant: Closed lifecycle/result/reason/deadline tags distinguish draft/revised/ready/submitted/expired/won/lost/retry, while tenant, actor, chargeback, packet, event key/digest, credentials, and evidence values never become tags or error text.
- Failure sequence: Outcome event key becomes a tag, failed submit emits committed success, or evidence details leak in an exception.
- Implementation: `DisputeOperationsMetrics`, risk/merchant audit rows, `disputeOperations` readiness, and the bounded scheduler use closed operation/outcome tags only.
- Proof: Lifecycle contract proves exact audit rows, existing metric/privacy and safe unexpected-error canaries pass, and live readiness was `UP` during the public demo.
- Adversarial evidence: Credentials, tenant/workflow/outcome IDs, event keys, evidence values, PostgreSQL row errors, rollback paths, and closed metric-tag inventory.
- Status: PASS

## C40-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained upgrades install an immutable reason catalogue and empty workflow state, preserve Chapter 32 rows, protect history, and a bounded retrying expiry scheduler is disabled-safe and idempotent across restart.
- Failure sequence: Migration invents submissions, scheduler scans unbounded rows, retry duplicates expiry, catalogue mismatch reports ready, or retained packet meaning changes.
- Implementation: Forward-only V59/V60, indexed `LIMIT` expiry scan, configurable batch/poll, five PostgreSQL protection triggers, readiness catalogue/schema checks, and no network adapter.
- Proof: Fresh PostgreSQL assertions cover V59/V60, eight definitions, indexes, columns, and five triggers; retained V58→V60 upgrade and public live `WON|1|4|4|1` reconciliation passed; 717 backend tests and 5 UI tests passed.
- Adversarial evidence: Empty/fresh schema, retained V58 state, repeated startup, missing catalogue/version, more due rows than batch, retry after committed expiry, and unavailable dependencies.
- Status: PASS

## Frozen Boundary Tables

| Boundary | Valid | Rejected |
|---|---|---|
| Deadline | submit at/before `dueAt`; expire strictly after | submit after; expire at/before |
| Version | append revision; submit exact latest version | mutate/delete; stale/foreign version |
| Readiness | closed reason-specific required fields present | missing requirements or unknown evidence |
| Outcome | submitted packet plus first exact `WON`/`LOST` event | pre-submit, conflicting duplicate, second terminal outcome |
| Money/fraud | operational state only | ledger, chargeback allocation, label, or decision rewrite |
| Actor | VIEWER/ANALYST/RISK_MANAGER read; live RISK_MANAGER evidence revision and submission after actor/credential lock; trusted simulator outcome | VIEWER/ANALYST mutation, disabled/stale actor or replay, merchant outcome forgery, foreign tenant |

## Post-closure recovery: `ER-0067`

The original role tests started requests with actors that were already enabled
or disabled. They did not prove the dangerous ordering in which authentication
succeeds and disable commits before the dispute mutation reaches its workflow
lock. The repaired lock order is:

```text
idempotency/replay boundary
  -> risk operator FOR UPDATE
  -> operator credential FOR UPDATE
  -> live status and current-role revalidation
  -> RISK_MANAGER mutation authorization
  -> dispute workflow FOR UPDATE
  -> evidence/event/audit commit
```

`forcedOperatorDisableBeforeEvidenceRevisionRejectsInFlightAndFutureMutations`
holds the operator lock inside the disable transaction, observes the in-flight
revision waiting at that exact PostgreSQL boundary, releases disable, and then
proves the loser writes no evidence version, workflow event, or audit. A future
service call returns risk-resource-not-found and the disabled raw credential is
unauthorized at HTTP. The same actor-first helper protects submission and both
completed-replay authorization callbacks.
`forcedOperatorDisableBeforeSubmissionRejectsInFlightAndFutureMutations` and
`completedMutationReplayReauthorizesAfterOperatorDisable` extend that proof to
submit and completed revise/submit replay; both pass in the 19/19 PostgreSQL
16.14 contract.
