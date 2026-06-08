# Source Materials

These materials are background references for product, architecture, and
learning. They are useful for reasoning, but they are not authoritative
implementation requirements.

When a source-material idea becomes a THAAP decision, record it in
`docs/IMPLEMENTATION_MASTER_PLAN.md`, an ADR, or the relevant architecture doc.

## How To Use This Folder

Use the references in this order:

1. Start with **Tier 1** when shaping THAAP's core product bar.
2. Use **Tier 2** when designing ML scoring, model governance, or fraud
   operations.
3. Use **Tier 3** when designing the later offline GenAI investigation service.
4. Use **Tier 4** only for additional intuition or phrasing; do not treat it as
   primary evidence.

Core THAAP boundary:

```text
LLMs stay out of the real-time payment authorization path.
GenAI is offline, advisory, audited, and human-authorized.
```

## Tier 1: Direct Product Benchmarks

These are the most useful references for THAAP's challenger-product bar.

- [Stripe Radar](https://stripe.com/in/radar)
  - Why it matters: best public benchmark for payment-native fraud prevention
    connected to real payment flows, network signals, custom rules, review
    workflows, dispute prevention, and fraud-team operations.
  - THAAP mapping: fraud decisions, review queues, custom rules,
    explainability, dispute/friendly-fraud surfaces, abuse prevention, and
    privacy-safe network intelligence.
  - Use when designing: product scope, analyst dashboard surfaces,
    fraud-action vocabulary, rule controls, dispute workflows, and
    challenger-grade product requirements.

- [How we built it: Stripe Radar](https://stripe.dev/blog/how-we-built-it-stripe-radar)
  - Why it matters: official Stripe engineering reference for fraud ML
    architecture, production scoring, feature systems, explainability, and
    iteration.
  - THAAP mapping: feature snapshots, low-latency scoring, model lifecycle,
    per-merchant impact checks, model rollout, and operational governance.
  - Use when designing: model-serving architecture, feature pipeline,
    backtesting, release gates, and model explanation surfaces.

## Tier 2: ML, Rules, Case Management, And Scale

These are useful for shaping model-serving, rule governance, analyst operations,
and scale/resiliency expectations.

- `mastercard_advanced_ai_technology_datasheet.pdf`
  - Local copy of the Mastercard Brighterion advanced AI datasheet.
  - Why it matters: official Mastercard/Brighterion reference for explainable
    AI scoring, model deployment, rules management, case management, business
    insights, scale, and resiliency expectations.
  - THAAP mapping: `model-serving-service`, rules testing/deployment, case
    routing, auditability, and business-insight dashboards.
  - Use when designing: model-serving roadmap, rule management, case management,
    analyst operations, and executive/business metrics.

- [Mastercard advanced AI technology datasheet](https://www.mastercard.com/content/dam/mccom/shared/business/b2b/reports/advanced-ai-technology-datasheet.pdf)
  - Canonical external URL for the local PDF above.

## Tier 3: Future Agentic Fraud Operations

These are useful for the later offline `fraud-intelligence-agent-service` and
agent-assisted analyst workflows. They should not change V1 real-time
authorization design.

- `aws-agentic-genai-fraud-detection-reference-architecture.jpg`
  - Why it matters: later-phase orchestrator/domain-agent architecture for fraud
    investigation, model evaluation, and rule simulation.
  - THAAP mapping: orchestrator agent, fraud detection agent, model evaluator
    agent, fraud rules agent, analytics store, graph store, model serving, and
    durable rules governance.
  - Use when designing: service decomposition for offline agentic workflows,
    tool boundaries, scheduled investigations, and rule-simulation workflows.

- `safe-agentic-ai-five-boundary-control-model.jpg`
  - Why it matters: clear guardrail model for safe agentic AI: identity, intent,
    semantic/context validation, authorization, and execution monitoring.
  - THAAP mapping: tenant-scoped agent tool access, least privilege, approval
    gates, execution verification, and audit logs.
  - Use when designing: agent permissions, tool allowlists, approval workflows,
    and audit schema.

- `classifier-verifier-hitl-guardian-pipeline.jpg`
  - Why it matters: useful separation between classifier, verifier,
    human-in-the-loop approval, audit logs, and feedback loops.
  - THAAP mapping: offline agent verification, anomaly checks, rule
    recommendations, model evaluation, and analyst review workflows.
  - Use when designing: agent review gates, recommendation confidence, manual
    approval, and learning loops.

- `agentic-ai-trust-boundary-sentinel-aria.jpg`
  - Why it matters: product framing for a guarded assistant UI, enforcement
    layer, and operations automation boundary.
  - THAAP mapping: future `analyst-dashboard-ui` plus
    `fraud-intelligence-agent-service`.
  - Use when designing: assistant UI boundaries, incident operations support,
    and safe remediation workflows.

## Tier 4: Secondary Explainers

These are useful for intuition, but they are not primary sources. Prefer Tier 1
or Tier 2 when making product or architecture decisions.

- [How Stripe Detects Fraudulent Transactions Within 100 ms](https://blog.bytebytego.com/p/how-stripe-detects-fraudulent-transactions)
  - Best learning explainer in this folder for Radar-style fraud architecture.
  - Why it matters: readable summary of fast fraud scoring, feature
    computation, model iteration, explainability, and per-merchant rollout
    impact.
  - THAAP mapping: helps explain why latency, feature freshness,
    explainability, and segment impact matter.
  - Staff-engineering takeaway: fraud detection is a low-latency distributed
    systems problem, a product trust problem, and an ML governance problem at
    the same time.
  - Caveat: secondary source summarizing public Stripe material.

- [What Is Brighterion Transaction Risk Management By Mastercard?](https://corepay.net/articles/what-is-brighterion-transaction-risk-management-by-mastercard/)
  - Why it matters: concise merchant/acquirer-facing explanation of
    pre-authorization scoring, rules, case management, merchant monitoring, and
    threshold calibration.
  - THAAP mapping: useful framing for merchant risk, rules, case management,
    threshold tuning, and high-risk merchant operations.
  - Caveat: third-party payment-processor article, not a Mastercard product
    document.

## Scratchpad

- `useful_links.txt`
  - Original scratchpad of external links used during planning.
  - Curated useful entries are promoted into the tiers above.
  - Duplicate links are intentionally de-duped here.
