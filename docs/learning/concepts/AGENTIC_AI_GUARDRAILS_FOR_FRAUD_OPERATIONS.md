# Agentic AI Guardrails For Fraud Operations From First Principles

This note explains the concept as we learn it while building THAAP. It is a
learning note, not the implementation source of truth.

## The Core Idea

Agentic AI guardrails are the control system around an AI assistant that can
read data, call tools, make recommendations, or trigger workflows.

In payments and fraud, the assistant is not the authority. The system is.

The safe shape is:

```text
analyst request
  -> identity and role check
  -> intent classification
  -> tenant-scoped context validation
  -> tool and data authorization
  -> guarded tool execution
  -> verifier and anomaly checks
  -> human approval for high-risk actions
  -> audit log and feedback loop
```

The assistant can help investigate, explain, draft, summarize, and recommend.
It must not silently approve money movement, activate rules, promote models, or
change production risk policy.

## The Problem It Solves

An unguarded AI assistant can become a confused superuser.

Without guardrails, the assistant might:

- read another merchant's data
- infer or expose sensitive customer, device, card, or fraud signals
- call tools outside the user's role
- hallucinate a rule or investigation conclusion
- activate a harmful rule that blocks legitimate payments
- leak production context into prompts or logs
- mutate payment, review, rule, or model state without approval
- make real-time authorization slower or non-deterministic

Guardrails turn the assistant from "a chatbot with tools" into an auditable
operations surface.

## Why It Matters In Payments And Fraud

Payment fraud work touches high-risk boundaries:

- payment authorization
- capture, refund, chargeback, and dispute workflows
- merchant risk policy
- rule activation
- model promotion
- manual review outcomes
- tenant isolation
- sensitive identity, device, IP, address, and payment metadata

Fraud teams need speed, but payment systems need determinism. The right boundary
is not "no AI." The right boundary is:

```text
LLMs stay out of the real-time payment authorization path.
GenAI is offline, advisory, audited, and human-authorized.
```

The real-time path remains latency-bounded and reproducible. The offline
assistant improves analyst leverage.

## How THAAP Uses It

Current THAAP source-of-truth architecture already says:

- real-time fraud decisions are deterministic and auditable
- decision actions are `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW`
- every decision stores features, reason codes, rule execution, and later model
  metadata
- LLMs never run in the real-time authorization path
- `fraud-intelligence-agent-service` is a later-phase offline service

Planned THAAP agent capabilities should start with read-only and simulation
workflows:

- summarize a review case
- explain a fraud decision from reason codes and feature snapshots
- find related transactions
- draft a dispute evidence packet
- generate an incident summary for card testing or enumeration
- propose candidate fraud rules
- simulate candidate rules against historical data
- compare model or rule performance by merchant segment
- recommend model feature or training-data improvements

High-risk actions require human approval and audit:

- activate or disable a fraud rule
- change merchant risk thresholds
- resolve review cases in bulk
- issue refunds or handle chargebacks
- change card-testing mitigations
- promote or roll back a model
- expose cross-merchant aggregate intelligence

## Design Tradeoffs

Chosen mental model:

- use an offline assistant for investigation, simulation, explanation, and
  recommendations
- keep real-time authorization deterministic
- require tenant-scoped tool access
- use tool allowlists rather than open-ended execution
- require approval for high-risk state changes
- persist agent actions and recommendations as auditable records

Alternative: put an LLM directly into the decision engine.

That is rejected for THAAP because it threatens:

- latency budgets
- reproducibility
- replay safety
- deterministic idempotency behavior
- explainability
- tenant isolation
- model/rule governance
- payment-system trust

Alternative: forbid GenAI entirely.

That is also too blunt. Fraud operations involve messy evidence, narratives,
case context, pattern discovery, and analyst workload. Offline GenAI can help
there if the system controls what it can read and do.

## Failure Modes

Important failure modes:

- Cross-tenant data leak: assistant retrieves data for the wrong merchant.
- Privilege escalation: analyst asks the assistant to perform an admin-only
  action.
- Tool overreach: assistant calls a broad SQL/search tool instead of a
  scoped investigation API.
- Prompt injection: transaction notes, webhook payloads, or merchant-provided
  metadata try to override system rules.
- False confidence: assistant presents a weak correlation as confirmed fraud.
- Unsafe automation: assistant activates a rule that increases false positives
  or revenue loss.
- Audit gap: the system stores the final action but not the prompt, tool calls,
  evidence, approval, and reason.
- Data minimization failure: assistant exposes raw or unnecessary sensitive
  identity/device/payment context.
- Feedback poisoning: incorrect analyst or agent labels are blindly fed into
  training data.

## Tests And Proof

When THAAP implements this, proof should include:

- tenant isolation tests for every agent tool
- role/permission tests for each tool and action type
- approval-gate tests for high-risk actions
- audit-log tests that record request, intent, tool calls, evidence,
  recommendation, approval, and outcome
- prompt-injection tests using merchant-controlled text fields
- sensitive-data redaction tests for prompts, logs, and responses
- rule-simulation tests before activation
- model-evaluation tests before promotion
- replay tests showing agent recommendations do not alter historical payment
  decisions

For now, the proof is architectural:

- THAAP's master plan and fraud architecture keep GenAI out of the real-time
  authorization path.
- The source-materials index classifies agentic AI diagrams as later-phase
  references, not V1 requirements.

## What Remains To Build

Future work:

- define `fraud-intelligence-agent-service` tool contracts
- create an agent action/audit data model
- define analyst/admin roles and permissions
- define allowed tools by persona
- add prompt and response redaction rules
- add approval workflow for high-risk recommendations
- add rule-simulation and model-evaluation tools
- add incident-report and case-summary tools
- decide whether to start with one guarded assistant or an orchestrator with
  specialized domain agents
- write an ADR when agent tool execution becomes implementation scope
