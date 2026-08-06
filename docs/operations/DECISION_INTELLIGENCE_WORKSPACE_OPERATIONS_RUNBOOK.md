# Decision Intelligence Workspace Operations Runbook

- Scope: Phase 6 — the routed analyst workspace, the versioned public contract, and
  the supported Python sandbox SDK
- Owners: Risk Platform (workspace), Payments Core (contract and SDK), Risk
  Operations (queue and insights)
- Companion runbook: [`EVENT_PIPELINE_AND_PROJECTION_OPERATIONS_RUNBOOK.md`](EVENT_PIPELINE_AND_PROJECTION_OPERATIONS_RUNBOOK.md)

## The one rule that governs every procedure here

**Recovery never edits history.** Canonical events, original fraud decision
evidence, terminal bulk-action items, applied migration history, and audit rows are
immutable. Every procedure below either retries an operation under its existing
identity, fences a stale worker, or rebuilds a derived artifact. If a procedure
seems to require editing a committed row to make a number look right, the procedure
is wrong and the number is telling you something true.

The reason is not purity. A fraud decision is evidence about what the system knew at
a moment in time. An operator who edits it to match today's understanding destroys
the only record that could later explain — to a merchant, an auditor, or a court —
why a payment was blocked.

## 1. Reading the signals

All Phase 6 metrics use closed enums for capability, outcome and reason. None carry
merchant, operator, payment, review, view, cursor, credential, URL, amount, message
or exception text as a dimension. If you find yourself wanting to filter a dashboard
by payment id, that is by design: use the request id from the error envelope and the
audit log instead.

| Signal | Healthy | What a breach means |
|---|---|---|
| Investigation section status | `AVAILABLE` / `EMPTY` / `NOT_APPLICABLE` | Sustained `UNAVAILABLE` on one section means a dependency is down, not that the payment has no such facts |
| Source snapshot outcome | committed within the five-second bounded read | Timeouts mean the repeatable-read transaction is contending; check long-running queries before assuming a data problem |
| Projection freshness | `FRESH` | `PARTIAL` and `STALE` are honest states, not incidents. They become incidents when they persist past the projection runbook's thresholds |
| Bulk action backlog / oldest age | draining | A rising oldest-age with a flat succeeded count is the stuck-lease signature — see §3 |
| Insight not-computable reason | rare and explained | A spike usually means an empty denominator, which is a data-coverage question, not a bug |
| Contract drift gate | pass | Any failure blocks release; see §5 |

Readiness composes source database, active projections, and the bulk worker. One
unavailable optional insight does **not** make payment mutation readiness false — the
UI reports the affected slice instead. A readiness endpoint that reported "ready"
during a partial outage would be worse than no readiness endpoint.

## 2. An investigation section is unavailable

1. Read the section status and the request id from the response envelope. The
   envelope always carries both; the UI shows them in the slice's alert.
2. Decide which authority failed. Source sections, projection sections, webhook
   activity and notes read independently and declare their own as-of state, so a
   single failing section localises the fault.
3. If the source snapshot failed: check database contention and the five-second
   bounded read. Do not widen the timeout to make the page load — a longer read
   holds a repeatable-read snapshot open and makes contention worse.
4. If a projection section failed: follow the projection runbook. The workspace will
   keep serving source truth, correctly labelled, while the projection recovers.
5. Never present a failed section as empty. If a fix requires that, stop: rendering
   missing data as zero is the specific failure the section-status contract exists to
   prevent.

## 3. A bulk review assignment run is stuck

Symptoms: oldest-age climbing, terminal item count flat.

1. Read the request's durable state and its items. Item truth is authoritative —
   `SUCCEEDED`, `STALE`, `NOT_ELIGIBLE`, `NOT_FOUND`, `DENIED`, `FAILED`. The request
   status is derived from item truth, never the other way round.
2. If a worker died mid-run, its claimed item stays claimed until the lease expires.
   Wait for expiry. Takeover is fenced by lease token and epoch, so a stale worker
   that wakes up afterwards cannot finalize anything.
3. Do **not** update item rows to unblock a run. A terminal item is committed
   evidence that a source mutation, its event, and its audit entry either happened
   together or did not happen at all.
4. To retry failed or stale items, submit a **new** request with a new idempotency
   key and the items' current expected assignment versions. Completed items stay
   completed; retrying cannot duplicate an assignment event.
5. If the same items fail repeatedly, read the per-item failure code before
   escalating. `STALE` means someone else acted on the case — usually correct
   behaviour, not an error.

## 4. Insight numbers look wrong

Before treating this as a defect, check the three things that make an insight look
wrong while being right:

1. **Denominator.** Every rate publishes numerator, denominator and unknown count. A
   rate that "dropped" often has a denominator that grew. An empty denominator is
   `NOT_COMPUTABLE`, never 0%.
2. **As-of boundary.** Queue productivity reads authoritative review facts under
   `sourceAsOf`. Decision and outcome insights read the active projection generation
   with its own cutoff. These are different clocks on purpose; comparing them
   directly will look like a discrepancy and is not one.
3. **Origin and currency.** Live and synthetic traffic partition separately, and money
   never sums across currencies. A "missing" total is usually a second currency.

If a projection generation activated between two queries, the second read is against
a different generation and says so. Re-read rather than reconciling by hand.

## 5. The public contract or SDK drifts

1. Run `scripts/openapi-contract-check.sh`. It compares committed and runtime bytes,
   asserts credential and route boundaries, and lints.
2. Run `scripts/openapi-compatibility-check.py` and
   `scripts/openapi-breaking-change-check.sh` against the reviewed baseline.
3. If the committed artifact is stale, regenerate it from the running application:
   `mvn test -Dtest=VersionedPublicContractTests` writes
   `target/openapi/runtime-public-contract.json`, then
   `scripts/generate-openapi-contract.py --source …` freezes it.
4. **Regenerate the SDK in the same change**: `scripts/generate-python-sdk.sh`,
   then `scripts/python-sdk-regeneration-check.sh`. A contract change without a client
   regeneration is how `ER-0096` happened — the client silently disagreed with the
   server about which credentials existed, and nothing failed.
5. Never hand-edit a generated operation or model. The regeneration gate diffs the
   whole tree and will reject it, which is the point.

## 6. A developer reports the sandbox SDK failing

1. Confirm the client's contract digest matches the committed contract:
   `clients/python/thaap_sdk/thaap_sdk/generated/CONTRACT_SHA256`. A mismatch is §5.
2. Confirm the base URL is loopback. The client refuses non-loopback plain HTTP,
   userinfo, paths and query strings by design; that refusal is not a bug.
3. For an ambiguous mutation outcome, the client raises `ThaapTransportError` with
   `outcome_unknown=True`. The only safe recovery is replaying with the **identical**
   idempotency key and body. A new key on retry is a double charge.
4. For scenario operations, all six require a valid runner token in addition to the
   merchant key. Without it the traffic origin is `MERCHANT_API` and the call is
   refused. If a scenario call succeeded but the payment shows as live traffic,
   escalate — that is a data-integrity issue, not a client issue.
5. `scripts/python-sdk-package-check.py` reproduces a clean offline install if you
   suspect an environment problem rather than a client problem.

## 7. Migration recovery (V86–V91)

1. Migrations are forward-only and capability-named. There is no reverse migration
   and none should be written; a rollback plan that assumes Flyway can undo V86–V91
   is not a plan.
2. A failed migration leaves Flyway history recording the failure. Fix forward with a
   new migration. Never edit an applied migration file — the checksum guard exists
   because an edited applied migration means two deployments disagree about what the
   schema is.
3. Backfills derive only from existing immutable fields and fail closed on ambiguous
   tenant, source, identity, timestamp or count truth. A backfill that "mostly
   worked" has produced relationship anchors nobody can vouch for; investigate the
   ambiguity rather than relaxing the guard.
4. Fresh `V1→V91` and retained `V85→V91` are both proven in
   `DecisionIntelligencePostgreSqlMigrationTests` and
   `ReviewBulkAssignmentMigrationPostgreSqlTests`. Reproduce locally against real
   PostgreSQL before touching a shared environment.

## 8. Closing or reopening the phase

`scripts/phase-closure-check.py --phase-6` validates all seven Chapter 53–59
matrices and dossiers, the presence of every Phase 6 product artifact, the exact
completion marker in every source of truth, and that the committed SDK's stamped
digest equals the committed contract's digest.

It is a structural gate. It certifies that the repository is internally consistent;
it cannot certify that the evidence is true. The traceability pass and the temporally
separate hostile pass remain human obligations, and both are why `ER-0096`,
`ER-0097` and `ER-0098` were found at all.
