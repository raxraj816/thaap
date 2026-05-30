# Stripe Radar-Like Fraud Detection Platform — End-to-End Implementation Prompt

> Reference only. This prompt is preserved for background. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## Role

Act as a **Senior Staff Software Engineer, ML Platform Architect, Distributed Systems Architect, Fraud Detection Architect, and Technical Product Lead**.

Your job is to help design and implement an end-to-end fraud detection platform similar to **Stripe Radar**, but with an additional **GenAI-powered fraud intelligence layer**.

The system must support:

1. Real-time payment fraud scoring.
2. Rules-based fraud decisions.
3. ML-based fraud risk scoring.
4. Graph-based fraud ring detection.
5. Historical fraud analytics.
6. Rule backtesting and simulation.
7. Fraud analyst dashboard.
8. Model evaluation and monitoring.
9. GenAI agents for investigation, model evaluation, rule recommendation, and fraud explanation.
10. Human approval workflow before deploying new fraud rules.

The system must be production-grade, scalable, secure, observable, and designed with Staff Engineer-level thinking.

---

## 1. Product Goal

Build a fraud detection platform for payment systems that can:

```text
1. Accept payment authorization requests.
2. Extract fraud-relevant features.
3. Score payment risk using ML models.
4. Apply deterministic fraud rules.
5. Make one of several decisions:
   - approve
   - decline
   - step-up authentication
   - manual review
6. Store all transactions, features, model scores, rules fired, and final decisions.
7. Learn from chargebacks, manual review results, disputes, and confirmed fraud labels.
8. Detect fraud rings using graph intelligence.
9. Allow fraud analysts to investigate suspicious behavior using natural language.
10. Recommend new fraud rules after historical simulation.
11. Continuously evaluate model performance and fraud rule performance.
```

This should not be just a toy project. Design it as a real-world fraud operations platform.

---

## 2. Core Architecture Principle

Separate the platform into two major paths:

```text
A. Real-Time Fraud Decision Path
B. Offline / Near-Real-Time Fraud Intelligence Path
```

### A. Real-Time Fraud Decision Path

This path handles payment authorization.

It must be:

```text
low-latency
deterministic
highly available
secure
observable
auditable
```

The real-time path must **not call LLMs directly**.

Reason:

```text
LLMs are slower, non-deterministic, expensive, and harder to audit.
Payment decisions require predictable latency and deterministic behavior.
```

Real-time flow:

```text
Payment Request
   ↓
Payment Ingestion API
   ↓
Feature Extraction Service
   ↓
Fraud Scoring Service
   ↓
Rules Engine
   ↓
Decision Engine
   ↓
Approve / Decline / Step-up / Manual Review
   ↓
Event Logging
```

### B. Fraud Intelligence Path

This path works asynchronously.

It can use agents and LLMs.

Fraud intelligence flow:

```text
Transaction Events / Chargebacks / Review Labels
   ↓
Data Lake / Analytics Store / Graph Store
   ↓
Fraud Intelligence Orchestrator
   ↓
Specialized Agents
   ├── Fraud Pattern Detection Agent
   ├── Model Evaluator Agent
   ├── Fraud Rule Recommendation Agent
   ├── Graph Fraud Investigation Agent
   └── Fraud Explanation Agent
   ↓
Rule Recommendations / Model Insights / Analyst Reports
   ↓
Human Approval Workflow
   ↓
Rule Registry / Model Improvement Backlog
```

---

## 3. Preferred Technology Stack

Use this stack unless there is a strong reason to choose otherwise.

### Backend

```text
Java 21
Spring Boot 3.x
Spring Web
Spring Security
Spring Data JPA
Spring Validation
Spring Actuator
Spring Cloud where useful
```

### Real-Time Messaging

```text
Apache Kafka
Kafka Streams where useful
```

### Databases

Use different databases for different access patterns.

```text
PostgreSQL:
- users
- merchants
- payment transactions
- fraud decisions
- rules
- reviews
- chargebacks
- audit logs

Redis:
- low-latency feature cache
- velocity counters
- temporary risk signals
- idempotency cache

ClickHouse:
- high-volume analytical queries
- transaction analytics
- model score logs
- rule performance analytics

Neo4j:
- entity graph
- customer-device-IP-card-merchant relationships
- fraud ring detection

S3-compatible object storage:
- raw events
- training datasets
- model artifacts
- backtesting outputs
```

For production AWS version:

```text
PostgreSQL → Amazon RDS / Aurora PostgreSQL
Redis → ElastiCache Redis
Kafka → MSK
ClickHouse → ClickHouse Cloud or Apache Pinot / Redshift alternative
Neo4j → Neo4j Aura or Amazon Neptune
S3 → Amazon S3
ML deployment → SageMaker / Kubernetes
LLM → Amazon Bedrock or OpenAI-compatible abstraction
```

### Frontend

```text
React
TypeScript
TanStack Query
Zustand or Redux Toolkit
Tailwind CSS
Recharts
```

### ML

```text
Python
FastAPI
XGBoost / LightGBM
scikit-learn
pandas
NumPy
MLflow
Feast or custom feature store
SHAP for explainability
```

### Deployment

```text
Docker
Kubernetes
Helm
Terraform
GitHub Actions
OpenTelemetry
Prometheus
Grafana
Loki or ELK
```

---

## 4. High-Level Services

Implement the system as modular services.

For MVP, services may live in a monorepo. For production, they can be deployed independently.

### Required Services

```text
1. payment-ingestion-service
2. feature-service
3. fraud-scoring-service
4. rules-engine-service
5. decision-engine-service
6. transaction-ledger-service
7. event-streaming-service
8. chargeback-service
9. manual-review-service
10. rule-registry-service
11. rule-simulation-service
12. model-registry-service
13. model-monitoring-service
14. graph-intelligence-service
15. fraud-intelligence-agent-service
16. analyst-dashboard-ui
17. auth-service
18. audit-service
```

---

## 5. Repository Structure

Create a monorepo.

```text
fraud-detection-platform/
│
├── backend/
│   ├── payment-ingestion-service/
│   ├── feature-service/
│   ├── fraud-scoring-service/
│   ├── rules-engine-service/
│   ├── decision-engine-service/
│   ├── transaction-ledger-service/
│   ├── chargeback-service/
│   ├── manual-review-service/
│   ├── rule-registry-service/
│   ├── rule-simulation-service/
│   ├── graph-intelligence-service/
│   ├── fraud-intelligence-agent-service/
│   ├── audit-service/
│   └── common/
│
├── ml-platform/
│   ├── training/
│   ├── feature-engineering/
│   ├── model-serving/
│   ├── model-evaluation/
│   ├── model-registry/
│   └── notebooks/
│
├── frontend/
│   └── analyst-dashboard/
│
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   ├── helm/
│   └── terraform/
│
├── data/
│   ├── sample-transactions/
│   ├── synthetic-data-generator/
│   └── seed-data/
│
├── docs/
│   ├── architecture.md
│   ├── api-contracts.md
│   ├── data-model.md
│   ├── ml-design.md
│   ├── rules-engine.md
│   ├── agentic-fraud-intelligence.md
│   ├── security.md
│   └── runbook.md
│
└── README.md
```

---

## 6. Domain Model

Design the fraud platform around these core entities.

### Merchant

```text
merchant_id
merchant_name
merchant_category_code
business_country
risk_tier
onboarding_date
chargeback_rate
status
created_at
updated_at
```

### Customer

```text
customer_id
merchant_id
email_hash
phone_hash
account_age_days
country
kyc_status
risk_score
created_at
updated_at
```

### Payment Transaction

```text
transaction_id
merchant_id
customer_id
payment_method_id
amount
currency
merchant_category_code
transaction_timestamp
ip_address_hash
device_id
billing_country
shipping_country
card_bin
card_country
status
created_at
```

### Payment Method

```text
payment_method_id
customer_id
card_bin
card_last4_hash
card_country
card_fingerprint
first_seen_at
last_seen_at
```

### Device

```text
device_id
device_fingerprint
first_seen_at
last_seen_at
risk_score
```

### Fraud Decision

```text
decision_id
transaction_id
risk_score
model_score
rules_score
graph_score
decision
reason_codes
rules_triggered
model_version
decision_latency_ms
created_at
```

### Fraud Rule

```text
rule_id
rule_name
rule_description
rule_expression
action
priority
status
version
created_by
approved_by
created_at
approved_at
activated_at
deactivated_at
```

### Rule Simulation Result

```text
simulation_id
rule_id
rule_version
start_date
end_date
transactions_evaluated
fraud_captured_count
fraud_captured_amount
false_positive_count
false_positive_rate
good_transactions_impacted
manual_review_increase
recommended_action
created_at
```

### Chargeback

```text
chargeback_id
transaction_id
merchant_id
customer_id
reason_code
amount
currency
chargeback_date
is_confirmed_fraud
created_at
```

### Manual Review Case

```text
review_id
transaction_id
assigned_to
review_status
analyst_decision
analyst_notes
created_at
resolved_at
```

---

## 7. Real-Time Payment Fraud Flow

Implement this flow carefully.

```text
Client / Merchant
   ↓
POST /payments/authorize
   ↓
payment-ingestion-service
   ↓
feature-service
   ↓
fraud-scoring-service
   ↓
rules-engine-service
   ↓
decision-engine-service
   ↓
response
```

### Payment Authorization API

Create endpoint:

```http
POST /api/v1/payments/authorize
```

Request:

```json
{
  "merchantId": "m_123",
  "customerId": "c_456",
  "paymentMethodId": "pm_789",
  "amount": 12500,
  "currency": "INR",
  "merchantCategoryCode": "5812",
  "ipAddress": "203.0.113.10",
  "deviceId": "device_abc",
  "billingCountry": "IN",
  "shippingCountry": "IN",
  "metadata": {
    "checkoutSessionId": "cs_123",
    "userAgent": "Mozilla/5.0"
  }
}
```

Response:

```json
{
  "transactionId": "txn_123",
  "decision": "APPROVE",
  "riskScore": 0.27,
  "reasonCodes": [
    "LOW_RISK_CUSTOMER",
    "KNOWN_DEVICE"
  ],
  "requiresAction": false
}
```

Possible decisions:

```text
APPROVE
DECLINE
STEP_UP_AUTH
MANUAL_REVIEW
```

---

## 8. Feature Engineering

Build feature extraction in layers.

### Real-Time Features

These must be computed quickly.

Examples:

```text
transaction_amount
currency
merchant_category_code
account_age_days
is_new_customer
is_new_device
is_new_payment_method
billing_shipping_country_mismatch
card_ip_country_mismatch
transaction_hour_of_day
transaction_day_of_week
```

### Velocity Features

Use Redis for fast counters.

Examples:

```text
customer_txn_count_5m
customer_txn_count_1h
customer_txn_count_24h
card_txn_count_5m
card_failed_count_10m
ip_txn_count_1h
device_txn_count_1h
merchant_txn_count_1h
amount_sum_customer_1h
amount_sum_card_24h
```

Redis key examples:

```text
velocity:customer:{customerId}:txn_count:5m
velocity:card:{cardFingerprint}:failed_count:10m
velocity:ip:{ipHash}:txn_count:1h
velocity:device:{deviceId}:txn_count:1h
```

Use TTL-based counters.

### Historical Features

Stored in PostgreSQL or ClickHouse.

Examples:

```text
merchant_chargeback_rate_30d
customer_chargeback_count_90d
payment_method_age_days
device_first_seen_days_ago
merchant_approval_rate_7d
merchant_decline_rate_7d
average_transaction_amount_30d
```

### Graph Features

From Neo4j.

Examples:

```text
fraud_neighbor_count_1_hop
fraud_neighbor_count_2_hop
shared_device_with_fraud_customer_count
shared_ip_with_fraud_customer_count
shared_card_with_multiple_customers
connected_component_risk_score
distance_to_nearest_known_fraud
merchant_cluster_risk_score
```

---

## 9. Rules Engine

Implement a deterministic fraud rules engine.

Rules must support expressions like:

```text
account_age_days < 3
AND transaction_amount > 10000
AND device_fraud_neighbor_count >= 2
AND customer_txn_count_1h > 5
```

### Rule Actions

Supported actions:

```text
ALLOW
APPROVE
DECLINE
STEP_UP_AUTH
MANUAL_REVIEW
ADD_RISK_SCORE
ADD_REASON_CODE
```

### Rule Example

```json
{
  "ruleName": "New account high velocity risky device",
  "description": "New customers using devices linked to known fraud accounts with high transaction velocity should be reviewed.",
  "expression": "account_age_days < 3 AND customer_txn_count_1h > 5 AND device_fraud_neighbor_count >= 2",
  "action": "MANUAL_REVIEW",
  "priority": 100,
  "status": "ACTIVE"
}
```

### Rule Engine Requirements

Implement:

```text
1. Rule parsing.
2. Rule validation.
3. Rule versioning.
4. Rule priority.
5. Rule action resolution.
6. Rule conflict handling.
7. Rule execution latency tracking.
8. Rule result explanation.
9. Rule audit logging.
10. Rule rollback.
```

Do not allow unsafe dynamic code execution.

Use a safe expression engine or build a restricted DSL.

---

## 10. Fraud Scoring Service

Build a service that combines:

```text
1. ML model score
2. Rule score
3. Graph score
4. Merchant risk score
5. Customer risk score
```

Example score composition:

```text
final_risk_score =
    0.50 * ml_model_score
  + 0.20 * graph_risk_score
  + 0.15 * rule_risk_score
  + 0.10 * merchant_risk_score
  + 0.05 * customer_risk_score
```

Make weights configurable.

### Decision Thresholds

Example:

```text
risk_score < 0.40       → APPROVE
0.40 to 0.65            → APPROVE with monitoring
0.65 to 0.80            → STEP_UP_AUTH
0.80 to 0.92            → MANUAL_REVIEW
risk_score >= 0.92      → DECLINE
```

Thresholds should be configurable per merchant, region, and risk tier.

---

## 11. ML Model

Start with XGBoost or LightGBM.

### Training Dataset

Create a dataset where each row represents a transaction.

Label:

```text
0 = legitimate
1 = confirmed fraud
```

Features:

```text
transaction_amount
account_age_days
is_new_device
is_new_payment_method
customer_txn_count_5m
customer_txn_count_1h
card_failed_count_10m
ip_txn_count_1h
merchant_chargeback_rate_30d
card_ip_country_mismatch
billing_shipping_country_mismatch
fraud_neighbor_count_1_hop
fraud_neighbor_count_2_hop
distance_to_nearest_known_fraud
merchant_cluster_risk_score
```

### Model Training Steps

Implement:

```text
1. Load transaction data.
2. Join fraud labels from chargebacks and review outcomes.
3. Generate features.
4. Split data by time, not randomly.
5. Train baseline logistic regression.
6. Train XGBoost / LightGBM.
7. Evaluate precision, recall, F1, ROC-AUC, PR-AUC.
8. Evaluate segment-level performance.
9. Calibrate probabilities.
10. Export model artifact.
11. Register model in MLflow.
12. Deploy model to model-serving service.
```

Use time-based split because fraud data is temporal.

Example:

```text
Train: January to March
Validation: April
Test: May
```

Do not randomly split because that leaks future fraud patterns into training.

### Required Metrics

Track:

```text
precision
recall
F1
ROC-AUC
PR-AUC
false positive rate
false negative rate
chargeback capture rate
fraud amount captured
good customer decline rate
manual review rate
approval rate
```

For fraud detection, PR-AUC is often more useful than ROC-AUC because fraud is rare.

---

## 12. Model Serving

Create a Python FastAPI model-serving service.

Endpoint:

```http
POST /api/v1/models/fraud-score
```

Request:

```json
{
  "transactionId": "txn_123",
  "features": {
    "transaction_amount": 12500,
    "account_age_days": 2,
    "is_new_device": true,
    "customer_txn_count_1h": 8,
    "device_fraud_neighbor_count": 3
  }
}
```

Response:

```json
{
  "modelVersion": "fraud-xgb-v1.0.0",
  "score": 0.87,
  "reasonCodes": [
    "NEW_DEVICE",
    "HIGH_VELOCITY",
    "FRAUD_GRAPH_PROXIMITY"
  ],
  "topFeatures": [
    {
      "name": "device_fraud_neighbor_count",
      "impact": 0.31
    },
    {
      "name": "customer_txn_count_1h",
      "impact": 0.22
    }
  ]
}
```

---

## 13. Graph Fraud Intelligence

Use Neo4j for MVP.

### Graph Nodes

```text
Customer
Device
IP
PaymentMethod
CardFingerprint
Merchant
Transaction
Chargeback
FraudCase
Email
Phone
Address
```

### Graph Relationships

```text
Customer -[:USES_DEVICE]-> Device
Customer -[:USES_PAYMENT_METHOD]-> PaymentMethod
Customer -[:LOGGED_IN_FROM]-> IP
Customer -[:PLACED_TRANSACTION]-> Transaction
Transaction -[:PAID_TO]-> Merchant
Transaction -[:RESULTED_IN]-> Chargeback
PaymentMethod -[:HAS_CARD_FINGERPRINT]-> CardFingerprint
Customer -[:HAS_EMAIL]-> Email
Customer -[:HAS_PHONE]-> Phone
Customer -[:SHIPS_TO]-> Address
FraudCase -[:INVOLVES]-> Customer
FraudCase -[:INVOLVES]-> Transaction
```

### Graph Queries

Implement queries for:

```text
1. Customers sharing device with known fraud customers.
2. Customers sharing IP with known fraud customers.
3. Cards used by multiple unrelated customers.
4. Merchants connected to high chargeback clusters.
5. Two-hop and three-hop fraud proximity.
6. Connected component risk scoring.
7. Fraud ring candidate detection.
```

Example Cypher:

```cypher
MATCH (fraud:Customer {knownFraud: true})-[:USES_DEVICE]->(d:Device)<-[:USES_DEVICE]-(candidate:Customer)
WHERE candidate.knownFraud = false
RETURN candidate.customerId, count(DISTINCT fraud) AS fraudNeighborCount
ORDER BY fraudNeighborCount DESC
LIMIT 100;
```

---

## 14. Rule Simulation Service

Build rule backtesting before rule activation.

Input:

```json
{
  "ruleExpression": "account_age_days < 3 AND customer_txn_count_1h > 5 AND device_fraud_neighbor_count >= 2",
  "action": "MANUAL_REVIEW",
  "startDate": "2026-01-01",
  "endDate": "2026-01-31"
}
```

Output:

```json
{
  "transactionsEvaluated": 1200000,
  "matchedTransactions": 8421,
  "confirmedFraudMatched": 927,
  "fraudCapturedAmount": 3840000,
  "falsePositiveCount": 712,
  "falsePositiveRate": 0.014,
  "goodTransactionsImpacted": 712,
  "manualReviewIncrease": 0.08,
  "recommendedAction": "DEPLOY_AS_STEP_UP_AUTH",
  "risk": "LOW_TO_MEDIUM"
}
```

Simulation must calculate:

```text
fraud captured
fraud amount captured
false positives
false positive rate
false negatives reduced
good transactions impacted
manual review load increase
merchant impact
country impact
customer segment impact
revenue impact
```

---

## 15. Fraud Intelligence Agents

Implement agentic intelligence as an offline system.

Use agents only for:

```text
investigation
explanation
analytics
model evaluation
rule recommendation
rule simulation orchestration
```

Do not let agents directly approve or decline real-time payments.

### Agent 1: Fraud Intelligence Orchestrator

Responsibilities:

```text
1. Understand analyst query.
2. Break query into tasks.
3. Route tasks to specialized agents.
4. Call tools safely.
5. Combine results.
6. Produce final report.
7. Recommend next actions.
```

Example user query:

```text
"Analyze last week's chargebacks and recommend rules to reduce similar fraud."
```

Orchestrator plan:

```text
1. Query chargebacks from last week.
2. Group by merchant, device, IP, card BIN, and customer age.
3. Ask Graph Agent to find shared entities.
4. Ask Model Evaluator Agent to inspect model scores.
5. Ask Rule Agent to generate candidate rules.
6. Run simulations.
7. Return ranked recommendations.
```

### Agent 2: Fraud Pattern Detection Agent

Responsibilities:

```text
1. Analyze suspicious transaction clusters.
2. Detect card testing.
3. Detect account takeover patterns.
4. Detect synthetic identity signals.
5. Detect merchant fraud.
6. Detect shared device/IP/card fraud rings.
```

### Agent 3: Model Evaluator Agent

Responsibilities:

```text
1. Analyze false positives.
2. Analyze false negatives.
3. Identify weak fraud segments.
4. Detect model drift.
5. Detect feature drift.
6. Recommend new features.
7. Recommend retraining.
```

Example output:

```text
The current model is missing shared-device fraud rings because graph-based features are weak.
Recommendation:
1. Add fraud_neighbor_count_2_hop.
2. Add shared_device_with_known_fraud_count.
3. Retrain model using recent chargebacks.
```

### Agent 4: Fraud Rule Recommendation Agent

Responsibilities:

```text
1. Generate candidate fraud rules.
2. Validate syntax.
3. Estimate business impact.
4. Trigger historical simulation.
5. Rank rules by fraud captured and false positive rate.
6. Submit rule for human approval.
```

### Agent 5: Fraud Explanation Agent

Responsibilities:

```text
1. Explain why a transaction was approved, declined, stepped up, or reviewed.
2. Explain which rules fired.
3. Explain which model features contributed.
4. Explain graph risk.
5. Generate analyst-readable summaries.
```

Example explanation:

```text
Transaction txn_123 was sent to manual review because:
1. Customer account age is only 2 days.
2. Device is linked to 3 confirmed fraud accounts.
3. Customer made 8 transactions in the last hour.
4. XGBoost model score was 0.87.
5. Rule R-102 triggered manual review.
```

---

## 16. Agent Tools

Agents should access tools through safe interfaces.

Do not give agents raw database credentials.

Create tool APIs.

### Tools

```text
1. queryTransactionAnalyticsTool
2. queryGraphTool
3. getModelPerformanceTool
4. getRulePerformanceTool
5. simulateRuleTool
6. generateRuleCandidateTool
7. fetchChargebacksTool
8. fetchManualReviewOutcomesTool
9. createInvestigationReportTool
10. submitRuleForApprovalTool
```

Each tool must have:

```text
input schema
output schema
authorization check
audit logging
rate limit
timeout
read/write permission boundary
```

---

## 17. Human Approval Workflow

No generated rule should become active automatically.

Workflow:

```text
DRAFT
   ↓
VALIDATED
   ↓
SIMULATED
   ↓
PENDING_APPROVAL
   ↓
APPROVED
   ↓
CANARY
   ↓
ACTIVE
   ↓
ROLLED_BACK / DEACTIVATED
```

Approval screen should show:

```text
rule expression
business explanation
simulation period
fraud captured
false positive rate
good users impacted
merchant impact
manual review load
recommended rollout
rollback button
approver comments
```

---

## 18. Dashboard Requirements

Build a React + TypeScript analyst dashboard.

### Pages

```text
1. Overview Dashboard
2. Transaction Search
3. Transaction Detail
4. Fraud Decision Explanation
5. Manual Review Queue
6. Chargeback Analytics
7. Rules Management
8. Rule Simulation
9. Model Performance
10. Graph Investigation
11. Agentic Fraud Copilot
12. Audit Logs
```

### Overview Dashboard Metrics

```text
total transactions
approved transactions
declined transactions
step-up transactions
manual review transactions
fraud rate
chargeback rate
false positive rate
model precision
model recall
fraud amount prevented
top risky merchants
top risky countries
top triggered rules
```

### Transaction Detail Page

Show:

```text
transaction metadata
customer information
payment method signals
device signals
IP signals
model score
rule score
graph score
final risk score
decision
reason codes
rules triggered
model top features
graph connections
audit timeline
```

---

## 19. Observability

Implement full observability.

### Technical Metrics

```text
payment_authorization_latency_p50
payment_authorization_latency_p95
payment_authorization_latency_p99
feature_extraction_latency
model_scoring_latency
rules_engine_latency
decision_engine_latency
database_latency
redis_latency
kafka_lag
service_error_rate
```

### Fraud Metrics

```text
approval_rate
decline_rate
step_up_rate
manual_review_rate
chargeback_rate
fraud_capture_rate
false_positive_rate
false_negative_rate
good_customer_decline_rate
fraud_amount_prevented
```

### ML Metrics

```text
model_precision
model_recall
model_f1
model_auc
model_pr_auc
model_drift
feature_drift
calibration_error
segment_level_recall
```

### Agent Metrics

```text
agent_task_success_rate
agent_tool_call_failure_rate
agent_average_latency
agent_simulation_success_rate
agent_recommendation_acceptance_rate
```

Use:

```text
OpenTelemetry
Prometheus
Grafana
Loki / ELK
Jaeger / Tempo
```

---

## 20. Security Requirements

Implement security from day one.

### Data Security

```text
1. Never store raw card PAN.
2. Hash/tokenize sensitive identifiers.
3. Encrypt data at rest.
4. Encrypt data in transit.
5. Use field-level encryption for sensitive data.
6. Use least-privilege IAM.
7. Maintain audit logs for every analyst action.
8. Mask PII in UI.
9. Implement retention policy.
10. Make the architecture PCI-DSS-aware.
```

### Agent Security

```text
1. Agents must not access raw PII unless explicitly authorized.
2. Agents must not activate rules directly.
3. Agents must use approved tools only.
4. Tool calls must be logged.
5. Generated SQL or graph queries must be validated.
6. Write operations must require explicit permission.
7. Sensitive outputs must be redacted.
```

---

## 21. Implementation Phases

Implement in this order.

### Phase 1: Foundation

Build:

```text
1. Monorepo structure.
2. Docker Compose environment.
3. PostgreSQL setup.
4. Redis setup.
5. Kafka setup.
6. Neo4j setup.
7. ClickHouse setup.
8. Spring Boot common libraries.
9. Shared DTOs.
10. Error handling framework.
11. Logging framework.
12. Authentication foundation.
```

Deliverables:

```text
docker-compose.yml
database migrations
service skeletons
health endpoints
local development setup
```

### Phase 2: Payment Ingestion and Transaction Storage

Build:

```text
1. Payment authorization API.
2. Request validation.
3. Idempotency key support.
4. Transaction persistence.
5. Transaction event publishing to Kafka.
6. Transaction ledger service.
```

Deliverables:

```text
POST /api/v1/payments/authorize
transactions table
Kafka transaction-events topic
basic integration tests
```

### Phase 3: Feature Service

Build:

```text
1. Static feature extraction.
2. Redis velocity counters.
3. Historical feature lookup.
4. Graph feature placeholder.
5. Feature response API.
```

Deliverables:

```text
feature vector per transaction
Redis counters
feature audit logs
```

### Phase 4: Rules Engine

Build:

```text
1. Rule registry.
2. Rule DSL.
3. Rule parser.
4. Rule validator.
5. Rule executor.
6. Rule priority handling.
7. Rule result explanation.
8. Rule versioning.
```

Deliverables:

```text
create rule API
validate rule API
execute rules API
rule management tests
```

### Phase 5: ML Baseline

Build:

```text
1. Synthetic fraud dataset generator.
2. Feature engineering pipeline.
3. Baseline logistic regression.
4. XGBoost model.
5. Model evaluation notebook/script.
6. MLflow model registry.
7. FastAPI model serving endpoint.
```

Deliverables:

```text
trained fraud model
model metrics report
model serving API
Spring Boot integration with model API
```

### Phase 6: Decision Engine

Build:

```text
1. Combine ML score, rule results, and graph score.
2. Apply decision thresholds.
3. Generate final decision.
4. Persist decision.
5. Publish fraud-decision event.
6. Return response to payment API.
```

Deliverables:

```text
real-time fraud decision flow
decision logs
reason codes
latency metrics
```

### Phase 7: Manual Review and Chargebacks

Build:

```text
1. Manual review queue.
2. Assign review cases.
3. Analyst decision API.
4. Chargeback ingestion API.
5. Fraud label creation.
6. Feedback event publishing.
```

Deliverables:

```text
manual review workflow
chargeback tables
fraud labels
feedback loop
```

### Phase 8: Graph Intelligence

Build:

```text
1. Entity graph schema in Neo4j.
2. Event consumer to populate graph.
3. Customer-device-IP-payment-method graph relationships.
4. Known fraud node marking.
5. Graph feature APIs.
6. Fraud ring detection queries.
```

Deliverables:

```text
Neo4j graph populated from events
graph risk features
fraud ring detection endpoint
```

### Phase 9: Rule Simulation

Build:

```text
1. Historical feature replay.
2. Rule backtesting engine.
3. False positive calculation.
4. Fraud captured calculation.
5. Business impact report.
6. Simulation result persistence.
```

Deliverables:

```text
simulate rule API
simulation report
rule impact dashboard
```

### Phase 10: Dashboard

Build:

```text
1. Overview page.
2. Transaction search.
3. Transaction detail.
4. Rules management.
5. Rule simulation UI.
6. Manual review queue.
7. Model performance page.
8. Graph investigation page.
```

Deliverables:

```text
React dashboard
API integration
charts
tables
filters
risk explanations
```

### Phase 11: Fraud Intelligence Agents

Build:

```text
1. Agent orchestrator.
2. Fraud pattern detection agent.
3. Model evaluator agent.
4. Rule recommendation agent.
5. Fraud explanation agent.
6. Tool interfaces.
7. Audit logging for agent actions.
8. Human approval integration.
```

Deliverables:

```text
natural language fraud investigation
agent-generated fraud reports
agent-generated rule candidates
agent-triggered simulations
```

### Phase 12: Production Hardening

Build:

```text
1. Kubernetes deployment.
2. Helm charts.
3. Terraform infrastructure.
4. CI/CD pipeline.
5. Canary deployments.
6. Load testing.
7. Chaos testing.
8. Security testing.
9. Cost optimization.
10. Runbooks.
```

Deliverables:

```text
production deployment setup
monitoring dashboards
alerts
SLOs
incident runbooks
```

---

## 22. Testing Strategy

Implement tests at every layer.

### Unit Tests

```text
rule parser
rule evaluator
feature extraction
decision thresholds
score calculation
reason code generation
```

### Integration Tests

```text
payment API to decision engine
feature service to Redis
rules engine to database
model scoring integration
Kafka event publishing
Neo4j graph update
```

### ML Tests

```text
data schema validation
feature distribution checks
training reproducibility
model metric threshold checks
model calibration checks
```

### Agent Tests

```text
tool permission tests
prompt injection tests
invalid query tests
unsafe rule generation tests
hallucination guardrail tests
```

### Load Tests

Test:

```text
100 TPS
500 TPS
1,000 TPS
5,000 TPS
```

Measure:

```text
p50 latency
p95 latency
p99 latency
error rate
Kafka lag
Redis latency
database saturation
```

---

## 23. Acceptance Criteria

The implementation is successful when:

```text
1. A payment request can be scored end to end.
2. Features are extracted and persisted.
3. ML model score is generated.
4. Rules are evaluated.
5. Final fraud decision is returned.
6. Transaction events are published.
7. Manual review works.
8. Chargebacks become fraud labels.
9. Neo4j graph shows fraud relationships.
10. Rule simulation works on historical data.
11. Dashboard shows fraud metrics.
12. Agent can investigate chargebacks.
13. Agent can recommend a rule.
14. Rule requires human approval before activation.
15. Observability dashboards show latency, error, and fraud metrics.
```

---

## 24. Engineering Constraints

Follow these principles:

```text
1. Prefer correctness over cleverness.
2. Keep real-time decision path deterministic.
3. Keep LLMs outside payment authorization.
4. Every decision must be explainable.
5. Every rule must be versioned.
6. Every rule activation must be auditable.
7. Every model must be versioned.
8. Every model prediction must store model version.
9. Every agent action must be logged.
10. Every fraud metric must be segmentable by merchant, country, payment method, and time.
```

---

## 25. First Implementation Sprint

Start with this exact sprint.

### Sprint 1 Goal

Build the foundation for real-time payment scoring.

### Sprint 1 Tasks

```text
1. Create monorepo.
2. Create Docker Compose with PostgreSQL, Redis, Kafka, Neo4j.
3. Create Spring Boot payment-ingestion-service.
4. Create PostgreSQL schema for merchants, customers, transactions, fraud_decisions.
5. Implement POST /api/v1/payments/authorize.
6. Add request validation.
7. Add transaction persistence.
8. Add Kafka transaction event publishing.
9. Create feature-service skeleton.
10. Implement basic features:
   - transaction_amount
   - account_age_days
   - is_new_device
   - billing_shipping_country_mismatch
   - card_ip_country_mismatch
11. Create simple rules-engine-service.
12. Add one hardcoded rule:
   - if amount > 100000 then MANUAL_REVIEW
13. Create decision-engine-service.
14. Return APPROVE or MANUAL_REVIEW.
15. Add integration test for full flow.
16. Add README with local setup instructions.
```

### Sprint 1 Done Means

```text
A developer can run docker-compose up,
send a payment authorization request,
and receive a fraud decision response.
```

---

## 26. Expected Final System

At the end, the product should behave like this:

```text
Merchant submits payment.
System scores fraud risk in real time.
System applies ML and rules.
System returns approve/decline/step-up/review.
All events are logged.
Chargebacks feed back into labels.
Graph system detects fraud rings.
Rule simulator tests new rules safely.
Analyst dashboard explains decisions.
GenAI agents investigate fraud patterns.
Agents recommend rules.
Humans approve rules.
System improves over time.
```

---

## 27. Important Design Rule

Never build this as only an ML model.

A real fraud detection platform is:

```text
ML models
+ rules engine
+ graph intelligence
+ feature platform
+ decision engine
+ feedback loop
+ analyst tools
+ simulation engine
+ monitoring
+ security
+ governance
```

The GenAI agent layer should make fraud analysts and ML engineers faster, but the real-time system should remain deterministic, explainable, and safe.

---

## 28. Output Format for Implementation

When implementing, always provide:

```text
1. Architecture explanation.
2. Service responsibilities.
3. Data model.
4. API contracts.
5. Step-by-step implementation.
6. Production-quality code.
7. Tests.
8. Observability.
9. Security considerations.
10. Failure modes.
11. Tradeoffs.
12. Next steps.
```

For Java code:

```text
Use Java 21.
Use Spring Boot 3.
Use clear package structure.
Add comments explaining important decisions.
Handle errors properly.
Use DTOs.
Use validation annotations.
Use service-layer abstraction.
Use repository abstraction.
Write unit tests and integration tests.
```

For ML code:

```text
Use Python.
Keep training reproducible.
Use time-based train/test split.
Track metrics.
Register model.
Expose model through FastAPI.
Explain model predictions.
```

For agents:

```text
Use tools, not direct database access.
Validate every tool call.
Log every action.
Prevent direct production rule activation.
Require human approval.
```

---

## 29. Build Order Summary

Build in this order:

```text
1. Payment ingestion
2. Transaction storage
3. Feature extraction
4. Rules engine
5. Basic decision engine
6. ML scoring
7. Feedback labels
8. Manual review
9. Graph intelligence
10. Rule simulation
11. Dashboard
12. Model monitoring
13. Agentic fraud intelligence
14. Production deployment
```

---

## 30. Final Instruction

Implement this system incrementally.

Do not skip foundational pieces.

Do not start with GenAI agents first.

Start with the real-time fraud decision platform, then build the data foundation, then graph intelligence, then simulation, then agents.

At each phase, produce working code, tests, documentation, and clear architectural reasoning.

---

## Recommended Immediate Start

Start with **Sprint 1** immediately:

```text
payment-ingestion-service
feature-service
rules-engine-service
decision-engine-service
PostgreSQL
Redis
Kafka
basic fraud decision API
```

This gives the system its backbone.

Then add:

```text
ML model → graph intelligence → simulation → dashboard → agents
```

This is the correct Staff Engineer-level path because it builds the **decision system first**, then adds intelligence and automation on top.
