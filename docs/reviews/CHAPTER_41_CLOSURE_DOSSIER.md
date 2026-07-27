# Chapter 41 Closure Dossier

- Chapter: Chapter 41 — Developer Console And Trusted Scenario Operations
- Chapter number: 41
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_41_INVARIANT_PROOF_MATRIX.md
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

- A separately authenticated runner—not merchant metadata or a test card—assigns synthetic traffic origin.
- Versioned seed/specification yields a deterministic 4- or 10-step plan, stable child idempotency keys, resumable lease-fenced progress, and one sanitized terminal report derived only from that run's verified resources.
- The public catalogue contains exactly nine simulator-only cards; the server verifies every customer, method, intent, attempt, processor outcome, and fraud lineage row before advancing progress.
- The developer console stores credentials in memory only, executes a closed relative-path operation set, and renders durable server state rather than browser-invented progress.
- A zero-source-reading executable client rejects unsafe origins, proxies, redirects, paths, and slow responses, then reconciles merchant and synthetic attempts through public APIs.

## Enforcement boundaries

| Promise | Real boundary | Evidence |
|---|---|---|
| Trusted origin | merchant authentication plus separate runner token; server-assigned `MerchantContext` | ordinary merchant 403, synthetic persisted lineage, live/synthetic demo |
| Immutable plan | V61 spec/seed digests, ordered step rows, stable derived keys | golden digests, same/different-seed contract, V62 triggers |
| One worker | run-row lock, lease owner/epoch/expiry, monotonic checkpoint | forced PostgreSQL waiter, expiry takeover, stale-worker conflict |
| Verified progress | tenant-qualified resource queries and exact fixture/outcome/lineage predicates | foreign-resource conflict, 4-step approval, 10-step card-testing execution |
| Terminal evidence | run-owned verified resource graph, canonical report bytes plus SHA-256 digest, closed failure code | unrelated same-tenant runs before/after target, exact digest recomputation, exact/conflicting replay, one failure event |
| Client/browser safety | loopback fixed origin, empty proxy handler, redirect denial, closed paths, bounded timeout, memory state | five Python safety tests and six UI tests |

## Failure inventory

The frozen inventory covered origin forgery, tenant substitution, test/live-card
confusion, seed drift, stale/concurrent ownership, crash and retry boundaries,
duplicate or out-of-order steps, terminal failure replay, unsafe proxies,
redirects and URLs, slow dependencies, browser persistence, report/metric
leakage, disabled creation, retained migrations, and monitoring contamination.
The recovered family also covers same-tenant attempts unrelated to the target
run before and after its terminal report.

## Boundary family evidence

Valid and invalid origin, seed, scenario type, resource, tenant, ownership,
checkpoint, failure, report, client-origin, redirect, timeout, browser-storage,
metric, readiness, and migration partitions reconcile with the frozen table.
The hostile pass pulled the full card-testing and failure families into exact
contract tests and added actual redirect/timeout exchanges before closure.
`ER-0070` later reopened report ownership and added unrelated-run partitions
before and after terminalization.

## Migration evidence

- Fresh H2 and PostgreSQL schemas validate and apply all 62 migrations.
- `PostgreSqlMigrationTests` proves V61/V62 tables, columns, indexes, composite constraints, and three protection-trigger families.
- The retained local database upgraded in place from V60 to V62. Existing payment, fraud, dispute, and Phase 4.5 state remained intact, and startup created no scenario run.

## Exact proof

- `DeveloperScenarioContractTests` covers the public catalogue, runner authority, deterministic deduplication, tenant/resource verification, approved and card-testing execution, canonical report digest, exact/conflicting completion replay, terminal failure replay, readiness, and metric privacy.
- `terminalReportAndDigestAreBoundOnlyToAttemptsOwnedByTheExactRun` proves a
  target report counts only its one verified synthetic attempt when unrelated
  same-tenant runs exist before and after it, and that its canonical body and
  digest remain unchanged. The focused developer-scenario contract run passed
  `9/9`.
- `DeveloperScenarioConfigurationTests` proves fail-closed creation disablement and the 5-second-to-5-minute lease bound.
- `DeveloperScenarioPostgreSqlConcurrencyTests` observes a contender waiting on the run row, proves one live owner, advances the expired lease epoch, rejects the stale worker, and proves event/run history triggers.
- Full regression: `728` backend tests and `134` repository Python tests passed with zero failures, errors, or skips; `6` UI tests and the TypeScript/Vite production build passed.

## Demo evidence

- Five executable-client safety tests cover loopback origin grammar, closed step paths, ambient proxy exclusion, a live cross-origin redirect, a live slow-response timeout, and missing credentials before network use.
- Public live demo result: `SUCCEEDED`, `4/4` verified steps, report digest `e5f605a0b190be305678a22658e39ddad385c89926f67f5bf598989334c21380`.
- Live reconciliation result: `MERCHANT_API:1|SYNTHETIC_SCENARIO:1`; catalogue `developer_scenarios_v1` exposed nine explicitly simulator-only cards.
- Credentials came only from environment variables, were never printed, and the returned run/report was scanned for API key, runner token, and raw seed canaries.

## Hostile review

- The initial happy-path proof did not exercise the 10-step `CARD_TESTING_BURST` family; the family was reopened and now proves three distinct governed declines and exact durable counts.
- Terminal failure had implementation but no exact-replay evidence; a contract now proves same-code replay, different-code conflict, no progress, no report, and one failure event.
- Report digest presence alone was weak proof; the contract now hashes exact stored canonical bytes and compares the API document.
- Client handler inspection alone was weak redirect/timeout proof; real local 302 and slow-response exchanges now fail closed.
- Live same-owner lease renewal was kept event-bounded, while a new owner must advance the epoch; the PostgreSQL interleaving suite was rerun after that repair.

## Operational signals

- `developerScenarios` joins readiness and fails on unavailable schema, incompatible run catalogue version, or a fixture catalogue other than nine entries.
- `THAAP_DEVELOPER_SCENARIO_CREATION_ENABLED` is a fail-closed operational switch; `THAAP_DEVELOPER_SCENARIO_LEASE_DURATION` is startup-bounded from 5 seconds through 5 minutes.
- Metrics are transaction-aware and expose only `operation`, `scenario_type`, `outcome`, and `transaction`; privacy canaries prove seed, API key, merchant UUID, card, resource, and credential values are absent.
- Immutable events distinguish create, claim, verified step, success, and closed failure without retaining raw request or error content.

## Custom infrastructure decision

`NOT_APPLICABLE`. The slice uses Spring transactions, PostgreSQL row locking,
existing merchant/runner authentication, existing public payment APIs, React,
and Python standard-library HTTP. It adds no queue, workflow engine, scripting
runtime, browser persistence layer, or server-side outbound client.

## Escaped-review assessment

`ER-0070` escaped the prior Chapter 41 completion claim. Tenant-qualified
aggregation was mistaken for run-owned aggregation, allowing unrelated
same-tenant attempts to contaminate a terminal report. The query now reaches
attempts only through the target run's verified step-resource graph, and the
focused contract covers unrelated traffic both before and after completion.
The process correction is to require an explicit ownership path for every
terminal-report aggregate, not merely a tenant predicate.

## Post-closure recovery

The chapter was reopened while `ER-0070` was unresolved. The remediation did
not delete or relabel any payment attempt; it corrected the report cohort to
the immutable resource graph owned by the run. Focused proof shows both
run-local origin counts and post-completion digest stability, so the original
determinism promise is once again supported and the chapter-level
recommendation returns to `MERGE`.

## Known debt

No Chapter 41 debt is accepted. Remote arbitrary URLs, live card processing,
external workflow orchestration, free-form scenario scripts, and production
hosted credential storage are rejected scope rather than hidden deferrals.

## Source-of-truth reconciliation

The Phase 4.5 plan, implementation master plan, README, daily note, weekly
review, proof matrix, and this dossier identify Chapter 41 as complete and
Chapter 42 webhook operations as the next dependency-ready slice.

## Merge recommendation

`MERGE`. Every frozen Chapter 41 matrix entry is `PASS`; fresh and retained
database proof, production PostgreSQL ownership/history proof, exact API and
client security boundaries, UI build, public live demo, hostile review, and
operational evidence are green.
