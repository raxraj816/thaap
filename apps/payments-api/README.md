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

## Local Run

From the repository root:

```bash
scripts/local-infra-up.sh
mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository spring-boot:run
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
