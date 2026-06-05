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
- capture safe identity, device, session, and checkout risk context
- support fraud taxonomy beyond binary labels
- detect card-testing and enumeration patterns as operational incidents
- support merchant risk policy and merchant risk monitoring
- support dispute evidence and friendly-fraud workflows
- support rule simulation before activation
- support graph investigation
- support privacy-safe cross-merchant aggregate features
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
- risk context
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
- `RiskContext`
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

Customer and identity features:

- account age
- prior successful payments
- prior declines
- prior reviews
- prior refunds
- prior chargebacks
- email domain risk bucket, simulated
- phone country mismatch
- address reuse count
- login age

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

Risk context features:

- IP country
- device age
- session age
- checkout channel
- user agent family
- shipping/billing/card country mismatch
- CAPTCHA outcome
- bot score
- 3DS liability-shift expectation

Merchant risk features:

- merchant risk tier
- merchant category
- merchant authorization rate
- merchant decline rate
- merchant chargeback rate
- merchant fraud/dispute ratio
- merchant enumeration ratio
- merchant review backlog

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
- `BLOCK` card-testing pattern with many low-amount attempts
- `REVIEW` new customer, high amount, mismatched shipping/card geography
- `REQUEST_3DS` elevated risk above merchant policy threshold

## 8. Card-Testing And Enumeration Incidents

Card-testing is a distinct operational threat, not merely a high velocity
transaction. The system should group related attempts into
`CardTestingIncident` records.

Signals:

- spikes in failed authorizations
- spikes in blocked low-amount payments
- many distinct card fingerprints from one IP/device/session group
- many card setup attempts without successful purchases
- abnormal issuer decline rate
- repeated CVC/AVS failures
- enumeration ratio using approved and declined attempts

Incident outputs:

- status
- severity
- time window
- attempt count
- distinct card/BIN/IP/device counts
- likely attack surface
- recommended mitigation
- analyst notes

Allowed mitigations:

- tighten rate limits
- require CAPTCHA or bot-friction mode
- temporarily restrict payment-method creation
- recommend API-key rotation
- notify merchant/operator

Mitigations must create audit records or explicit incident updates. They must
not silently disable legitimate merchant traffic.

## 9. Lists Architecture

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

## 10. Manual Review Feedback Loop

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

Fraud labels can additionally include a fraud type:

- stolen card
- card testing
- account takeover
- synthetic identity
- friendly fraud
- refund abuse
- promo abuse
- reseller abuse
- triangulation
- merchant fraud
- scam or social engineering
- unknown fraud

These labels feed:

- rule evaluation
- ML datasets
- model monitoring
- analyst reporting

## 11. Chargeback And Dispute Evidence Feedback Loop

Chargebacks are delayed labels.

Flow:

```text
chargeback created
  -> link to payment intent / attempt / capture
  -> write dispute ledger entries
  -> if fraud reason, create FraudLabel
  -> create or link DisputeEvidencePacket
  -> emit chargeback.created
  -> update analytics
  -> feed training dataset
```

Important:

- not every chargeback is fraud
- fraud chargebacks are high-value labels
- chargeback label time is later than event time
- model training must respect label delay
- friendly fraud requires separate type/source/confidence handling
- evidence packets are product artifacts, not ML features unless they were
  known at decision time

Dispute evidence MVP:

- reason code
- evidence due date
- 3DS/liability-shift state
- AVS/CVC outcomes
- delivery or fulfillment signal, simulated
- customer communication notes, simulated
- prior undisputed transactions
- device/IP continuity
- refund history
- outcome

## 12. ML Training Architecture

### 12.1 Dataset Unit

One row per PaymentAttempt.

Required columns:

- payment attempt ID
- merchant ID
- customer ID, hashed/encoded as needed
- payment method fingerprint, encoded as needed
- event time
- label time
- label
- fraud type, when known
- label source
- feature version
- merchant risk tier
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

### 12.2 Splitting

Use time-based splits:

```text
train: oldest period
validation: later period
test: newest held-out period
```

Never use random split as the primary fraud evaluation split.

### 12.3 Leakage Prevention

Do not include:

- future chargeback fields
- final review outcome as input feature
- post-decision state changes
- dispute evidence fields created after the decision
- future aggregate features

Do include:

- decision-time payment features
- historical customer features known at decision time
- historical velocity features known at decision time

### 12.4 Data-First Model Strategy

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

### 12.5 Model Governance

Every model promoted beyond experimentation must have:

- dataset version
- feature version
- training window
- label definition
- unknown-outcome policy
- model card
- dataset card
- segment regression report
- replay report
- shadow-mode results
- approval record
- rollback target
- owner

Promotion flow:

```text
candidate model
  -> offline evaluation
  -> replay
  -> shadow mode
  -> limited canary
  -> approval
  -> active
```

## 13. ML Evaluation

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
- fraud-type coverage
- merchant-risk-tier performance
- 3DS friction rate
- review queue pressure
- dispute ratio and false-positive cost

Segments:

- merchant
- country
- BIN
- card funding type
- amount band
- new vs returning customer
- merchant risk tier
- fraud type, when labels are available

Promotion rule:

- global improvement is not enough
- segment regressions must be reviewed
- model version and training data version must be recorded

### 13.1 Offline Evaluation And ReplayEngine

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
- fraud-type impact
- merchant risk policy impact
- review-capacity breach count
- dispute and chargeback impact estimate

No major rule/model threshold change should be activated without replay evidence.

### 13.2 Calibration And Threshold Tuning

Fraud thresholds are multi-objective business controls.

Optimize across:

- fraud capture
- false positive rate
- false positive dollar impact
- manual review capacity
- 3DS friction
- merchant risk tolerance
- customer lifetime value, later
- review capacity
- 3DS friction budget
- dispute and chargeback cost

Required reports:

- calibration curve
- precision/recall curve
- threshold sweep
- review capacity simulation
- merchant segment impact
- per-merchant regression report for material merchants or synthetic tenant segments
- risk policy simulation report

Threshold policy:

- start with global defaults
- move to merchant risk tier thresholds
- later support merchant-specific thresholds
- replay every threshold change before activation

## 14. Model Serving Architecture

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

## 15. Graph Fraud Architecture

### 15.1 Graph Nodes

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
- MerchantRiskProfile
- Session
- AddressHash
- EmailDomain
- CardTestingIncident

### 15.2 Graph Relationships

- Customer `USED` Device
- Customer `USED` CardFingerprint
- Customer `USED` IP
- PaymentAttempt `FOR` Merchant
- PaymentAttempt `BY` Customer
- PaymentAttempt `WITH` CardFingerprint
- PaymentAttempt `FROM` IP
- Chargeback `AGAINST` PaymentAttempt
- FraudCase `LABELS` Customer or PaymentAttempt
- Session `USED` Device
- Customer `USED` AddressHash
- Customer `HAS` EmailDomain
- CardTestingIncident `INVOLVES` PaymentAttempt
- Merchant `HAS_RISK_PROFILE` MerchantRiskProfile

### 15.3 First Graph Queries

Implement:

- customers sharing a device with fraud-labeled customers
- cards used by multiple customers in a short time window
- IPs with high distinct customer count and high decline rate
- two-hop fraud proximity from customer to known fraud cluster
- merchant-level cluster with high chargeback density
- card-testing incident neighborhoods
- friendly-fraud repeat device/address patterns

Graph output:

- risk contribution
- neighbor counts
- path explanations
- example linked entities
- query latency

Graph should support investigation first and real-time features later.

Privacy-safe network rule:

- Cross-merchant graph and aggregate features must be transformed,
  anonymized, or thresholded before use.
- A merchant-facing analyst must never see another merchant's raw customer,
  payment, device, IP, or graph object.
- Any global feature must carry an explicit global/anonymized scope marker.

## 16. Agentic Fraud Intelligence Architecture

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
- `get_card_testing_incident(incidentId)`
- `get_dispute_evidence_packet(chargebackId)`
- `simulate_risk_policy(candidatePolicy, timeWindow)`
- `create_rule_recommendation(candidateRule, simulationId)`

Tool audit fields:

- agent name
- user or scheduler
- input parameters
- output hash or summary
- duration
- data sensitivity class

## 17. Fraud Observability

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
- card-testing incident count
- enumeration ratio
- fraud/dispute ratio
- merchant risk tier distribution
- review SLA breach count
- dispute evidence win/loss rate

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
  -> risk context capture
  -> feature extraction
  -> velocity lookup
  -> rule evaluation
  -> model score
  -> decision engine
  -> decision persistence
```

## 18. Fraud Acceptance Criteria

Fraud architecture is complete when:

- every PaymentAttempt has FraudDecision after fraud MVP
- rules can `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW`
- feature snapshots are persisted
- risk context is persisted safely
- velocity counters exclude current payment
- review outcomes create labels
- fraud chargebacks create labels
- fraud labels support fraud type and source
- card-testing incidents group abusive patterns
- dispute evidence packets link chargebacks to evidence and outcomes
- ML dataset uses time-based split
- promoted models have dataset/model cards and rollback targets
- model prediction stores model version
- model timeout falls back safely
- rule simulation reports business impact
- merchant risk policy changes are simulated before activation once replay exists
- graph can identify synthetic rings
- cross-merchant intelligence is privacy-safe
- agents cannot mutate live state directly
