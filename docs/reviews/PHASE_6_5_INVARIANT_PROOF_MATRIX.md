# Phase 6.5 Invariant Proof Matrix

- Chapter: Chapter 66 — Phase 6.5 integrated closure
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: PENDING

This matrix was drafted and frozen on 2026-08-08, before any Terraform, deployment, seed or
runbook existed, and committed in that state so that it can be reviewed as a plan rather than
recognised as a description. Every row is `PENDING`. Chapter 66 closes them.

**Why it is a phase matrix.** Section 13.1 of the Phase 6.5 plan replaced seven per-chapter
matrices with one, on the grounds that a phase adding no migration, no schema, no API and no
payment behaviour change would otherwise produce seven near-identical documents. That decision was
sound and incompletely specified: it never said *when* the phase matrix is drafted, and a matrix
written at Chapter 66 covering work finished in Chapters 63–65 could not honestly carry
`PRE_IMPLEMENTATION`. This document is that correction, recorded as `ER-0105`. It is filed under
Chapter 66 because Chapter 66 is the chapter that closes it; the rows carry the chapter that owns
each one.

**What it covers.** Chapters 63, 64 and 65 — infrastructure, first deploy, and operations. Chapter
66 is closure ceremony and introduces no product invariant of its own; its obligation is to close
every row below on executed evidence and to fold in
`docs/reviews/CHAPTER_60_INVARIANT_PROOF_MATRIX.md`.

**What it does not cover.** Chapters 61 and 62 shipped before this correction existed, under
Section 13.1 as originally written, and therefore have no pre-implementation matrix. Their evidence
is real and recorded — executed gate runs in `docs/PHASE_6_5_DETAILED_IMPLEMENTATION_PLAN.md`
Section 14 and `docs/learning/daily/2026-08-08.md` §12–§20 — but it was written after the fact and
must be presented that way. `ER-0105` records this rather than absorbing it, and the Chapter 66
dossier must state it in the same terms rather than folding those two chapters in silently.

**Failure inventory mapping.** Section 11 of the plan lists twelve pre-implementation failure
sequences. Items 1–4 were closed by Chapters 60–62. Items 5–12 are owned here: migration race
(`C64-CON-001`), lease duplication (`C64-CON-001`), routing drift (`C63-API-001`), readiness
dishonesty (`C64-OBS-001`), broker loss (`C65-OPS-002`), restore fiction (`C65-DATA-001`), cost
runaway (`C65-OBS-002`), and credential leakage into an artifact (closed by `C60-TEN-001`, re-proven
against the deployed task role by `C63-SEC-001`).

## C63-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: "Private and invite-only" is enforced by a mechanism, not by nobody knowing the hostname. An unauthenticated request from an address outside the operator allowlist is refused before it reaches any THAAP surface, including the workspace shell, the actuator endpoints and the OpenAPI document — and the load balancer's own DNS name is not a way around that.
- Failure sequence: ACM issues the certificate; the hostname is published to Certificate Transparency logs within minutes, because CT logging cannot be disabled without making the certificate untrusted in every browser; a CT-stream scanner reaches the URL before the first design partner does. Separately, an operator who knows only the CloudFront hostname discovers the ALB's `*.elb.amazonaws.com` name from a redirect, a certificate SAN or a header, and reaches the API directly with the edge control bypassed entirely. A third path: the tasks run in public subnets with no NAT Gateway, so a task with a public IP is itself addressable if a security group rule is ever widened.
- Implementation: A WAF WebACL associated with the CloudFront distribution, default action `BLOCK`, admitting an IP set of operator addresses. The ALB accepts only requests carrying a shared secret header injected by CloudFront and held in Secrets Manager, so the origin is not reachable by its own name. Task security groups admit the ALB security group alone, by group reference rather than by CIDR. This is also what makes `server.forward-headers-strategy: framework` — set under Chapter 62 — safe rather than a way to spoof a client IP past the rate limiter.
- Proof: `scripts/deployed-exposure-check.sh` will request the workspace root, `/actuator/health`, `/actuator/info` and one routed API prefix from a non-allowlisted address and require `403` from the edge on every one; repeat from an allowlisted address and require the application to answer; request the ALB DNS name directly without the shared header and require refusal; and resolve every task's public IP and require the API port closed from outside the ALB security group.
- Adversarial evidence: The check must run from an address that is genuinely not on the allowlist, not from a shell inside the VPC, because a request that never leaves the network proves nothing about the edge. The ALB-bypass case is the one that matters most and is the one a WAF-only implementation silently fails: the WebACL is attached to CloudFront, so an origin reachable by its own hostname is protected by nothing. The header value must be read from Secrets Manager rather than committed, and rotating it must be provable without downtime.
- Status: PENDING

## C63-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: The entire topology is defined in version control and rebuildable from an empty account. Nothing load-bearing exists that Terraform did not create, and the region is a variable rather than a constant, so a second region is configuration rather than a rewrite.
- Failure sequence: A resource is created by hand in the console during the first deploy, becomes load-bearing, and is invisible to `terraform destroy` and to the next rebuild; the state file lives on one laptop and two applies race it; `ap-south-1` is written as a literal in fifty places and the first attempt at a second region is a refactor; a rebuild from zero fails because it depends on an ordering that only existed the first time.
- Implementation: `infra/terraform/` covering the Section 6 topology, with remote state in S3 and locking in DynamoDB, bootstrapped explicitly rather than by hand. Every region, availability-zone and ARN reference derives from a variable. A gate rejects a literal region string outside the variable definition.
- Proof: `scripts/terraform-region-parameterisation-check.py` will fail on any literal region string outside `variables.tf`; `terraform apply` from an empty account will produce a reachable stack; the immediately following `terraform plan` will report no changes; and `terraform plan` against a second region variable will resolve without a code change.
- Adversarial evidence: The clean second `plan` is the assertion that matters — an apply that succeeds and then immediately wants to change something is describing a resource it does not actually own. The second-region plan proves the parameterisation is real rather than cosmetic without paying to run a second stack. The gate will be proven by reintroducing a literal `ap-south-1` into a module and confirming it is caught and named.
- Status: PENDING

## C63-API-001

- Lens: API_PRODUCT
- Invariant: Production routing and local development routing derive from one list. A prefix reachable in `docker compose up` is reachable through CloudFront, and one that is not is not — the two cannot drift.
- Failure sequence: An eighth API prefix is added to `vite.config.ts` and to `nginx.conf` and forgotten in the CloudFront behaviors, so a feature works on every developer machine and returns the SPA's `index.html` in production; or a CloudFront behaviour is ordered so a broader path pattern shadows a narrower one and an API call is served the workspace bundle with a `200`.
- Implementation: CloudFront cache behaviors generated from, or checked against, the same seven prefixes `paymentsApiProxy` exports, which `routingContract.test.ts` already holds equal across the Vite proxy and nginx.
- Proof: `scripts/routing-contract-check.py` will extend that equality to the Terraform behaviors and fail when any of the four diverge; the deployed smoke proof will request every prefix through CloudFront and require the API to answer rather than the SPA.
- Adversarial evidence: Asserting a non-`502` status is not sufficient here, because the failure mode returns `200` with the wrong body — the SPA fallback is what makes routing drift invisible. The check must assert the response came from the API, and the gate will be proven by deleting one behaviour from the Terraform and confirming it is caught.
- Status: PENDING

## C64-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Two ECS tasks against one RDS instance are safe. Flyway migrates exactly once on first boot, and the eight lease-fenced `@Scheduled` workers do not double-process when replicas start together or when one is killed mid-work.
- Failure sequence: Both tasks start simultaneously against an empty database and both attempt `V1`; a webhook delivery or projection chunk is claimed by two replicas because the fencing epoch is not honoured across processes; a task is killed mid-chunk and its lease is never reclaimed, so the work stops silently rather than loudly.
- Implementation: No new coordination. The existing Flyway lock and the database-lease-with-fencing-epoch design already proven by the Phase 5 restart suites and by `ApplicationRestartHarness`, now exercised across two ECS tasks rather than two local processes.
- Proof: The deployed smoke proof will assert `flyway_schema_history` holds 91 successful rows, no failed row and no duplicated version after both tasks reach readiness; one task will then be stopped mid-work and the reclaimed lease and absence of duplicate effect asserted against the production database.
- Adversarial evidence: The database must be asserted empty before both tasks launch, and the tasks must start as one desired-count change rather than sequentially — a service scaled from one to two finds the schema at head and never contends for the lock, proving nothing while appearing to pass. `scripts/multi-replica-check.sh` already makes this argument locally; the deployed proof must not be weaker than the local one.
- Status: PENDING

## C64-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Deployment changes no payment, fraud, decision, ledger or webhook behaviour. Every Phase 1–6 demo passes against the deployed HTTPS endpoint and produces the same payment and fraud truth it produces locally.
- Failure sequence: A demo passes locally and fails deployed because it hardcodes loopback; a decision differs because the task's timezone, locale or heap differs from a laptop's; a fraud rule behaves differently because a bounded worker saw different container memory; a demo is skipped and the phase closes on a subset nobody counted.
- Implementation: A base-URL parameter for every `scripts/demo-*.py`, which currently hardcode loopback. No payment or fraud code changes. `TZ=UTC` and `LANG=C.UTF-8` are already pinned in the image and the heap is already bounded by container memory percentage.
- Proof: Every `scripts/demo-*.py` will run against the deployed URL and be required to complete, with the count of demos executed asserted rather than described. `scripts/demo-payment-investigation.py` must again report `explainedAction: BLOCK`, `winningControl: BLOCK_USD_EXTREME_AMOUNT_V1` and `retainedLosingControls: 6`.
- Adversarial evidence: The claim is stated as an exact count of demos that completed, because a demo that silently stopped being run is the failure `ER-0103` recorded — that script had never completed once, and three defects each hid the next. A green run of a subset is the specific thing this row must not accept.
- Status: PENDING

## C64-OBS-001

- Lens: OBSERVABILITY
- Invariant: Readiness is honest in both directions on a deployed instance — down when it cannot serve, up when it can, including when it is idle — and a running task can be traced back to the commit that produced it and to the request that produced an error.
- Failure sequence: The ALB health check is bound to readiness and every task leaves rotation five minutes after traffic goes quiet, so a demo that is idle by design is permanently unavailable; liveness passes while readiness fails and an unhealthy task is never replaced; readiness detail leaks internal dependency names to the internet; a CloudWatch error cannot be tied to the merchant-visible response that reported it.
- Implementation: All sixteen indicators in the existing readiness group, with `show-details: never` retained; the ALB target group bound to `/actuator/health/readiness`; `pendingStreamCount` published under `ER-0104` so a backlog is distinguishable from a broken query; the existing request-ID log pattern preserved to CloudWatch.
- Proof: The deployed smoke proof will assert all sixteen indicators `UP`, assert `/actuator/info` reports `1.0.0` and the deployed commit, leave the stack idle beyond the previous staleness threshold and re-assert `UP`, and trace one deliberately failed request from its merchant-visible `requestId` to its CloudWatch log line.
- Adversarial evidence: The idle observation is the row's reason for existing. `ER-0104` was found by leaving a containerised stack quiet, not by any test, and its correction is only proven when a deployed instance stays `UP` through the idle window that previously took it down. Asserting sixteen indicators at the moment of deployment, when everything has just run, is the measurement that would have missed it.
- Status: PENDING

## C65-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: The seeded demo tenant is one tenant among others, and the scheduled reset destroys exactly that tenant's data and nothing else. A visitor exploring the demo cannot reach another merchant's payments, decisions, notes or keys.
- Failure sequence: The reset truncates tables rather than scoping by merchant and removes a second tenant's data; the seed reuses one merchant's API key across geographies so a demo visitor holds a credential that works beyond the demo; the multi-currency seed writes an INR payment under a merchant configured for USD and the money truth is wrong from the first row; the reset runs while a demo is in flight and leaves partial lineage.
- Implementation: A seed built by reusing the existing demo scripts rather than new fixtures, spanning more than one currency and merchant geography including INR and an Indian merchant; an EventBridge-scheduled reset scoped by merchant identity.
- Proof: A second, untouched tenant will be created before the reset and asserted byte-identical after it, across payments, attempts, decisions, review cases, notes and ledger rows. The seed's currency and geography spread will be asserted from the database rather than from the script that wrote it.
- Adversarial evidence: The untouched-tenant assertion is what distinguishes a scoped reset from a truncate that happens to look right on a single-tenant database. The reset must also be run while a demo journey is mid-flight, because a reset proven only against a quiet system is proven against the condition it will never meet.
- Status: PENDING

## C65-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: A restore from an automated backup produces a database whose Flyway state, fraud decision lineage and feature snapshots are intact and readable by the running application version. The procedure is rehearsed, not assumed.
- Failure sequence: Automated backups exist and have never been restored, so the first restore is attempted during an incident; a restored instance is at a Flyway version the deployed application refuses to validate against; encrypted analyst notes and webhook secrets are unreadable because the restored data was written under a key version no longer configured; feature snapshots restore but their rule-execution lineage does not, so a decision can no longer be explained.
- Implementation: RDS single-AZ with seven-day automated backups, restored into a scratch instance and pointed at by a scratch application task rather than by inspection alone.
- Proof: A restore will be performed into a scratch instance, `flyway_schema_history` asserted at 91 successful rows with no failed row, and a decision made before the backup re-explained end to end through the running application — winning control, retained losing controls, and feature snapshot — against the restored data.
- Adversarial evidence: Reading rows out of a restored database with `psql` proves the bytes survived, not that the product can still explain a decision. The restore is only evidence if an application instance serves an investigation from it, and the KEK versions configured at restore time must be the ones the data was written under — which is the failure a restore rehearsal on a freshly seeded database would never surface.
- Status: PENDING

## C65-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Deployment, rollback and secret rotation are written procedures with recorded rehearsals. A rollback returns the system to a known-good version without data loss, and a single-broker Kafka restart does not lose an event the outbox cannot replay.
- Failure sequence: A rollback is attempted from memory during an incident and pins an image tag that no longer exists; the rolled-back application version cannot validate against a schema the newer version migrated; the single Kafka task restarts and in-flight events are lost with no outbox row to replay them from; a secret is rotated and half the tasks hold the old value with no defined overlap.
- Implementation: `ADR-0012` recording the ECS Fargate and single-broker decisions with their named replacement triggers; an operations runbook covering deploy, rollback, secret rotation and restore; images addressable by commit SHA so a rollback names an immutable artifact.
- Proof: A rollback to the previous image SHA will be rehearsed and recorded, with readiness re-asserted afterwards. The Kafka task will be deliberately restarted mid-publication and the outbox required to replay every unacknowledged event, with the published and consumed counts asserted equal.
- Adversarial evidence: An unrehearsed procedure is not evidence, which is why both are executed rather than written. The broker restart must happen with publications genuinely in flight rather than on a quiet stream — a restart of an idle broker loses nothing and proves nothing, and single-broker Kafka is recorded in Section 13 as a known single point of failure precisely so this row cannot quietly claim more than was measured.
- Status: PENDING

## C65-OBS-002

- Lens: OBSERVABILITY
- Invariant: Every alarm named in Section 12 exists, is wired to a destination a human reads, and has been observed firing. Spend is bounded by an alarm that arrives before the invoice does.
- Failure sequence: Alarms are created and never tested, so the first time one matters it is misconfigured, points at an unmonitored address, or is in `INSUFFICIENT_DATA` and silent; a misconfigured resource or an abusive caller produces a bill nobody notices for a month; projection consumer lag grows and no alarm covers it because the metric was never published.
- Implementation: CloudWatch alarms on ALB 5xx rate, ECS task health and restart count, RDS CPU, storage and connection saturation, Kafka task restarts and projection consumer lag, plus an AWS Budgets alarm on total spend.
- Proof: Each alarm will be driven into `ALARM` deliberately — by generating 5xx responses, stopping a task, and lowering a threshold below current usage — and the resulting notification recorded. The budget alarm will be verified by setting a threshold below current spend and observing delivery.
- Adversarial evidence: An alarm that has never fired is a configuration, not a control, which is the correction `ER-0096` recorded in a different form. Observing the transition into `ALARM` and the arrival of the notification are two separate assertions, because an alarm that fires into a destination nobody reads is the same outage with more paperwork.
- Status: PENDING
