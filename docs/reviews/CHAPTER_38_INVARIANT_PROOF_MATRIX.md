# Chapter 38 Invariant Proof Matrix

- Chapter: Chapter 38 — Historical Risk Policy Simulation
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

The `ER-0065` recovery reconstructs the complete decision-time control baseline
and replaces only the candidate's governed family, including same-`rule_code`
authored versions and exact-source safety-exception evidence. Independent
semantic review passed, `HistoricalRiskSimulationPostgreSqlTests` passed 8/8
on PostgreSQL 16.14, and the full backend suite passed 759/759 without skips.

This matrix was drafted and frozen on 2026-07-25 before Chapter 38 production
schema, code, configuration, or tests. It derives from the approved Phase 4.5
plan, immutable Phase 3/4 decision-time lineage, Chapter 36 taxonomy/time
semantics, the current merchant-policy lifecycle, and the Spring Batch 6 JDBC
job-repository/chunk/restart contract. Every test named here is a proof
obligation rather than a completion claim.

## C38-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Simulation reads historical PaymentAttempt, FraudDecision, FeatureSnapshot/FeatureValue, rule/control evidence, Authorization, capture/refund, and label facts without mutating, locking for update, replaying, or re-emitting any payment, processor, fraud, review, label, ledger, dispute, webhook, or risk-profile truth.
- Failure sequence: A worker calls the live confirm/evaluator path, loads current Redis/RiskContext, publishes a webhook, changes a decision, or locks payment rows long enough to interfere with traffic.
- Implementation: The keyset cohort reader selects immutable decision-time rows and descriptive money evidence at `asOf`; the processor reconstructs the stored rule/list/velocity/policy/authored-control baseline and replaces only the selected candidate family; all writes target simulation and Spring Batch tables.
- Proof: `HistoricalRiskSimulationIntegrationTests.jdbcBatchRunUsesFrozenFeaturesAndReconcilesItsImmutableReport`, `everyCandidateFamilyPreservesUnrelatedHistoricalControlEvidence`, the inherited PostgreSQL subclass, source dependency inspection, and `demo-historical-risk-simulation.py` prove public runs while payment/fraud/ledger/label counts remain byte-equivalent by durable query.
- Adversarial evidence: Approved/declined/3DS/review/block attempts, mixed policy-plus-authored-rule decisions, same-rule-family replacement, safety-exception exact source lineage, missing authorization, captured/refunded exposure, concurrent confirm, current Redis disagreement, and candidate stronger/weaker/equal action.
- Status: PASS

## C38-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Run creation, candidate snapshot, cohort rows, checkpoints, segments, results, lifecycle events, reports, and cancellation are merchant-qualified from the authenticated operator; foreign simulation/candidate IDs are indistinguishable from absence and job parameters cannot substitute a tenant.
- Failure sequence: A manager copies another merchant's policy/run/job execution ID, a batch parameter overrides merchant identity, or a report query joins a result by bare UUID.
- Implementation: Actor-derived tenant identity, manager-only mutations, composite tenant foreign keys, merchant-qualified public reads, and a server-owned run-ID-only Batch parameter fence candidate/run/result access.
- Proof: `publicApiEnforcesProgressReportRoleAndTenantBoundaries`, candidate-not-found paths, PostgreSQL V54 composite constraints, and inherited H2/PostgreSQL lifecycle tests cover creator/viewer/manager and copied foreign run identities.
- Adversarial evidence: Foreign policy, run, batch execution ID, result row, cancel, report, creator, and copied idempotency key.
- Status: PASS

## C38-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Simulation persists and returns only safe IDs, closed codes, versions, actions, counts, digests, timestamps, and descriptive integer amounts; raw PAN/CVV, email/phone/address/IP, device/session identity, HMAC values, API/operator credentials, idempotency material, note plaintext, list values, or arbitrary feature strings never enter run/result/report/log/metric/audit payloads.
- Failure sequence: A generic feature serializer, exception, segment key, or diagnostic report copies sensitive FeatureValue bytes or note content into simulation state.
- Implementation: Results persist only closed IDs/codes/actions/counts/amounts; reports expose closed aggregates; the only segment dimension is trusted traffic origin; audit and metrics carry allowlisted metadata without feature values.
- Proof: Schema/source scans, `RiskSimulationOperationalBoundaryTests.metricsUseOnlyClosedLowCardinalityDimensions`, the public API serialization assertion, and the live demo's safe response/durable reconciliation prove the exposed representation boundary.
- Adversarial evidence: PAN-like long value, email/IP/device/list HMAC, note canary, exception text, malicious policy display name, and unknown feature type.
- Status: PASS

## C38-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Each chunk atomically upserts one immutable result per `(merchant,run,payment_attempt)`, advances its exact `(created_at,id)` checkpoint and counts only after writes succeed, and can restart after rollback/crash without duplicate rows, skipped rows, or double-counted aggregates.
- Failure sequence: A crash occurs after result insert but before checkpoint, after checkpoint but before Spring Batch metadata commit, or while equal-timestamp attempts span the page boundary.
- Implementation: Spring Batch uses the application JDBC repository; `(decision_created_at,payment_attempt_id)` is the stable checkpoint; each chunk atomically inserts unique results, updates counts/checkpoint, and commits Batch execution context.
- Proof: `failedChunkRollsBackResultsAndProgressThenRestartsWithoutDuplication` forces failure after result writes and before progress; `failedFinalizerRestartsFromJdbcMetadataWithoutDuplicatingResults` forces the next boundary. Both run against H2 and PostgreSQL with exact result/report/execution counts.
- Adversarial evidence: Chunk sizes 1/bound/default, first/middle/final failure, duplicate job launch, stale STARTED recovery, and zero-row cohort.
- Status: PASS

## C38-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Cancellation and finalization serialize on the run projection: a committed cancel prevents later success, a committed success remains success, exactly one terminal event/report exists, and worker retries cannot revive a terminal run.
- Failure sequence: Cancel observes RUNNING while the worker finalizes, both append terminal events, or a delayed worker overwrites CANCELED with SUCCEEDED.
- Implementation: Lifecycle, cancel, writer, and finalizer serialize through the run projection, use an immutable execution token and compare-and-set state, and rely on unique terminal evidence plus the deferred PostgreSQL terminal-truth trigger.
- Proof: `canceledQueuedRunCannotBeRevivedByADelayedWorker` and `committedCancellationWinsAgainstPausedFinalization` prove delayed-start and paused-finalizer losers; successful finalization plus terminal mutation triggers prove the opposite winner and one terminal event/report.
- Adversarial evidence: Same/different idempotency keys, disabled actor after launch, JobOperator stop failure, rollback/retry, and final chunk with zero eligible rows.
- Status: PASS

## C38-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: A run freezes merchant, candidate type/version/canonical bytes/SHA-256 digest, cohort `[start,end)`, `asOf`, origin policy, label cutoff/policy, evaluator, feature catalogue, rule catalogue, taxonomy, query digest, and creation actor before queueing; later candidate edits or catalogue changes cannot alter evaluation meaning.
- Failure sequence: A draft threshold is added after launch, JSON/member order changes a digest, `asOf` moves during processing, or a future catalogue is silently used.
- Implementation: Run creation freezes canonical policy bytes, SHA-256, cohort/query identity, evaluator/catalogue/rule/taxonomy versions, and the PostgreSQL history guard makes those fields immutable.
- Proof: `RiskSimulationCandidateSnapshotTests` proves exact bytes, one-byte digest tamper, and unsupported schema; `activationGateRequiresDistinctManagerAndExactCurrentCandidateDigest` proves post-run candidate drift; migration tests prove retained identity.
- Adversarial evidence: Empty/32/33 thresholds, different ordering, Unicode display metadata, edited draft, retired/active candidate, corrupt digest, and missing definition.
- Status: PASS

## C38-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Candidate action is derived only from feature/control versions stored at the original decision. A policy candidate replaces historical policy-threshold evidence while preserving authored rules; an authored-rule candidate replaces every historical version with the candidate's immutable `rule_code` while preserving policy and unrelated rule families; a safety exception can suppress only matched historical evidence for its exact `sourceRuleVersionId` and preserves that row's historical `effective_action` or suppression state. Outcome analysis observes only non-superseded labels with `available_at <= label_cutoff <= asOf`; contradictory/superseded/unlabeled states remain distinct and missing/corrupt lineage is excluded or fails under a closed reason.
- Failure sequence: Current Redis makes a historical candidate match, a policy candidate drops a blocking authored rule, an authored candidate drops policy or unrelated rules, an authored candidate double-counts an older ACTIVE version of the same `rule_code`, a safety exception suppresses a different active successor or invents evidence for an approved-never-active source, a late fraud label leaks into an earlier run, unlabeled becomes legitimate, superseded labels double count, or missing features default to ALLOW.
- Implementation: The historical evaluator loads only immutable `feature_values`, rule/list/velocity evidence, policy-threshold evaluations, authored-rule evaluations, and non-superseded labels available by the frozen cutoff. The baseline always preserves static rule/list/velocity evidence; candidate-type branches preserve unrelated policy/authored evidence and exclude only the replaced policy family, authored `rule_code`, or exact safety-exception source version. Safety-exception evaluation reads the exact historical row's `matched` and nullable `effective_action`; missing or incompatible features become closed exclusions, never ALLOW or legitimate defaults.
- Proof: The end-to-end stored-feature fixture produces `ALLOW->REVIEW` from the frozen amount feature and explicit `UNLABELED`. `everyCandidateFamilyPreservesUnrelatedHistoricalControlEvidence` proves policy/authored preservation, same-`rule_code` replacement, retired source versus active successor, historically suppressed exact source, and approved-never-active source partitions. Catalogue/digest tests fail closed, and source inspection confirms no RiskContext/Redis/current-feature dependency.
- Adversarial evidence: Policy plus blocking rule, unrelated and same-code authored versions, retired exact source plus active successor, matched source with `effective_action IS NULL`, source definition approved but never active/evaluated, label at cutoff, occurrence before but availability after cutoff, LEGITIMATE/FRAUD conflict, superseded chain, absent value, and wrong feature catalogue.
- Status: PASS

## C38-API-001

- Lens: API_PRODUCT
- Invariant: Only a live tenant RISK_MANAGER can idempotently create/cancel a bounded run; VIEWER/ANALYST can read only SUCCEEDED tenant reports while creators can read safe progress; inputs require UTC `start < end <= asOf <= now`, at most 365 cohort days, explicit origin policy, supported candidate type, and stable request identity.
- Failure sequence: A viewer creates work, a partial/ambiguous timestamp broadens the cohort, a huge run exhausts the database, an in-progress report leaks partial truth, or replay bypasses a disabled actor.
- Implementation: Strict global JSON parsing, exact-microsecond temporal validation, the 365-day bound, actor-scoped idempotency/replay authorization, and separate create/progress/report/cancel endpoints enforce the product contract.
- Proof: `publicApiEnforcesProgressReportRoleAndTenantBoundaries`, delayed cancel tests, existing global JSON error contracts, service boundary checks, and byte-exact create replay in the live demo cover public success and denial states.
- Adversarial evidence: Naive/offset timestamps, future `asOf`, empty cohort, unsupported candidate, 0/366 days, missing key, duplicate request, and terminal cancel.
- Status: PASS

## C38-API-002

- Lens: API_PRODUCT
- Invariant: A successful report deterministically exposes processed/eligible/excluded counts, exclusion reasons, old-to-candidate action matrix, protected-suppression count, labelled fraud caught/missed, known-legitimate friction, contradictory and unlabeled coverage, origin breakdown, descriptive authorization/capture/refund amounts, safe sufficiently populated segments, exact report digest, completion time, and expiry.
- Failure sequence: Counts do not reconcile, an amount is described as predicted revenue/loss, small segments leak identity, map order changes the report digest, or absence renders as zero coverage.
- Implementation: One ordered canonical report reconciles population, transition, exclusion, outcome, origin, descriptive money, safety, and coverage aggregates; segments require ten rows; the immutable row binds SHA-256 and expiry.
- Proof: Integration tests assert exact hand-computed population/transition, both restart paths preserve one report, PostgreSQL constraints enforce sums, and the public demo reproduces SHA-256 from returned report bytes and matches the database digest.
- Adversarial evidence: All action transitions, missing Authorization, multiple ledger rows, refund, synthetic origin, insufficient segment, and contradictory labels.
- Status: PASS

## C38-ACT-001

- Lens: PAYMENT_FRAUD
- Invariant: The reusable activation gate accepts only a tenant-bound `SUCCEEDED` unexpired report with exact candidate digest, zero protected-suppression attempt, configured minimum eligible cohort and label coverage, no blocking data-quality result, and a different approving operator; it never activates a candidate itself.
- Failure sequence: Candidate edit drift, stale report, self-approval, foreign report, partial job, low coverage, or protected suppression is treated as activation permission.
- Implementation: The evidence-only gate returns a typed disposition/reason list for success, freshness, exact digest, independent active manager, eligible population, label coverage, exclusions, and protected suppression without performing activation.
- Proof: `activationGateRequiresDistinctManagerAndExactCurrentCandidateDigest` proves self-approval rejection, independent acceptance, and digest drift; operational/configuration tests cover the remaining closed prerequisites consumed transactionally by Chapter 39.
- Adversarial evidence: Every lifecycle status, digest match/mismatch, expiry minus/at/plus one microsecond, cohort/coverage thresholds, creator=approver, and blocking exclusions.
- Status: PASS

## C38-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish queued age, start, chunk commit/rollback latency, progress, exclusion class, restart, cancel, success, failure stage, and readiness using closed low-cardinality tags; transaction rollback never emits committed progress and no metric/log contains tenant, actor, run, candidate, payment, digest, checkpoint, or sensitive feature values.
- Failure sequence: Chunk rollback increments processed, exception text leaks a feature, high-cardinality run IDs become tags, or a disabled worker appears healthy while create remains enabled.
- Implementation: Dispatch/job metrics accept only closed phase/result codes; progress is durable only at chunk commit; readiness validates worker/create mode and the required product/Batch tables without returning dependency exceptions.
- Proof: `RiskSimulationOperationalBoundaryTests` proves tag shape, all worker/create mode partitions, incomplete schema, and unavailable database; injected rollback proves no durable false progress.
- Adversarial evidence: Reader/processor/writer/finalizer failure, cancellation, restart, corrupt lineage, database timeout, and hostile candidate metadata.
- Status: PASS

## C38-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V53 upgrades install Spring Batch JDBC metadata and simulation tables empty by default, preserve all prior bytes, validate indexes/constraints/triggers, and support API-only/worker modes without inventing broker, distributed lease, or Kafka claims.
- Failure sequence: Boot auto-runs the job, an upgrade creates historical results, Batch uses an in-memory repository, a partial schema reports ready, two workers duplicate a run, or migration rewrites payment/fraud lineage.
- Implementation: V54 installs Spring Batch 6.0.3 JDBC metadata and simulation state; V55 installs append-only, run-history, transition, and deferred terminal-evidence guards; startup auto-run is disabled and the executor/queue/chunks are bounded.
- Proof: `PostgreSqlMigrationTests` proves fresh V1 and retained V53 upgrades, exact tables/columns/triggers and empty default state; 704-test current-binary execution, PostgreSQL restart tests, readiness tests, and retained local startup all pass.
- Adversarial evidence: Missing Batch table, retained active reviews/notes/monitoring rows, worker disabled with creation enabled, abrupt execution, and two launchers.
- Status: PASS

## C38-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 38 closes only after point-in-time, candidate, label, restart, cancellation, tenant, privacy, report, gate, migration, and operational boundary families pass at real enforcement boundaries, a live create-progress-report demo reconciles PostgreSQL, and no blocking finding or undocumented debt remains.
- Failure sequence: A synchronous unit test substitutes for Batch restart, H2 substitutes for PostgreSQL interleaving/migration, green aggregate counts hide future-label leakage, or source-of-truth documents claim completion before live proof.
- Implementation: The matrix drove schema/code/tests before closure; focused H2, PostgreSQL, API, browser, migration, operational, live-demo, and repository proof were followed by a separate representation/failure hostile pass.
- Proof: 702-test full Java regression passed before the final commit-edge probe; the subsequent focused probe passes, 123 Python tests, 4 UI tests plus build, fresh/retained migrations, and the public PostgreSQL demo pass. The live hostile pass found and fixed the cross-Jackson report representation defect before closure.
- Adversarial evidence: DRAFT matrix, synchronous fake worker, duplicate result after restart, stale candidate, unsafe report, contradictory source status, open P1/P2 promise finding, or vague deferral.
- Status: PASS

## Frozen Boundary Tables

### Historical time and outcome boundary

| Boundary | Included / valid | Excluded / rejected |
|---|---|---|
| Cohort | decision attempt time in UTC `[start,end)` | before start, at/after end, missing immutable decision |
| Knowledge | decision-time features/control only | current RiskContext, Redis, later policy/configuration |
| Counterfactual baseline | stored static controls plus candidate-type-preserved policy/authored evidence | candidate evaluated against an empty baseline or current control state |
| Candidate replacement | policy family; authored `rule_code` family; exact safety source version | exact-version-only authored replacement, unrelated-rule removal, broad safety suppression |
| Label | current non-superseded label with `available_at <= cutoff <= asOf` | future availability, superseded label, contradictory label as legitimate |
| Origin | `LIVE_ONLY` default; explicit `INCLUDE_SYNTHETIC` | browser/merchant-forged origin, implicit synthetic inclusion |
| Missing lineage | closed exclusion or blocking failure by reason | default feature, fictional ALLOW/legitimate, silent row loss |
| Exposure | descriptive integer authorization/capture/refund minor units | prediction, guarantee, inferred revenue/loss |

### Job, report, and gate boundary

| Boundary | Valid transition/truth | Rejected transition/truth |
|---|---|---|
| Job | `DRAFT -> QUEUED -> RUNNING -> SUCCEEDED|FAILED|CANCELED` | terminal rewrite, two terminal events, startup auto-run |
| Chunk | atomic results + checkpoint + counts, restart idempotent | checkpoint without rows, duplicate/omitted attempt, long payment lock |
| Report | one immutable canonical report/digest after success | partial report, digest drift, small unsafe segment |
| Cancel/finalize | first committed terminal transition wins | canceled-to-success, success-to-canceled, stale worker revival |
| Gate freshness | completion plus 24h is valid strictly before expiry | at/after expiry, non-success, candidate digest drift |
| Gate authority | different live tenant manager, evidence-only disposition | self/foreign/disabled actor, gate directly activates candidate |

## Post-closure recovery verification: `ER-0065`

The first repair restored policy, authored-rule, and safety-exception evidence
to the baseline, but independent review found a deeper family error: replacing
only the candidate authored-rule version would leave the historical ACTIVE
version of the same logical `rule_code` in the baseline and overstate risk.
The implementation now resolves the frozen candidate version to its immutable
`rule_code` and excludes that entire family while preserving every unrelated
historical authored rule and policy threshold.

Safety exceptions use a narrower identity. They may suppress only the exact
`sourceRuleVersionId` row that was actually evaluated for the historical
decision. The evaluator carries forward its nullable `effective_action`, so a
historically suppressed row is not resurrected; a retired source does not
silence its active successor; and an approved-but-never-active source cannot
create counterfactual evidence that never existed.

The integration contract
`everyCandidateFamilyPreservesUnrelatedHistoricalControlEvidence` passes these
partitions on H2 and in the inherited 8/8 PostgreSQL 16.14 suite. Independent
semantic review confirmed the replacement/suppression identities at the
production query boundary.
