# payments-api

Primary V1 backend deployable.

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
API key once. This bootstrap endpoint is disabled unless
`THAAP_ADMIN_BOOTSTRAP_TOKEN` is configured, and requests must send the matching
`X-Admin-Bootstrap-Token` header:

```bash
curl -X POST http://localhost:8080/admin/merchants \
  -H 'X-Admin-Bootstrap-Token: change-me-local-admin-bootstrap-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo Merchant"}'
```

Merchant-facing APIs derive tenant scope from `X-Api-Key`; only the API key
prefix and hash are stored. Clients must not be trusted to choose `merchantId`
in request bodies. Mutating payment APIs require `Idempotency-Key`, and
idempotency records are unique per `(merchant_id, key)`.

The idempotency service now treats each key as a small state machine:

- reserve a merchant-scoped `IN_PROGRESS` row before running the mutation
- bind the key to an operation name and keyed request hash
- reject duplicates while the first mutation is still in progress
- mark the row `COMPLETED` only after the mutation response is durable
- replay completed responses for exact retries
- release the reservation when the mutation fails so retryable failures are not
  cached as successful work

`THAAP_HASH_PEPPER` must also be configured so customer PII hashes, internal
card fingerprints, and idempotency request hashes use keyed, domain-separated
HMACs instead of plain deterministic SHA-256.

Implemented tenant-scoped endpoints:

- `POST /customers`
- `POST /payment_methods`
- `POST /payment_intents`
- `POST /payment_intents/{id}/confirm`
- `GET /payment_intents/{id}`
- `GET /payment_attempts/{id}`
- `POST /payment_intents/{id}/capture`
- `POST /payment_intents/{id}/cancel`
- `POST /refunds`
- `POST /webhook_endpoints`
- `GET /webhook_deliveries`
- `GET /fraud/decisions`
- `GET /rules`
- `GET /reviews`

Tenant isolation tests cover cross-merchant reads, customer ownership checks,
payment mutation checks, idempotency scoping, webhook delivery visibility, and
fraud/rule/review list scoping.

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
  `REQUIRES_CONFIRMATION`, `AUTHORIZED`, and `REQUIRES_CAPTURE`
- `capture` is only allowed from `REQUIRES_CAPTURE`
- `refund` is only allowed from `CAPTURED`, and V1 only supports a full refund
  whose amount equals the captured PaymentIntent amount
- terminal or incompatible states return `409 conflict`

This intentionally makes immediate capture/refund after create invalid. Later
processor and capture chapters will move PaymentIntents into `REQUIRES_CAPTURE`
or `CAPTURED` through reviewed side effects instead of test shortcuts. Later
refund/ledger chapters will replace the full-refund-only V1 guard with durable
partial-refund accounting.

## PaymentIntent Confirm And PaymentAttempt Contract

`POST /payment_intents/{id}/confirm` creates the first durable scoring/audit
unit for a payment flow: `PaymentAttempt`.

The Chapter 10 confirm path:

- requires `Idempotency-Key`
- accepts `paymentMethodId`
- loads the PaymentIntent through `(id, merchant_id)`
- loads the PaymentMethod through `(id, merchant_id)`
- verifies the PaymentMethod belongs to the same customer as the PaymentIntent
- only creates attempts for PaymentIntents in `REQUIRES_CONFIRMATION`
- stores `payment_intent_id`, `payment_method_id`, `idempotency_key`, `status`,
  `attempt_number`, nullable fraud/processor/3DS fields, and timestamps
- serializes attempt numbering with a pessimistic PaymentIntent row lock
- calls a deterministic in-memory confirm decision engine that returns
  `ALLOW` with reason code `RULES_NOT_ENABLED`
- moves the PaymentAttempt from `CREATED` to `READY_FOR_PROCESSOR`
- moves the PaymentIntent from `REQUIRES_CONFIRMATION` to `PROCESSING`
- emits `payment_attempt.created` delivery rows for active webhook endpoints

The confirm response returns the public PaymentAttempt shape. It does not
include internal request hashes, stored idempotency response bodies, card
fingerprints, or merchant secrets.

`GET /payment_attempts/{id}` fetches attempts through merchant scope.

Durable `FraudDecision`, `FeatureSnapshot`, processor authorization, final
authorization/decline mapping, and ledgering remain deferred to the
master-plan chapters that own those side effects. Chapter 10 deliberately does
not persist the current thin `fraud_decisions` placeholder row. When
FeatureSnapshot is implemented, it must include freshness metadata: computed
time, oldest feature timestamp, max feature age, freshness status, stale
feature names, and missing feature names.

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
