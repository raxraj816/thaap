# payments-api

Primary V1 backend deployable.

Implemented Day 2 stack:

- Java 25
- Spring Boot 4.0.6
- Spring Web
- Spring Validation
- Spring Data JPA
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

`Merchant` is the tenant root. Admin setup creates merchants and issues an
API key:

```bash
curl -X POST http://localhost:8080/admin/merchants \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo Merchant"}'
```

Merchant-facing APIs derive tenant scope from `X-Api-Key`; clients must not be
trusted to choose `merchantId` in request bodies. Mutating payment APIs require
`Idempotency-Key`, and idempotency records are unique per `(merchant_id, key)`.

Implemented tenant-scoped endpoints:

- `POST /customers`
- `POST /payment_methods`
- `POST /payment_intents`
- `GET /payment_intents/{id}`
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
