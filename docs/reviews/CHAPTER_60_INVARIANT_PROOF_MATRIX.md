# Chapter 60 Invariant Proof Matrix

- Chapter: Chapter 60 — Reproducible Build Artifacts
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

This matrix was drafted and frozen on 2026-08-08, after the Phase 6.5 plan was approved and
before any `Dockerfile`, dependency pin, compose service or CI image step was written. Every row
then closed on an artifact that exists and has a recorded run, per the `ER-0096`/`ER-0098`
correction that a gate which has never been executed is not evidence.

Chapter 60 makes the existing system buildable and startable as artifacts. It does not deploy to
AWS (Chapters 63–64) and does not establish release identity (Chapter 61). It changed one piece
of production code — `PaymentRiskProjectionHealthIndicator` — because containerising the system
exposed a readiness defect that no test could have found without a running deployment; that is
recorded as `ER-0104` rather than absorbed silently into this chapter.

Two escaped findings were raised by this chapter's work: `ER-0103` (the `VP-004` demo had never
run to completion, three defects each hiding the next) and `ER-0104` (readiness reported `DOWN`
on an idle system). Both are `RESOLVED`.

## C60-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: A clean checkout with no host-cached state produces a runnable image, and `docker compose up` starts THAAP end to end — API, workspace, PostgreSQL, Redis and Kafka — until the sixteen-indicator readiness group reports `UP`, rather than starting three empty databases and calling it a stack.
- Failure sequence: The image builds only because a prior `mvn` run left `target/` populated; the API starts before PostgreSQL accepts connections; Kafka is advertised on a hostname reachable from the host but not from inside the compose network; the workspace caches a failed bootstrap because it started first.
- Implementation: multi-stage `apps/payments-api/Dockerfile` resolving and building inside the image; `.dockerignore` excluding `target/`, `.m2/` and `node_modules/`; compose `depends_on` with `condition: service_healthy`; container healthchecks bound to readiness; Kafka given a second advertised listener so `kafka:9092` resolves on the compose network while the host keeps its usual port.
- Proof: `scripts/compose-stack-check.sh` builds from the working tree, starts every service in an isolated project on its own volumes, and passes: readiness `UP`, liveness `UP`, workspace shell and deep links served, all seven routed prefixes reaching the API, and 91 migrations applied on a fresh database with none failed.
- Adversarial evidence: The Kafka defect is the reason this row is not a formality. A single `localhost` advertisement worked from the host for six phases and was unreachable from every container, so the failure appeared only once the API was containerised — the first time anything ran the way it will run in production. The check builds from source in an isolated compose project rather than reusing a warm developer stack, so a build that depends on host state fails here rather than on a deploy box.
- Status: PASS

## C60-OPS-002

- Lens: DATA_ML_LINEAGE
- Invariant: The same commit resolves to the same dependency set on every machine, so a decision produced by a rebuilt image is produced by the same code that produced it before. No manifest carries a floating specifier and no base image floats on a mutable tag.
- Failure sequence: A deploy-box `npm install` resolves a newer major; the lockfile is regenerated during a deploy and the built bundle no longer matches the reviewed source; a base image tag moves and a rebuild changes runtime behaviour; the lockfile carries only the generating platform's optional dependencies, so `npm ci` fails inside the build image.
- Implementation: every `"latest"` in `apps/analyst-dashboard-ui/package.json` replaced with the exact version already resolved in `package-lock.json`; the lockfile regenerated inside the Linux build image so it carries cross-platform optional dependencies; `npm ci` everywhere; all four base images pinned by multi-architecture index digest.
- Proof: `scripts/dependency-pin-check.py` passes with 15 exact pins, each matching the lockfile, no un-pinned base image and no `npm install` instruction. `npm ci` then completes on both macOS and inside the Linux image from one lockfile, and `npm run build` produces a byte-identical bundle hash before and after the change.
- Adversarial evidence: The gate was proven by reintroducing `"latest"` for react, which it caught, and by the original defect it was written for — fourteen dependencies including react, typescript and vite were pinned to `"latest"`, and nothing failed, because the lockfile was silently doing the work. A high-severity `nanoid` advisory surfaced during the same pass and was fixed rather than registered, leaving zero vulnerabilities. The check also rejects a lockfile without cross-platform optional entries, because that failure appears only inside the image and never on the machine that wrote it.
- Status: PASS

## C60-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: No image layer contains secret material, build credentials or the build toolchain, and the application runs as a non-root user that cannot rewrite its own code.
- Failure sequence: A `.env` or credential file is copied in by a broad `COPY . .`; a build-time secret persists in an intermediate layer and survives its own deletion in a later one; the container runs as root; the final stage ships the JDK and Maven repository.
- Implementation: multi-stage builds discarding the builder stage; explicit `.dockerignore` in both applications; no build-time secrets, so every secret arrives at runtime; JRE-only and nginx-only final stages; dedicated non-root users; application directories owned by root.
- Proof: `scripts/image-content-check.sh` passes ten assertions across both images — no credential-shaped file in any layer, non-root runtime users, an application directory not writable by its runtime user, no `mvn`, `javac` or `node` in a runtime stage, and every base image pinned by digest.
- Adversarial evidence: The check walks every layer rather than the final filesystem, because a file deleted in a later layer remains readable in the one that added it — inspecting the result would pass an image that leaks. Proven by building a deliberately leaky image carrying `/app/.env` with a live-looking admin bootstrap token: caught and named. The trust-store exclusion was added only after the first run reported hundreds of CA certificates, since a gate that cries wolf is one that gets switched off, which is the failure `ER-0099` recorded.
- Status: PASS

## C60-API-001

- Lens: API_PRODUCT
- Invariant: A developer with only Docker can start the full stack and complete a payment and investigation journey without reading source, setting an undocumented variable, or running a command the documentation does not name.
- Failure sequence: The quickstart requires a locally installed Java 25 and Maven; a required variable is documented nowhere and the API boots degraded; the workspace cannot reach the API because production routing differs from the Vite dev proxy; the documented command is stale on a clean machine.
- Implementation: a single compose command starting every service; local development values supplied as compose defaults so the stack starts without a secrets ceremony; the workspace container routing the same seven prefixes the dev proxy does.
- Proof: `scripts/compose-stack-check.sh` proves the stack serves; `scripts/demo-payment-investigation.py` then completes three consecutive times against it, reporting `explainedAction: BLOCK`, `winningControl: BLOCK_USD_EXTREME_AMOUNT_V1`, `retainedLosingControls: 6`, `timelineCeiling: 7`, `relatedScope: SAME_MERCHANT_SERVER_DERIVED` and `sourceFingerprintStable: true`.
- Adversarial evidence: That demo had never completed. Running it against the containerised stack found three independent defects, each hiding the next, recorded as `ER-0103` — a query against a column that has not existed since `V1`, an assertion that raced the projection it searched, and a field read by the wrong name. The journey is therefore proven by execution rather than by the script's continued existence, which is the distinction the `ER-0096` correction was written to enforce.
- Status: PASS

## C60-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Containerisation changes no payment, fraud, decision, ledger or webhook behaviour. The suite that passes on the host passes with the same count and no new skips.
- Failure sequence: A container timezone or locale differs and timestamp-precision or currency behaviour changes; the container JVM sees different memory and a bounded worker changes concurrency; a missing native dependency disables a code path that still reports healthy; tests pass only because a suite was skipped.
- Implementation: no payment or fraud code changed in this chapter; `TZ=UTC` and `LANG=C.UTF-8` pinned in the image; heap bounded by container memory percentage; the existing `--enable-native-access=ALL-UNNAMED` argument preserved at runtime.
- Proof: The full backend suite passes at **1392 tests, 0 failures, 0 errors, 0 skipped**, `BUILD SUCCESS`. The count rose from 1387 by exactly the five tests added to `PaymentRiskProjectionHealthIndicatorTests` for `ER-0104`; no pre-existing test changed. The workspace suite passes at 86, up from 83 by the three added to `routingContract.test.ts`.
- Adversarial evidence: A skipped test would fail this comparison, because the claim is stated as an exact count rather than a green result — a reduced count that still reports success is the condition the repository's CI Docker check already exists to prevent. Real payment truth was also produced through the containerised stack rather than only asserted: three payment intents, five attempts, five fraud decisions, fifteen rule execution results and one review case, with the deterministic `BLOCK` precedence intact.
- Status: PASS

## C60-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: No tenant data, merchant API key, operator credential or seeded identity is baked into any image layer. An image is tenant-neutral and carries nothing that could authenticate against a running system.
- Failure sequence: A developer's environment file with a working admin bootstrap token is copied into the image; a fixture containing merchant keys ships in the runtime layer; a seeded database volume is committed; compose defaults become durable production credentials because nothing forces them to be overridden.
- Implementation: `.dockerignore` excluding environment files, keys and local configuration; no database volume in either image; compose defaults confined to the local profile and documented as protecting nothing; production secrets supplied only at runtime, with Chapter 62 owning the refusal to start without them.
- Proof: `scripts/image-content-check.sh` finds no credential-shaped file in any layer of either image, and the leaky-image negative case confirms the search would find one.
- Adversarial evidence: The claim is proven by absence across all layers rather than by the application declining to read a credential, because the risk is a published artifact rather than a running process. The local database used during verification was never mounted into an image, and the developer's existing volume — 119 merchants and 198 payment intents — was deliberately left untouched by running every check in an isolated compose project with its own volumes.
- Status: PASS

## C60-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Running more than one API instance against one database is safe. Flyway migrates exactly once, and the eight lease-fenced `@Scheduled` workers do not double-process when replicas start together.
- Failure sequence: Two instances start simultaneously and both attempt the same migration; a lease is taken by two replicas because the fencing epoch is not honoured across processes; a replica killed mid-chunk never has its lease reclaimed; scaling produces duplicate webhook deliveries or duplicate projection writes.
- Implementation: no new coordination. The existing Flyway lock and the database-lease-with-fencing-epoch design already proven by the Phase 5 restart suites are relied on directly, which is what makes the Chapter 63 two-task ECS service safe without leader election.
- Proof: `scripts/multi-replica-check.sh` launches two replicas in one batch against a verified-empty database and passes: both report readiness `UP`, 91 migrations are applied exactly once with no failed row and no duplicated version, and no publication lease is held by more than one owner.
- Adversarial evidence: The database is asserted empty before launch and the check fails if it is not, because a migration race exists only on first boot — starting replicas sequentially would find the schema at head and never contend for the lock, proving nothing while appearing to pass. The replicas are launched as one background batch rather than in a loop, so neither is given a head start.
- Status: PASS

## C60-OBS-001

- Lens: OBSERVABILITY
- Invariant: A running container can be traced back to the inputs that produced it, its logs and readiness signal are reachable from the container runtime without exec-ing into it, and readiness is honest in both directions — down when the instance cannot serve, up when it can.
- Failure sequence: The image is tagged `latest` and nobody can say what is running; logs are written inside the container and lost on restart; liveness passes while readiness fails so an unhealthy task is never replaced; readiness detail leaks internal dependency state; readiness reports down on a healthy instance and an orchestrator removes it from service.
- Implementation: base images pinned by digest; logs to stdout only; container healthchecks bound to `/actuator/health/readiness` rather than liveness; `show-details: never` retained; and, under `ER-0104`, readiness gated on pending work rather than elapsed time, with `pendingStreamCount` published so an operator can tell a backlog from a broken query.
- Proof: `scripts/image-content-check.sh` proves digest pinning; `scripts/compose-stack-check.sh` proves liveness and readiness answer independently over HTTP; `PaymentRiskProjectionHealthIndicatorTests` passes seven tests covering both directions of the readiness question. Version and commit reporting is Chapter 61's obligation and is deliberately not claimed here.
- Adversarial evidence: Readiness was proven wrong before it was proven right. On an idle stack with zero consumer lag, all 52 publications acknowledged and nothing pending, it reported `DOWN` at checkpoint ages of six and eight minutes — which, wired to a load balancer, would remove every task from rotation roughly five minutes after traffic goes quiet, permanently for a demo that is idle by design. The same database now reports `UP` at fourteen minutes idle, and a tenant with unapplied streams and a six-minute-old checkpoint still reports `STALE`, so the failure detection this replaced is intact rather than merely quieter.
- Status: PASS
