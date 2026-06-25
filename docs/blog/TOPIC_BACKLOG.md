# Public Concept Topic Backlog

Pick one public technical concept per week. The post should teach the concept in
detail without mentioning private product names, repository files, or weekly
implementation status.

## Ready Topics

### Why Fraud Systems Need Explainable Intelligence

Thesis: a fraud system is only trustworthy when decisions can be reconstructed
from the evidence available at decision time.

Cover:

- why "the model said so" is not enough
- decision evidence and feature snapshots
- analyst review and compliance review
- audit replay
- privacy-safe explanations
- real-time decision boundaries

### Building Idempotent Payment APIs

Thesis: a payment API is not trustworthy until retries, conflicts, and
concurrency are explicit parts of the contract.

Cover:

- why clients retry
- duplicate charge prevention
- idempotency keys
- request fingerprints
- conflict responses
- concurrency and one-winner behavior
- operational metrics

### Designing Audit Replay For Financial Decisions

Thesis: financial systems need audit records that can reconstruct decisions
without exposing secrets or inventing missing context.

Cover:

- logs vs audit trails
- replayable decision records
- immutable event history
- safe redaction
- reviewer experience
- operational debugging

### Why LLMs Should Stay Out Of Real-Time Authorization

Thesis: LLMs can help investigation and explanation, but real-time payment
authorization needs deterministic, bounded behavior.

Cover:

- latency budgets
- nondeterminism
- authorization safety
- agent tool boundaries
- investigator copilots
- human-in-the-loop workflows

## Near-Term Topics

### How Real-Time Risk Scoring Works

Cover:

- request-time scoring
- feature freshness
- latency budgets
- deterministic fallbacks
- rules, models, and thresholds
- why explanations still matter

### Rule Engines Vs ML Models In Fraud Detection

Cover:

- rules for known patterns
- models for statistical patterns
- hybrid decisioning
- governance and drift
- analyst feedback
- evaluation metrics

### How AI Agents Can Help Fraud Investigators

Cover:

- evidence summarization
- case timelines
- safe tool use
- action recommendations vs autonomous decisions
- human approval
- auditability

### Event-Driven Architecture For Risk Systems

Cover:

- synchronous authorization vs asynchronous investigation
- event streams
- backfills
- replay
- delayed enrichment
- operational resilience

### What I Learned Building An AI-Native Risk Platform

Use as a milestone essay after several concept posts exist.

Cover:

- what "AI-native" should mean in risk systems
- where AI helps
- where deterministic systems still matter
- explainability and governance
- product lessons

## Topic Selection Rule

Choose the concept that you can explain most truthfully and deeply this week.

The public article should feel like a serious technical essay, not a project
status report.

For every topic, choose the concrete artifact before drafting:

- decision record
- feature snapshot
- event timeline
- retry timeline
- rule/model comparison table
- audit replay flow
- analyst case timeline
- architecture diagram with boundaries
