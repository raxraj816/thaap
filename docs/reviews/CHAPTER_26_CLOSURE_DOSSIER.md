# Chapter 26 Closure Dossier

- Chapter: Chapter 26 — Analyst Identity, Review Claim, And Label Truth
- Chapter number: 26
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_26_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

Chapter 26's promises were frozen on 2026-07-13 before implementation. The
implementation-to-proof traceability pass and later hostile failure-sequence
pass are complete. Every matrix row is `PASS`, no Chapter 26 debt or escaped
finding remains, and this recommendation applies only to Chapter 26—not to the
unfinished Phase 4 chapters.

## Observable promises

- A dedicated active tenant-owned RiskOperator owns analyst mutations; a
  merchant API key never impersonates an analyst.
- Claim, release, structured annotation, and terminal resolution create
  durable, append-only, role-authorized truth.
- `CANCEL` and `CANCEL_AND_REPORT_FRAUD` cancel only pre-authorization review
  state without new processor, ledger, refund, or rewritten decision truth.
- Only explicit confirmed fraud creates one typed, time-safe immutable label.
- Merchant cancellation, competing claims, stale release, operator disable,
  and competing resolution converge to one committed truth.

## Failure inventory

The pre-implementation inventory covered credential state and key rotation,
role/assignment authorization, tenant substitution, secret/privacy surfaces,
closed annotation and resolution grammar, duplicate/replay behavior, rollback
at each durable write, merchant/analyst and analyst/analyst races, label
taxonomy/time/supersession, fresh/retained migrations, webhook consent,
bounded observability, and premature closure. The hostile pass expanded the
escaped families discovered during execution: Spring 4 HTTP JSON configuration,
unsupported media mapping, V24-triggered retained upgrades, ORM stale state,
transaction-completion metrics, and fraud taxonomy binding.

## Boundary family evidence

- Authorization: VIEWER, ANALYST, RISK_MANAGER, unassigned, assigned, released,
  reassigned, disabled, malformed credential, foreign actor, and locked-disable
  partitions.
- Lifecycle: OPEN/ASSIGNED/RESOLVED/CANCELED projections; claim/claim,
  reassign/release, merchant/analyst, and resolve/resolve orderings.
- Resolution: CANCEL versus CANCEL_AND_REPORT_FRAUD, all closed type/reason
  combinations, future evidence, same-key replay, and changed-body conflict.
- Persistence: six rollback positions, immutable history, cross-tenant actor,
  mismatched source lineage, self-cycle, and cross-payment supersession.
- API/privacy: cursor limits/ties/malformed values, unknown/nested fields,
  media type, strict credential surfaces, persistence/log/audit/metric scans.
- Operations: fresh V1→V28, retained V25→V28, unchanged historical review and
  subscription cohorts, ORM validation, and latest-code live retained demo.

## Enforcement boundaries

Public controllers provide bounded DTOs, strict JSON grammar, scoped
idempotency, and missing-equivalent tenant behavior. `ReviewOperationsService`
revalidates a locked operator and locks PaymentIntent, PaymentAttempt, then
ManualReviewCase. PostgreSQL composite keys, uniqueness, exact checks,
immutability triggers, source-lineage triggers, and deferred projection checks
protect durable truth. Audit, webhook, idempotency completion, and final-outcome
metrics participate in or observe the same transaction.

## Exact proof

- 351 backend tests pass.
- `ReviewResolutionAndLabelContractTests`: 9 API, cursor, role, credential, privacy,
  replay, label, and webhook-consent cases.
- `ReviewResolutionPostgreSqlConcurrencyTests`: 15 production-database cases,
  including five deterministic forced waits and six rollback injection points.
- `PostgreSqlMigrationTests`: 14 migration cases, including retained V25→V28.
- 80 repository Python tests pass, including four demo-client boundary tests.
- Captured constraint failures contain no failing row or constraint detail.

The traceability pass maps every frozen promise to implementation and exact
tests in the invariant matrix. The later hostile pass found and fixed:

1. unsafe exception/failing-row logging;
2. a V26 retained-upgrade conflict with the V24 immutability trigger;
3. stale ORM review state after waiting on payment locks;
4. rollback metrics registered too late to observe early failure;
5. fraud-type/reason combinations that were independently valid but jointly
   semantically false;
6. unsupported media mapped to 500 and unknown request fields silently ignored;
7. unproved equal-time cursor and label-supersession partitions.

Each affected family was re-enumerated and re-proved in the full green suite.

## Migration evidence

Fresh H2 and PostgreSQL V1→V28 migrations and Hibernate validation pass. The
retained PostgreSQL cohort creates realistic V25 OPEN/CANCELED reviews and a
legacy webhook subscription, snapshots historical values, migrates through
V26–V28, proves byte/field equality and exact new-column defaults, verifies no
new risk-event consent, checks Flyway history, and confirms the expanded
immutability trigger still rejects historical mutation. The retained local
database also migrated to and ran successfully on V28.

## Demo evidence

The latest-code live command completed against retained local PostgreSQL V28:

```text
Chapter 26 analyst review-to-label demo passed.
payment=CANCELED attempt=CANCELED review=RESOLVED decision=REVIEW
resolution=1 fraud_labels=1 annotations=1 explicit_risk_webhooks=4
processor_money_before=2|0|0 processor_money_after=2|0|0
replay=exact tenant_isolation=missing-equivalent credential_leaks=zero
```

The demo uses public APIs for merchant/payment/operator/review/annotation/
resolution/read behavior, rejects non-loopback origins and redirects, performs
read-only SQL reconciliation, requires explicit webhook consent, verifies
byte-exact replay, and scans durable credential surfaces.

## Operational signals

`thaap.risk.operations` uses exactly the closed tags `operation`, `outcome`,
and `transaction`. Six rollback cases prove a single `rolled_back` increment,
zero false `committed` increment, and one committed increment after clean
retry. Error logs contain only request ID, path, and exception type; Hibernate
failing-row detail is suppressed. Tests scan metrics, logs, audit, and durable
credential fields for raw secrets and unbounded values.

## Known debt

No Chapter 26 technical, security, data/ML, or operational debt is accepted or
deferred. `docs/TECHNICAL_DEBT_REGISTER.md` remains authoritative; no new
`TD-NNNN` entry is required because every discovered frozen-promise defect was
fixed in this slice.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 26 uses the existing Spring MVC/JPA transaction,
Flyway, PostgreSQL, versioned HMAC/encryption, idempotency, audit, and webhook
substrates. It introduces no proprietary protocol, scheduler, queue, crypto,
or concurrency framework.

## Escaped-review assessment

`COMPLETE`. Chapter 26 had no earlier completion claim, so findings discovered
during its implementation and pre-closure hostile review are not escaped
findings. The later post-claim authorization-before-replay review registered
ER-0055; V49 principal/credential-scoped idempotency and lower-role cross-actor
API proof resolve it. No unresolved Chapter 26 P0/P1 or frozen-promise P2 exists.

## Source-of-truth reconciliation

README, implementation master plan, Phase 4 detailed plan, architecture and
concept notes, daily/deep-dive/weekly learning trail, matrix, dossier, migration
inventory, app/script READMEs, debt register, and escaped-review register agree:
Chapter 26 is complete; Chapter 27 is the next dependency-ready implementation
slice; Phase 4 as a whole remains in progress.

## Merge recommendation

`MERGE`. Chapter 26 meets its frozen promises at the API, transaction,
PostgreSQL, migration, privacy, observability, and live-demo boundaries. This
recommendation does not authorize a Phase 4 completion claim; Chapters 27–35
remain separately gated.
