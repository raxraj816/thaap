
# End-to-End Payment + Fraud Detection Platform Implementation Plan

> Reference only. This original long-form plan is preserved for background. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

**Role framing:** Senior Staff Software Engineer + ML Platform Architect + Payments Architect  
**Product goal:** Build a real-world payment platform with integrated fraud detection, ML scoring, graph intelligence, rule simulation, analyst operations, and GenAI-powered fraud intelligence agents.

This plan assumes a serious execution timeline of **180 days / 26 weeks**. The goal is not only to create a demo, but to build a product-grade foundation that can become a Stripe Radar-like fraud platform with its own payment lifecycle.

---

## 0. Product Vision

Build a platform that supports the full lifecycle:

```text
Merchant / Checkout
   ↓
PaymentIntent creation
   ↓
PaymentMethod attachment
   ↓
Payment confirmation
   ↓
Real-time fraud evaluation
   ↓
Processor authorization simulation
   ↓
Authorization / decline / step-up / manual review
   ↓
Capture / refund / chargeback
   ↓
Ledger + events + dashboard
   ↓
Fraud labels + ML feedback loop
   ↓
Rules, models, graph intelligence, agents improve the system
```

The final product should include:

1. Payment service with PaymentIntent lifecycle.
2. Fraud decision service with rules, features, ML score, and graph risk.
3. Processor adapter simulation.
4. Ledger and idempotency foundation.
5. Kafka event-driven architecture.
6. PostgreSQL transactional store.
7. Redis real-time feature/velocity store.
8. ClickHouse analytics store.
9. Neo4j graph fraud intelligence.
10. ML training and model-serving platform.
11. Manual review and chargeback feedback loop.
12. Rule registry, rule simulation, and approval workflow.
13. React fraud operations dashboard.
14. GenAI fraud intelligence agents for investigation, explanation, model evaluation, and rule recommendation.
15. Production-grade observability, testing, security, and deployment.

---

## 1. Architecture Principles

### 1.1 Separate real-time payment decisions from offline intelligence

The real-time path must be deterministic, low-latency, observable, and safe.

```text
Payment confirmation
   ↓
Feature extraction
   ↓
ML score
   ↓
Rules evaluation
   ↓
Decision engine
   ↓
Processor authorization or friction/decline/review
```

The offline/near-real-time path can use agents and heavier analytics.

```text
Kafka events + chargebacks + review labels
   ↓
Analytics store + graph store + model logs
   ↓
Fraud intelligence agents
   ↓
Rule recommendations + model insights + analyst reports
```

### 1.2 Do not put LLMs in the real-time authorization path

LLMs should support investigation, explanation, and rule recommendation. They should not directly approve or decline payments.

### 1.3 Every important decision must be auditable

For each payment decision, persist:

- PaymentIntent ID
- PaymentAttempt ID
- FraudDecision ID
- Feature snapshot
- Model version
- Rule versions
- Risk scores
- Reason codes
- Final action
- Latency
- Timestamp

### 1.4 Every money movement must be ledgered

Even in MVP, capture and refund should create ledger entries with a double-entry invariant:

```text
sum(debits) == sum(credits)
```

---

## 2. Target Technology Stack

### Backend

- Java 21
- Spring Boot 3.x
- Spring Web
- Spring Security
- Spring Data JPA
- Flyway
- Spring Validation
- Spring Actuator
- Resilience4j

### Data and Messaging

- PostgreSQL for transactional state
- Redis for velocity counters and idempotency acceleration
- Kafka for events
- ClickHouse for analytics
- Neo4j for graph intelligence
- S3-compatible object storage for datasets and model artifacts

### ML Platform

- Python
- FastAPI
- XGBoost / LightGBM
- scikit-learn
- pandas / NumPy
- MLflow
- SHAP

### Frontend

- React
- TypeScript
- Tailwind CSS
- TanStack Query
- Recharts
- Zustand or Redux Toolkit

### DevOps

- Docker
- Docker Compose
- Kubernetes
- Helm
- Terraform
- GitHub Actions
- OpenTelemetry
- Prometheus
- Grafana
- Loki / ELK

---

## 3. Core Services

### MVP deployable services

1. `payment-service`
2. `fraud-decision-service`
3. `feature-service`
4. `rules-engine-service`
5. `processor-adapter-service`
6. `ledger-service`
7. `manual-review-service`
8. `chargeback-service`
9. `graph-intelligence-service`
10. `model-serving-service`
11. `rule-simulation-service`
12. `fraud-intelligence-agent-service`
13. `analyst-dashboard-ui`

For speed, early phases can run some modules inside fewer deployables, but package boundaries should remain clean.

---

## 4. Critical Domain Objects

### Payment domain

- Merchant
- Customer
- PaymentIntent
- PaymentMethod
- PaymentAttempt
- Authorization
- Capture
- Refund
- LedgerEntry
- IdempotencyKey
- ProcessorEvent
- WebhookDelivery

### Fraud domain

- FraudDecision
- FeatureSnapshot
- FraudRule
- RuleExecutionResult
- RuleSimulationResult
- ManualReviewCase
- Chargeback
- FraudLabel
- ModelPrediction
- ModelVersion
- RiskReasonCode

### Graph domain

- Customer
- Device
- IP
- PaymentMethod
- CardFingerprint
- Merchant
- Transaction
- Chargeback
- FraudCase
- Email
- Phone
- Address

---

## 5. PaymentIntent Lifecycle

```text
REQUIRES_PAYMENT_METHOD
   ↓
REQUIRES_CONFIRMATION
   ↓
REQUIRES_FRAUD_CHECK
   ↓
PROCESSING
   ↓
AUTHORIZED
   ↓
CAPTURED
```

Alternative states:

```text
REQUIRES_ACTION
MANUAL_REVIEW
DECLINED
DECLINED_BY_FRAUD
DECLINED_BY_PROCESSOR
CANCELED
VOIDED
FAILED
REFUNDED
PARTIALLY_REFUNDED
```

---

## 6. Real-Time Decision SLA Targets

Initial MVP target:

- P50 authorization latency: under 300 ms
- P95 authorization latency: under 800 ms
- P99 authorization latency: under 1500 ms

Production target:

- P50: under 100 ms
- P95: under 300 ms
- P99: under 700 ms

---

## 7. 180-Day Execution Plan

The plan is split into 26 weeks. Every day has a concrete focus, detailed tasks, deliverable, and validation.

## Week 1: Foundation, architecture, and local platform setup

### Day 1: Define product scope and repo foundation

**Tasks**

- Create the product README with vision, modules, and architecture principles.
- Create the monorepo structure for backend, frontend, ml-platform, infrastructure, docs, and data.
- Define coding standards for Java, Python, SQL, TypeScript, and Git branching.
- Create a high-level C4 context diagram in Markdown using Mermaid.

**Deliverable**

- Initial monorepo with README and architecture docs.

**Validation**

- A new developer can understand what the product does and where each module belongs.

### Day 2: Create local infrastructure with Docker Compose

**Tasks**

- Add PostgreSQL, Redis, Kafka, Zookeeper/Redpanda, Neo4j, ClickHouse, and MinIO/S3-compatible storage to Docker Compose.
- Add health checks and named volumes.
- Create `.env.example` with local credentials and ports.
- Document startup and teardown commands.

**Deliverable**

- Working local infrastructure stack.

**Validation**

- `docker compose up` starts all infrastructure services successfully.

### Day 3: Create shared backend foundation

**Tasks**

- Create backend/common module for shared DTOs, exceptions, IDs, error responses, and observability helpers.
- Define standard API response format.
- Add global exception handling template for Spring Boot services.
- Define ID prefixes like `pi_`, `pa_`, `fd_`, `pm_`, `auth_`, `cap_`, `re_`.

**Deliverable**

- Common Java library for backend services.

**Validation**

- A sample service can import common response and exception classes.

### Day 4: Create payment-service skeleton

**Tasks**

- Generate Spring Boot 3 Java 21 payment-service.
- Add packages: api, application, domain, repository, infrastructure, events, config.
- Add Spring Actuator health endpoint.
- Add Flyway migration support.
- Configure PostgreSQL connection.

**Deliverable**

- Running payment-service skeleton.

**Validation**

- `GET /actuator/health` returns UP and database connection works.

### Day 5: Create database migration foundation

**Tasks**

- Create initial schemas for merchants, customers, payment_intents, payment_methods, payment_attempts, fraud_decisions, authorizations, captures, refunds, ledger_entries, and idempotency_keys.
- Add indexes on merchant_id, customer_id, payment_intent_id, status, created_at.
- Add enum-like constraints for lifecycle states.
- Add audit columns created_at and updated_at.

**Deliverable**

- Initial Flyway migrations.

**Validation**

- Migrations run successfully on a clean PostgreSQL database.

### Day 6: Create event contract foundation

**Tasks**

- Define canonical event envelope with event_id, event_type, aggregate_id, occurred_at, version, payload.
- Create Kafka topic naming convention.
- Define initial event contracts for payment_intent_created, payment_method_attached, payment_intent_confirmed, fraud_decision_completed, payment_authorized, payment_declined, payment_captured, payment_refunded.

**Deliverable**

- Event contract documentation and Java event classes.

**Validation**

- Events can be serialized to JSON and validated against examples.

### Day 7: Weekly architecture review and cleanup

**Tasks**

- Review local stack reliability.
- Review package boundaries.
- Write ADR-001 for real-time vs offline fraud intelligence separation.
- Write ADR-002 for PostgreSQL + Redis + Kafka + Neo4j + ClickHouse selection.
- Fix setup gaps discovered during the week.

**Deliverable**

- Week 1 architecture decision records.

**Validation**

- Another developer can run the local environment and understand the first design decisions.

---

## Week 2: PaymentIntent lifecycle MVP

### Day 8: Implement merchant and customer seed data

**Tasks**

- Create Merchant entity and repository.
- Create Customer entity and repository.
- Add seed data for test merchants and customers.
- Create basic APIs or admin seed runner for local development.
- Add tests for loading merchant and customer records.

**Deliverable**

- Seeded merchant and customer foundation.

**Validation**

- PaymentIntent creation can reference real merchant and customer IDs.

### Day 9: Implement Create PaymentIntent API

**Tasks**

- Create DTO for POST /api/v1/payment-intents.
- Validate amount, currency, merchantId, customerId, captureMethod, confirmationMethod.
- Persist PaymentIntent with REQUIRES_PAYMENT_METHOD status.
- Generate client_secret placeholder securely for local demo.
- Publish payment_intent_created event.

**Deliverable**

- Create PaymentIntent endpoint.

**Validation**

- API returns paymentIntentId, status, amount, currency, and clientSecret.

### Day 10: Implement PaymentMethod attachment

**Tasks**

- Create DTO for card token attachment.
- Implement fake card token parser for tokens like tok_visa_4242.
- Store card_bin, card_last4_hash, card_fingerprint, card_country, brand, billing_country.
- Never store raw PAN.
- Move PaymentIntent to REQUIRES_CONFIRMATION.

**Deliverable**

- Attach PaymentMethod endpoint.

**Validation**

- PaymentIntent status changes from REQUIRES_PAYMENT_METHOD to REQUIRES_CONFIRMATION.

### Day 11: Implement idempotency foundation

**Tasks**

- Create IdempotencyKey entity and repository.
- Compute request hash from method, path, merchant, and body.
- Store response body and status code.
- Return cached response for repeated same request.
- Reject same key with different request payload.

**Deliverable**

- Reusable idempotency component.

**Validation**

- Repeated create/confirm/capture request with same key does not duplicate side effects.

### Day 12: Implement PaymentAttempt creation

**Tasks**

- Create PaymentAttempt entity lifecycle.
- On confirmation, create a new attempt for the PaymentIntent.
- Store amount, currency, merchant, customer, payment method, and status.
- Link PaymentIntent.latest_payment_attempt_id.
- Publish payment_attempt_created event.

**Deliverable**

- PaymentAttempt persistence.

**Validation**

- Every confirmation attempt creates exactly one PaymentAttempt unless idempotency returns cached response.

### Day 13: Implement basic status transitions

**Tasks**

- Create PaymentIntentStateMachine service.
- Allow only valid transitions.
- Reject invalid transitions with domain errors.
- Add unit tests for normal and invalid transitions.
- Document lifecycle in docs/payment-lifecycle.md.

**Deliverable**

- PaymentIntent state machine.

**Validation**

- Invalid transitions such as CAPTURED to REQUIRES_CONFIRMATION are rejected.

### Day 14: Weekly E2E payment intent review

**Tasks**

- Run create intent + attach method + confirm skeleton flow.
- Add integration tests using Testcontainers PostgreSQL if feasible.
- Improve error response consistency.
- Update README with API examples.
- Document known limitations.

**Deliverable**

- Working PaymentIntent lifecycle up to confirmation skeleton.

**Validation**

- A developer can create a PaymentIntent and attach a PaymentMethod using curl/Postman.

---

## Week 3: Fraud decision stub and processor adapter MVP

### Day 15: Create fraud-decision-service skeleton

**Tasks**

- Generate Spring Boot fraud-decision-service.
- Create FraudDecisionRequest and FraudDecisionResponse DTOs.
- Add endpoint POST /api/v1/fraud/evaluate.
- Return deterministic stub decisions based on amount, card token, or device ID.
- Add health endpoint and OpenAPI docs.

**Deliverable**

- Fraud decision stub service.

**Validation**

- Payment service can call fraud-decision-service locally.

### Day 16: Integrate payment confirmation with fraud decision

**Tasks**

- On confirm, move PaymentIntent to REQUIRES_FRAUD_CHECK.
- Build FraudDecisionRequest from payment, customer, merchant, payment method, IP, device, countries, MCC.
- Call fraud-decision-service via HTTP client.
- Persist returned FraudDecision in payment database for MVP.
- Map APPROVE/DECLINE/MANUAL_REVIEW/STEP_UP_AUTH to PaymentIntent states.

**Deliverable**

- Payment confirmation invokes fraud decision.

**Validation**

- Fraud DECLINE prevents processor authorization.

### Day 17: Create processor-adapter-service skeleton

**Tasks**

- Generate Spring Boot processor-adapter-service.
- Create POST /api/v1/processor/authorize.
- Support fake card behavior: tok_visa_4242 approved, tok_card_declined declined, tok_processor_timeout timeout, tok_3ds_required requires action.
- Return processorAuthorizationId and responseCode.
- Add latency simulation configuration.

**Deliverable**

- Fake processor adapter.

**Validation**

- Processor adapter returns deterministic test responses.

### Day 18: Integrate processor authorization

**Tasks**

- If fraud decision is APPROVE, call processor adapter.
- Persist Authorization record.
- Move PaymentIntent to AUTHORIZED on processor approval.
- Move PaymentIntent to DECLINED_BY_PROCESSOR on processor decline.
- Publish payment_authorized or payment_declined events.

**Deliverable**

- End-to-end authorization flow.

**Validation**

- Approved fake card reaches AUTHORIZED status after fraud approval.

### Day 19: Implement confirm response variants

**Tasks**

- Return different JSON responses for AUTHORIZED, REQUIRES_ACTION, MANUAL_REVIEW, and DECLINED.
- Include riskScore, reasonCodes, and authorizationId where applicable.
- Do not leak sensitive card data.
- Normalize error codes for timeout and processor failure.
- Add API examples in docs.

**Deliverable**

- Production-shaped confirm API responses.

**Validation**

- Each fraud/processor scenario returns a clear response.

### Day 20: Add integration tests for payment confirmation

**Tasks**

- Test approve path.
- Test fraud decline path.
- Test manual review path.
- Test step-up path.
- Test processor decline path.
- Test idempotent confirm returns same response.

**Deliverable**

- Payment confirmation integration test suite.

**Validation**

- All core payment confirmation scenarios pass locally.

### Day 21: Weekly demo: first end-to-end payment

**Tasks**

- Create demo script for create PaymentIntent, attach card, confirm, fraud check, processor authorize, event publish.
- Document happy path and decline path.
- Refactor duplicated DTO mapping.
- Review boundaries between payment and fraud services.
- Write ADR-003 for processor adapter simulation.

**Deliverable**

- First complete payment authorization demo.

**Validation**

- A stakeholder can see payment-to-fraud-to-authorization working end to end.

---

## Week 4: Feature service and basic rules engine

### Day 22: Create feature-service skeleton

**Tasks**

- Generate Spring Boot feature-service.
- Create FeatureRequest and FeatureResponse contracts.
- Implement endpoint POST /api/v1/features/extract.
- Return basic static features from request and database lookup.
- Add feature snapshot schema.

**Deliverable**

- Feature service API.

**Validation**

- Fraud-decision-service can request features.

### Day 23: Implement basic feature extraction

**Tasks**

- Compute transaction_amount, currency, account_age_days, is_new_customer, is_new_device, billing_shipping_country_mismatch, card_country_ip_country_mismatch placeholder, transaction_hour_of_day, transaction_day_of_week.
- Persist FeatureSnapshot for audit.
- Create reason-friendly feature names.
- Add unit tests for each feature.

**Deliverable**

- Static feature extraction.

**Validation**

- Feature values match expected output for sample requests.

### Day 24: Implement Redis velocity counters

**Tasks**

- Add Redis integration.
- Create velocity keys for customer/card/device/IP counts over 5m, 1h, 24h.
- Increment counters on payment attempt creation or feature extraction.
- Set TTLs correctly.
- Add tests using embedded/test Redis or integration environment.

**Deliverable**

- Velocity feature store foundation.

**Validation**

- Repeated payments increase velocity counters as expected.

### Day 25: Create rules-engine-service skeleton

**Tasks**

- Generate Spring Boot rules-engine-service.
- Define FraudRule entity, RuleExpression, RuleAction, RuleStatus.
- Create endpoints to create, validate, list, and execute rules.
- Store rules in PostgreSQL.
- Add initial hardcoded and database-backed rule loading.

**Deliverable**

- Rules engine service skeleton.

**Validation**

- Can create and list a fraud rule.

### Day 26: Implement safe rule DSL MVP

**Tasks**

- Support comparisons: <, <=, >, >=, ==, !=.
- Support boolean AND/OR with parentheses if feasible.
- Restrict allowed feature names.
- Reject unsafe or unknown expressions.
- Return detailed validation errors.

**Deliverable**

- Safe rule expression evaluator MVP.

**Validation**

- Rule expressions evaluate correctly against feature maps.

### Day 27: Integrate fraud decision with features and rules

**Tasks**

- Fraud-decision-service calls feature-service.
- Fraud-decision-service calls rules-engine-service.
- Combine rule results into rulesScore and reasonCodes.
- Return decision based on rules before model exists.
- Persist rulesTriggered in FraudDecision.

**Deliverable**

- Rules-based fraud decisions.

**Validation**

- A configured high-risk rule can trigger MANUAL_REVIEW or DECLINE.

### Day 28: Weekly review: deterministic fraud engine MVP

**Tasks**

- Create demo rules: high amount, new account high amount, high velocity, billing/shipping mismatch.
- Run test payment flows for each rule.
- Measure latency from payment confirm to decision.
- Document rule DSL limitations.
- Write ADR-004 for safe DSL over dynamic code execution.

**Deliverable**

- Feature + rules based fraud MVP.

**Validation**

- End-to-end payment flow uses real extracted features and rules.

---

## Week 5: Payment capture, refund, ledger, and webhook foundation

### Day 29: Implement capture endpoint and capture lifecycle

**Tasks**

- Define the exact scope for payment capture and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for payment capture.
- Wire payment capture into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for payment capture.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for payment capture.

**Validation**

- The payment capture capability works locally through API calls, tests, events, and persisted state.

### Day 30: Create double-entry ledger MVP

**Tasks**

- Define the exact scope for ledger entries and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for ledger entries.
- Wire ledger entries into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for ledger entries.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for ledger entries.

**Validation**

- The ledger entries capability works locally through API calls, tests, events, and persisted state.

### Day 31: Implement refund endpoint and refund ledger entries

**Tasks**

- Define the exact scope for refunds and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for refunds.
- Wire refunds into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for refunds.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for refunds.

**Validation**

- The refunds capability works locally through API calls, tests, events, and persisted state.

### Day 32: Add cancel/void authorization flow

**Tasks**

- Define the exact scope for voids and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for voids.
- Wire voids into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for voids.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for voids.

**Validation**

- The voids capability works locally through API calls, tests, events, and persisted state.

### Day 33: Create webhook endpoint registry

**Tasks**

- Define the exact scope for webhooks and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for webhooks.
- Wire webhooks into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for webhooks.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for webhooks.

**Validation**

- The webhooks capability works locally through API calls, tests, events, and persisted state.

### Day 34: Implement webhook delivery worker and retries

**Tasks**

- Define the exact scope for retries and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for retries.
- Wire retries into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for retries.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for retries.

**Validation**

- The retries capability works locally through API calls, tests, events, and persisted state.

### Day 35: Review payment money movement invariants

**Tasks**

- Define the exact scope for invariants and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for invariants.
- Wire invariants into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for invariants.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for invariants.

**Validation**

- The invariants capability works locally through API calls, tests, events, and persisted state.

---

## Week 6: Analytics store and event consumers

### Day 36: Create Kafka consumer foundation

**Tasks**

- Define the exact scope for Kafka consumers and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Kafka consumers.
- Wire Kafka consumers into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Kafka consumers.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Kafka consumers.

**Validation**

- The Kafka consumers capability works locally through API calls, tests, events, and persisted state.

### Day 37: Create ClickHouse schema for transaction analytics

**Tasks**

- Define the exact scope for ClickHouse and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for ClickHouse.
- Wire ClickHouse into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for ClickHouse.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for ClickHouse.

**Validation**

- The ClickHouse capability works locally through API calls, tests, events, and persisted state.

### Day 38: Stream payment events into analytics store

**Tasks**

- Define the exact scope for analytics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analytics.
- Wire analytics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analytics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analytics.

**Validation**

- The analytics capability works locally through API calls, tests, events, and persisted state.

### Day 39: Stream fraud decision events into analytics store

**Tasks**

- Define the exact scope for event replay and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for event replay.
- Wire event replay into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for event replay.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for event replay.

**Validation**

- The event replay capability works locally through API calls, tests, events, and persisted state.

### Day 40: Create dashboard query APIs

**Tasks**

- Define the exact scope for query APIs and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for query APIs.
- Wire query APIs into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for query APIs.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for query APIs.

**Validation**

- The query APIs capability works locally through API calls, tests, events, and persisted state.

### Day 41: Add replay scripts for local events

**Tasks**

- Define the exact scope for consistency and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for consistency.
- Wire consistency into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for consistency.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for consistency.

**Validation**

- The consistency capability works locally through API calls, tests, events, and persisted state.

### Day 42: Validate analytics consistency

**Tasks**

- Define the exact scope for dashboards and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for dashboards.
- Wire dashboards into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for dashboards.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for dashboards.

**Validation**

- The dashboards capability works locally through API calls, tests, events, and persisted state.

---

## Week 7: Manual review workflow

### Day 43: Create manual-review-service

**Tasks**

- Define the exact scope for manual review and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for manual review.
- Wire manual review into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for manual review.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for manual review.

**Validation**

- The manual review capability works locally through API calls, tests, events, and persisted state.

### Day 44: Create review case lifecycle

**Tasks**

- Define the exact scope for review lifecycle and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for review lifecycle.
- Wire review lifecycle into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for review lifecycle.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for review lifecycle.

**Validation**

- The review lifecycle capability works locally through API calls, tests, events, and persisted state.

### Day 45: Integrate manual review from fraud decision

**Tasks**

- Define the exact scope for analyst decision and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analyst decision.
- Wire analyst decision into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analyst decision.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analyst decision.

**Validation**

- The analyst decision capability works locally through API calls, tests, events, and persisted state.

### Day 46: Build analyst decision APIs

**Tasks**

- Define the exact scope for review events and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for review events.
- Wire review events into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for review events.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for review events.

**Validation**

- The review events capability works locally through API calls, tests, events, and persisted state.

### Day 47: Publish review outcome events

**Tasks**

- Define the exact scope for fraud labels and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for fraud labels.
- Wire fraud labels into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for fraud labels.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for fraud labels.

**Validation**

- The fraud labels capability works locally through API calls, tests, events, and persisted state.

### Day 48: Feed review outcomes into fraud labels

**Tasks**

- Define the exact scope for operations and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for operations.
- Wire operations into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for operations.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for operations.

**Validation**

- The operations capability works locally through API calls, tests, events, and persisted state.

### Day 49: Review analyst workflow end to end

**Tasks**

- Define the exact scope for case management and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for case management.
- Wire case management into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for case management.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for case management.

**Validation**

- The case management capability works locally through API calls, tests, events, and persisted state.

---

## Week 8: Chargebacks and fraud label feedback

### Day 50: Create chargeback-service

**Tasks**

- Define the exact scope for chargebacks and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for chargebacks.
- Wire chargebacks into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for chargebacks.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for chargebacks.

**Validation**

- The chargebacks capability works locally through API calls, tests, events, and persisted state.

### Day 51: Implement chargeback ingestion API

**Tasks**

- Define the exact scope for fraud labels and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for fraud labels.
- Wire fraud labels into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for fraud labels.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for fraud labels.

**Validation**

- The fraud labels capability works locally through API calls, tests, events, and persisted state.

### Day 52: Link chargebacks to payments

**Tasks**

- Define the exact scope for feedback and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for feedback.
- Wire feedback into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for feedback.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for feedback.

**Validation**

- The feedback capability works locally through API calls, tests, events, and persisted state.

### Day 53: Create fraud labels from chargebacks

**Tasks**

- Define the exact scope for analytics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analytics.
- Wire analytics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analytics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analytics.

**Validation**

- The analytics capability works locally through API calls, tests, events, and persisted state.

### Day 54: Publish chargeback events

**Tasks**

- Define the exact scope for events and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for events.
- Wire events into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for events.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for events.

**Validation**

- The events capability works locally through API calls, tests, events, and persisted state.

### Day 55: Create chargeback analytics views

**Tasks**

- Define the exact scope for linking and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for linking.
- Wire linking into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for linking.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for linking.

**Validation**

- The linking capability works locally through API calls, tests, events, and persisted state.

### Day 56: Validate feedback loop

**Tasks**

- Define the exact scope for model training data and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model training data.
- Wire model training data into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model training data.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model training data.

**Validation**

- The model training data capability works locally through API calls, tests, events, and persisted state.

---

## Week 9: ML baseline data pipeline

### Day 57: Create synthetic fraud data generator

**Tasks**

- Define the exact scope for synthetic data and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for synthetic data.
- Wire synthetic data into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for synthetic data.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for synthetic data.

**Validation**

- The synthetic data capability works locally through API calls, tests, events, and persisted state.

### Day 58: Create feature engineering pipeline

**Tasks**

- Define the exact scope for features and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for features.
- Wire features into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for features.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for features.

**Validation**

- The features capability works locally through API calls, tests, events, and persisted state.

### Day 59: Build training dataset joins

**Tasks**

- Define the exact scope for training dataset and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for training dataset.
- Wire training dataset into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for training dataset.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for training dataset.

**Validation**

- The training dataset capability works locally through API calls, tests, events, and persisted state.

### Day 60: Implement time-based train/validation/test split

**Tasks**

- Define the exact scope for time split and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for time split.
- Wire time split into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for time split.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for time split.

**Validation**

- The time split capability works locally through API calls, tests, events, and persisted state.

### Day 61: Train logistic regression baseline

**Tasks**

- Define the exact scope for baseline and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for baseline.
- Wire baseline into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for baseline.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for baseline.

**Validation**

- The baseline capability works locally through API calls, tests, events, and persisted state.

### Day 62: Train XGBoost baseline

**Tasks**

- Define the exact scope for XGBoost and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for XGBoost.
- Wire XGBoost into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for XGBoost.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for XGBoost.

**Validation**

- The XGBoost capability works locally through API calls, tests, events, and persisted state.

### Day 63: Create model evaluation report

**Tasks**

- Define the exact scope for metrics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for metrics.
- Wire metrics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for metrics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for metrics.

**Validation**

- The metrics capability works locally through API calls, tests, events, and persisted state.

---

## Week 10: Model serving and real-time ML integration

### Day 64: Create FastAPI model-serving-service

**Tasks**

- Define the exact scope for FastAPI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for FastAPI.
- Wire FastAPI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for FastAPI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for FastAPI.

**Validation**

- The FastAPI capability works locally through API calls, tests, events, and persisted state.

### Day 65: Load registered model artifact

**Tasks**

- Define the exact scope for model serving and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model serving.
- Wire model serving into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model serving.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model serving.

**Validation**

- The model serving capability works locally through API calls, tests, events, and persisted state.

### Day 66: Implement fraud score endpoint

**Tasks**

- Define the exact scope for SHAP and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for SHAP.
- Wire SHAP into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for SHAP.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for SHAP.

**Validation**

- The SHAP capability works locally through API calls, tests, events, and persisted state.

### Day 67: Add SHAP/top-feature reason extraction

**Tasks**

- Define the exact scope for ML score and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for ML score.
- Wire ML score into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for ML score.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for ML score.

**Validation**

- The ML score capability works locally through API calls, tests, events, and persisted state.

### Day 68: Integrate fraud-decision-service with model-serving

**Tasks**

- Define the exact scope for fallback and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for fallback.
- Wire fallback into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for fallback.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for fallback.

**Validation**

- The fallback capability works locally through API calls, tests, events, and persisted state.

### Day 69: Add fallback behavior for model timeout

**Tasks**

- Define the exact scope for integration and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for integration.
- Wire integration into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for integration.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for integration.

**Validation**

- The integration capability works locally through API calls, tests, events, and persisted state.

### Day 70: Validate real-time ML scoring

**Tasks**

- Define the exact scope for latency and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for latency.
- Wire latency into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for latency.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for latency.

**Validation**

- The latency capability works locally through API calls, tests, events, and persisted state.

---

## Week 11: Model registry and monitoring foundation

### Day 71: Set up MLflow

**Tasks**

- Define the exact scope for MLflow and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for MLflow.
- Wire MLflow into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for MLflow.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for MLflow.

**Validation**

- The MLflow capability works locally through API calls, tests, events, and persisted state.

### Day 72: Register model versions

**Tasks**

- Define the exact scope for model versions and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model versions.
- Wire model versions into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model versions.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model versions.

**Validation**

- The model versions capability works locally through API calls, tests, events, and persisted state.

### Day 73: Store prediction logs

**Tasks**

- Define the exact scope for prediction logs and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for prediction logs.
- Wire prediction logs into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for prediction logs.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for prediction logs.

**Validation**

- The prediction logs capability works locally through API calls, tests, events, and persisted state.

### Day 74: Track model metrics by version

**Tasks**

- Define the exact scope for metrics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for metrics.
- Wire metrics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for metrics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for metrics.

**Validation**

- The metrics capability works locally through API calls, tests, events, and persisted state.

### Day 75: Add feature distribution logging

**Tasks**

- Define the exact scope for feature drift and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for feature drift.
- Wire feature drift into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for feature drift.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for feature drift.

**Validation**

- The feature drift capability works locally through API calls, tests, events, and persisted state.

### Day 76: Create drift detection scripts

**Tasks**

- Define the exact scope for governance and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for governance.
- Wire governance into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for governance.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for governance.

**Validation**

- The governance capability works locally through API calls, tests, events, and persisted state.

### Day 77: Review model governance

**Tasks**

- Define the exact scope for registry and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for registry.
- Wire registry into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for registry.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for registry.

**Validation**

- The registry capability works locally through API calls, tests, events, and persisted state.

---

## Week 12: Graph intelligence foundation

### Day 78: Create Neo4j schema constraints

**Tasks**

- Define the exact scope for Neo4j and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Neo4j.
- Wire Neo4j into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Neo4j.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Neo4j.

**Validation**

- The Neo4j capability works locally through API calls, tests, events, and persisted state.

### Day 79: Build graph event consumer

**Tasks**

- Define the exact scope for graph schema and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph schema.
- Wire graph schema into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph schema.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph schema.

**Validation**

- The graph schema capability works locally through API calls, tests, events, and persisted state.

### Day 80: Create customer-device-IP-card-merchant nodes

**Tasks**

- Define the exact scope for relationships and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for relationships.
- Wire relationships into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for relationships.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for relationships.

**Validation**

- The relationships capability works locally through API calls, tests, events, and persisted state.

### Day 81: Create graph relationships from payment events

**Tasks**

- Define the exact scope for known fraud and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for known fraud.
- Wire known fraud into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for known fraud.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for known fraud.

**Validation**

- The known fraud capability works locally through API calls, tests, events, and persisted state.

### Day 82: Mark known fraud customers from labels

**Tasks**

- Define the exact scope for graph features and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph features.
- Wire graph features into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph features.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph features.

**Validation**

- The graph features capability works locally through API calls, tests, events, and persisted state.

### Day 83: Implement graph feature query API

**Tasks**

- Define the exact scope for fraud proximity and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for fraud proximity.
- Wire fraud proximity into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for fraud proximity.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for fraud proximity.

**Validation**

- The fraud proximity capability works locally through API calls, tests, events, and persisted state.

### Day 84: Validate graph feature extraction

**Tasks**

- Define the exact scope for events and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for events.
- Wire events into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for events.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for events.

**Validation**

- The events capability works locally through API calls, tests, events, and persisted state.

---

## Week 13: Fraud ring detection

### Day 85: Implement shared device fraud query

**Tasks**

- Define the exact scope for shared device and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for shared device.
- Wire shared device into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for shared device.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for shared device.

**Validation**

- The shared device capability works locally through API calls, tests, events, and persisted state.

### Day 86: Implement shared IP fraud query

**Tasks**

- Define the exact scope for shared IP and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for shared IP.
- Wire shared IP into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for shared IP.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for shared IP.

**Validation**

- The shared IP capability works locally through API calls, tests, events, and persisted state.

### Day 87: Implement card fingerprint reuse query

**Tasks**

- Define the exact scope for card reuse and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for card reuse.
- Wire card reuse into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for card reuse.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for card reuse.

**Validation**

- The card reuse capability works locally through API calls, tests, events, and persisted state.

### Day 88: Implement two-hop fraud proximity

**Tasks**

- Define the exact scope for two-hop and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for two-hop.
- Wire two-hop into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for two-hop.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for two-hop.

**Validation**

- The two-hop capability works locally through API calls, tests, events, and persisted state.

### Day 89: Implement connected component risk scoring

**Tasks**

- Define the exact scope for component risk and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for component risk.
- Wire component risk into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for component risk.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for component risk.

**Validation**

- The component risk capability works locally through API calls, tests, events, and persisted state.

### Day 90: Expose fraud ring investigation API

**Tasks**

- Define the exact scope for investigation and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for investigation.
- Wire investigation into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for investigation.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for investigation.

**Validation**

- The investigation capability works locally through API calls, tests, events, and persisted state.

### Day 91: Review graph results with sample attacks

**Tasks**

- Define the exact scope for graph fraud and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph fraud.
- Wire graph fraud into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph fraud.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph fraud.

**Validation**

- The graph fraud capability works locally through API calls, tests, events, and persisted state.

---

## Week 14: Rule registry, versioning, and approval workflow

### Day 92: Add rule version model

**Tasks**

- Define the exact scope for rule versioning and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rule versioning.
- Wire rule versioning into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rule versioning.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rule versioning.

**Validation**

- The rule versioning capability works locally through API calls, tests, events, and persisted state.

### Day 93: Implement rule status transitions

**Tasks**

- Define the exact scope for approval and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for approval.
- Wire approval into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for approval.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for approval.

**Validation**

- The approval capability works locally through API calls, tests, events, and persisted state.

### Day 94: Add approval metadata

**Tasks**

- Define the exact scope for canary and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for canary.
- Wire canary into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for canary.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for canary.

**Validation**

- The canary capability works locally through API calls, tests, events, and persisted state.

### Day 95: Implement canary status

**Tasks**

- Define the exact scope for rollback and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rollback.
- Wire rollback into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rollback.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rollback.

**Validation**

- The rollback capability works locally through API calls, tests, events, and persisted state.

### Day 96: Implement rollback to previous version

**Tasks**

- Define the exact scope for audit and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for audit.
- Wire audit into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for audit.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for audit.

**Validation**

- The audit capability works locally through API calls, tests, events, and persisted state.

### Day 97: Add audit logs for rule changes

**Tasks**

- Define the exact scope for governance and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for governance.
- Wire governance into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for governance.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for governance.

**Validation**

- The governance capability works locally through API calls, tests, events, and persisted state.

### Day 98: Validate rule governance

**Tasks**

- Define the exact scope for lifecycle and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for lifecycle.
- Wire lifecycle into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for lifecycle.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for lifecycle.

**Validation**

- The lifecycle capability works locally through API calls, tests, events, and persisted state.

---

## Week 15: Rule simulation and backtesting

### Day 99: Create rule-simulation-service

**Tasks**

- Define the exact scope for simulation and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for simulation.
- Wire simulation into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for simulation.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for simulation.

**Validation**

- The simulation capability works locally through API calls, tests, events, and persisted state.

### Day 100: Build historical feature replay

**Tasks**

- Define the exact scope for backtesting and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for backtesting.
- Wire backtesting into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for backtesting.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for backtesting.

**Validation**

- The backtesting capability works locally through API calls, tests, events, and persisted state.

### Day 101: Execute candidate rules over historical data

**Tasks**

- Define the exact scope for false positives and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for false positives.
- Wire false positives into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for false positives.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for false positives.

**Validation**

- The false positives capability works locally through API calls, tests, events, and persisted state.

### Day 102: Calculate fraud captured and false positives

**Tasks**

- Define the exact scope for business impact and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for business impact.
- Wire business impact into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for business impact.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for business impact.

**Validation**

- The business impact capability works locally through API calls, tests, events, and persisted state.

### Day 103: Calculate business impact

**Tasks**

- Define the exact scope for historical replay and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for historical replay.
- Wire historical replay into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for historical replay.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for historical replay.

**Validation**

- The historical replay capability works locally through API calls, tests, events, and persisted state.

### Day 104: Persist simulation results

**Tasks**

- Define the exact scope for rule readiness and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rule readiness.
- Wire rule readiness into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rule readiness.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rule readiness.

**Validation**

- The rule readiness capability works locally through API calls, tests, events, and persisted state.

### Day 105: Review rule deployment readiness

**Tasks**

- Define the exact scope for analytics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analytics.
- Wire analytics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analytics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analytics.

**Validation**

- The analytics capability works locally through API calls, tests, events, and persisted state.

---

## Week 16: Frontend dashboard foundation

### Day 106: Create React TypeScript dashboard

**Tasks**

- Define the exact scope for React and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for React.
- Wire React into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for React.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for React.

**Validation**

- The React capability works locally through API calls, tests, events, and persisted state.

### Day 107: Set up routing and layout

**Tasks**

- Define the exact scope for dashboard and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for dashboard.
- Wire dashboard into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for dashboard.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for dashboard.

**Validation**

- The dashboard capability works locally through API calls, tests, events, and persisted state.

### Day 108: Create auth placeholder

**Tasks**

- Define the exact scope for routing and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for routing.
- Wire routing into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for routing.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for routing.

**Validation**

- The routing capability works locally through API calls, tests, events, and persisted state.

### Day 109: Build overview dashboard page

**Tasks**

- Define the exact scope for overview and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for overview.
- Wire overview into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for overview.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for overview.

**Validation**

- The overview capability works locally through API calls, tests, events, and persisted state.

### Day 110: Build transaction search page

**Tasks**

- Define the exact scope for transaction search and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for transaction search.
- Wire transaction search into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for transaction search.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for transaction search.

**Validation**

- The transaction search capability works locally through API calls, tests, events, and persisted state.

### Day 111: Build transaction detail page

**Tasks**

- Define the exact scope for detail and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for detail.
- Wire detail into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for detail.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for detail.

**Validation**

- The detail capability works locally through API calls, tests, events, and persisted state.

### Day 112: Connect APIs with TanStack Query

**Tasks**

- Define the exact scope for API integration and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for API integration.
- Wire API integration into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for API integration.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for API integration.

**Validation**

- The API integration capability works locally through API calls, tests, events, and persisted state.

---

## Week 17: Fraud operations dashboard

### Day 113: Build manual review queue UI

**Tasks**

- Define the exact scope for manual review UI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for manual review UI.
- Wire manual review UI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for manual review UI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for manual review UI.

**Validation**

- The manual review UI capability works locally through API calls, tests, events, and persisted state.

### Day 114: Build review case detail UI

**Tasks**

- Define the exact scope for rules UI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rules UI.
- Wire rules UI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rules UI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rules UI.

**Validation**

- The rules UI capability works locally through API calls, tests, events, and persisted state.

### Day 115: Build rules management UI

**Tasks**

- Define the exact scope for simulation UI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for simulation UI.
- Wire simulation UI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for simulation UI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for simulation UI.

**Validation**

- The simulation UI capability works locally through API calls, tests, events, and persisted state.

### Day 116: Build rule simulation UI

**Tasks**

- Define the exact scope for chargebacks UI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for chargebacks UI.
- Wire chargebacks UI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for chargebacks UI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for chargebacks UI.

**Validation**

- The chargebacks UI capability works locally through API calls, tests, events, and persisted state.

### Day 117: Build chargeback analytics UI

**Tasks**

- Define the exact scope for model performance and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model performance.
- Wire model performance into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model performance.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model performance.

**Validation**

- The model performance capability works locally through API calls, tests, events, and persisted state.

### Day 118: Build model performance UI

**Tasks**

- Define the exact scope for usability and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for usability.
- Wire usability into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for usability.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for usability.

**Validation**

- The usability capability works locally through API calls, tests, events, and persisted state.

### Day 119: Review analyst usability

**Tasks**

- Define the exact scope for ops and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for ops.
- Wire ops into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for ops.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for ops.

**Validation**

- The ops capability works locally through API calls, tests, events, and persisted state.

---

## Week 18: Graph investigation dashboard

### Day 120: Build graph search UI

**Tasks**

- Define the exact scope for graph UI and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph UI.
- Wire graph UI into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph UI.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph UI.

**Validation**

- The graph UI capability works locally through API calls, tests, events, and persisted state.

### Day 121: Show customer/device/IP/card relationships

**Tasks**

- Define the exact scope for relationships and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for relationships.
- Wire relationships into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for relationships.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for relationships.

**Validation**

- The relationships capability works locally through API calls, tests, events, and persisted state.

### Day 122: Show fraud neighbor counts

**Tasks**

- Define the exact scope for fraud rings and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for fraud rings.
- Wire fraud rings into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for fraud rings.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for fraud rings.

**Validation**

- The fraud rings capability works locally through API calls, tests, events, and persisted state.

### Day 123: Show fraud ring candidates

**Tasks**

- Define the exact scope for filters and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for filters.
- Wire filters into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for filters.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for filters.

**Validation**

- The filters capability works locally through API calls, tests, events, and persisted state.

### Day 124: Add filters by merchant and time

**Tasks**

- Define the exact scope for explanations and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for explanations.
- Wire explanations into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for explanations.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for explanations.

**Validation**

- The explanations capability works locally through API calls, tests, events, and persisted state.

### Day 125: Add graph explanation panel

**Tasks**

- Define the exact scope for investigation and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for investigation.
- Wire investigation into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for investigation.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for investigation.

**Validation**

- The investigation capability works locally through API calls, tests, events, and persisted state.

### Day 126: Validate investigation workflows

**Tasks**

- Define the exact scope for analyst and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analyst.
- Wire analyst into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analyst.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analyst.

**Validation**

- The analyst capability works locally through API calls, tests, events, and persisted state.

---

## Week 19: Fraud explanation system

### Day 127: Create reason code taxonomy

**Tasks**

- Define the exact scope for reason codes and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for reason codes.
- Wire reason codes into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for reason codes.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for reason codes.

**Validation**

- The reason codes capability works locally through API calls, tests, events, and persisted state.

### Day 128: Map features to human-readable explanations

**Tasks**

- Define the exact scope for explanations and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for explanations.
- Wire explanations into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for explanations.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for explanations.

**Validation**

- The explanations capability works locally through API calls, tests, events, and persisted state.

### Day 129: Map rules to explanations

**Tasks**

- Define the exact scope for model reasons and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model reasons.
- Wire model reasons into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model reasons.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model reasons.

**Validation**

- The model reasons capability works locally through API calls, tests, events, and persisted state.

### Day 130: Map model top features to explanations

**Tasks**

- Define the exact scope for rule reasons and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rule reasons.
- Wire rule reasons into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rule reasons.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rule reasons.

**Validation**

- The rule reasons capability works locally through API calls, tests, events, and persisted state.

### Day 131: Map graph signals to explanations

**Tasks**

- Define the exact scope for graph reasons and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph reasons.
- Wire graph reasons into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph reasons.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph reasons.

**Validation**

- The graph reasons capability works locally through API calls, tests, events, and persisted state.

### Day 132: Create transaction explanation API

**Tasks**

- Define the exact scope for API and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for API.
- Wire API into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for API.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for API.

**Validation**

- The API capability works locally through API calls, tests, events, and persisted state.

### Day 133: Show explanations in dashboard

**Tasks**

- Define the exact scope for dashboard and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for dashboard.
- Wire dashboard into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for dashboard.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for dashboard.

**Validation**

- The dashboard capability works locally through API calls, tests, events, and persisted state.

---

## Week 20: Agentic fraud intelligence foundation

### Day 134: Create fraud-intelligence-agent-service

**Tasks**

- Define the exact scope for agents and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for agents.
- Wire agents into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for agents.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for agents.

**Validation**

- The agents capability works locally through API calls, tests, events, and persisted state.

### Day 135: Define safe tool interface

**Tasks**

- Define the exact scope for tools and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for tools.
- Wire tools into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for tools.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for tools.

**Validation**

- The tools capability works locally through API calls, tests, events, and persisted state.

### Day 136: Implement transaction analytics tool

**Tasks**

- Define the exact scope for orchestrator and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for orchestrator.
- Wire orchestrator into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for orchestrator.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for orchestrator.

**Validation**

- The orchestrator capability works locally through API calls, tests, events, and persisted state.

### Day 137: Implement graph query tool

**Tasks**

- Define the exact scope for audit and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for audit.
- Wire audit into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for audit.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for audit.

**Validation**

- The audit capability works locally through API calls, tests, events, and persisted state.

### Day 138: Implement model performance tool

**Tasks**

- Define the exact scope for analytics tool and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for analytics tool.
- Wire analytics tool into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for analytics tool.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for analytics tool.

**Validation**

- The analytics tool capability works locally through API calls, tests, events, and persisted state.

### Day 139: Implement rule simulation tool

**Tasks**

- Define the exact scope for graph tool and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for graph tool.
- Wire graph tool into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for graph tool.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for graph tool.

**Validation**

- The graph tool capability works locally through API calls, tests, events, and persisted state.

### Day 140: Create orchestrator prompt and workflow

**Tasks**

- Define the exact scope for simulation tool and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for simulation tool.
- Wire simulation tool into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for simulation tool.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for simulation tool.

**Validation**

- The simulation tool capability works locally through API calls, tests, events, and persisted state.

### Day 141: Validate tool audit logging

**Tasks**

- Define the exact scope for validate tool audit logging and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for validate tool audit logging.
- Wire validate tool audit logging into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for validate tool audit logging.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for validate tool audit logging.

**Validation**

- The validate tool audit logging capability works locally through API calls, tests, events, and persisted state.

---

## Week 21: Fraud pattern and model evaluator agents

### Day 142: Implement Fraud Pattern Detection Agent

**Tasks**

- Define the exact scope for pattern agent and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for pattern agent.
- Wire pattern agent into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for pattern agent.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for pattern agent.

**Validation**

- The pattern agent capability works locally through API calls, tests, events, and persisted state.

### Day 143: Implement Model Evaluator Agent

**Tasks**

- Define the exact scope for model evaluator and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for model evaluator.
- Wire model evaluator into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for model evaluator.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for model evaluator.

**Validation**

- The model evaluator capability works locally through API calls, tests, events, and persisted state.

### Day 144: Analyze false negatives

**Tasks**

- Define the exact scope for false negatives and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for false negatives.
- Wire false negatives into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for false negatives.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for false negatives.

**Validation**

- The false negatives capability works locally through API calls, tests, events, and persisted state.

### Day 145: Analyze false positives

**Tasks**

- Define the exact scope for false positives and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for false positives.
- Wire false positives into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for false positives.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for false positives.

**Validation**

- The false positives capability works locally through API calls, tests, events, and persisted state.

### Day 146: Generate model improvement report

**Tasks**

- Define the exact scope for recommendations and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for recommendations.
- Wire recommendations into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for recommendations.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for recommendations.

**Validation**

- The recommendations capability works locally through API calls, tests, events, and persisted state.

### Day 147: Recommend new graph/velocity features

**Tasks**

- Define the exact scope for reports and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for reports.
- Wire reports into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for reports.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for reports.

**Validation**

- The reports capability works locally through API calls, tests, events, and persisted state.

### Day 148: Review agent output quality

**Tasks**

- Define the exact scope for quality and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for quality.
- Wire quality into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for quality.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for quality.

**Validation**

- The quality capability works locally through API calls, tests, events, and persisted state.

---

## Week 22: Rule recommendation and explanation agents

### Day 149: Implement Rule Recommendation Agent

**Tasks**

- Define the exact scope for rule agent and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for rule agent.
- Wire rule agent into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for rule agent.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for rule agent.

**Validation**

- The rule agent capability works locally through API calls, tests, events, and persisted state.

### Day 150: Generate candidate rules from fraud clusters

**Tasks**

- Define the exact scope for candidate rules and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for candidate rules.
- Wire candidate rules into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for candidate rules.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for candidate rules.

**Validation**

- The candidate rules capability works locally through API calls, tests, events, and persisted state.

### Day 151: Validate rule syntax automatically

**Tasks**

- Define the exact scope for validation and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for validation.
- Wire validation into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for validation.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for validation.

**Validation**

- The validation capability works locally through API calls, tests, events, and persisted state.

### Day 152: Trigger simulations from agent

**Tasks**

- Define the exact scope for simulation and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for simulation.
- Wire simulation into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for simulation.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for simulation.

**Validation**

- The simulation capability works locally through API calls, tests, events, and persisted state.

### Day 153: Implement Fraud Explanation Agent

**Tasks**

- Define the exact scope for explanation agent and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for explanation agent.
- Wire explanation agent into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for explanation agent.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for explanation agent.

**Validation**

- The explanation agent capability works locally through API calls, tests, events, and persisted state.

### Day 154: Add human approval handoff

**Tasks**

- Define the exact scope for approval and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for approval.
- Wire approval into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for approval.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for approval.

**Validation**

- The approval capability works locally through API calls, tests, events, and persisted state.

### Day 155: Validate agent safety

**Tasks**

- Define the exact scope for safety and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for safety.
- Wire safety into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for safety.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for safety.

**Validation**

- The safety capability works locally through API calls, tests, events, and persisted state.

---

## Week 23: Security, auth, and compliance hardening

### Day 156: Add Spring Security foundation

**Tasks**

- Define the exact scope for auth and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for auth.
- Wire auth into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for auth.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for auth.

**Validation**

- The auth capability works locally through API calls, tests, events, and persisted state.

### Day 157: Implement role-based access control

**Tasks**

- Define the exact scope for RBAC and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for RBAC.
- Wire RBAC into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for RBAC.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for RBAC.

**Validation**

- The RBAC capability works locally through API calls, tests, events, and persisted state.

### Day 158: Mask sensitive payment method data

**Tasks**

- Define the exact scope for masking and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for masking.
- Wire masking into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for masking.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for masking.

**Validation**

- The masking capability works locally through API calls, tests, events, and persisted state.

### Day 159: Hash/tokenize sensitive identifiers

**Tasks**

- Define the exact scope for tokenization and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for tokenization.
- Wire tokenization into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for tokenization.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for tokenization.

**Validation**

- The tokenization capability works locally through API calls, tests, events, and persisted state.

### Day 160: Add audit logging across services

**Tasks**

- Define the exact scope for audit and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for audit.
- Wire audit into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for audit.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for audit.

**Validation**

- The audit capability works locally through API calls, tests, events, and persisted state.

### Day 161: Add secret management strategy

**Tasks**

- Define the exact scope for secrets and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for secrets.
- Wire secrets into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for secrets.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for secrets.

**Validation**

- The secrets capability works locally through API calls, tests, events, and persisted state.

### Day 162: Run security review

**Tasks**

- Define the exact scope for security and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for security.
- Wire security into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for security.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for security.

**Validation**

- The security capability works locally through API calls, tests, events, and persisted state.

---

## Week 24: Observability and reliability

### Day 163: Add OpenTelemetry tracing

**Tasks**

- Define the exact scope for tracing and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for tracing.
- Wire tracing into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for tracing.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for tracing.

**Validation**

- The tracing capability works locally through API calls, tests, events, and persisted state.

### Day 164: Add Prometheus metrics

**Tasks**

- Define the exact scope for metrics and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for metrics.
- Wire metrics into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for metrics.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for metrics.

**Validation**

- The metrics capability works locally through API calls, tests, events, and persisted state.

### Day 165: Create Grafana dashboards

**Tasks**

- Define the exact scope for dashboards and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for dashboards.
- Wire dashboards into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for dashboards.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for dashboards.

**Validation**

- The dashboards capability works locally through API calls, tests, events, and persisted state.

### Day 166: Add structured logs

**Tasks**

- Define the exact scope for alerts and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for alerts.
- Wire alerts into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for alerts.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for alerts.

**Validation**

- The alerts capability works locally through API calls, tests, events, and persisted state.

### Day 167: Add alerts for latency/errors/Kafka lag

**Tasks**

- Define the exact scope for resilience and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for resilience.
- Wire resilience into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for resilience.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for resilience.

**Validation**

- The resilience capability works locally through API calls, tests, events, and persisted state.

### Day 168: Add Resilience4j timeouts and retries

**Tasks**

- Define the exact scope for SLOs and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for SLOs.
- Wire SLOs into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for SLOs.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for SLOs.

**Validation**

- The SLOs capability works locally through API calls, tests, events, and persisted state.

### Day 169: Review SLOs

**Tasks**

- Define the exact scope for reliability and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for reliability.
- Wire reliability into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for reliability.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for reliability.

**Validation**

- The reliability capability works locally through API calls, tests, events, and persisted state.

---

## Week 25: Performance and scale testing

### Day 170: Create load test scripts

**Tasks**

- Define the exact scope for load tests and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for load tests.
- Wire load tests into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for load tests.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for load tests.

**Validation**

- The load tests capability works locally through API calls, tests, events, and persisted state.

### Day 171: Test 100 TPS

**Tasks**

- Define the exact scope for TPS and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for TPS.
- Wire TPS into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for TPS.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for TPS.

**Validation**

- The TPS capability works locally through API calls, tests, events, and persisted state.

### Day 172: Test 500 TPS

**Tasks**

- Define the exact scope for bottlenecks and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for bottlenecks.
- Wire bottlenecks into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for bottlenecks.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for bottlenecks.

**Validation**

- The bottlenecks capability works locally through API calls, tests, events, and persisted state.

### Day 173: Test 1000 TPS

**Tasks**

- Define the exact scope for indexes and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for indexes.
- Wire indexes into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for indexes.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for indexes.

**Validation**

- The indexes capability works locally through API calls, tests, events, and persisted state.

### Day 174: Profile bottlenecks

**Tasks**

- Define the exact scope for Redis and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Redis.
- Wire Redis into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Redis.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Redis.

**Validation**

- The Redis capability works locally through API calls, tests, events, and persisted state.

### Day 175: Optimize database indexes and Redis calls

**Tasks**

- Define the exact scope for capacity and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for capacity.
- Wire capacity into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for capacity.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for capacity.

**Validation**

- The capacity capability works locally through API calls, tests, events, and persisted state.

### Day 176: Review capacity plan

**Tasks**

- Define the exact scope for performance and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for performance.
- Wire performance into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for performance.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for performance.

**Validation**

- The performance capability works locally through API calls, tests, events, and persisted state.

---

## Week 26: Kubernetes and CI/CD

### Day 177: Create Dockerfiles for services

**Tasks**

- Define the exact scope for Docker and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Docker.
- Wire Docker into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Docker.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Docker.

**Validation**

- The Docker capability works locally through API calls, tests, events, and persisted state.

### Day 178: Create Helm charts

**Tasks**

- Define the exact scope for Helm and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Helm.
- Wire Helm into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Helm.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Helm.

**Validation**

- The Helm capability works locally through API calls, tests, events, and persisted state.

### Day 179: Create local Kubernetes deployment

**Tasks**

- Define the exact scope for Kubernetes and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for Kubernetes.
- Wire Kubernetes into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for Kubernetes.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for Kubernetes.

**Validation**

- The Kubernetes capability works locally through API calls, tests, events, and persisted state.

### Day 180: Create GitHub Actions build pipeline

**Tasks**

- Define the exact scope for CI/CD and update the relevant design document before coding.
- Implement the core domain model, API contract, and persistence changes required for CI/CD.
- Wire CI/CD into the existing payment/fraud event flow without breaking earlier phases.
- Add unit tests for domain logic and integration tests for the main success and failure paths.
- Add observability: structured logs, metrics, trace spans, and audit events for CI/CD.
- Update README/API examples and document known limitations or follow-up tasks.

**Deliverable**

- Working implementation slice for CI/CD.

**Validation**

- The CI/CD capability works locally through API calls, tests, events, and persisted state.

---


---

# 8. Final Product Acceptance Criteria

The platform is ready for a real product demo when all of the following are true:

1. Merchant can create a PaymentIntent.
2. Customer can attach a fake tokenized card.
3. Merchant can confirm the PaymentIntent.
4. Payment service calls fraud decision before processor authorization.
5. Fraud decision uses features, rules, ML score, and graph score.
6. Approved payment is authorized by fake processor.
7. Fraud-declined payment is not sent to processor.
8. Manual-review payment creates a review case.
9. Captured payment creates ledger entries.
10. Refund creates reverse ledger entries.
11. Payment, fraud, ledger, review, and chargeback events are published to Kafka.
12. ClickHouse analytics contains payment and fraud events.
13. Neo4j graph contains customer-device-IP-card-merchant relationships.
14. ML model is trained using historical labels and served through API.
15. Fraud decision stores model version, rule versions, score breakdown, and reason codes.
16. Analyst dashboard shows payments, fraud decisions, rules, model metrics, graph links, reviews, and chargebacks.
17. Rule simulation can backtest a candidate rule against historical data.
18. Rule activation requires human approval.
19. Agents can investigate fraud patterns and recommend rules safely.
20. Agents cannot activate production rules directly.
21. Audit logs exist for rule changes, manual reviews, payment state changes, and agent tool calls.
22. Observability dashboards show latency, errors, Kafka lag, fraud metrics, and model metrics.
23. Load tests demonstrate the MVP target throughput.
24. Security review confirms sensitive data masking and tokenization.
25. Documentation explains local setup, architecture, APIs, runbooks, and known limitations.

---

# 9. Daily Working Routine

Use this daily loop:

```text
1. Read yesterday's deliverable and current phase goal.
2. Write a 5-minute mini-design before coding.
3. Implement the smallest production-shaped slice.
4. Add tests before moving on.
5. Run local full-flow validation.
6. Update docs and API examples.
7. Commit with a clear message.
8. Record blockers and next-day tasks.
```

---

# 10. Weekly Review Template

At the end of every week, answer:

1. What user-visible capability was completed?
2. What part of the real payment/fraud workflow now works end to end?
3. What data is now persisted?
4. What events are now published?
5. What tests prove correctness?
6. What observability exists?
7. What security risk was reduced?
8. What architectural decision was made?
9. What remains risky or unclear?
10. What is the next week's most important outcome?

---

# 11. Non-Negotiable Engineering Rules

1. Never store raw card PAN.
2. Never let LLM agents directly approve or decline real-time payments.
3. Never activate generated fraud rules without human approval.
4. Every payment mutation must support idempotency.
5. Every fraud decision must be explainable.
6. Every model prediction must include model version.
7. Every rule execution must include rule version.
8. Every money movement must create ledger entries.
9. Every service must expose health, metrics, and structured logs.
10. Every critical state transition must be auditable.

---

# 12. Suggested First Command Sequence

```bash
mkdir end-to-end-payment-fraud-platform
cd end-to-end-payment-fraud-platform
mkdir backend frontend ml-platform infrastructure docs data
mkdir backend/payment-service backend/fraud-decision-service backend/feature-service backend/rules-engine-service backend/processor-adapter-service backend/common
mkdir frontend/analyst-dashboard
mkdir ml-platform/training ml-platform/model-serving ml-platform/feature-engineering ml-platform/model-evaluation
mkdir infrastructure/docker infrastructure/kubernetes infrastructure/helm infrastructure/terraform
```

Then start with:

```text
Day 1 → repo and architecture docs
Day 2 → Docker Compose infrastructure
Day 3 → common backend foundation
Day 4 → payment-service skeleton
Day 5 → database migrations
Day 6 → event contracts
Day 7 → architecture review
```

---

# 13. Final Staff Engineer Guidance

The strongest product strategy is:

```text
Build the payment lifecycle first.
Then integrate deterministic fraud decisioning.
Then add features and rules.
Then add ML.
Then add graph intelligence.
Then add feedback loops.
Then add simulation and dashboard.
Then add agents.
Then harden for production.
```

This order matters because agents and ML are only useful when they have high-quality events, labels, features, rules, and payment lifecycle data to reason over.

The final product should feel like:

```text
Stripe PaymentIntents
+ Stripe Radar-style fraud scoring
+ graph fraud intelligence
+ rule simulation platform
+ ML platform
+ fraud operations dashboard
+ GenAI fraud investigation copilot
```

That combination is strong enough to demonstrate Senior Staff-level architecture, product thinking, distributed systems design, ML platform maturity, and payment-domain depth.
