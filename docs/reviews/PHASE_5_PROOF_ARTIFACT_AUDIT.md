# Phase 5 Proof Artifact Audit

- Date: 2026-08-06
- Trigger: the `ER-0096` and `ER-0098` process correction, which required that every
  script named as proof in a chapter matrix have a recorded execution before the
  chapter closes. That correction was scoped to scripts. This audit asked the wider
  question it implies: does every artifact named as proof in any matrix exist at all?
- Escaped finding: `ER-0099`
- Registered debt: `TD-0007`, `TD-0008`

## Method

Every backticked test class and file reference in the `Proof` field of all 42 chapter
matrices was resolved against the repository. 59 references across seven chapters
resolved to nothing. Each was then classified by reading the chapter's real suites and
comparing what they assert against what the row promises. Where the audit could not
determine coverage from a suite's method names, the suite body was read; where a
concern depends on a mechanism (Micrometer meters, process restart, forced lock
ordering), the repository was searched for that mechanism directly rather than inferred
from naming, because inferring a boundary from a convenient surface is the specific
error recorded in `ER-0098`.

Chapters 19–35, 37–43R and 53–59 are clean: every artifact they name exists.

## Why the drift happened

Every Phase 5 matrix is `PRE_IMPLEMENTATION`. Its `Proof` fields are written in future
tense as obligations — "`ProjectionRebuildObservabilityTests` will inspect Micrometer
meters". Implementation then consolidated each domain into one contract class plus a
`*PostgreSqlTests` subclass that re-runs the same test bodies against real PostgreSQL
via Testcontainers. `Status` was flipped to `PASS` without rewriting `Proof` to name
what was built. Chapter 50 planned eleven suites and shipped seven test methods;
Chapter 46 planned ten and shipped 302 lines across two classes.

The consolidation is sound engineering — one body proven against both H2 and real
PostgreSQL is stronger than two divergent suites. The defect is that the matrix was
never updated to say so, so the durable traceability artifact points at names that
resolve to nothing.

## Classification

`COVERED` means the concern is genuinely held by a suite under a different name; the
`Proof` field has been reconciled to name it. `GAP` means no assertion of that concern
exists anywhere in the repository; the row now cites the debt that owns it.

### Chapter 36 — Merchant risk monitoring

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C36-PAY-001 | `MerchantRiskMonitoringDatabaseContractTests` | COVERED | `MerchantRiskMonitoringPostgreSqlTests` |
| C36-TEN-001 | `MerchantRiskMonitoringTenantIsolationTests` | COVERED | `MerchantRiskMonitoringPostgreSqlTests` — `snapshotIsManagerOnlyActorBoundImmutableAndNeverActivatesProfile` drives a second merchant |

### Chapter 44 — Canonical event contract and atomic outbox

Real suites: `CanonicalEventCodecTests` (8 tests), `DomainEventWriterContractTests`
(5), `DomainEventPostgreSqlTests` (4), `PlatformOperatorContractTests` (5),
`PlatformOperatorPostgreSqlTests` (4).

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C44-EVT-001 | `DomainEventFoundationScopeTests` | COVERED | `DomainEventWriterContractTests.writerRejectsCallsWithoutAnOwningDomainTransaction` |
| C44-ATO-001 | `DomainEventAtomicityPostgreSqlTests` | COVERED | `DomainEventWriterContractTests` — one-transaction commit and rollback-after-append |
| C44-STR-001 | `EventStreamVersionPostgreSqlConcurrencyTests` | COVERED | `DomainEventPostgreSqlTests` — both forced arrival orders |
| C44-CAN-001 | `DomainEventIntegrityPostgreSqlTests` | COVERED | `DomainEventPostgreSqlTests` — immutable history guards |
| C44-TEN-001 | `DomainEventTenantIsolationTests` | COVERED | `DomainEventWriterContractTests` — versions independent across tenants |
| C44-PLT-001 | `PlatformOperatorPostgreSqlConcurrencyTests` | COVERED | `PlatformOperatorPostgreSqlTests` — concurrent bootstrap, actor revalidation under lock |
| C44-LIN-001 | `DomainEventLineageTests`, `DomainEventMigrationPostgreSqlTests` | COVERED | `CanonicalEventCodecTests`, `DomainEventPostgreSqlTests` |
| C44-CAT-001 | `DomainEventCatalogueTests` | COVERED | `CanonicalEventCodecTests.frozenCatalogueHasExactlyOneSchemaForEveryExactWireType` |
| C44-OPS-001 | `DomainEventMigrationPostgreSqlTests` | COVERED | `DomainEventPostgreSqlTests` — fresh and retained V64 |
| **C44-OBS-001** | `DomainEventObservabilityTests` | **GAP** | none — no meter or log-canary assertion exists for the event foundation (`TD-0007`) |

### Chapter 45 — Payment domain events

Real suites: `PaymentDomainEventServiceTests` (3), `PaymentDomainEventRollbackTests`
(5), `PaymentEventObservabilityTests` (3).

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C45-PAY-001 | `PaymentDomainEventContractTests` | COVERED | `PaymentDomainEventServiceTests` |
| C45-MNY-001 | `PaymentMoneyEventPostgreSqlTests` | COVERED | `PaymentDomainEventRollbackTests` — capture and refund failure paths |
| C45-TEN-001 | `PaymentDomainEventTenantIsolationTests` | COVERED | `PaymentDomainEventServiceTests.mapperRejectsCrossTenantLineage…` |
| C45-SEC-001 | `PaymentEventPrivacyTests` | COVERED | `PaymentDomainEventServiceTests` — allowlisted canonical schemas |
| C45-DATA-001 | `PaymentEventLineageTests` | COVERED | `PaymentDomainEventServiceTests` — safe lineage preserved |
| C45-CAT-001 | `PaymentEventCatalogueTests` | COVERED | `PaymentDomainEventServiceTests` |
| C45-CON-001 | `PaymentEventPostgreSqlConcurrencyTests` | COVERED | `PaymentIntentCaptureCancelPostgreSqlConcurrencyTests` asserts durable event postconditions; note `RefundPostgreSqlConcurrencyTests` does not, so the refund half of the row rests on `PaymentDomainEventRollbackTests` alone |
| **C45-OPS-001** | `PaymentEventDeploymentPostgreSqlTests` | **GAP** | none — no mixed producer-version, enable/disable restart or Kafka-unavailable test (`TD-0007`) |

### Chapter 46 — Risk operations events

Real suites: `RiskOperationsEventProducerTests` (2 tests, 189 lines),
`RiskOperationsDomainEventRollbackTests` (2 tests, 113 lines). Ten planned suites
became 302 lines. This is the thinnest chapter in Phase 5.

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C46-FRD-001 | `RiskOperationsDomainEventContractTests` | COVERED | `RiskOperationsEventProducerTests` |
| C46-CAT-001 | `RiskOperationsEventCatalogueTests` | COVERED | `RiskOperationsEventProducerTests.everyRiskPlatformCatalogueFactProducesOneSchemaValidTenantQualifiedCommand` |
| C46-TEN-001 | `RiskOperationsEventTenantIsolationTests` | COVERED | same — tenant-qualified command assertion |
| C46-AUD-001 | `RiskOperationsAuditCompletenessTests` | COVERED | `RiskOperationsDomainEventRollbackTests` — source, audit, stream and publication roll back together |
| **C46-AUTH-001** | `RiskOperationsEventAuthorizationTests` | **GAP** | none — no role-matrix or disable-after-authentication test for event producers (`TD-0007`) |
| **C46-CON-001** | `RiskOperationsEventPostgreSqlConcurrencyTests` | **GAP** | none — no forced ordering with event postconditions (`TD-0007`) |
| **C46-DATA-001** | `RiskOperationsEventLineageTests` | **GAP** | none (`TD-0007`) |
| **C46-SEC-001** | `RiskOperationsEventPrivacyTests` | **GAP** | none — no prohibited-value canaries (`TD-0007`) |
| **C46-OBS-001** | `RiskOperationsEventObservabilityTests` | **GAP** | none (`TD-0007`) |
| **C46-OPS-001** | `RiskOperationsEventDeploymentPostgreSqlTests` | **GAP** | none (`TD-0007`) |

### Chapter 50 — Projection rebuild

Real suites: `ProjectionRebuildControlTests` (7 tests) and
`ProjectionRebuildPostgreSqlTests`, which extends it against real PostgreSQL. The
`ProjectionConsumer*` suites are Chapter 49's and do not cover rebuild.

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C50-PAY-001 | `ProjectionRebuildSourceNonInterferencePostgreSqlTests` | COVERED | `ProjectionRebuildPostgreSqlTests` — `sourceCounts()` asserted equal across start, reconcile, activate and rollback |
| C50-TEN-001 | `ProjectionRebuildAuthorizationContractTests`, `ProjectionRebuildPostgreSqlConcurrencyTests` | COVERED | `ProjectionRebuildControlTests.cancellationIsFencedAndAdministratorCannotOperateDataPlane` proves `PLATFORM_ADMIN` is forbidden; merchant roles, disabled credentials and copied foreign IDs are not covered |
| C50-DATA-001 | `ProjectionRebuildCutoffPostgreSqlTests` | COVERED | `ProjectionRebuildPostgreSqlTests` — bootstrap manifest reconciliation |
| C50-DATA-002 | `ProjectionReconciliationPostgreSqlTests` | COVERED | `ProjectionRebuildPostgreSqlTests` — five reconciliation invariants recorded `PASS` |
| C50-API-001 | `ProjectionRebuildApiContractTests` | COVERED | `ProjectionRebuildControlTests` — exact and conflicting replay; note these are service-level, not HTTP-level |
| **C50-SEC-001** | `ProjectionRebuildPrivacyContractTests` | **GAP** | none — no canary seeded or sink scanned (`TD-0007`) |
| **C50-CON-001** | `ProjectionRebuildLeasePostgreSqlConcurrencyTests` | **GAP** | chunking is proven; lease expiry, takeover and stale finalization are not, though production emits a `LEASED` event (`TD-0007`) |
| **C50-CON-002** | `ProjectionGenerationActivationPostgreSqlConcurrencyTests` | **GAP** | the activate/rollback path is proven; no forced concurrent ordering (`TD-0007`) |
| **C50-OBS-001** | `ProjectionRebuildObservabilityTests` | **GAP** | none — a repository-wide `MeterRegistry` search returns no rebuild test (`TD-0007`) |
| **C50-OPS-001** | `ProjectionRebuildRestartIntegrationTests` | **GAP** | none — no process restart across a chunk or activation boundary (`TD-0007`) |

### Chapter 51 — Offline replay datasets

Real suites: `OfflineReplayDatasetIntegrationTests` (4 tests) and
`OfflineReplayDatasetPostgreSqlTests`, which extends it.

| Row | Planned artifact | Verdict | Real artifact |
|---|---|---|---|
| C51-DATA-001 | `OfflineReplayDatasetLineagePostgreSqlTests` | COVERED | `OfflineReplayDatasetPostgreSqlTests.sameTimestampDecisionCannotBorrowAdjacentFraudEventCutoffMembership` |
| C51-DATA-002 | `OfflineReplayPointInTimePostgreSqlTests` | COVERED | `…exactLabelCutoffIsIncludedAndFutureLabelDoesNotLeak` |
| C51-DATA-003 | `OfflineReplaySimulationCompatibilityPostgreSqlTests` | COVERED | `…governedSimulationOverlapMustMatchEveryDeclaredFamily` |
| C51-API-001 | `OfflineReplayDatasetApiContractTests` | COVERED | `…apiIsActorIdempotentRoleBoundAndTenantOpaque` |
| C51-TEN-001 | `OfflineReplayDatasetTenantIsolationTests` | COVERED | same — role partitions and tenant opacity |
| C51-SEC-001 | `OfflineReplayDatasetPrivacyContractTests` | COVERED | same — asserts the response excludes a seeded PAN and contact address |
| **C51-PAY-001** | `OfflineReplayDatasetSourceNonInterferencePostgreSqlTests` | **GAP** | none — no before/after source digest across create, resume and read (`TD-0007`) |
| **C51-CON-001** | `OfflineReplayDatasetPostgreSqlConcurrencyTests` | **GAP** | none — no forced creator/worker ordering or lease takeover (`TD-0007`) |
| **C51-OBS-001** | `OfflineReplayDatasetObservabilityTests` | **GAP** | none (`TD-0007`) |
| **C51-OPS-001** | `OfflineReplayDatasetRestartIntegrationTests` | **GAP** | none (`TD-0007`) |

### Chapter 52 — Phase 5 integrated closure

No `PhaseFive*` artifact exists under any name; the only `Phase*` test in the
repository is `Phase6Workspace.test.tsx`. The Chapter 52 closure dossier names no test
class — its `Exact proof` section is an aggregate count of 854 backend tests. All six
cross-cutting rows are `GAP` and carry `TD-0008`.

| Row | Planned artifact | Verdict |
|---|---|---|
| C52-OPS-002 | `phase_five_closure_check_tests.py` | COVERED — the substance is in `scripts/phase_closure_check_tests.py`, which passes `--phase-5` |
| **C52-PAY-001** | `PhaseFiveSourceNonInterferenceIntegrationTests` | **GAP** (`TD-0008`) |
| **C52-TEN-001** | `PhaseFiveTenantIsolationIntegrationTests` | **GAP** (`TD-0008`) |
| **C52-SEC-001** | `PhaseFivePrivacySinkIntegrationTests` | **GAP** (`TD-0008`) |
| **C52-CON-001** | `PhaseFiveFailureSequenceIntegrationTests` | **GAP** (`TD-0008`) |
| **C52-DATA-001** | `PhaseFiveLineageTraceabilityIntegrationTests` | **GAP** (`TD-0008`) |
| **C52-OBS-001** | `PhaseFiveOperationalSignalsIntegrationTests` | **GAP** (`TD-0008`) |

## Totals

| | Rows |
|---|---|
| COVERED — reconciled to the suite that holds them | 36 |
| GAP — no assertion exists, registered as `TD-0007` | 17 |
| GAP — no assertion exists, registered as `TD-0008` | 6 |
| **Total unresolved references found** | **59** |

## What this does and does not mean

It does not mean Phase 5 is broken. No suite contradicts a closed row, the source-truth
and correctness rows these sit beside are proven, and the 36 `COVERED` rows were held
all along under different names. The Phase 5 demo ran live and reported exact evidence.

It does mean the adversarial half of Phase 5's proof — the canary scan, the forced lease
takeover, the restart boundary, the meter assertion, the two-tenant cross-cutting
composition — is largely unbuilt, and that this was invisible because the gate checked
that a `Proof` field was non-empty rather than that it was true. Every escaped finding
in Phases 4 and 6 was found in exactly the territory these missing suites cover.

## The remaining structural weakness

Every `GAP` row above is still `Status: PASS`, and says so beside a sentence admitting
the suite it promised was never written. That reads as a contradiction because it is
one. `scripts/invariant-proof-check.py` requires every row to be `PASS`, so a matrix has
no vocabulary for "this invariant is held on the ordinary path and unproven
adversarially" — the only expressible states are closed and absent. That is why the
condition survived: a reviewer who noticed a thin row had nowhere to record it short of
blocking the chapter, and blocking a merged phase is not a move anyone makes at the end
of a closure pass.

The `TD-NNNN` citation is a workaround, not a fix. It makes the gap visible and owned,
which is a real improvement over silence, but the row still counts as closed in every
aggregate. A durable fix would add a status such as `PARTIAL` that carries a mandatory
debt reference and is rejected at phase closure rather than at chapter closure, so a
chapter can proceed honestly while a phase cannot complete on partial proof. That change
is not made here: it alters the meaning of every one of the 511 invariant rows in the
repository and belongs in its own reviewed change, not inside an audit.

## Enforcement added

`scripts/invariant-proof-check.py` now resolves every artifact a `Proof` field names and
fails closed on any that does not exist. The only exemption is an explicit `TD-NNNN`
citation, which moves the gap into a register that `scripts/technical-debt-check.py`
holds to an owner, a trigger and done criteria. A row can no longer close on a name.
