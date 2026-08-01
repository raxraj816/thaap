# payments-api

Primary V1 backend deployable.

The founder-confirmed Phase 6 API, investigation, versioning, OpenAPI, error, and Python
SDK contracts are specified in
[`../../docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md`](../../docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md).
The 2026-08-02 promise freeze now has frozen pre-implementation matrices and
open `DO_NOT_MERGE` dossiers for every Chapter 53–59 slice. Chapters 53 and 57
are dependency-ready; runtime behavior has not changed yet.

V1 uses the deterministic processor simulator. THAAP's declared destination is
a hosted merchant-facing fraud/risk platform integrating first through
versioned existing-PSP adapters; this service must therefore preserve external
source provenance, idempotency, reconciliation, and simulator/live separation
without expanding into raw PAN/CVV custody or claiming THAAP-owned settlement.

Implemented backend foundation:

- Java 25
- Spring Boot 4.0.6
- Spring Web
- Spring Validation
- Spring Data JPA
- Flyway
- Spring Actuator
- PostgreSQL
- Redis

Responsibilities:

- merchant, customer, and payment method APIs
- PaymentIntent and PaymentAttempt lifecycle
- idempotency
- fraud decision orchestration
- deterministic rules
- manual review
- processor simulation orchestration
- authorization, capture, cancel, refund, and chargeback flows
- ledger writes
- webhook records
- audit logs
- outbox events

## Merchant Tenant Boundary

`Merchant` is the tenant root. Admin setup creates merchants and reveals an
API key in the creation response. An idempotent retry replays that same
response during its 24-hour window. This bootstrap endpoint is disabled unless
`THAAP_ADMIN_BOOTSTRAP_TOKEN` is configured, and requests must send the matching
`X-Admin-Bootstrap-Token` header:

```bash
curl -X POST http://localhost:8080/admin/merchants \
  -H 'X-Admin-Bootstrap-Token: change-me-local-admin-bootstrap-token' \
  -H 'Idempotency-Key: merchant-onboarding-001' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo Merchant"}'
```

Merchant-facing APIs derive tenant scope from `X-Api-Key`; only the API key
prefix and hash are stored. Clients must not be trusted to choose `merchantId`
in request bodies. Mutating payment APIs require `Idempotency-Key`. Merchant
API replay remains account-scoped; risk-operator mutation replay is additionally
scoped to the authenticated operator and credential lifecycle so a different
analyst or manager cannot consume a stored privileged response. Merchant and customer
creation accept the header for safe retry and reconciliation. Legacy callers
may omit it, but a present blank or oversized header is rejected; the
synthetic generator always supplies one.

The idempotency service now treats each key as a small state machine:

- reserve a merchant-scoped `IN_PROGRESS` row before running the mutation
- bind the key to an operation name and keyed request hash
- persist the request-hash key version and verify replay with that original
  version across routine key rotation
- lock and validate reservation ownership before invoking mutation or processor
  work, then reject or serialize duplicates while that work is in progress
- fence reservations with an expiring owner token so process-death state can
  be reclaimed without allowing a stale owner to commit
- mark the row `COMPLETED` only after the mutation response is durable
- replay completed responses for exact retries
- release the reservation when the mutation fails so retryable failures are not
  cached as successful work

Merchant onboarding uses a global HMACed idempotency identity because no
merchant tenant exists yet. Exact replay returns the same merchant and raw API
key for 24 hours. V16 stores only unique versioned HMAC aliases for every
configured current/previous pepper; all aliases point to one creation record.
The API key is deterministically derived under that record's original pepper
version and verified against the merchant's stored hash, so neither the raw
idempotency key nor raw API key is persisted. Startup fails closed if an
in-progress or unexpired replay record requires an unavailable version. Customer
creation reuses the normal merchant-scoped idempotency transaction; its stored
response contains only safe IDs, never email or phone.
`THAAP_IDEMPOTENCY_RESERVATION_LEASE` controls the fail-stop reservation lease
and defaults to 30 seconds.

`THAAP_HASH_PEPPER` is the rotatable replay-identity key. V17 stores the original
key version for generic request hashes and gives webhook endpoint creation the
same tenant-scoped current/previous alias model as merchant creation.
`THAAP_HASH_PEPPER_VERSION` defaults to `v1`;
`THAAP_PREVIOUS_HASH_PEPPERS` uses comma-separated `version=base64` entries.
Rotate in four phases: deploy the V17-capable code everywhere with the old
version current and both keys configured; promote the new current version;
retain the old version through every live 24-hour replay window; then remove it
only after startup key-inventory checks no longer require it. Key-version names
are immutable and must match `[A-Za-z0-9._-]{1,32}`. Different version labels
must use distinct key material; startup rejects duplicate bytes because they
would make alias and retention semantics ambiguous.

`THAAP_CORRELATION_PEPPER` separately protects stable customer email/phone
pseudonyms and internal card fingerprints. Routine replay-key rotation must not
change this value, because doing so would split fraud velocity, graph, and ML
lineage. On the V17 upgrade, set it to the pre-rotation `THAAP_HASH_PEPPER`
value to preserve existing digests. Changing the correlation key is an explicit
offline data re-key operation, not part of the online replay-key procedure.

Implemented tenant-scoped endpoints:

- `POST /customers`
- `POST /payment_methods`
- `POST /payment_intents`
- `POST /payment_intents/{id}/confirm`
- `GET /payment_intents/{id}`
- `GET /payment_attempts/{id}`
- `GET /payment_attempts/{id}/authorization`
- `POST /payment_intents/{id}/capture`
- `POST /payment_intents/{id}/cancel`
- `POST /refunds`
- `GET /refunds/{id}`
- `GET /ledger/entries`
- `POST /webhook_endpoints`
- `POST /webhook_endpoints/{id}/rotate_secret`
- `POST /webhook_endpoints/{id}/disable`
- `POST /webhook_endpoints/{id}/verification_challenges`
- `GET /webhook_endpoints/{id}/verification_challenges/{challengeId}`
- `GET /webhook_deliveries`
- `GET /webhook_deliveries/{id}`
- `GET /webhook_deliveries/{id}/attempts`
- `POST /webhook_deliveries/{id}/redeliver`
- `GET /risk/webhook-dead-letters`
- `POST /risk/webhook-deliveries/{id}/redeliver`
- `GET /fraud/decisions`
- `GET /fraud/decisions/{id}`
- `GET /rules`
- `GET /fraud/reviews`
- `GET /fraud/reviews/{id}`
- `GET /reviews` (legacy empty review scaffold; Phase 3 work items use `/fraud/reviews`)

## Trusted Developer Scenario Operations

V61/V62 add a tenant-scoped cooperative scenario runner on top of the existing
public payment APIs. `X-Api-Key` authenticates the merchant and the separately
configured `X-Thaap-Scenario-Runner-Token` authorizes server assignment of
`SYNTHETIC_SCENARIO`; request bodies, metadata, and possession of a test-card
value cannot assign that origin.

The public endpoints are:

- `GET /developer/test-cards` — bounded simulator-only catalogue; no credential required
- `POST /developer/scenario-runs` — deterministic run creation; `Idempotency-Key` required
- `GET /developer/scenario-runs/{id}` — durable plan and progress
- `POST /developer/scenario-runs/{id}/claims` — bounded cooperative-client lease
- `POST /developer/scenario-runs/{id}/steps/{index}/complete` — verify a public API resource and advance exactly once
- `POST /developer/scenario-runs/{id}/fail` — terminal closed failure code
- `GET /developer/scenario-runs/{id}/report` — immutable sanitized terminal report

`APPROVED_PAYMENT`, `ISSUER_DECLINE`, and `CARD_TESTING_BURST` are closed
scenario types. The same merchant/type/catalogue/seed specification resolves to
the same run and step identities. PostgreSQL protects immutable specification,
step, event, and terminal-report history; a lease epoch fences stale browser or
CLI workers. The final report contains outcome and live-versus-synthetic counts
but excludes raw seed, test-card value, customer contact, credentials,
idempotency material, and resource IDs.

Configure bounded operation with:

```text
THAAP_DEVELOPER_SCENARIO_CREATION_ENABLED=true
THAAP_DEVELOPER_SCENARIO_LEASE_DURATION=45s
```

Run the zero-source-reading public quickstart documented in `scripts/README.md`.

Tenant isolation tests cover cross-merchant reads, customer ownership checks,
payment mutation checks, idempotency scoping, webhook delivery visibility, and
fraud/rule/review list scoping.

## Deterministic Processor Catalog

Chapter 20 makes the simulator mappings one immutable runtime contract keyed by
safe stored BIN-plus-last4 metadata. The catalog covers approval, issuer
decline, insufficient funds, suspected fraud, CVC failure, AVS line1 failure,
AVS postal failure, and 3DS-required. Any other valid simulator card follows
the documented default approval behavior.

The complete fixture table and the crucial processor-truth-versus-fraud-truth
distinction are documented in
[`PROCESSOR_AUTHORIZATION.md`](../../docs/learning/concepts/PROCESSOR_AUTHORIZATION.md).
Run the loopback-only public-API proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/demo-processor-outcome-catalog.py
```

The runner rejects all HTTP redirects before a second request is created, so
merchant API keys and the admin bootstrap token cannot leave the validated
loopback origin through redirect handling.

## Webhook Delivery Contract

Chapter 19 uses three durable records: an immutable `WebhookEvent`, one
endpoint-specific `WebhookDelivery`, and append-only numbered
`WebhookDeliveryAttempt` evidence. Payment state and its event/delivery rows
commit before external HTTP. The worker claims in a short transaction, sends
outside database transactions, then finalizes only the matching claim token.
Delivery is at least once. After signature verification, merchants must parse
the signed payload `id`, require it to equal `THAAP-Event-Id`, and deduplicate
on that authenticated payload value. The header is correlation metadata; it is
not independently covered by the HMAC.
Delivery listing uses a stable newest-first cursor. Pass the last row's
`createdAt` and `id` back as `beforeCreatedAt` and `beforeId` to read the next
page; both cursor fields are required together.

Endpoint creation accepts optional `enabledEvents`. If omitted, all currently
supported event types are subscribed. Creation and rotation reveal a
`whsec_...` signing secret once. THAAP stores only its prefix/hash plus an
AES-256-GCM ciphertext protected by the separately configured encryption key.
Creation requires `Idempotency-Key`; a lost reveal response is recovered by
replaying the identical request with that key during its 24-hour window.
Rotation is accepted only for
`ACTIVE` or `REQUIRES_SECRET_ROTATION` endpoints. A `DISABLED` endpoint returns
`409 Conflict`; Chapter 19 has no implicit re-enable action.

Every callback includes:

```text
Content-Type: application/json
User-Agent: THAAP-Webhook/1.0
THAAP-Event-Id: <stable-event-uuid>
THAAP-Delivery-Id: <delivery-uuid>
THAAP-Signature: t=<unix-seconds>,v1=<lowercase-hex-hmac-sha256>
```

Verify over the exact received bytes:

```text
signed_input = ascii(timestamp) + "." + raw_request_body
expected = HMAC-SHA256(endpoint_secret, signed_input)
```

Reject stale timestamps (five minutes is the local fixture tolerance), parse
the signature header strictly, and compare MACs in constant time before JSON
processing. Only then parse the signed body, validate its `id` as a UUID,
constant-time compare its exact value with `THAAP-Event-Id`, and record the
body `id` transactionally with merchant side effects so a lease-recovery
duplicate is harmless.
The loopback reference fixture demonstrates that boundary with a SQLite
uniqueness row keyed by the authenticated body `id` and bound to the exact
payload hash. The row is the representative merchant business effect: it
commits before `204`, survives restart, acknowledges an identical duplicate
without applying the effect again, and rejects reuse of one event ID with
different payload bytes.
After dispatch preparation, THAAP supplies a timestamp lower bound to the
signing transaction. That transaction acquires the endpoint row lock, captures
the actual signature time, validates the claim-bound generation, decrypts, and
signs before releasing the lock. Rotation therefore cannot move a current
generation to previous between authorization and cryptographic use, and time
spent waiting for the lock cannot authorize use after expiry. A stale
generation below the retry ceiling records retryable known-no-send evidence so
the next claim binds current state. On the final attempt, THAAP rebinds current
state before the first HTTP opportunity rather than exhausting on an internal
snapshot. Local signing-engine failure remains known-no-send; unexpected
database/runtime failure remains eligible for lease recovery.

Attempt 1 is immediate. Failed attempts schedule attempt 2 after 1 minute,
attempt 3 after 5 minutes, attempt 4 after 30 minutes, and attempt 5 after 2
hours; attempt 5 failure exhausts the delivery. Every `2xx` succeeds. Network
errors, timeouts, and `3xx`/`4xx`/`5xx` responses retry; redirects are never
followed and response bodies are discarded. Numeric statuses outside
`100..599`, unsupported/malformed HTTP-version tokens, and control-bearing
reason phrases are malformed transport failures. The text transport accepts
HTTP/1.0 and HTTP/1.1 status lines. Bounded informational `1xx` responses are
consumed and only a final status with syntactically valid response fields and a
complete, bounded HTTP response head determines delivery truth. Every non-empty
field line requires an ASCII HTTP-token name, an exact colon separator, and a
control-safe value; missing-colon, whitespace-name, invalid-token,
control-bearing, and obsolete-folded fields fail. A status line without the
terminating empty line is a post-send transport failure, not an
acknowledgement. The 32 KiB response-head budget includes status lines, fields,
CRLF framing, and every informational plus final section. Exactly 32 KiB is
accepted; the next byte fails.
Both event execution and the socket boundary reject a body larger than 64 KiB
measured as UTF-8 before outbound connection.

Operational metrics separate bounded attempt outcome, terminal status, and
failure `error.code`. They distinguish HTTP rejection, resolver/transport
failure, lease expiry, endpoint disable, target denial, and secret failure
without merchant, endpoint, delivery, resource, URL, payload, or secret tags.
Bulk-disable counters come from the rows actually updated per canonical event
type, not from a pre-update count that can race a worker claim.

Attempt reads expose `dispatchStartedAt` and a nullable `httpRequestSent`.
`false` means the attempt has not crossed the durable dispatch boundary, `true`
means the transport completed its request write, and `null` means the attempt
crossed that boundary but THAAP cannot yet or can no longer determine the
network outcome. That state covers an in-flight request, a partial socket write,
and crash/lease recovery.

Local execution is disabled by default. Generate a 32-byte key and enable it:

```bash
export THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export THAAP_WEBHOOK_SECRET_KEY_VERSION="v1"
export THAAP_WEBHOOK_EXECUTION_ENABLED=true
```

For key-encryption-key rotation, configure the new key/version for encryption
and retain prior keys as comma-separated `version=base64` entries in
`THAAP_WEBHOOK_SECRET_DECRYPTION_KEYS`. Startup fails if any still-decryptable
current, unexpired previous, or live creation-replay ciphertext uses an
unavailable version, even when delivery execution is disabled. The first
delivery under an old version decrypts it
under a row lock and re-encrypts the same merchant signing secret with the
current key, so rotation does not strand or exhaust deliveries.

Rotation and disable take a write lock on the endpoint row. Payment mutations
take a compatible shared lock while selecting active subscriptions and writing
their event/delivery rows. Therefore disable either waits for an already
selected delivery and exhausts it, or wins first and prevents that delivery
from being created. Concurrent rotation can never undo disable.

Local/test execution accepts loopback HTTP callbacks. Outside local/test,
callbacks require HTTPS, a non-empty comma-separated
`THAAP_WEBHOOK_ALLOWED_HOSTS`, and public resolved addresses. User-info,
fragments, redirects, private/link-local/multicast/metadata targets, IPv6
unique-local ranges, CGNAT, documentation/benchmark/reserved ranges, and
unchecked DNS re-resolution are rejected. DNS runs through a bounded resolver
pool with a configured resolution deadline and bounded queue, so a
non-returning platform resolver cannot stall the scheduler or grow work without
limit. URL/scheme/allowlist violations and prohibited resolved addresses are
terminal target-policy decisions. Resolver timeout, saturation, interruption,
unknown host, empty address answers, and resolver infrastructure failures are
retryable known-no-send outcomes. The request timeout is a wall-clock deadline
across TLS, write, and response parsing. The claim lease must exceed connect
plus request timeout. DNS-resolution, connect, and request durations must remain
positive bounded whole milliseconds after conversion; zero, negative,
fractional-millisecond, configured-maximum, and conversion-overflow values fail
startup rather than being truncated into different Java timeout semantics.
Workers claim one delivery immediately before dispatch rather than leasing a
waiting batch. These settings are bounded through the `THAAP_WEBHOOK_*`
configuration in `application.yml`.
The outbound HTTP request target is derived from the URI ASCII representation:
Unicode path/query characters are UTF-8 percent-encoded and existing percent
escapes remain unchanged instead of being replaced during ASCII socket writes.
`thaap.webhook.due` measures the remaining database backlog after each poll,
including observe-only polls while execution is disabled; disabled polls never
claim or send work.
attempt and terminal counters include lease-recovered `OUTCOME_UNKNOWN` and
disable/exhaustion paths. Finalization logs include bounded `latencyMs` without
payload, URL, signature, or secret material.

Run the signed failure-to-retry-to-success demonstration after starting the API
with execution enabled:

```bash
scripts/demo-webhook-delivery-flow.sh
```

### Verification, dead letters, and manual redelivery

V63/V64 add a merchant-verifiable endpoint ownership handshake and an
operator-visible recovery loop without creating a second event queue. A
verification challenge carries a random 32-byte base64url token inside a
canonical signed payload. PostgreSQL stores only its SHA-256 digest and binds
the challenge to the merchant, endpoint, normalized URL, signing-secret
generation, challenge ID, and expiry. The callback must return exactly one
43-character unpadded base64url value in the final successful response field
`THAAP-Webhook-Verification`. Informational and non-2xx fields, body echoes,
duplicates, folding, controls, padding, and malformed values fail closed.

External HTTP remains outside the database transaction. Preparation writes
durable challenge state, signing locks the exact endpoint generation, and a
post-sign endpoint-then-challenge permit revalidates pending/unexpired/current
state before recording the bounded dispatch lease. Transport then performs the
signed exchange, and a final locked transaction fences completion against
endpoint disable, URL change, secret rotation, expiry, or a newer pending
challenge. A bounded
scheduler expires overdue challenges idempotently. Configure its TTL, batch,
and polling with `THAAP_WEBHOOK_VERIFICATION_CHALLENGE_TTL` (30 seconds to 5
minutes), `THAAP_WEBHOOK_VERIFICATION_EXPIRY_BATCH_SIZE` (1 to 100), and
`THAAP_WEBHOOK_VERIFICATION_EXPIRY_POLL_DELAY`.

Dead-letter reads are tenant-scoped, keyset-bounded views of terminal
`EXHAUSTED` deliveries. They accept closed event/error/time filters and return
only sanitized identity, generation, attempt, and timestamp evidence—never
event payloads, endpoint URLs, signatures, tokens, hashes, secrets, or raw
response data. Merchant owners may request exact-event redelivery through the
merchant route. Operator dead-letter inspection and redelivery are restricted
to a live `RISK_MANAGER`; `VIEWER` and `ANALYST` are rejected on execution and
idempotent replay.
The existing idempotency boundary binds replay to the authenticated principal.

Manual redelivery appends an actor-bound `webhook_redelivery_requests` row,
advances the delivery generation by exactly one, and resets only the
generation-local retry budget. It reuses the immutable event UUID and persisted
UTF-8 payload. Attempts retain global and generation-local numbers, automatic
versus manual source, exact event digest, signing-secret generation, and send
certainty. HTTP 408, 425, 429, and 5xx results retry; other non-2xx statuses are
terminal receiver rejection. PostgreSQL triggers prevent event/request/event-
history mutation, skipped generations, counter rollback, and terminal attempt
or verification rewrites.

Readiness checks the V63 objects and bounded pending/expired challenge state.
Low-cardinality metrics distinguish verification, expiry, automatic retry, and
manual redelivery without tenant, actor, URL, event, payload, credential, or
secret tags. Run the end-to-end proof after enabling local webhook execution:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-webhook-operations.py
```

The script provisions a fresh merchant and manager, starts a loopback receiver,
verifies the endpoint, forces one permanent 400 failure, inspects the operator
dead-letter view, repairs the receiver, redelivers generation 2, verifies both
HMAC signatures, and proves the event ID and payload bytes are identical. It is
loopback-only, ignores proxies, rejects redirects, and emits sanitized evidence.

Chapter 5 contract tests also cover merchant admin setup, including bootstrap-token
gating, one-time API key reveal, persisted prefix/hash behavior, validation
errors, and immediate use of the returned key against merchant-facing APIs.

Customer and payment method contract tests prove that raw email, phone, and test
card numbers are not returned or persisted. Customer PII is stored as keyed
normalized hashes but hidden from public responses. Payment methods store an
internal keyed fingerprint, while public responses expose only simulator-safe
metadata: token, BIN, last4, funding type, and country.

## PaymentIntent Create Contract

`POST /payment_intents` creates the merchant-facing payment container for a
customer owned by the authenticated merchant. The request must include
`Idempotency-Key` and a body with `customerId`, positive `amount`, a supported
V1 `currency`, and optional `captureMethod`, `description`, and metadata.

V1 supported currencies are `USD`, `EUR`, `GBP`, and `INR`. Currency input is
case-insensitive but responses and storage normalize to uppercase. The simulator
currently caps PaymentIntent amount at `100000000` minor units until processor,
merchant-risk, and per-currency limits exist.

Metadata is a constrained `Map<String,String>`:

- at most 20 keys
- keys must match `[A-Za-z0-9_-]{1,40}`
- values must be non-null strings up to 500 characters
- metadata must not contain PAN, CVV, secrets, or sensitive personal data

The create path:

- scopes the customer lookup to `(customer_id, merchant_id)`
- stores `merchant_id`, `customer_id`, `amount`, normalized uppercase
  `currency`, `capture_method`, `status`, optional `description`, JSON metadata,
  `created_at`, and `updated_at`
- defaults missing `captureMethod` to `AUTOMATIC`
- starts each new PaymentIntent in `REQUIRES_CONFIRMATION`
- creates `payment_intent.created` webhook delivery records for active
  merchant endpoints after the PaymentIntent write succeeds
- returns only the public PaymentIntent response, not idempotency hashes,
  response bodies, API-key hashes, or unrelated merchant state. The public
  response includes `createdAt` and `updatedAt` for merchant reconciliation,
  debugging, webhook correlation, and analyst timelines.

Replaying the same request with the same idempotency key returns the original
response without creating another PaymentIntent or duplicate webhook delivery.
Reusing the same key with a changed body or different operation returns `409`.
Concurrent duplicates see the committed `IN_PROGRESS` reservation and return
`409` without running the mutation supplier.

## PaymentIntent Lifecycle Guards

PaymentIntent is the payment container, so it must reject impossible lifecycle
transitions before fraud, processor, ledger, or dispute side effects are built
on top.

Current guards:

- `cancel` is allowed from pre-terminal or uncaptured states such as
  `REQUIRES_CONFIRMATION`, `REQUIRES_PAYMENT_METHOD`, `REQUIRES_ACTION`, and
  `REQUIRES_CAPTURE`
- approved `AUTOMATIC` confirm captures inside the confirm transaction
- approved `MANUAL` confirm moves to `REQUIRES_CAPTURE`
- the public capture endpoint accepts only `MANUAL` PaymentIntents in
  `REQUIRES_CAPTURE`
- `refund` is only allowed from `CAPTURED`, and V1 only supports a full refund
  whose amount equals the captured PaymentIntent amount
- terminal or incompatible states return `409 conflict`

This intentionally makes immediate capture/refund after create invalid.
Approved confirm follows the merchant-selected capture method: `AUTOMATIC`
captures immediately and `MANUAL` waits in `REQUIRES_CAPTURE`. Later
refund/ledger chapters will replace the full-refund-only V1 guard with durable
refund objects and reversal accounting.

## PaymentIntent Confirm And PaymentAttempt Contract

`POST /payment_intents/{id}/confirm` creates the first durable scoring/audit
unit for a payment flow: `PaymentAttempt`.

The Phase 3 confirm path:

- requires `Idempotency-Key`
- accepts `paymentMethodId`
- loads the PaymentIntent through `(id, merchant_id)`
- loads the PaymentMethod through `(id, merchant_id)`
- verifies the PaymentMethod belongs to the same customer as the PaymentIntent
- creates attempts for PaymentIntents in `REQUIRES_CONFIRMATION` or the
  retryable-decline state `REQUIRES_PAYMENT_METHOD`
- stores `payment_intent_id`, `payment_method_id`, `idempotency_key`, `status`,
  `attempt_number`, nullable fraud/processor/3DS fields, and timestamps
- serializes attempt numbering with a pessimistic PaymentIntent row lock
- evaluates all three immutable `rules_v1` definitions against the exact typed
  `DecisionFeatureContext`, persists every match and non-match, and aggregates
  with `BLOCK > REVIEW > REQUEST_3DS > ALLOW`
- persists a durable `FraudDecision` with action, risk score, risk level,
  latency, ruleset version, reason codes, feature freshness status, and
  decision timestamps
- materializes the exact seven Chapter 22 V1 features through immutable
  `FeatureDefinition` rows and typed, point-in-time `FeatureValue` rows
- constructs one exact, immutable `DecisionFeatureContext` from those values;
  this safe context is the fraud engine's only input and is also the source of
  the persisted snapshot, so BIN, last4, IDs, metadata, and other unrecorded
  facts cannot influence a decision
- persists an immutable lexical `FeatureSnapshot` with `features_v1`, computed
  time, oldest feature timestamp, max feature age, freshness status, and
  stale/missing feature names
- explicitly flushes the complete decision/snapshot/value lineage before any
  processor call while retaining rollback in the confirm transaction
- attaches the `fraudDecisionId` to the PaymentAttempt
- applies branch-specific PaymentAttempt and PaymentIntent truth before any
  external call: processor-ready, blocked, action-required, or in-review
- creates one tenant-scoped `OPEN` ManualReviewCase atomically for `REVIEW`
- calls `ProcessorAdapter` only after an `ALLOW` decision; every non-allow
  branch creates zero Authorization, capture, void, refund, or ledger rows
- persists a durable `Authorization` row with processor name, processor
  reference, response code, network status, requested amount, authorized
  amount, currency, verification checks, non-null 3DS result, simulator
  outcome code, and creation time
- maps approved outcomes to PaymentAttempt `AUTHORIZED` and PaymentIntent
  `REQUIRES_CAPTURE`
- maps retryable declines to PaymentAttempt `DECLINED` and PaymentIntent
  `REQUIRES_PAYMENT_METHOD`
- maps 3DS-required outcomes to PaymentAttempt `REQUIRES_ACTION`, PaymentIntent
  `REQUIRES_ACTION`, and `threeDsStatus=REQUESTED`
- writes `audit_logs` for `payment_intent.confirmed`,
  `fraud_decision.completed`, and the final authorization outcome
- emits `payment_attempt.created` delivery rows for active webhook endpoints

The confirm response returns the public PaymentAttempt shape. It does not
include internal request hashes, stored idempotency response bodies, card
fingerprints, or merchant secrets. Audit payloads also omit raw card input, raw
PII, API keys, card fingerprints, and idempotency hashes.
`AuditLogService` sanitizes before/after payloads at the service boundary so a
future caller cannot accidentally persist raw PAN, CVV, API keys,
idempotency keys, email, phone, passwords, tokens, or long card-like digit
sequences. Audit actor identity stores `actor_type=MERCHANT_API_KEY` and
`actor_id` as the API-key prefix, while `merchant_id` remains the tenant
boundary.

The feature snapshot storage guard defaults to `16384` serialized UTF-8 bytes
and can be configured with:

```yaml
thaap:
  fraud:
    feature-snapshot:
      max-json-bytes: ${THAAP_FEATURE_SNAPSHOT_MAX_JSON_BYTES:16384}
```

Actuator metrics include:

- `thaap.audit.logs.created`
- `thaap.audit.logs.failed`
- `fraud_feature_freshness_total{status,traffic_origin}`
- `fraud_feature_materialization_seconds{outcome,traffic_origin}`
- `fraud_decision_failures_total{stage,cause,feature_code}`
- `fraud_rule_evaluations_total{rule_code,outcome}`
- `fraud_decisions_total{action,risk_level,traffic_origin}`
- `fraud_decision_latency_seconds{action}`
- `fraud_review_cases_opened_total{traffic_origin}`
- `fraud_review_cases_canceled_total{traffic_origin}`
- `fraud_review_case_cancellation_failures_total{status,traffic_origin,cause}`

Metric dimensions are bounded catalogue/stage enums and codes. They never include a
merchant/resource identifier, feature value, card field, PII, credential, or
idempotency material. V18 adds the typed schema and seven definitions. V19
rejects definition insertion/mutation/deletion and historical decision,
snapshot, or value updates on both H2 and PostgreSQL.

V22 forward-repairs the originally shipped V18 three-column feature identity
into a definition ID/code/version/type foreign key without editing applied
history. V23 similarly binds copied rule-result meaning and review case
payment/decision/reason/origin lineage; V24 makes review identity/reasons
immutable and permits only `OPEN -> CANCELED`. PostgreSQL therefore rejects values or
fraud evidence whose declared semantics disagree with their immutable source.
V25 conservatively removes V20-added fraud/review event subscriptions from
legacy endpoints whose old and new event rows belong to different creation
cohorts. Exact-five and partial legacy selections therefore remain unchanged,
while endpoints created after Phase 3 retain their explicit nine-event set.
A real confirm-path PostgreSQL test
also forces a lineage constraint failure at the explicit flush and proves zero
processor calls, total rollback, reservation release, and one successful
same-key retry.

Audit created/failed counters are transaction-completion counters: an audit row
counts as created only after commit, and counts as failed if it was registered
but the surrounding transaction rolls back or cannot commit.

`GET /payment_attempts/{id}` fetches attempts through merchant scope.
`GET /payment_attempts/{id}/authorization` fetches safe processor
authorization evidence for the attempt through the same merchant scope. It
returns processor name/reference, response code, network status, requested and
authorized amounts, authorization status, verification results, 3DS result, and
creation time. It also returns processor-neutral authorization-void evidence
when an approved manual authorization was canceled: `voided`, void ID, status,
reason, and timestamp. The original authorization remains `APPROVED` because
the void is a later lifecycle fact, not a rewrite of the issuer decision. The
merchant-facing contract deliberately omits the internal
simulator outcome field and uses processor-neutral evidence. It does not expose
raw payment input, PII, API keys, idempotency state, hashes, card fingerprints,
or webhook secrets.

Historical Chapter 13 decisions deliberately remain `rules_stub_v1` with
`RULES_NOT_ENABLED`, nullable `modelVersion`, and no fabricated rule results.
New decisions use `rules_v1`; their fixed severity score remains a rules score,
not a model probability.
Processor simulator outcomes are stored on `Authorization.simulatorOutcomeCode`
because they are produced after the fraud decision and represent processor
truth, not fraud-decision reason truth.

`GET /fraud/decisions` returns tenant-scoped decision summaries.
`GET /fraud/decisions/{id}` returns the exact snapshot and ordered rule results.
`GET /fraud/reviews` supports status, limit 1–100, and an owned stable cursor;
`GET /fraud/reviews/{id}` returns one case. The existing idempotent PaymentIntent
cancel endpoint is the only Phase 3 review mutation and moves `OPEN` to
`CANCELED` with `MERCHANT_CANCELED`. Assignment, analyst resolution, notes,
labels, and review-triggered payment actions remain Phase 4 scope.

The confirmed Phase 4 plan sequences dedicated risk-operator identity, review
assignment/resolution and label truth before approval/refund actions,
authentication completion, RiskContext, Redis velocity/lists, merchant policy,
chargebacks, post-authorization actions, incidents, and rate limiting. The plan
and Chapter 26 proof artifacts were confirmed by founder/domain review before
code. Chapters 26–32 are complete. Their operator, assignment, annotation,
cancel/report-fraud, approval, same-attempt processor continuation, capture, and
typed label boundaries plus source-bound authentication, safe RiskContext,
typed real-time context features, exact Redis velocity, typed fraud lists, and
V26–V40 schema pass verified matrices, closure dossiers, retained migration
proof, real Redis/deterministic PostgreSQL interleavings, full regression
suites, and public live demos. Chapter 31 additionally proves immutable
merchant risk profiles/policies, dual control, typed threshold evaluation,
protected action aggregation, V41/V42 lineage, and a retained-database public
demo. Chapter 32 adds merchant-ingested simulator chargebacks, exact refund
offsets, balanced dispute ledger groups, reason-qualified label availability,
and bounded DRAFT evidence packets with V43/V44 PostgreSQL protection. Phase 4
is complete through Chapter 35 integrated closure. Chapter 33 adds append-only
merchant-ingested signals, recommendation-versus-action truth, manager-
authorized full-refund reuse, balanced ledger, and label lineage. Chapter 34
adds durable suspected card-testing incidents plus exact Redis fixed-window
limits: definite exceedance is `429`, dependency ambiguity is `503`, and Redis
identities are HMAC-only.
The final Phase 4 gate passes 629 backend tests, 114 repository Python tests,
fresh V1-to-V49 and retained V48-to-V49 proof, and all nine public
implementation-chapter demos.

## Phase 4.5 Simulation Activation Gate

Historical simulation is evidence, not automatic permission to change a live
risk control. Activation defaults to at least 100 eligible rows and 50% label
coverage:

```text
THAAP_SIMULATION_GATE_MINIMUM_ELIGIBLE_POPULATION=100
THAAP_SIMULATION_GATE_MINIMUM_LABEL_COVERAGE_PERCENT=50
```

Both values are rechecked inside the locked control activation or rollback
transaction together with successful status, exact tenant/candidate digest,
creator, and report freshness. The one-row governed-controls public demo may
use `1` and `0` only in the local proof environment; the exact command and
warning are documented in `scripts/README.md`. Absence of either override
retains the production-safe default.

Phase 4.5 is complete through Chapter 43R. Fresh/retained V1–V64 migrations,
forced PostgreSQL orderings, simulation and key-lifecycle restarts, rule and
protocol boundary families, seven public workflows, live browser verification,
759 backend tests, 140 repository tests, 20 UI tests plus the production build,
and all Chapter 36–43R matrix/dossier gates pass. Founder implementation
authorization on 2026-07-27 confirmed the Phase 5 detailed plan's sixteen
decisions before implementation began. Phase 5 is complete: all Chapter 44–52
`PRE_IMPLEMENTATION` matrices are
`FROZEN`/`VERIFIED`/all-`PASS`, and every corresponding dossier is
`READY_TO_MERGE`/`MERGE`. V65–V85 implement canonical events, exact immutable
event-to-decision/snapshot/payment lineage, Kafka recovery, idempotent
projections, losslessly paginated analytics, rebuild/activation, and
point-in-time replay datasets. The public payment-risk analytics demo and real
Kafka/PostgreSQL proof pass.
Phase 5 implementation status: COMPLETE
The permanent THAAP vision matrix now
maps 23 challenger promises to exact current evidence and Phase 5–10 owner
slices; the readiness gate rejects vague or stale coverage. The API also does
not claim Phase 6's unified decision/developer product, Phase 7 governed ML,
Phase 8 network/graph/underwriting intelligence, Phase 9 hosted trust/agents,
real-network dispute handling, or partial-money semantics.

Run the complete loopback-only Phase 3 proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/demo-fraud-decision-loop.py
```
`GET /fraud/decisions/{id}` returns a tenant-scoped decision plus its linked
feature snapshot.

## Processor Simulator

The processor simulator is deterministic and test-card driven, but it reads
only safe stored card metadata after PaymentMethod creation. It does not depend
on raw PAN after tokenization. The in-memory catalog key is explicitly
`card_bin:card_last4`, for example `499999:0001`; this is simulator-specific
lookup logic, not production processor-routing logic.

Initial simulator catalog:

- `4999990000000001` -> approved
- `4999990000000002` -> issuer decline
- `4999990000000003` -> insufficient funds
- `4999990000000004` -> suspected fraud decline
- `4999990000000005` -> CVC failure
- `4999990000000006` -> AVS line1 failure
- `4999990000000007` -> AVS postal failure
- `4999990000000008` -> 3DS required
- unknown simulator cards, including `4242424242424242` -> approved

The `authorizations` table is the source of truth for processor outcomes. The
`payment_attempts.processor_authorization_id` field stores the simulator
processor reference for response correlation, but later capture, refund,
dispute, fraud, and analyst workflows should join to `authorizations` for full
processor truth. Each authorization stores both `requested_amount` and
`authorized_amount` so future partial authorization handling can distinguish
what THAAP asked for from what the network approved. Approved simulator
authorizations set `authorized_amount` to the requested amount; declined and
3DS-required simulator outcomes set `authorized_amount` to `0` because no funds
are reserved yet.

## Capture And Ledger Contract

`POST /payment_intents/{id}/capture` is the Chapter 15 money-truth chapter.
It requires `Idempotency-Key`, loads the PaymentIntent through merchant scope
with a row lock, requires status `REQUIRES_CAPTURE`, and requires a
tenant-scoped approved, non-voided `Authorization` from an authorized
PaymentAttempt. Only `MANUAL` PaymentIntents can use this endpoint.

Capture is full-amount only in this chapter. Inside the same idempotent
transaction, the API:

- writes one balanced ledger transaction group with two append-only entries:
  debit `CUSTOMER_RECEIVABLE` and credit `MERCHANT_PAYABLE`
- moves the PaymentIntent to `CAPTURED`
- creates `payment_intent.captured` webhook delivery records for active
  endpoints
- writes a safe `payment_intent.captured` audit log with the authorization ID,
  ledger transaction group ID, and ledger entry IDs

If ledger persistence, webhook creation, audit persistence, or idempotency
completion fails, the capture state change rolls back. Idempotent replay of the
same capture key returns the original response without duplicating ledger
entries, webhook deliveries, or audit logs. A different idempotency key after
capture returns `409`.

For `AUTOMATIC` PaymentIntents, the same capture ledger, webhook, audit, and
state transition runs during approved confirm. Confirm idempotency therefore
protects authorization and capture as one transaction: a ledger or audit
failure rolls the PaymentIntent back to `REQUIRES_CONFIRMATION` and removes the
attempt, fraud lineage, authorization, webhook, ledger, and idempotency
completion.

## Cancel And Authorization Void Contract

`POST /payment_intents/{id}/cancel` requires `Idempotency-Key` and serializes on
the merchant-scoped PaymentIntent row.

- Cancel before approval, after a retryable decline, or while 3DS action is
  required moves the intent to `CANCELED` without inventing void evidence.
- Cancel from the reserved `AUTHORIZED` state or `REQUIRES_CAPTURE` appends one
  `authorization_voids` row linked to the same merchant, Authorization,
  PaymentAttempt, and PaymentIntent.
- The void uses status `RECORDED` and reason `MERCHANT_CANCELED`. This is local
  simulator invalidation truth, not a claim that a real card network reversal
  occurred.
- Cancel, void evidence, webhook delivery records, audit log, and idempotency
  completion commit or roll back together.
- Captured, refunded, already-canceled, or otherwise terminal intents reject
  cancellation.

V6 composite foreign keys reject cross-object or cross-tenant void lineage, and
unique constraints allow only one void per authorization and PaymentIntent.

`GET /ledger/entries` returns only the current merchant's safe ledger fields:
entry ID, transaction group ID, source type, source ID, PaymentIntent ID,
optional Refund ID, account, direction, amount, currency, and creation time. It
does not expose another merchant's ledger rows. The endpoint defaults to 50
rows and rejects limits above 100 so merchant financial history cannot
accidentally become an unbounded read path.

The ledger schema protects source truth at the database layer. Capture rows
retain the PaymentIntent as their source and use debit `CUSTOMER_RECEIVABLE` /
credit `MERCHANT_PAYABLE`. Refund rows use the durable Refund as their source,
retain the original PaymentIntent ID, and exactly reverse those postings with
debit `MERCHANT_PAYABLE` / credit `CUSTOMER_RECEIVABLE`. Composite foreign keys
prevent a Refund from being attached to another payment's ledger rows.

## Durable Full Refund Contract

`POST /refunds` requires `Idempotency-Key`, `paymentIntentId`, and the full
captured amount. The operation locks the merchant-scoped PaymentIntent and
requires all three claims before money truth changes:

- PaymentIntent status is `CAPTURED`
- a matching approved Authorization exists
- exactly two balanced capture-ledger rows match amount and currency

The transaction persists a `RECORDED` Refund linked to the same PaymentIntent,
PaymentAttempt, and Authorization; writes two balanced reversal entries; moves
the PaymentIntent to `REFUNDED`; creates `refund.created` webhook delivery
records using the Refund ID; writes a safe Refund audit log; and completes
idempotency. Any failure rolls all of those writes back.

`GET /refunds/{id}` is tenant-scoped and returns the durable Refund lineage.
`RECORDED` means the local simulator has recorded the reversal; it does not
claim a card network or acquirer completed an external refund. Partial refunds,
refund reasons, approval workflows, fee reversal, and external refund calls
remain deferred.

## Database Migrations

The API schema is managed by Flyway from `src/main/resources/db/migration`.
Hibernate validates entity mappings against the migrated schema; it does not
create or update durable local tables.

The initial migration creates tenant-scoped tables for merchants, customers,
payment methods, payment intents, payment attempts, idempotency records,
webhook endpoints and deliveries, fraud decisions, fraud rules, and review
cases. It also installs
foreign keys, composite tenant ownership constraints, hashed secret columns,
webhook retry metadata, safe card metadata, PaymentIntent description/metadata,
capture method storage, and hot lookup indexes such as
`(merchant_id, idempotency_key)` and `(merchant_id, status)` for idempotency
records.

Chapter 11 authorizations are added through a follow-on Flyway migration. V5
adds append-only `ledger_entries` for capture accounting. V6 adds constrained
`authorization_voids` and preserves already-approved legacy automatic rows.
Forward-only V7 completes the compatibility boundary by converting every other
legacy non-terminal `AUTOMATIC` row that could still progress toward capture to
`MANUAL`. This preserves the old explicit-capture behavior for existing
confirms, retries, action flows, recovery, and approved payments instead of
changing money movement during deployment. Terminal rows remain untouched as
historical truth. V8 adds durable Refund lineage and refund ledger source
constraints. It backfills explicit PaymentIntent lineage onto capture rows and
fails fast when pre-V8 `REFUNDED` rows exist because their random response ID,
authorization lineage, and accounting evidence cannot be reconstructed
honestly. This local-only environment must be reset or explicitly remediated
before retrying that upgrade. Do not edit an already-applied migration to add
new durable product truth.

V9 adds immutable webhook events, endpoint subscriptions, append-only delivery
attempts, lease/claim state, encrypted-secret columns, safe terminal errors, and
same-tenant composite constraints. Existing endpoint hashes cannot recover the
original HMAC key, so V9 marks those endpoints `REQUIRES_SECRET_ROTATION`
instead of inventing a usable secret. Existing delivery rows receive a
deterministic immutable envelope and become claimable only after their endpoint
is safely rotated. Pristine queued rows owned by a legacy endpoint already
marked inactive become `EXHAUSTED` with `ENDPOINT_DISABLED`.
Released V8 had no webhook executor or attempt table. If a V8 row nevertheless
claims execution through a non-pending status, nonzero attempt count, retry or
last-attempt time, delivered time, or last error, V9 fails before creating new
schema truth. Reset or explicitly remediate that local state; V9 will not
preserve aggregate success/retry claims without authoritative attempt evidence.
V8 also had no database event-type constraint. V9 therefore rejects any value
outside the five canonical Chapter 19 event types before its first DDL
statement rather than leaving partial local migration state.
Because Chapter 19's V9 was exercised locally while still uncommitted, a local
volume that contains an earlier draft V9 may have the same Flyway version but an
older table shape. Hibernate validation intentionally rejects that false schema.
Preserve any needed data separately, then reset the local-only volume; do not
repair it by editing the finalized migration after merge.

V10 adds trusted traffic-origin lineage. V11 adds webhook secret generations,
endpoint-create replay, and the fair dispatch cursor; V12 enforces exact
tenant/URL identity. V13 additively repairs the earlier pre-dispatch attempt
shape. V14 leases endpoint-create reservations, and V15 leases general
idempotency plus merchant creation. V16 versions merchant creation derivation
and backfills each V15 key HMAC into `merchant_creation_identities`, preserving
one database identity across pepper rotation without rewriting the V15
migration.

V63 adds generation-aware delivery and attempt lineage, immutable redelivery
requests, hashed endpoint-verification challenges/events, endpoint verification
projection state, composite tenant constraints, and bounded operational
indexes. Retained deliveries remain in generation 1; their total attempt count
becomes the generation-local count, historical attempts retain their original
number and acquire the original event digest, and no request or challenge is
invented. V64 installs PostgreSQL guards for immutable identities, monotonic
generation transitions, append-only request/event history, and terminal
attempt/challenge state. The retained V62→V64 migration test proves the original
delivery and attempt fields remain equivalent.

V4 creates the `audit_logs` table and its first query indexes. For local
development and test databases, the single Flyway migration is intentionally
simple. For a real production rollout on a live high-traffic database, split the
operational rollout:

- deploy application code with the audit-write behavior behind normal migration
  sequencing
- create new audit tables before writes depend on them
- use `CREATE INDEX CONCURRENTLY` for indexes on large live tables
- add heavyweight foreign keys with `NOT VALID`, then validate them in a
  follow-up migration
- watch Flyway logs plus `thaap.audit.logs.failed`,
  `thaap.audit.logs.created`, `fraud_feature_freshness_total`,
  `fraud_feature_materialization_seconds`, and
  `fraud_decision_failures_total`

V6 and V7 update legacy `payment_intents` rows, while V6 also adds supporting
unique constraints to `payment_attempts` and `authorizations`. In a real
high-volume deployment, split compatibility updates into bounded backfills and
create supporting unique indexes with an online/concurrent rollout before
attaching foreign keys. Later refund, dispute, fee, ledger, and audit migrations
will touch similarly hot tables.

Chapters 1-3 local databases were created with temporary Hibernate scaffolding. If
that local PostgreSQL volume still exists, reset it before first Flyway startup:

```bash
docker compose -f infra/docker/compose.yml down --volumes
scripts/local-infra-up.sh
```

## Request IDs And Error Contract

Every API response includes `X-Request-Id`. Clients can provide one for
correlation, or the API generates one:

```bash
curl -i -X POST http://localhost:8080/customers \
  -H 'X-Api-Key: thaap_test_...' \
  -H 'X-Request-Id: req_demo_001' \
  -H 'Content-Type: application/json' \
  -d '{"email":"not-an-email"}'
```

Representative error response:

```json
{
  "requestId": "req_demo_001",
  "timestamp": "2026-06-01T10:15:30Z",
  "status": 400,
  "error": "validation_failed",
  "message": "Request validation failed",
  "path": "/customers",
  "fieldErrors": [
    {
      "field": "email",
      "message": "must be a well-formed email address"
    }
  ]
}
```

The same shape is used for 400, 401, 404, 409, and 500-class failures. Request
IDs are also placed in logging MDC under `requestId`.

## Local Run

From the repository root:

```bash
scripts/local-infra-up.sh
mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
scripts/payments-api-run.sh
```

Health endpoints:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

The default `local` profile connects to PostgreSQL and Redis from
`infra/docker/compose.yml`. The `test` profile uses an in-memory H2 database
and disables Redis health so unit-level checks do not require Docker.
