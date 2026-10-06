# THAAP Runtime Configuration Reference

Every environment variable the `payments-api` application reads, in one place.

This document is a frozen Phase 6.5 promise, not a convenience. An operator who
has to read `application.yml` to find out what a system needs is an operator who
will miss the one variable that turns encryption off, and a variable that exists
only in source is a variable nobody sets deliberately.

`scripts/runtime-configuration-reference-check.py` holds this file equal to
`apps/payments-api/src/main/resources/application.yml`: a variable added to the
application and not documented here fails the readiness gate, as does a variable
documented here that the application does not read, and as does a default that
has drifted.

## How to read the deployment column

| Value | Meaning |
|---|---|
| **Required** | The application refuses to start without it under the `aws` profile. There are exactly eight. |
| **Set** | Not enforced by the startup gate, but a deployment that leaves it at its default is misconfigured — the default names a laptop. |
| **Tune** | Safe to leave alone. Change it when a measurement says to. |
| **Optional** | Absent is a valid, ordinary state. |

## 1. The eight required secrets

`RuntimeSecretContractGate` refuses to start the application when any of these is
absent, empty, below its minimum shape, reused for another purpose, or set to a
value this repository publishes for local development. The refusal happens while
the environment is being prepared — before the first bean, before the database
connection, before the Flyway lock — and reports every failing variable at once.

The reason the gate exists is that all eight default to the empty string so a
laptop starts without a secrets ceremony, and every one of them permits the
application to start, report healthy, and serve traffic while protecting nothing.

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_ADMIN_BOOTSTRAP_TOKEN` | `(empty)` | Required | Merchant creation authority. Whoever holds it can mint merchants and their API keys. Minimum 32 characters. |
| `THAAP_PLATFORM_BOOTSTRAP_TOKEN` | `(empty)` | Required | Platform operator authority, across tenants. Minimum 32 characters. |
| `THAAP_SCENARIO_RUNNER_TOKEN` | `(empty)` | Required | Proves synthetic scenario traffic came from the trusted runner rather than from a caller who would like their payments treated as simulated. Minimum 32 characters. |
| `THAAP_HASH_PEPPER` | `(empty)` | Required | Keys the HMAC over merchant API keys, credentials and replay identities. Minimum 32 characters. |
| `THAAP_CORRELATION_PEPPER` | `(empty)` | Required | Keys the stable pseudonyms correlating customers and fraud across PAN, email and phone. Minimum 32 characters. |
| `THAAP_AUTHENTICATION_REPLAY_ENCRYPTION_KEY` | `(empty)` | Required | AES-256 KEK for stored authentication replays. Base64 of exactly 32 bytes. |
| `THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY` | `(empty)` | Required | AES-256 KEK for merchant webhook signing secrets. Base64 of exactly 32 bytes. |
| `THAAP_REVIEW_NOTE_ENCRYPTION_KEY` | `(empty)` | Required | AES-256 KEK for analyst review notes — an analyst's own words about a named customer. Base64 of exactly 32 bytes. |

Generate them:

```bash
openssl rand -hex 32
```

```bash
openssl rand -base64 32
```

Three rules are worth stating explicitly, because each one has refused a
plausible deployment during this chapter's proof:

- **No value may be reused for two of the eight.** Each must be independently
  rotatable and independently compromisable. Sharing the hash pepper with the
  correlation pepper would tie credential rotation to fraud identity, so a
  routine key roll would silently split every customer's history in two. Sharing
  a KEK across the three ciphers means one disclosure decrypts analyst notes,
  webhook secrets and authentication replays together.
- **No value published in this repository is accepted.** The local stack, both
  READMEs and the test fixtures all publish working secrets on purpose, so that a
  stranger can start THAAP. The most likely path to a deployed system holding a
  known key is somebody copying the quickstart that was written to be copied.
- **No value that looks like a placeholder is accepted** — anything containing
  `change-me`, `local-`, `test-`, `example`, `placeholder`, `dummy`, `your-` or
  `insert-`. None of these can occur in the output of the generators above, so the
  rule costs a correctly configured deployment nothing.

**Migration note.** `apps/payments-api/README.md` describes setting
`THAAP_CORRELATION_PEPPER` to the pre-rotation `THAAP_HASH_PEPPER` when upgrading
a database across `V17`. The distinctness rule refuses that, deliberately: it is
a migration procedure for an existing database, and no THAAP database has ever
been deployed. A future deployment carrying pre-`V17` data must re-key rather than
share.

## 2. Rotation key sets

Optional by design. An empty previous-key set is what every deployment that has
never rotated looks like, and requiring one would make an ordinary state look
broken. The existing rotation suites prove the overlap behaviour.

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_HASH_PEPPER_VERSION` | `v1` | Tune | Names the current hash pepper so stored digests record which key produced them. |
| `THAAP_PREVIOUS_HASH_PEPPERS` | `(empty)` | Optional | Comma-separated `version=base64` entries kept readable during a pepper rotation. |
| `THAAP_AUTHENTICATION_REPLAY_KEY_VERSION` | `v1` | Tune | Names the current authentication replay KEK. |
| `THAAP_AUTHENTICATION_REPLAY_DECRYPTION_KEYS` | `(empty)` | Optional | Superseded replay KEKs still needed to read existing rows. |
| `THAAP_WEBHOOK_SECRET_KEY_VERSION` | `v1` | Tune | Names the current webhook secret KEK. |
| `THAAP_WEBHOOK_SECRET_DECRYPTION_KEYS` | `(empty)` | Optional | Superseded webhook KEKs. Startup fails if a still-decryptable secret has no key here. |
| `THAAP_WEBHOOK_SECRET_ROTATION_GRACE` | `15m` | Tune | How long both the old and new signing secret are accepted during a rotation. |
| `THAAP_REVIEW_NOTE_KEY_VERSION` | `v1` | Tune | Names the current review note KEK. |
| `THAAP_REVIEW_NOTE_DECRYPTION_KEYS` | `(empty)` | Optional | Superseded review note KEKs. |

## 3. Data stores and transport

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_POSTGRES_HOST` | `localhost` | Set | PostgreSQL host. The RDS endpoint in a deployment. |
| `THAAP_POSTGRES_PORT` | `5432` | Tune | PostgreSQL port. |
| `THAAP_POSTGRES_DB` | `thaap_payments` | Tune | Database name. |
| `THAAP_POSTGRES_USER` | `thaap` | Set | Database user. |
| `THAAP_POSTGRES_PASSWORD` | `thaap` | Set | Database password, from Secrets Manager in a deployment. Not part of the startup secret contract: a wrong database password fails loudly on its own, which is the property the eight above lack. |
| `THAAP_DB_POOL_MAX_SIZE` | `10` | Tune | Hikari maximum pool size. Two API tasks at 10 each against a `db.t4g.micro` is comfortable; raise it only against a measured wait. |
| `THAAP_DB_POOL_MIN_IDLE` | `2` | Tune | Hikari minimum idle connections. |
| `THAAP_REDIS_HOST` | `localhost` | Set | Redis host. Redis is ephemeral risk coordination per `ADR-0010`; losing it degrades rather than corrupts. |
| `THAAP_REDIS_PORT` | `6379` | Tune | Redis port. |
| `THAAP_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Set | Kafka bootstrap servers. In the compose network and on ECS this is an in-network address, never a loopback one — a client connects to what the broker advertises, not to what it was handed. |
| `THAAP_PAYMENTS_API_PORT` | `8080` | Tune | HTTP listen port. The container healthcheck and the ALB target group both assume 8080. |

## 4. Event publication and projections

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_EVENT_PUBLICATION_ENABLED` | `true` | Tune | The domain event stream. Phase 5 and 6 analytics, projections and workspace insights all depend on it; disabling it is not a supported deployment. |
| `THAAP_PROJECTION_CONSUMER_ENABLED` | `true` | Tune | Consumes the stream into the payment risk projection. |
| `THAAP_PROJECTION_REBUILD_WORKER_ENABLED` | `true` | Tune | Runs projection rebuilds. Lease-fenced, so more than one task is safe. |
| `THAAP_PROJECTION_REBUILD_POLL_DELAY` | `5s` | Tune | How often the rebuild worker looks for work. |

## 5. Fraud, velocity and rate limiting

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_VELOCITY_ENABLED` | `true` | Tune | Redis-backed velocity features. |
| `THAAP_FEATURE_SNAPSHOT_MAX_JSON_BYTES` | `16384` | Tune | Ceiling on a stored feature snapshot. Bounds the lineage record a fraud decision carries. |
| `THAAP_RATE_LIMIT_ENABLED` | `true` | Tune | The request rate limiter. |
| `THAAP_RATE_LIMIT_WINDOW` | `1m` | Tune | Rate limit window. |
| `THAAP_RATE_LIMIT_CONFIRM_MERCHANT` | `100` | Tune | Confirms per merchant per window. |
| `THAAP_RATE_LIMIT_CONFIRM_CREDENTIAL` | `60` | Tune | Confirms per API credential per window. |
| `THAAP_RATE_LIMIT_CONFIRM_CLIENT_IP` | `120` | Tune | Confirms per client IP per window. Meaningless behind a load balancer unless forwarded headers are trusted — see Section 9. |
| `THAAP_RATE_LIMIT_PAYMENT_METHOD_MERCHANT` | `30` | Tune | Payment method creations per merchant per window. |
| `THAAP_RATE_LIMIT_PAYMENT_METHOD_CREDENTIAL` | `20` | Tune | Payment method creations per credential per window. |
| `THAAP_RATE_LIMIT_PAYMENT_METHOD_CLIENT_IP` | `40` | Tune | Payment method creations per client IP per window. |
| `THAAP_CARD_TESTING_INCIDENTS_ENABLED` | `true` | Tune | Card-testing incident detection. |
| `THAAP_CARD_TESTING_DETECTION_WINDOW` | `10m` | Tune | Window over which card-testing behaviour is assessed. |
| `THAAP_CARD_TESTING_MINIMUM_ATTEMPTS` | `5` | Tune | Attempts before a card-testing incident can open. |
| `THAAP_CARD_TESTING_MINIMUM_DECLINES` | `3` | Tune | Declines before a card-testing incident can open. |
| `THAAP_CARD_TESTING_MINIMUM_DISTINCT_CARDS` | `3` | Tune | Distinct cards before a card-testing incident can open. |

## 6. Authentication and idempotency

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_AUTHENTICATION_CHALLENGE_TTL` | `5m` | Tune | How long an authentication challenge stays answerable. |
| `THAAP_IDEMPOTENCY_RESERVATION_LEASE` | `30s` | Tune | Fail-stop reservation lease for an in-flight idempotent request. |

## 7. Webhooks

`THAAP_WEBHOOK_EXECUTION_ENABLED` is the one to read twice.

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_WEBHOOK_EXECUTION_ENABLED` | `false` | Tune | Outbound webhook delivery. **Enabling it makes `THAAP_WEBHOOK_ALLOWED_HOSTS` a live SSRF boundary against the VPC and the instance metadata endpoint.** The address pinning in `SocketWebhookHttpTransport` is the control that makes it safe, and it must be verified against the deployed network before this is turned on. |
| `THAAP_WEBHOOK_ALLOWED_HOSTS` | `(empty)` | Set | The allowlist that boundary enforces. Empty means no destination is permitted. |
| `THAAP_WEBHOOK_BATCH_SIZE` | `20` | Tune | Deliveries claimed per poll. |
| `THAAP_WEBHOOK_MAX_CLAIMS_PER_MERCHANT_PER_POLL` | `5` | Tune | Per-merchant fairness bound within a batch. |
| `THAAP_WEBHOOK_MAX_CLAIMS_PER_ENDPOINT_PER_POLL` | `2` | Tune | Per-endpoint fairness bound within a batch. |
| `THAAP_WEBHOOK_LEASE_DURATION` | `30s` | Tune | Delivery lease. Fenced, so a killed task's work is reclaimed rather than duplicated. |
| `THAAP_WEBHOOK_POLL_DELAY` | `1s` | Tune | How often the delivery worker polls. |
| `THAAP_WEBHOOK_CONNECT_TIMEOUT` | `2s` | Tune | Connect timeout for an outbound delivery. |
| `THAAP_WEBHOOK_REQUEST_TIMEOUT` | `5s` | Tune | Request timeout for an outbound delivery. |
| `THAAP_WEBHOOK_TARGET_RESOLUTION_TIMEOUT` | `2s` | Tune | DNS resolution timeout for a delivery target. |
| `THAAP_WEBHOOK_RESOLVER_THREADS` | `4` | Tune | Threads available for target resolution. |
| `THAAP_WEBHOOK_VERIFICATION_CHALLENGE_TTL` | `2m` | Tune | Endpoint verification challenge lifetime (30s–5m). |
| `THAAP_WEBHOOK_VERIFICATION_EXPIRY_ENABLED` | `true` | Tune | Expires unanswered verification challenges. |
| `THAAP_WEBHOOK_VERIFICATION_EXPIRY_BATCH_SIZE` | `50` | Tune | Challenges expired per pass (1–100). |
| `THAAP_WEBHOOK_VERIFICATION_EXPIRY_POLL_DELAY` | `30s` | Tune | How often expiry runs. |

## 8. Workspace, simulation, replay and disputes

| Variable | Default | Deployment | Purpose |
|---|---|---|---|
| `THAAP_REVIEW_NOTE_RETENTION_POLL_DELAY` | `1h` | Tune | How often review note retention runs. |
| `THAAP_SIMULATION_WORKER_ENABLED` | `true` | Tune | Historical risk simulation worker. |
| `THAAP_SIMULATION_CREATION_ENABLED` | `true` | Tune | Whether new simulations may be created. |
| `THAAP_SIMULATION_CHUNK_SIZE` | `100` | Tune | Rows per simulation chunk. |
| `THAAP_SIMULATION_MAXIMUM_COHORT_DAYS` | `365` | Tune | Longest cohort a simulation may span. |
| `THAAP_SIMULATION_REPORT_FRESHNESS` | `24h` | Tune | How long a simulation report is considered current. |
| `THAAP_SIMULATION_GATE_MINIMUM_ELIGIBLE_POPULATION` | `100` | Tune | Population below which a simulation will not gate a control. |
| `THAAP_SIMULATION_GATE_MINIMUM_LABEL_COVERAGE_PERCENT` | `50` | Tune | Label coverage below which a simulation will not gate a control. |
| `THAAP_REPLAY_DATASET_WORKER_ENABLED` | `true` | Tune | Offline replay dataset worker. |
| `THAAP_REPLAY_DATASET_CREATION_ENABLED` | `true` | Tune | Whether new replay datasets may be created. |
| `THAAP_REPLAY_DATASET_CHUNK_SIZE` | `100` | Tune | Rows per replay dataset chunk. |
| `THAAP_REPLAY_DATASET_MAXIMUM_COHORT_DAYS` | `365` | Tune | Longest cohort a replay dataset may span. |
| `THAAP_REPLAY_DATASET_MAXIMUM_POPULATION` | `1000000` | Tune | Ceiling on a replay dataset population. |
| `THAAP_REPLAY_DATASET_LEASE_DURATION` | `60s` | Tune | Replay dataset chunk lease. |
| `THAAP_REPLAY_DATASET_RETRY_LIMIT` | `10` | Tune | Retries before a replay dataset chunk is abandoned. |
| `THAAP_REPLAY_DATASET_RECOVERY_BATCH_SIZE` | `10` | Tune | Datasets recovered per pass. |
| `THAAP_REPLAY_DATASET_RECOVERY_POLL_DELAY` | `5s` | Tune | How often recovery runs. |
| `THAAP_REPLAY_DATASET_STALE_AGE` | `5m` | Tune | Age at which an in-flight dataset is treated as abandoned. |
| `THAAP_DISPUTE_EXPIRY_ENABLED` | `true` | Tune | Expires disputes past their response window. |
| `THAAP_DISPUTE_EXPIRY_BATCH_SIZE` | `50` | Tune | Disputes expired per pass. |
| `THAAP_DISPUTE_EXPIRY_POLL_DELAY` | `1m` | Tune | How often dispute expiry runs. |
| `THAAP_DEVELOPER_SCENARIO_CREATION_ENABLED` | `true` | Tune | Whether developer scenarios may be created. |
| `THAAP_DEVELOPER_SCENARIO_LEASE_DURATION` | `45s` | Tune | Developer scenario execution lease. |

## 9. The `aws` profile

Activated with `SPRING_PROFILES_ACTIVE=aws`. It is deliberately short, because
almost everything a deployment changes is already an environment variable above,
and re-stating one here would create a second place for it to live.

| Setting | Value | Why |
|---|---|---|
| `thaap.runtime.secret-contract-enforced` | `true` | Turns on the refusal described in Section 1. This is the reason the profile exists. |
| `server.forward-headers-strategy` | `framework` | THAAP sits behind an ALB, so the socket address belongs to the load balancer. Without this, per-client-IP rate limits collapse into one bucket and per-IP fraud velocity becomes a constant — which is worse than absent, because the feature is still computed and recorded. |
| `logging.level.root` | `INFO` | Deployed logs go to stdout and from there to CloudWatch, where volume is a cost. |

**`forward-headers-strategy` is a security dependency, not a formatting
preference.** `framework` trusts `X-Forwarded-For` from whoever sent it. That is
safe only because the task security group admits traffic from the ALB alone. If
the tasks ever become directly reachable, a caller can choose its own client IP
and defeat both the rate limiter and the per-IP velocity signal. Chapter 63 owns
that security group; this setting is what makes it load-bearing.

## 10. Build-time arguments

Not runtime configuration. These are Docker build arguments that stamp release
identity into the artifact, and they are listed here because an operator looking
for "where does the version come from" will look in this file.

| Argument | Default | Purpose |
|---|---|---|
| `THAAP_BUILD_COMMIT` | `unknown` | The commit the image was built from. Reaches `/actuator/info` as `build.commit` and the image as `org.opencontainers.image.revision`. |
| `THAAP_BUILD_VERSION` | `unknown` | The released version. Reaches the image as `org.opencontainers.image.version`; the application reports it from `pom.xml` as `build.version`. |

`unknown` is honest for a developer build, which genuinely does not know its
commit — `.dockerignore` excludes `.git/` from the build context deliberately.
`scripts/release-identity-check.sh` refuses to let a release carry it.
