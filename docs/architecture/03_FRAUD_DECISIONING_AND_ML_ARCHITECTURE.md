# 03. Fraud Decisioning And ML Architecture

Date: 2026-05-29

This document defines the fraud decisioning architecture, rules engine, feature pipeline, ML lifecycle, graph intelligence, manual review feedback loop, and GenAI agent boundary.

For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. Fraud Architecture Goals

The fraud system must:

- make deterministic real-time decisions
- keep latency bounded
- persist every decision
- explain every decision
- support rules and ML scoring
- support manual review
- learn from chargebacks and review outcomes
- support rule simulation before activation
- support graph investigation
- use GenAI only offline

Core rule:

```text
LLMs never run in the real-time authorization path.
```

## 2. Real-Time Fraud Decision Path

```text
PaymentAttempt
  -> feature extraction
  -> velocity lookup
  -> active rule evaluation
  -> model score, later
  -> graph signals, later if latency-safe
  -> decision engine
  -> FraudDecision
  -> action applied to payment flow
```

Decision actions:

- `ALLOW`
- `BLOCK`
- `REQUEST_3DS`
- `REVIEW`

## 3. Decision Engine Contract

Input:

- payment attempt ID
- merchant ID
- customer ID
- payment method ID
- amount
- currency
- capture method
- feature snapshot
- velocity features
- active ruleset
- merchant risk policy
- model score, later
- graph risk signals, later

Output:

- action
- risk score
- risk level
- reason codes
- model version, when available
- ruleset version
- rules fired
- latency

Persistence:

- `FraudDecision`
- `FeatureSnapshot`
- `RuleExecutionResult`
- `ModelPrediction`, later

Tenant requirement:

- every fraud decision, feature snapshot, model prediction, rule result, replay row, and investigation artifact must carry or derive `merchant_id`
- merchant-specific features must not be used across tenants unless explicitly transformed into global/anonymized aggregate signals
- model evaluation must report merchant and segment impact before promotion

## 4. Decision Ordering

Rule behavior order:

1. `REQUEST_3DS`
2. `ALLOW`
3. `BLOCK`
4. `REVIEW`

Meaning:

- `REQUEST_3DS` can require authentication before authorization.
- `ALLOW` short-circuits block/review rules.
- `BLOCK` short-circuits review rules.
- `REVIEW` creates a manual review case.

After ML exists, model thresholds and merchant policy are combined after deterministic rule results. Hard safety/compliance rules should still take precedence.

## 5. Risk Levels

Initial defaults:

- `LOW`: score < `0.30`
- `MEDIUM`: `0.30` to `0.65`
- `HIGH`: `0.65` to `0.85`
- `CRITICAL`: score > `0.85`

Merchant-configurable thresholds come later.

## 6. Feature Architecture

### 6.1 Feature Snapshot Principle

Every decision stores the exact features used.

Why:

- auditability
- reproducibility
- model debugging
- rule debugging
- backtesting
- analyst explanations

Rule:

```text
No future information in real-time feature snapshots.
```

### 6.2 Real-Time Features

Payment features:

- amount
- currency
- capture method
- payment method age
- card BIN country
- card funding type
- billing country
- shipping country
- CVC result
- AVS result
- 3DS status/result

Customer features:

- account age
- prior successful payments
- prior declines
- prior reviews
- prior refunds
- prior chargebacks

Velocity features:

- customer attempts in 5 minutes
- customer attempts in 1 hour
- customer attempts in 24 hours
- card fingerprint attempts in 5 minutes
- card fingerprint attempts in 1 hour
- card fingerprint attempts in 24 hours
- IP attempts in 5 minutes
- IP attempts in 1 hour
- IP attempts in 24 hours
- device attempts in 5 minutes
- device attempts in 1 hour
- device attempts in 24 hours
- merchant attempts in 5 minutes
- merchant attempts in 1 hour
- merchant attempts in 24 hours

Behavioral features:

- amount deviation from customer historical median
- distinct cards per customer
- distinct customers per card fingerprint
- country mismatch flags
- related transaction count

Graph features, later:

- fraud-labeled neighbors within one hop
- fraud-labeled neighbors within two hops
- shared device count
- shared IP count
- connected component risk score

### 6.3 Velocity Counter Rule

Velocity counters must exclude the current payment being evaluated.

Flow:

```text
read counters
  -> build features
  -> make decision
  -> persist decision
  -> increment counters after decision attempt is recorded
```

### 6.4 Feature Versioning

Start with:

- `features_v1`

When features change:

- create a new feature version
- preserve old snapshots
- train models against explicit feature version
- record feature version on model predictions

### 6.5 V1 Feature Store Abstraction

Do not jump directly to Feast. Add a simple V1 abstraction first.

Tables:

- `FeatureDefinition`
- `FeatureValue`
- `FeatureSnapshot`

`FeatureDefinition` fields:

- feature name
- entity type
- value type
- owner
- version
- description
- created time

`FeatureValue` fields:

- entity type
- entity ID
- feature name
- value
- feature timestamp
- computed timestamp
- feature version

`FeatureSnapshot` remains the immutable per-decision record linked to FraudDecision.

Point-in-time training rule:

```text
For a PaymentAttempt at time T,
training joins can only use FeatureValue rows where feature_timestamp <= T.
```

Do not build training rows by reading current customer/payment state.

## 7. Rules Engine Architecture

### 7.1 Rule DSL

Use constrained JSON, not arbitrary code.

Example:

```json
{
  "all": [
    { "field": "amount", "op": "gte", "value": 50000 },
    { "field": "velocity.customer_1h_count", "op": "gte", "value": 5 },
    { "field": "card_country", "op": "neq", "valueFrom": "billing_country" }
  ]
}
```

Operators:

- `eq`
- `neq`
- `gt`
- `gte`
- `lt`
- `lte`
- `in`
- `not_in`
- `exists`
- `is_missing`
- `contains`

Composition:

- `all`
- `any`
- `not`

Missing values:

- missing attributes must not silently pass comparisons
- use `is_missing` explicitly when missingness is intended

### 7.2 Rule Entity

Rule must include:

- ID
- name
- description
- condition expression
- action
- priority
- status
- version
- created by
- approved by
- created time
- activated time

### 7.3 Rule Lifecycle

Statuses:

- `DRAFT`
- `SIMULATING`
- `PENDING_APPROVAL`
- `ACTIVE`
- `CANARY`
- `DISABLED`
- `ARCHIVED`

Activation:

```text
Draft
  -> syntax validation
  -> historical simulation
  -> impact report
  -> human approval
  -> canary
  -> active
```

No rule becomes active without validation, simulation, and approval.

### 7.4 Initial Rules

Seed:

- `ALLOW` low-risk low-amount payment
- `BLOCK` blocked test card
- `REVIEW` suspicious high amount
- `REQUEST_3DS` configured 3DS test card
- `BLOCK` excessive card velocity
- `REVIEW` billing/card country mismatch

## 8. Lists Architecture

Lists are first-class rule inputs.

List types:

- card fingerprint
- BIN
- country
- IP
- customer
- email
- email domain
- generic string

List item fields:

- ID
- list ID
- value
- created by
- source
- created time
- optional expiration
- reason

Default:

- allowlist entries should be time-bounded where possible
- blocklist entries need review and source metadata

## 9. Manual Review Feedback Loop

Review is created by `REVIEW` fraud action.

Review actions:

- approve
- cancel
- refund
- refund-and-report-fraud

Review outputs:

- audit event
- review outcome event
- possible payment state change
- possible refund
- possible fraud label
- possible list update, later

Review outcome labels:

- legitimate
- fraud
- unknown

These labels feed:

- rule evaluation
- ML datasets
- model monitoring
- analyst reporting

## 10. Chargeback Feedback Loop

Chargebacks are delayed labels.

Flow:

```text
chargeback created
  -> link to payment intent / attempt / capture
  -> write dispute ledger entries
  -> if fraud reason, create FraudLabel
  -> emit chargeback.created
  -> update analytics
  -> feed training dataset
```

Important:

- not every chargeback is fraud
- fraud chargebacks are high-value labels
- chargeback label time is later than event time
- model training must respect label delay

## 11. ML Training Architecture

### 11.1 Dataset Unit

One row per PaymentAttempt.

Required columns:

- payment attempt ID
- merchant ID
- customer ID, hashed/encoded as needed
- payment method fingerprint, encoded as needed
- event time
- label time
- label
- feature version
- feature values

Labels:

- fraud
- legitimate
- unknown

Unknown outcomes include:

- blocked attempts
- abandoned 3DS attempts
- payments still within chargeback window
- unresolved reviews

### 11.2 Splitting

Use time-based splits:

```text
train: oldest period
validation: later period
test: newest held-out period
```

Never use random split as the primary fraud evaluation split.

### 11.3 Leakage Prevention

Do not include:

- future chargeback fields
- final review outcome as input feature
- post-decision state changes
- future aggregate features

Do include:

- decision-time payment features
- historical customer features known at decision time
- historical velocity features known at decision time

### 11.4 Data-First Model Strategy

Do not prescribe advanced model architecture before data exists.

Required sequence:

1. Generate realistic synthetic payment/fraud scenarios.
2. Run exploratory data analysis.
3. Validate labels, class imbalance, missingness, leakage risks, and segment distributions.
4. Train a simple logistic regression baseline.
5. Perform error analysis on false positives, false negatives, and unknown outcomes.
6. Add XGBoost or LightGBM only if baseline/error analysis justifies it.
7. Add calibration only after model scores show useful rank ordering.

Conditional later work:

- Graph-derived features only if EDA shows relationship-based fraud patterns.
- High-cardinality embeddings only if categorical cardinality and volume justify them.
- GNN experiments only if graph data is mature and simpler graph features are insufficient.

Advanced ML is optional and evidence-driven, not a predetermined milestone.

## 12. ML Evaluation

Primary metrics:

- PR-AUC
- recall at fixed false positive rate
- precision at manual review capacity
- dollar-weighted fraud capture
- false positive dollar impact

Secondary metrics:

- ROC-AUC
- calibration
- feature drift
- score distribution drift
- segment metrics

Segments:

- merchant
- country
- BIN
- card funding type
- amount band
- new vs returning customer

Promotion rule:

- global improvement is not enough
- segment regressions must be reviewed
- model version and training data version must be recorded

### 12.1 Offline Evaluation And ReplayEngine

Add ReplayEngine before relying on rule/model changes.

ReplayEngine inputs:

- historical PaymentAttempts
- persisted FeatureSnapshots
- labels from reviews and chargebacks
- candidate ruleset
- candidate model version, later

ReplayEngine rules:

- preserve merchant tenant scope
- do not read current Redis counters
- do not recompute features from current state
- use only persisted decision-time snapshots
- preserve unknown outcomes
- report results by merchant and segment

Replay outputs:

- action distribution
- fraud captured
- false positives
- legitimate dollars blocked
- manual review load
- 3DS request rate
- model/rule lift
- segment regressions
- example matched transactions

No major rule/model threshold change should be activated without replay evidence.

### 12.2 Calibration And Threshold Tuning

Fraud thresholds are multi-objective business controls.

Optimize across:

- fraud capture
- false positive rate
- false positive dollar impact
- manual review capacity
- 3DS friction
- merchant risk tolerance
- customer lifetime value, later

Required reports:

- calibration curve
- precision/recall curve
- threshold sweep
- review capacity simulation
- merchant segment impact
- per-merchant regression report for material merchants or synthetic tenant segments

Threshold policy:

- start with global defaults
- move to merchant risk tier thresholds
- later support merchant-specific thresholds
- replay every threshold change before activation

## 13. Model Serving Architecture

Service:

- `model-serving-service`

Endpoint:

```text
POST /score
```

Request:

```json
{
  "paymentAttemptId": "pa_123",
  "featureVersion": "features_v1",
  "features": {
    "amount": 12999,
    "customer_age_days": 14,
    "ip_velocity_1h": 4
  }
}
```

Response:

```json
{
  "score": 0.82,
  "riskLevel": "HIGH",
  "modelVersion": "xgb_2026_06_15_001",
  "reasonCodes": ["HIGH_IP_VELOCITY", "NEW_CUSTOMER_HIGH_AMOUNT"],
  "topFactors": [
    { "name": "ip_velocity_1h", "impact": 0.18 },
    { "name": "customer_age_days", "impact": 0.14 }
  ]
}
```

Timeout behavior:

- bounded timeout
- fallback to rules-only decisioning
- record `MODEL_TIMEOUT`
- do not fail payment confirmation solely because model service timed out, unless configured fail-closed

## 14. Graph Fraud Architecture

### 14.1 Graph Nodes

- Customer
- Device
- IP
- CardFingerprint
- EmailHash
- PhoneHash
- AddressHash
- Merchant
- PaymentAttempt
- Chargeback
- FraudCase

### 14.2 Graph Relationships

- Customer `USED` Device
- Customer `USED` CardFingerprint
- Customer `USED` IP
- PaymentAttempt `FOR` Merchant
- PaymentAttempt `BY` Customer
- PaymentAttempt `WITH` CardFingerprint
- PaymentAttempt `FROM` IP
- Chargeback `AGAINST` PaymentAttempt
- FraudCase `LABELS` Customer or PaymentAttempt

### 14.3 First Graph Queries

Implement:

- customers sharing a device with fraud-labeled customers
- cards used by multiple customers in a short time window
- IPs with high distinct customer count and high decline rate
- two-hop fraud proximity from customer to known fraud cluster
- merchant-level cluster with high chargeback density

Graph output:

- risk contribution
- neighbor counts
- path explanations
- example linked entities
- query latency

Graph should support investigation first and real-time features later.

## 15. Agentic Fraud Intelligence Architecture

Agents are offline.

Agents:

- `FraudIntelligenceOrchestrator`
- `FraudPatternDetectionAgent`
- `ModelEvaluatorAgent`
- `RuleRecommendationAgent`
- `FraudExplanationAgent`

Allowed actions:

- inspect
- summarize
- explain
- recommend
- simulate through tools
- create recommendation records

Forbidden actions:

- approve live payments
- block live payments
- capture or refund
- activate rules
- promote models
- directly query raw sensitive data

Tool contracts:

- `search_transactions(filters)`
- `get_transaction_detail(paymentAttemptId)`
- `get_rule_performance(ruleId, timeWindow)`
- `simulate_rule(candidateRule, timeWindow)`
- `get_model_metrics(modelVersion, timeWindow)`
- `get_false_positive_examples(filters)`
- `get_false_negative_examples(filters)`
- `get_graph_neighbors(entityId, depth)`
- `create_rule_recommendation(candidateRule, simulationId)`

Tool audit fields:

- agent name
- user or scheduler
- input parameters
- output hash or summary
- duration
- data sensitivity class

## 16. Fraud Observability

Metrics:

- fraud decision latency
- action distribution
- 3DS request rate
- manual review rate
- rule hit rate
- rule precision
- model score distribution
- model timeout rate
- false positive estimate
- chargeback rate
- fraud capture rate

Logs:

- decision ID
- payment attempt ID
- ruleset version
- model version
- reason codes
- latency

Traces:

```text
payment confirmation
  -> feature extraction
  -> velocity lookup
  -> rule evaluation
  -> model score
  -> decision engine
  -> decision persistence
```

## 17. Fraud Acceptance Criteria

Fraud architecture is complete when:

- every PaymentAttempt has FraudDecision after fraud MVP
- rules can `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW`
- feature snapshots are persisted
- velocity counters exclude current payment
- review outcomes create labels
- fraud chargebacks create labels
- ML dataset uses time-based split
- model prediction stores model version
- model timeout falls back safely
- rule simulation reports business impact
- graph can identify synthetic rings
- agents cannot mutate live state directly
