# Chapter 60R Local Release Repair Invariant Proof Matrix

- Chapter: Chapter 60R — Local Release Boundary Repair
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

Frozen on 2026-10-06 after inspecting existing artifacts and before repair implementation.
This is pre-implementation provenance for the repairs only, not retroactive certification of
Chapters 60–63. The integrated phase matrix remains authoritative and PENDING for AWS proof.
No account, domain, deployed task, hosted demo, backup restore or alarm delivery is certified here.
ER-0109 records the escaped local-release defects; ER-0106 still owns live exposure proof.

Scope reopened before ER-0118 implementation on 2026-10-06: the existing frozen local
promises now use recovery coordinate 60R, not integrated cloud-closure Chapter 66.
This is a coordinate correction, not new pre-implementation provenance for old repairs.
Add machine-enforced current-scope selection so CI/pre-push cannot validate only historical
matrices or omit a non-trivial dossier. Chapter 66 and the integrated phase remain open.

Scope reopened before ER-0119 implementation on 2026-10-06: governance review dates must
use the repository's India calendar on every host. Freeze the midnight UTC/India rollover,
month/year/leap boundaries, same-day/future/stale partitions, CLI/default-validation paths
and explicit historical replay. A timezone repair must not waive future dates or the 35-day
review limit, and must not change payment/event-time UTC semantics.

Scope reopened before ER-0120 implementation: bulk/single assignment contention must prove
both commit orders and actor-role partitions on PostgreSQL. Managers lose as STALE; analysts
lose non-disclosingly as NOT_FOUND. Both contenders must be observed waiting, with exactly
one version increment/event and no stolen assignment. Do not relax production authorization.

Scope reopened on 2026-10-06 before the ER-0110 repair: exact demo catalogue assertions
must reflect the current allowlisted typed features, while still rejecting missing/extra features,
wrong values and post-decision observations. This is a repair of proof, not a feature-schema change.

Scope reopened again before ER-0111 repair: isolated demo cohorts must not accidentally trigger
the production velocity control while intending to demonstrate a distinct repeated-attempt rule.
The control remains enabled; fixture identities, not risk policy, change.

Scope reopened before ER-0112 repair: governed-control activation must satisfy the unchanged
default minimum population (100) and label coverage (50%) through real public workflow evidence;
the retained one-row, unlabelled demo must not lower the gate or write artificial database facts.

Scope reopened before ER-0113 repair: bulk-assignment fixture selection must consume the actual
review-list envelope and reject missing, malformed or ambiguous case/version identities.

Scope reopened before ER-0114 repair: workspace insights must wait for the asynchronous
projection's published coverage, then replay the complete drill-through descriptor, rather
than confuse an empty catching-up projection with a broken payment journey.

ER-0114 boundary correction after reproduction: the actual insight contract deliberately
excludes the current incomplete UTC day. Fresh coverage therefore legitimately returns no
segments for newly created demo payments. Preserve this policy and explicitly report the
empty completed-day window; do not falsify event timestamps or claim positive drill proof.

Scope reopened before ER-0115 repair: processor-catalogue evidence must name the current
deterministic ruleset, isolate customer velocity, and reconcile the selected proof database.

Scope reopened before ER-0116 repair: a scenario-scoped report must contain only that run's
synthetic attempts; a separate live reference is proved by its own source response, not mixed
into the scenario report. Sequential CI journeys wait for top-level health between fixtures.

## RELEASE-PAY-001

Scope reopened before ER-0117 repair: the pinned broker's observed default 1 GiB heap
cannot consume its entire 1 GiB ECS budget; bound heap to 512 MiB and prove replacement
under the same Docker memory ceiling. This is not an AWS sizing/HA certification.

- Lens: PAYMENT_FRAUD
- Invariant: Packaging and routing repairs do not alter payment, fraud, ledger or analyst decision truth.
- Failure sequence: A packaging repair changes application behavior or skips a production-database proof.
- Implementation: Limit changes to release/deployment boundaries, configuration and proof artifacts.
- Proof: Full backend suite against available Docker/PostgreSQL; real container stack and payment-risk demo.
- Assignment contention repair: ER-0120 forces analyst/manager and bulk-first/single-first partitions, observing one then two PostgreSQL lock waiters. Each exact winner, version 1, single event, paired HTTP/item status and failure code is asserted. Ten focused tests pass; restoring the erroneous universal STALE expectation deterministically fails the analyst/single-first partition (four cases, exactly one failure, exit 1), then the correction is restored before full readiness.
- Adversarial evidence: Backend 1457/0 failures/0 errors/0 skips; real fresh seven-journey entry point exits 0 with default policy and public mutation/read-only reconciliation.
- Status: PASS

## READINESS-GOV-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Every ordinary readiness invocation validates the registered current matrix and dossier; a complete local repair cannot be mistaken for an incomplete cloud chapter or certify it by omission.
- Failure sequence: CI/pre-push call readiness without selectors and silently skip the current dossier; an explicit stale pair replaces it; malformed/duplicate-key/escaping/symlinked scope paths point outside the repository; local repairs claim Chapter 66 completion without cloud evidence.
- Implementation: Versioned current-scope manifest, strict selector/path/date validation and automatic resolution in the existing readiness gate; recovery coordinate 60R in README/master plan while Chapters 63–66 remain open. Shared aware-instant India review calendar for vision and debt default CLI/validation paths.
- Proof: `scripts/readiness_scope_check_tests.py`, `scripts/commit_readiness_check_tests.py`, `scripts/chapter_closure_check_tests.py`, full named readiness plus the exact no-selector pre-push command. ER-0119 adds `scripts/review_calendar_tests.py` and both governance checkers' default/future/stale tests, including real CLI subprocesses at the exact failed CI instant on Python 3.12/Linux/UTC.
- Adversarial evidence: Missing/invalid manifests and selectors fail before any success claim; real pending/dossier/source contradictions remain rejected (no-selector exit 1 before closure). After closure, full named readiness and ordinary no-selector readiness both exit 0 and enforce the registered pair plus inherited Phase 6 proof. No hook bypass, Git publication or AWS mutation is authorized by this repair.
- Status: PASS

## RELEASE-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: The isolated proof stack cannot collide with or tear down another compose project's containers or volumes; credential headers survive routing without defaults or tenant substitution.
- Failure sequence: Explicit container names collide across projects; cleanup runs on an existing project; router replaces credentials or returns another surface.
- Implementation: Project-scoped resource names, preflight ownership checks and untouched credential forwarding.
- Proof: Compose configuration isolation tests and real parallel-project namespace inspection; backend tenant tests.
- Adversarial evidence: Existing container, existing volume and network partitions must refuse before cleanup registration; invalid credentials stay API errors.
- Status: PASS

## RELEASE-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: WAF rate blocking precedes terminating allow; trusted proxy metadata is rebuilt at the first trusted hop and no viewer-supplied forwarding header controls client identity.
- Failure sequence: Allow short-circuits rate evaluation; an allowlisted viewer sends forged Forwarded/X-Forwarded headers through CloudFront or nginx.
- Implementation: Reorder WAF rules; sanitize forwarding headers at CloudFront viewer request and nginx; retain secret origin gate and security groups.
- Proof: Executable edge-function partition tests, Terraform mocked-plan assertions, nginx HTTP probes and backend regression suite.
- Adversarial evidence: Host/URI/query/credential representations remain untouched; forged IPv4/IPv6/port/prefix/proto metadata cannot survive the trusted-hop sanitizer. Mocked plans are not deployed WAF proof.
- Status: PASS

## RELEASE-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Certificate validation resource keys are plan-known; same-hostname certificates in two regions share one DNS record without duplicate-key failure.
- Failure sequence: ACM computes unknown record names at plan time; identical regional validation records become duplicate map keys; broker/service starts before storage or execution-role grants exist.
- Implementation: One statically addressed DNS record for the single certificate hostname, plus explicit service dependencies.
- Proof: Terraform plan tests with mocked computed attributes and duplicate regional DNS results; existing PostgreSQL forced-interleaving suites remain required.
- Adversarial evidence: Exercise default and second region plans; do not claim mocked AWS resources certify database or real cloud concurrency.
- Status: PASS

## RELEASE-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Kafka writes to the mounted persistent log directory; replacing its container with the same volume preserves topic records and consumed offsets.
- Failure sequence: Broker writes to its image-default temporary directory despite an unused mounted volume; task replacement loses events or replays consumer offsets; a demo still expects an obsolete seven-feature snapshot and fails against current legitimate typed lineage.
- Implementation: Explicit log directory and immutable broker image in compose and Terraform; stop-before-start single-broker deployment.
- Proof: Real broker replacement with retained volume, record and committed-offset assertions; topology configuration tests.
- Adversarial evidence: Container removal, not a process-only restart; actual 512 MiB heap under a 1 GiB Docker memory ceiling preserves records and offsets. Local volume proof is not EFS recovery/partition or highly available Kafka proof.
- Status: PASS

## RELEASE-API-001

- Lens: API_PRODUCT
- Invariant: Bare/nested API paths and operational HTTP paths route to API; real 403/404 remain errors, SPA deep links load shell, missing static assets do not become HTML success.
- Failure sequence: Distribution-wide error rewrite turns tenant/auth/API errors into 200 HTML; operational routes fall into S3; prefix-lookalike names accidentally reach API; smoke accepts any non-502 status.
- Implementation: Request-time SPA rewrite restricted to shell routes; generated API/operational behaviors; exact prefix boundaries; body-aware smoke checks.
- Proof: Node edge-function tests, UI router tests, Terraform behavior assertions and real nginx/API HTTP comparisons.
- Adversarial evidence: Test exact, nested, lookalike, query, denied, missing, asset and deep-link partitions; maintain caches disabled on credentialed APIs.
- Status: PASS

## RELEASE-OBS-001

- Lens: OBSERVABILITY
- Invariant: Local smoke observes top-level readiness and real API error/content-type, exact migration history and runtime identity honestly; target application 5xx is not invisible to infrastructure alarm configuration.
- Failure sequence: Smoke accepts SPA 200, nested UP inside DOWN, stale baseline history or dirty-tree commit-only identity; alarm observes ALB-generated 5xx but not target 5xx.
- Implementation: Strong smoke assertions, dirty build marker and application-target 5xx alarm.
- Proof: Body/content-type and top-level health predicates, real stack HTTP/Flyway checks and mocked-plan alarm assertions; inter-journey bounded health recovery is executed on the real fresh-stack CI path.
- Adversarial evidence: An alarm configuration is not notification delivery proof; projection-lag and task-restart operational work remains owned by Chapter 65.
- Status: PASS

## RELEASE-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: First-apply secret ordering is executable without starting unconfigured API tasks; release publish opt-in is honored and image architecture agrees with ECS.
- Failure sequence: Populate secret entries before they exist; CI builds amd64 and ECS requests ARM64; manual publish=false still pushes; mutable images silently replace provenance.
- Implementation: Explicit zero-task foundation stage, matching build/runtime architecture and publish decision guard; pin Kafka image digest.
- Proof: Terraform staged-plan tests, release workflow regression tests, format/validate on both roots and full repository gates.
- Adversarial evidence: Dirty-tree release gate is not bypassed; no commit, push, tag or AWS mutation in this audit. Real AWS deploy, no-drift plan, all hosted demos and operations rehearsals remain PENDING.
- Status: PASS
