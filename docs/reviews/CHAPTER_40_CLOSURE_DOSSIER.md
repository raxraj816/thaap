# Chapter 40 Closure Dossier

- Chapter: Chapter 40 — Dispute Deadline And Outcome Operations
- Chapter number: 40
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_40_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

`ER-0067` actor-and-credential-first locking is implemented for evidence and
submission execution/replay. All 19 PostgreSQL 16.14 dispute contracts pass,
including disable-versus-submit and completed replay after disable; the complete
backend suite passes 759/759 without skips.

## Observable promises

- Each new chargeback receives exactly one tenant workflow and an immutable reason-catalogue deadline derived from `receivedAt`; caller `evidenceDueAt` does not redefine operational policy.
- Evidence is text-free, bounded, canonical, digested, and append-only. Readiness comes from the exact latest version and submission binds that version permanently.
- VIEWER, ANALYST, and RISK_MANAGER can read tenant workflows; only a live RISK_MANAGER can revise evidence or submit the exact latest version. API work is valid at the deadline and expiry begins strictly after it.
- Only trusted synthetic traffic records `WON`/`LOST`; the outcome is idempotent, terminal, explicitly attested, and does not rewrite ledger, allocation, decision, or label truth.
- The cockpit renders deadline/readiness/missing requirements and uses an exact evidence-version submission action without storing or rendering the credential.

## Enforcement boundaries

| Promise | Real boundary | Evidence |
|---|---|---|
| Governed deadline | V59 catalogue + initializer + injected clock comparison | PostgreSQL lifecycle contract, `DisputeDeadlineSemanticsTests` |
| Immutable packet | V59 unique/composite keys + V60 triggers | PostgreSQL UPDATE/DELETE rejection and version 1/2 history |
| Latest readiness | locked service transaction + submitted-version FK | incomplete→ready and stale-version 409 proof |
| Role and live-actor control | VIEWER/ANALYST/RISK_MANAGER read; actor and credential `FOR UPDATE` before workflow; RISK_MANAGER-only mutation and replay | viewer/analyst mutation 403, disable-first lock wait then no write, manager success |
| One terminal outcome | trusted-origin gate + digest + unique workflow/event keys | untrusted 403, exact replay, one `OUTCOME_WON` |
| Serialized submit | base workflow `FOR UPDATE` before hydration | forced contender lock wait, one submit/event/audit |
| Money/fraud isolation | separate operational tables; no writer dependency | before/after contract and live read-only reconciliation |

## Failure inventory

The frozen inventory covered deadline partitions, missing/latest/stale evidence,
role and tenant substitution, untrusted/duplicate/out-of-order outcomes,
revision/submission/expiry concurrency, mutation/deletion, scheduler bounds,
migration state, privacy sinks, payment/fraud non-interference, and operator
disable after request authentication but before the dispute mutation lock. The
hostile pass found and repaired the PostgreSQL outer-join lock and
reserved-alias bugs.

## Boundary family evidence

Valid and invalid deadline, version, readiness, outcome, money/fraud, actor,
tenant, JSON, migration, and concurrency partitions were reconciled against the
frozen boundary table. The escaped SQL findings reopened the PostgreSQL grammar
and locking families before the lifecycle proof was rerun. `ER-0067` later
reopened the actor-liveness family and added the disable-first forced
interleaving plus completed-replay reauthorization.

## Migration evidence

- Fresh H2 and PostgreSQL schemas validated and applied all 60 migrations.
- `PostgreSqlMigrationTests` proves V59/V60, eight `dispute_deadlines_v1` rows, required columns, the expiry index, and five non-internal protection triggers.
- The retained local database upgraded in place from V58 to V60. Existing Chapter 32 and Chapters 36–39 state was retained; no reset or backfill invented dispute submissions/outcomes.

## Exact proof

- Backend full regression: `759` tests, `0` failures, `0` errors, `0` skipped.
- UI: `20` Vitest tests plus TypeScript and Vite production build passed.
- Deterministic PostgreSQL contention holds the workflow lock, observes the competing submit wait, and proves one submitted event/audit row after release.
- Focused post-closure proof
  `forcedOperatorDisableBeforeEvidenceRevisionRejectsInFlightAndFutureMutations`
  holds the live actor lock in the disabling transaction, observes the
  revision wait, and proves the disabled actor writes zero evidence,
  transition, or audit rows. The expanded chargeback/dispute contract passed
  `19/19` on PostgreSQL 16.14, including disable-before-submit and completed
  revise/submit replay after disable.

## Demo evidence

- Demo safety: `3` tests prove loopback-only origin and read-only argv-based PostgreSQL reconciliation.
- Public live demo: `DRAFT → READY → SUBMITTED → WON`, analyst evidence revision and submission denied, manager evidence revision plus exact-version submission replay byte-equal, trusted outcome replay byte-equal, readiness `UP`.
- Live durable result: `WON|1|4|4|1` = terminal workflow, one evidence version, four lifecycle events, four pre-existing capture/chargeback ledger rows, one pre-existing chargeback fraud label. Workflow actions added no money/fraud rows.

## Hostile review

- PostgreSQL rejected locking a nullable outer join; lock acquisition was split from projection hydration and re-proven.
- PostgreSQL rejected the H2-tolerated `authorization` alias; capability aliases were made dialect-safe and re-proven.
- An attempted stack-trace logging improvement exposed database row contents to privacy-canary tests. The request/path/exception-type-only safe log contract was restored before closure.
- Deadline boundary partitions, stale version, missing evidence, wrong role, untrusted source, append-only mutations, repeated submission, idempotent replay, and retained migration were reviewed against the frozen matrix.

## Operational signals

- `disputeOperations` joins the readiness group and verifies the eight-row catalogue plus compatible workflow versions.
- `thaap.disputes.expiry-*` bounds scheduling, batch size, and poll delay; the expiry query uses the due index and a maximum batch of 100.
- Metrics use only closed `operation` and `outcome` values; operator/outcome actions produce sanitized audit metadata.

## Custom infrastructure decision

`NOT_APPLICABLE`. The slice uses Spring transactions/scheduling, PostgreSQL,
existing tenant/operator/idempotency controls, and closed Java/React contracts;
it adds no workflow engine, broker, document store, cryptosystem, or network adapter.

## Escaped-review assessment

`ER-0067` escaped the prior Chapter 40 completion claim. The implementation
checked roles, but its workflow-first mutation boundary left a time-of-check to
time-of-use window after authentication. Evidence revision, submission, and
their completed-replay callbacks now lock and revalidate both the live actor
and credential before acquiring the workflow lock. The process correction is
to include authentication-success/disable-before-resource-lock orderings for
every privileged mutation and replay path.

## Post-closure recovery

The chapter was reopened while `ER-0067` was unresolved. The recovery preserved
the frozen role contract—three risk roles may read, only RISK_MANAGER may
mutate—and made authorization durable through the transaction boundary. The
deterministic PostgreSQL revision test proves disable wins without partial
evidence, event, or audit truth; role-matrix proof covers VIEWER/ANALYST
mutation denial and manager success. The submission and completed-replay
partitions pass at the same production lock boundary.

## Known debt

No Chapter 40 debt was accepted. Arbitrary file/document evidence, real card-network submission, representment settlement, and money reversal are rejected scope, not hidden deferrals; they require authenticated network and document-storage boundaries before implementation.

## Source-of-truth reconciliation

The Phase 4.5 plan, implementation master plan, README, daily note, weekly
review, proof matrix, dossier, and escaped-review register identify `ER-0067`
as resolved and Chapter 40 as `READY_TO_MERGE` with every invariant `PASS`.

## Merge recommendation

`MERGE`. Actor-first authorization and every expanded PostgreSQL revision,
submission, and replay partition pass; `ER-0067` is resolved and no Chapter 40
debt is open.
