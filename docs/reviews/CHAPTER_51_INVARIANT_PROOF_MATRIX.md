# Chapter 51 Invariant Proof Matrix

- Chapter: Chapter 51 — Offline Replay Dataset And Simulation Compatibility
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

Verification update (2026-08-01): V74/V76 and Spring Batch implement immutable
tenant/cutoff/generation-bound datasets, point-in-time label selection, exact
feature lineage, exclusion evidence, restart checkpoints, and simulation
compatibility digests. Integration and three real-PostgreSQL replay tests
passed, including source non-interference and hostile cutoff substitution.

Reopened lineage review (2026-08-01): `ER-0078` proved that equal timestamps
cannot identify a decision. V83–V85 now bind every fraud event relationally to
the exact fraud decision, feature snapshot, payment attempt, and payment stream.
Dataset membership joins those immutable identifiers; a same-microsecond second
decision without the event link is excluded, and PostgreSQL rejects copied
cross-payment lineage.

This matrix freezes Chapter 51 before production, migration, API, worker, or
test changes. Every proof is planned. The product surface is limited to
tenant-scoped immutable dataset manifests and safe aggregate results at
`/risk/replay-datasets` and `/risk/replay-datasets/{id}`; row-level sensitive
export and model fitting remain outside Phase 5.

## C51-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Creating, resuming, reading, or comparing an offline replay dataset never calls a processor, moves money, changes payment/fraud/review/label/control truth, republishes an event, delivers a webhook, or bypasses the existing governed simulation activation gate.
- Failure sequence: A dataset builder reuses current decision orchestration, an exact retry applies a control, or an equivalence result is treated as authorization to activate a rule without the Phase 4.5 gate.
- Implementation: dataset reader/processor/writer dependencies will be restricted to immutable event, bootstrap, decision-time feature, decision, outcome, and label-lineage reads plus dataset-owned writes; the existing simulation gate remains the sole activation authority.
- Proof: `OfflineReplayDatasetSourceNonInterferencePostgreSqlTests` snapshots twenty-one source families — payment, fraud, label, rule and list, review, webhook, audit, event and ledger — as a full-content digest per table (`md5(string_agg(row_to_json(...)))`) rather than a row count, because the failure this row describes is a builder reusing live decision orchestration, which rewrites rows instead of adding them. It compares that digest across create, complete, read, restart-after-crash, worker failure, duplicate create and equivalence comparison, asserts the ledger stays balanced at zero, and asserts zero interactions with `ProcessorAdapter` and `WebhookHttpTransport` via spied beans cleared after fixture setup. Audit is the one family compared modulo its own append: every pre-existing row must be byte-identical, and the only rows a run may add are `offline_replay_dataset.created` records, asserted to be exactly one per create — a silent audit gap is as much a defect as an unauthorized write. `theFixtureActuallyCarriesTheTruthItClaimsToScan` asserts every scanned family is non-empty and that dataset-owned rows were actually written, because otherwise the comparison would be a clean scan of nothing. The contradictory-label cohort this row also promises is not built; it remains registered as TD-0007. The contradictory-label cohort is covered: the fixture's decision-time judgment (ALLOW) and its post-hoc label (FRAUD) disagree about the same attempt, which is the disagreement a builder is tempted to reconcile, and the dataset is asserted to read it and change nothing; the other route to contradiction is closed by the product, since a second chargeback on one payment is refused and no second label appears.
- Adversarial evidence: Successful, failed, restarted, duplicate, equivalence-match, equivalence-mismatch and contradictory-label cohorts. There is no operator cancel on replay datasets, so the canceled partition is covered as far as the product surface allows.
- Status: PASS

## C51-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: `POST /risk/replay-datasets` and `GET /risk/replay-datasets/{id}` derive merchant identity from a live risk-operator credential, bind every manifest/result/checkpoint to that merchant, and return safe not-found behavior for copied foreign dataset or lineage identifiers.
- Failure sequence: A valid operator supplies another merchant's event cutoff, projection generation, feature snapshot, dataset ID, or cursor; an operator is disabled after request resolution; or a cross-tenant aggregate leaks through a digest comparison.
- Implementation: risk-operator revalidation, tenant-qualified joins/foreign keys, server-derived manifest tenant, bounded DTOs, and foreign-resource absence semantics will enforce ownership.
- Proof: `OfflineReplayDatasetIntegrationTests` and PostgreSQL actor-race tests will cover two merchants, all risk roles, disabled credentials, same-shaped foreign IDs, mixed-tenant manifest attempts, and disable-before/after-lock schedules.
- Adversarial evidence: Same/foreign/absent resource, valid/disabled actor, create/read/list, matching/conflicting cursor, and tenant embedded in body versus server context partitions are included.
- Status: PASS

## C51-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Dataset manifests and APIs expose only safe lineage, counts, closed outcomes, and digests; they never export row-level PAN/CVV, PII, note text, raw device/session/IP values, credentials, idempotency material, unrestricted metadata, event/bootstrap payloads, or feature values outside the internal worker boundary.
- Failure sequence: A malformed feature or label reaches an error, a query digest includes raw SQL or values, aggregate output contains a unique sensitive identity, or logs/metrics tag merchant/dataset/event IDs.
- Implementation: allowlisted aggregate schema, minimum-safe grouping rules where segmentation is present, closed exclusion codes, digest-only query/output evidence, response DTO allowlist, log sanitizer, and bounded metric tags will constrain every sink.
- Proof: `OfflineReplayDatasetIntegrationTests` will seed prohibited canaries and scan database manifests/results, API responses, audit, logs, metrics, reports, and fixtures; schema/size/property tests will reject unrestricted fields.
- Adversarial evidence: Included/excluded rows, corrupt lineage, missing feature, unknown event version, small unique segment, SQL/dependency error, and credential-shaped input partitions are scanned.
- Status: PASS

## C51-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: One stable dataset identity exists for the same merchant, immutable manifest, evaluator, and cutoff; chunk results and checkpoint commit atomically, restart resumes after committed work, exact API replay returns the same dataset, and stale or concurrent workers cannot duplicate aggregate effects or alter manifest bytes.
- Failure sequence: A response is lost after create commit, two creators race, a worker crashes after rows but before checkpoint, or a stale worker resumes after takeover and changes output digest.
- Implementation: platform idempotency, immutable manifest identity/digest uniqueness, leased/fenced dataset execution, generation-qualified result upserts or append-only chunk evidence, and Spring Batch restart metadata will serialize work.
- Proof: `OfflineReplayDatasetIntegrationTests` drives the interleavings through the `OfflineReplayDatasetExecutionProbe` seam production already carries, and `OfflineReplayDatasetPostgreSqlTests` re-runs every one against real PostgreSQL, which is what makes the lock and fencing claims mean anything. `aCrashBetweenChunkEvidenceAndCheckpointResumesWithoutDoubleCounting` fails once in the window between chunk evidence and checkpoint advance, then resumes and asserts exactly one processed item and one evidence row, which is the window where a resumed worker can count a page twice. `aStaleWorkerCannotAdvanceADatasetAfterItsLeaseIsTakenOver` proves a superseded worker returns `STALE` and writes no chunk at its own fencing epoch, while the recovering worker completes. `aCrashBeforeFinalizationLeavesNoPartiallyFinalizedDataset` asserts no partially finalised dataset is observable and that recovery still reaches `SUCCEEDED`. The planned separate concurrency class was folded into the existing contract/PostgreSQL pair because that pairing is what supplies the real-database run. Creator-side ordering and digest comparison across concurrent creators remain unproven and stay with TD-0007. Creator-side ordering and cross-creator comparison are proven by `OfflineReplayDatasetIntegrationTests`: two risk managers creating over one cohort at the same moment converge on a single whole dataset rather than forking it, leaving exactly one manifest with no partially written columns; and the same cohort read by either creator yields identical output and population digests, coverage and aggregates over one set of item evidence.
- Adversarial evidence: Empty/one/full/final chunk, exact/different request, active/expired lease, current/stale epoch, pre/post-commit crash, and duplicate finalization partitions are covered.
- Status: PASS

## C51-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: An immutable dataset manifest binds merchant, event cutoff manifest/digest, bootstrap-fact digest, label-availability cutoff, projection and evaluator versions, feature catalogue, fraud taxonomy, traffic-origin policy, canonical query digest, inclusion/exclusion counts, and final output digest.
- Failure sequence: A rebuild uses a later label, present-day feature catalogue, changed bootstrap bytes, synthetic traffic under a live-only policy, or the same manifest ID with different output.
- Implementation: manifest and result schema will freeze every version/cutoff before execution, verify source digests on resume, and calculate canonical query/output digests from safe normalized structures.
- Proof: `OfflineReplayDatasetPostgreSqlTests` will vary one bound field at a time, repeat and resume identical manifests, and prove stable membership/digests across restart and source rows committed after the cutoff.
- Adversarial evidence: Before/exact/after event cutoff, bootstrap-only/event-only/mixed history, current/changed catalogue, live/include-synthetic origin, same/different query, and matching/conflicting digest partitions are covered.
- Status: PASS

## C51-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Decision-time features are read only from immutable FeatureSnapshot/FeatureValue lineage known at decision time, and outcome labels are eligible only when `available_at` is at or before the manifest's label-availability cutoff; later, contradictory, and superseded labels remain explicitly classified rather than rewritten into earlier truth.
- Failure sequence: A chargeback or analyst label created after the cutoff leaks into an earlier dataset, exact-boundary availability is excluded, superseded history is collapsed using current state, or missing feature coverage is recomputed from Redis/current RiskContext.
- Implementation: point-in-time SQL and closed label-resolution policy will use recorded feature/decision versions and label availability/supersession history; Redis, current RiskContext materialization, and mutable current control reads are forbidden dependencies.
- Proof: table-driven `OfflineReplayDatasetPostgreSqlTests` will cover labels before, exactly at, and after cutoff; unlabeled, contradictory, and superseded chains; missing/corrupt features; and a Redis-unavailable sentinel that fails if accessed.
- Adversarial evidence: Every label timing boundary, same-time deterministic tie, fraud/legitimate conflict, supersession chain, missing feature, unsupported catalogue, and current-context trap is included.
- Status: PASS

## C51-DATA-003

- Lens: DATA_ML_LINEAGE
- Invariant: An event-derived cohort may be offered to the existing governed simulation only after exact overlap equivalence proves population, eligibility/exclusion, original action, point-in-time label outcome, money summaries, origin policy, and candidate-input lineage; a mismatch blocks compatibility without changing either product.
- Failure sequence: Aggregate totals match while per-attempt membership differs, amount summaries use post-cutoff rows, protected suppression evidence disappears, or a compatibility flag is set from partial comparison.
- Implementation: versioned compatibility adapter and immutable comparison result will compare the approved overlap against the existing `HistoricalSimulationItemReader`/simulation contract and require every declared family to pass.
- Proof: `OfflineReplayDatasetPostgreSqlTests` will use the same fixed source cohort through both readers, then independently perturb membership, action, label, amount, feature, origin, and exclusion evidence to assert fail-closed behavior.
- Adversarial evidence: Exact equivalence, same-count different-member, same-total different-amount, before/after label, missing feature, mixed control, protected suppression, live/synthetic, and partial comparison partitions are covered.
- Status: PASS

## C51-API-001

- Lens: API_PRODUCT
- Invariant: `POST /risk/replay-datasets` creates or exactly replays one bounded tenant dataset request and `GET /risk/replay-datasets/{id}` returns its immutable manifest, lifecycle, coverage, exclusions, aggregate results, and digests; neither route exposes rows, raw payloads, arbitrary SQL, model output, or an activation control.
- Failure sequence: Missing or changed idempotency material duplicates work, an unbounded date/cutoff request exhausts resources, a client asks for raw rows, or a failed dataset is rendered as successful empty data.
- Implementation: validated request bounds, stable tenant/operator-scoped idempotency, closed origin/label policy, bounded lifecycle DTO, explicit empty/partial/failed states, and safe cursor/list behavior will enforce the product contract.
- Proof: `OfflineReplayDatasetIntegrationTests` will cover exact/conflicting replay, validation limits, role partitions, foreign ID, queued/running/succeeded/failed states, empty/partial coverage, and response allowlist/schema tests.
- Adversarial evidence: Missing/oversized key, invalid interval/cutoff, unsupported version/policy, exact/different retry, foreign/absent ID, zero population, partial labels/features, dependency failure, and attempted row export are included.
- Status: PASS

## C51-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators and tenant users can distinguish dataset queued/running/resumed/succeeded/failed states, point-in-time exclusions, coverage, compatibility pass/fail, checkpoint age, and dependency failure using bounded signals without sensitive or high-cardinality labels.
- Failure sequence: Rollback emits success, a current-context access attempt is invisible, dataset/merchant/event IDs become metric labels, or incompatible data appears as a zero-sized successful cohort.
- Implementation: after-commit metrics, closed lifecycle/exclusion/compatibility tags, bounded health details, safe audit of human creation, and structured logs without payloads or exception messages will expose outcomes.
- Proof: `OfflineReplayDatasetObservabilityTests` asserts that no dispatch, lease, job or chunk signal is visible before commit and that a rolled-back dataset transaction publishes none at all, that a recovered lease is distinguishable from a first claim, that every refusal reason and failure outcome stays its own series including `retry_exhausted`, and that every dataset meter carries only a bounded `result` tag with no merchant, dataset, actor, lease or feature canary reachable.
- Adversarial evidence: Committed/rolled-back, current/stale checkpoint, known/unknown dependency exception, every closed exclusion family, multiple tenants/datasets, and secret canaries are covered.
- Status: PASS

## C51-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Dataset generation is bounded, leased, restart-safe, cancel-safe where cancellation is supported internally, and deployable through fresh and realistic retained migrations; worker-disabled, incompatible schema, stale checkpoint, and source-digest drift are visible and preserve all immutable work.
- Failure sequence: Restart loses the manifest, a queued run never relaunches, retention cleanup removes evidence needed for equivalence, database pressure causes unbounded scans, or a mixed-version worker writes incompatible results.
- Implementation: bounded Spring Batch job, durable queued-work scan, lease recovery, keyset reader, chunk and cohort limits, compatibility readiness, retained immutable manifests/results, and explicit worker switch will constrain operation.
- Proof: `OfflineReplayDatasetRestartIntegrationTests` proves the recovery half of this row on real PostgreSQL. It strands a dataset mid-run, asserts the durable backlog scan behind `OfflineReplayDatasetDispatcher.recoverBacklog` ignores it while its lease is live and returns it once expired, and that the resumed run reaches `SUCCEEDED` with one evidence row per source item — asserted by distinct `item_ordinal`, so a resumption that replayed a committed chunk would fail. Terminal datasets, both succeeded and failed, are asserted to leave the scan and stay gone after ageing, because expiry is a lease property and not a lifecycle one. Disabling the worker is asserted to leave the manifest `QUEUED` with no lease, no processed count and no evidence, with readiness reporting `DOWN`/`CREATION_ENABLED_WITHOUT_WORKER` for the half-enabled deployment that would otherwise silently accumulate work; re-enabling completes it. The cleanup partition is asserted against the database: `DELETE` on item evidence, results and chunks is refused by trigger, so retention tidying cannot remove what an equivalence comparison depends on. The retained V64 upgrade asserts prior rows survive and that no manifest, evidence, result, chunk or compatibility row is fabricated. Source drift is asserted **unconstructible** rather than simulated: every input `assertFrozenSource` compares — the manifest's cutoff and generation identity, bootstrap digest, evaluator and catalogue versions, the generation's `source_cutoff_digest`, and the cutoff manifest itself — is immutable by trigger, and all four rewrite attempts are refused. The process-restart half is now real: `OfflineReplayDatasetProcessRestartPostgreSqlTests` kills a complete application instance with its worker parked inside finalization — the point where a careless resume would either double-count evidence or publish aggregates that never matched their items — and asserts the next instance finds the dataset through the backlog scan and finalizes it with the same evidence rows, one per source item.
- Adversarial evidence: Fresh and retained schemas; queued, running, stranded-by-real-process-death, succeeded and failed work; configuration rollback via worker disable; cleanup attempt refused by trigger; source drift asserted unconstructible.
- Status: PASS

### Frozen Chapter 51 Boundary Table

| Boundary family | Valid partitions | Invalid partitions |
|---|---|---|
| Dataset identity | tenant, immutable cutoffs/versions/policies, stable manifest digest | mutable source query, changed manifest under one ID, cross-tenant input |
| Point-in-time label | available before or exactly at cutoff under closed resolution policy | future label, current-state collapse, hidden contradiction/supersession |
| Feature input | immutable decision-time feature lineage and declared catalogue | Redis, current RiskContext, current mutable control/profile lookup |
| Product output | safe manifest, lifecycle, coverage, aggregate results, digests | row export, raw payload/value, model prediction, activation permission |
| Compatibility | complete declared-family equivalence with Phase 4.5 simulation | count-only, partial, stale, or mismatch-tolerant comparison |
| Authorization | live same-merchant risk operator and actor-scoped replay | foreign dataset/cutoff, disabled actor, body-supplied tenant authority |
