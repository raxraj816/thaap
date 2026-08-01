# THAAP Vision Coverage Matrix

- Status: ACTIVE
- Last reviewed: 2026-07-27
- Product destination: FOUNDER CONFIRMED 2026-07-27
- Phase 5 implementation-decision freeze: FOUNDER CONFIRMED 2026-07-27
- Canonical vision: `docs/VISION.md`
- Implementation source: `docs/IMPLEMENTATION_MASTER_PLAN.md`
- Validation: `scripts/vision-coverage-check.py`

This matrix prevents phase sequencing from quietly shrinking THAAP's mission.
It maps every challenger promise to current evidence, the exact remaining gap,
an owner phase and slice, a trigger, completion evidence, and a product demo.

Allowed statuses:

- `PROVEN_CURRENT_SCOPE`: the currently promised product boundary has closure
  evidence; later expansion still needs an explicit owner when named
- `PARTIAL_NEXT_OWNER`: useful product truth exists, but a named capability gap
  remains
- `PLANNED_OWNER_ASSIGNED`: the prerequisite path and owner are explicit, but
  the product capability is not yet implemented
- `INTENTIONALLY_EXCLUDED`: the capability conflicts with THAAP's declared
  product strategy or current truthful boundary; the reason and
  reconsideration trigger remain explicit

No status means “finished forever.” A phase may close only if its affected
entries are reviewed, its delivered promise has executable proof, and every
remaining gap still has a concrete owner. Architecture or infrastructure alone
cannot satisfy a merchant, analyst, ML, or investigation promise.

## VP-001: Payment-Native Fraud

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: Fraud decisions remain embedded in a truthful authorization, capture, cancel, refund, dispute, ledger, idempotency, and webhook lifecycle.
- Current evidence: Chapters 17–20 and 23–35 prove processor, money, ledger, webhook, deterministic decision, review, label, authentication, chargeback, and post-authorization boundaries through public APIs and PostgreSQL.
- Remaining gap: Merchant reserve/hold simulation and a real external PSP adapter remain. Partial capture, payouts, and THAAP-owned acquirer movement stay outside the core simulator and are not required for the initial merchant-facing risk platform.
- Owner phase: Phase 8 and Phase 10
- Owner slice: Merchant Exposure And Reserve Simulation; External PSP Adapter And Provenance Contract
- Primary DRI: Payments Core
- Trigger: Start exposure simulation after Phase 7 produces governed outcome data; start the real adapter only after Phase 9 production-trust closure and an approved design-partner boundary.
- Done evidence: Money-affecting simulations preserve balanced ledger truth, and an external-adapter contract proves idempotency, ambiguity, compatibility, rollback, and no raw-card expansion without claiming real settlement.
- Merchant / analyst demo: Run one risky payment from confirm through processor, capture, refund or dispute, ledger reconciliation, decision explanation, and downstream outcome without source reading.
- Last reviewed: 2026-07-27

## VP-002: Bounded Real-Time Decision Quality

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Authorization decisions are deterministic, latency-bounded, observable, resilient to dependency failure, and safe under retry.
- Current evidence: Chapters 23–31 prove deterministic rule precedence, decision-time features, Redis ambiguity policy, merchant controls, processor isolation, idempotency, and no processor call for non-ALLOW actions.
- Remaining gap: No governed model score participates in the synchronous path, and model timeout, shadow comparison, champion/challenger, and calibrated fallback have not been proven.
- Owner phase: Phase 7
- Owner slice: Shadow Model Serving And Timeout-Safe Decision Integration
- Primary DRI: ML Architecture
- Trigger: Begin after Phase 5 closes the point-in-time dataset and event/projection lineage boundary.
- Done evidence: Shadow and active scoring record model/version/reasons/latency, rules-only fallback wins on timeout or invalid response, segment and calibration gates pass, and rollback restores the prior champion without rewriting decisions.
- Merchant / analyst demo: Confirm identical attempts under healthy, slow, unavailable, and rolled-back model states and explain the exact decision source and fallback.
- Last reviewed: 2026-07-27

## VP-003: Privacy-Safe Network Intelligence

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: THAAP progresses beyond isolated merchant rules through governed, privacy-safe cross-merchant aggregate intelligence without exposing raw tenant objects or identifiers.
- Current evidence: Server-assigned traffic origin, merchant-scoped HMAC identities, tenant isolation, synthetic scenarios, point-in-time features, and the Phase 5 event plan provide prerequisites; no cross-merchant product feature exists.
- Remaining gap: Consent/scope, anonymity threshold, cohort eligibility, contribution bounds, retention, explainability, tenant opt-out, and reconstruction resistance are not frozen.
- Owner phase: Phase 8
- Owner slice: Privacy-Safe Network Aggregate Intelligence
- Primary DRI: ML Architecture and Security/Platform
- Trigger: Start after Phase 7 proves merchant-scoped datasets and segment evaluation, with enough synthetic multi-merchant coverage to test privacy partitions.
- Done evidence: Approved privacy ADR and matrix prove aggregate-only output, minimum cohort/contribution rules, tenant opt-out, no reconstruction, point-in-time availability, segment impact, and safe degradation.
- Merchant / analyst demo: Show a synthetic cross-merchant attack where an aggregate signal improves an explainable decision while neither merchant can inspect the other's raw data.
- Last reviewed: 2026-07-27

## VP-004: Explainable Controls And Decisions

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Every decision exposes score/level, matched and non-matched controls, reason codes, feature snapshot, versions, related evidence, and a safe timeline.
- Current evidence: Chapters 22–31 and 38–39 preserve immutable feature/rule/control results, precedence, simulations, exceptions, activation, rollback, and decision evidence.
- Remaining gap: The cockpit lacks one unified payment-to-risk-to-analyst-to-outcome explanation surface and bounded related-transaction navigation.
- Owner phase: Phase 6
- Owner slice: Unified Decision Explanation And Lifecycle Timeline
- Primary DRI: Fraud/Risk Product and Product Design
- Trigger: Start after Phase 5 exposes a freshness-aware payment-risk projection.
- Done evidence: One tenant-scoped read model and UI reconcile payment, attempt, processor, rule/model, review, label, dispute, webhook, and ledger evidence with source links, freshness, empty/stale states, and privacy proof.
- Merchant / analyst demo: Open a risky attempt and explain every decision input, winning action, later analyst action, label, and money effect from one screen.
- Last reviewed: 2026-07-27

## VP-005: Analyst Operations Depth

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Analysts can search, prioritize, assign, investigate, annotate safely, resolve, govern controls, and measure outcomes without reading database rows.
- Current evidence: Chapters 26–27 and 36–40 prove actor roles, assignment/resolution, saved searches, protected notes, SLA-aware filters, monitoring, simulation, governed controls, and dispute operations.
- Remaining gap: Unified investigation timelines, bounded bulk operations, related-transaction pivots, queue productivity, outcome/business insights, and case-level performance views are incomplete.
- Owner phase: Phase 6
- Owner slice: Analyst Investigation Workspace And Business Insights
- Primary DRI: Risk Operations and Product Design
- Trigger: Start after Phase 5 analytics freshness and projection correctness are merge-ready.
- Done evidence: Role-safe search/detail/timeline, saved views, bounded bulk actions, related pivots, workload/SLA/outcome metrics, accessibility, partial failure, audit, and two-tenant proof pass.
- Merchant / analyst demo: Triage a queue, investigate linked evidence, take one bounded action, and show its audit, SLA, and outcome impact from the cockpit.
- Last reviewed: 2026-07-27

## VP-006: Post-Authorization Protection

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Fraud protection continues after authorization through warnings, refund recommendations, dispute prevention, friendly-fraud analysis, and outcome feedback.
- Current evidence: Chapters 32–33 and 40 prove chargebacks, exact refund offsets, post-authorization signals, recommendations, manager money actions, evidence versions, deadlines, and synthetic outcomes.
- Remaining gap: Strategy-level outcome optimization, early-warning coverage, recommendation quality measurement, partial-money policy, and friendly-fraud/representment depth are incomplete.
- Owner phase: Phase 8
- Owner slice: Post-Authorization Strategy And Outcome Optimization
- Primary DRI: Risk Operations and Payments Core
- Trigger: Start after Phase 7 supplies calibrated model/outcome evaluation and Phase 8 dispute workflow prerequisites are frozen.
- Done evidence: Point-in-time warnings and recommendations show eligibility, expected loss/benefit, reason, approval, money constraints, measured outcome, rollback, and no rewrite of original decision or ledger truth.
- Merchant / analyst demo: Take a captured risky payment from warning through recommendation, approved action, dispute/friendly-fraud outcome, and measured feedback.
- Last reviewed: 2026-07-27

## VP-007: Rule And Policy Governance

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: Control changes are validated, historically simulated, dual-approved, auditable, rollback-capable, and cannot erase protected safety evidence.
- Current evidence: Chapters 31 and 38–39 prove typed policies, point-in-time mixed-control simulation, bounded authored rules, narrow exceptions, dual control, activation, expiry, and rollback.
- Remaining gap: Rule and model governance need one comparable release view with segment impact and champion/challenger evidence after ML exists.
- Owner phase: Phase 7
- Owner slice: Unified Rule And Model Release Governance
- Primary DRI: Risk Platform and ML Architecture
- Trigger: Start when the first shadow model and model card are available.
- Done evidence: One governance surface compares rule/policy/model candidates, segment and label coverage, latency/fallback risk, approver separation, canary/shadow evidence, activation, and rollback target.
- Merchant / analyst demo: Compare a candidate rule and model against the same cohort, approve with a second manager, canary or shadow it, and roll back without changing historical decisions.
- Last reviewed: 2026-07-27

## VP-008: ML Feedback And Model Lifecycle

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: Models use point-in-time datasets and delayed labels, publish calibrated and segment-aware evidence, run safely, drift visibly, and retain registry/card/rollback lineage.
- Current evidence: Feature snapshots, label availability, traffic origin, historical simulation, and the Phase 5 offline dataset plan establish prerequisites; no trained or served model is product truth.
- Remaining gap: Dataset builder, data quality, time split, baseline training, calibration, registry, cards, serving, shadow/canary, champion/challenger, drift, and rollback remain unimplemented.
- Owner phase: Phase 7
- Owner slice: Governed ML Baseline And Lifecycle
- Primary DRI: ML Architecture
- Trigger: Start after Phase 5 Chapter 51 proves point-in-time dataset manifests and simulation compatibility.
- Done evidence: Dataset/model cards, leakage tests, baseline comparison, calibration, threshold and segment reports, MLflow registry, shadow serving, timeout fallback, champion/challenger approval, drift alerts, and rollback pass.
- Merchant / analyst demo: Train from a sealed dataset, inspect the model card, shadow-score live synthetic traffic, promote through approval, observe drift, and roll back safely.
- Last reviewed: 2026-07-27

## VP-009: Graph And Pattern Intelligence

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: Graph supports explainable investigation, fraud-ring discovery, and later governed features rather than an opaque score.
- Current evidence: HMAC-safe identities, typed risk context, incidents, immutable events, and synthetic scenarios provide future graph inputs; no graph store or product surface exists.
- Remaining gap: Entity identity, tenant/global scope, edge provenance, temporal validity, deletion, Neo4j ingestion, investigation UX, contribution explanation, and feature approval are not implemented.
- Owner phase: Phase 8
- Owner slice: Explainable Graph Investigation And Features
- Primary DRI: ML Architecture and Risk Platform
- Trigger: Start after privacy-safe network scope is approved and Phase 5 event replay is stable.
- Done evidence: Idempotent temporal graph ingestion, tenant/privacy enforcement, synthetic ring detection, path/neighbor explanations, latency, rebuild, deletion, and governed feature contribution proof pass.
- Merchant / analyst demo: Investigate a synthetic fraud ring, traverse explainable links, and show how a bounded graph signal would affect a decision without exposing another merchant's objects.
- Last reviewed: 2026-07-27

## VP-010: Adaptive Intelligence With Human Authority

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: Agents investigate, explain, simulate, and recommend while humans retain authority over payments, money, rules, models, and production changes.
- Current evidence: Actor roles, dual control, immutable evidence, simulation, audits, and safe read APIs provide prerequisites; no production GenAI agent exists.
- Remaining gap: Tool contracts, least privilege, prompt/data boundaries, evaluation, hallucination containment, provenance, cost/latency limits, approval, and incident response are not implemented.
- Owner phase: Phase 9
- Owner slice: Audited Fraud Intelligence Agents
- Primary DRI: AI/ML Platform and Risk Operations
- Trigger: Start after Phase 6 investigation APIs, Phase 7 model governance, and Phase 8 graph evidence are stable.
- Done evidence: Offline evaluations, adversarial tool tests, source citations, audit, budget limits, tenant isolation, human approval, and executable proof that agents cannot mutate live payment/rule/model state pass.
- Merchant / analyst demo: Ask an agent to investigate a case, verify cited evidence, simulate a recommendation, and show that execution still requires an authorized human workflow.
- Last reviewed: 2026-07-27

## VP-011: Security, Privacy, Tenant Trust, And Operational Resilience

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Tenant isolation, data minimization, secret hygiene, authorization, rate limits, audit, telemetry, rollback, retention, and incident response are product requirements.
- Current evidence: Chapters 19–21 and 26–43R prove tenant-qualified access, secret rotation/encryption, actor fencing, privacy canaries, rate limits, webhook egress controls, audit, operational metrics, and hostile concurrency/protocol boundaries.
- Remaining gap: Hosted identity/SSO, platform-wide RBAC, OpenTelemetry/SLOs, backup/restore, data deletion, CI/CD, blue-green compatibility, load/chaos, broader incident rehearsal, and real merchant support/privacy operations remain incomplete.
- Owner phase: Phase 9 and Phase 10
- Owner slice: Production Trust, Identity, Observability, And Recovery; Hosted Merchant Onboarding And Risk Control Plane
- Primary DRI: Security/Platform
- Trigger: Start detailed planning after Phases 5–8 establish event, UI, model, and graph operational assets requiring shared controls.
- Done evidence: Hosted identity and least privilege, SLO telemetry, load/chaos, encrypted backup/restore, retention/deletion, release compatibility, blue-green rollback, incident runbooks, merchant support ownership, and pilot incident/rollback proof pass at their real enforcement boundaries.
- Merchant / analyst demo: Operate the platform through a dependency failure, credential disable, version rollout, restore, and rollback while preserving tenant and payment truth.
- Last reviewed: 2026-07-27

## VP-012: Merchant Risk, Underwriting, And Exposure Controls

- Status: PARTIAL_NEXT_OWNER
- Vision promise: THAAP detects risky merchants as well as risky buyers through profiles, underwriting evidence, dispute/fraud thresholds, exposure limits, and reserve/hold simulation.
- Current evidence: Chapters 31 and 36 provide versioned merchant policies, monitoring metrics, point-in-time recommendations, risk tiers, and dual-controlled profile activation.
- Remaining gap: Onboarding underwriting, beneficial-risk evidence boundaries, rolling exposure, reserve/hold simulation, merchant-level limits, periodic review, and explainable release/escalation do not exist.
- Owner phase: Phase 8
- Owner slice: Merchant Underwriting, Exposure, Reserve, And Hold Simulation
- Primary DRI: Merchant Risk and Payments Core
- Trigger: Start after Phase 7 produces calibrated merchant/outcome features and Phase 5 analytics provides durable exposure inputs.
- Done evidence: Versioned underwriting cases, safe evidence, manager approval, exposure calculation, reserve/hold ledger simulation, release/escalation, tenant isolation, rollback, and no false real-settlement claim pass.
- Merchant / analyst demo: Onboard a synthetic risky merchant, show the evidence and exposure, apply an approved simulated reserve/hold, and later release or escalate it with balanced ledger and audit truth.
- Last reviewed: 2026-07-27

## VP-013: Card-Testing And Enumeration Defense

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: Card setup abuse, low-value testing, credential leakage, retry storms, bot friction, and enumeration patterns have controls and incident workflows.
- Current evidence: Chapters 29–30 and 34 prove safe identities, velocity, Redis ambiguity, durable card-testing incidents, fixed-window limits, manager mitigation, exact 429/503 semantics, and public abuse demos.
- Remaining gap: Cross-merchant campaigns, adaptive bot/device patterns, incident outcome measurement, and graph/network ring views remain owned by Phase 8; credential-leak playbooks remain owned by Phase 9.
- Owner phase: Phase 8 and Phase 9
- Owner slice: Network Enumeration And Ring Intelligence; Credential-Leak And Abuse Incident Readiness
- Primary DRI: Risk Platform and Security/Platform
- Trigger: Start network expansion after privacy-safe aggregate approval; start operational rehearsal when hosted credentials and SLO telemetry exist.
- Done evidence: Synthetic distributed campaigns are detected without tenant leakage, mitigations show measured impact, graph/network explanations pass, and credential-leak/runaway-retry drills preserve availability and audit truth.
- Merchant / analyst demo: Run a distributed synthetic card-testing campaign, detect and investigate it, apply a bounded mitigation, and show recovery plus outcome metrics.
- Last reviewed: 2026-07-27

## VP-014: Dispute, Representment, And Friendly-Fraud Operations

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Chargebacks support reason workflows, immutable evidence, deadlines, outcomes, friendly-fraud classification, representment simulation, and performance reporting.
- Current evidence: Chapters 32 and 40 prove chargeback accounting, evidence packets/versions, catalogue-derived deadlines, submission, trusted synthetic outcomes, audit, and money non-interference.
- Remaining gap: Document/file evidence, authenticated network submission, richer reason-specific packets, pre-dispute alerts, friendly-fraud classification, representment strategy, and win-rate/merchant reporting remain incomplete.
- Owner phase: Phase 8
- Owner slice: Friendly-Fraud And Representment Simulator
- Primary DRI: Risk Operations and Payments Core
- Trigger: Start after Phase 7 outcome models and Phase 5 analytics support point-in-time dispute performance cohorts; external files require an approved document-security boundary.
- Done evidence: Reason-specific immutable packets, safe document boundary or explicit text-free mode, simulated representment submission, pre-dispute/friendly-fraud decisions, outcome reporting, deadlines, audit, and ledger non-interference pass.
- Merchant / analyst demo: Investigate a friendly-fraud candidate, assemble and submit a versioned simulator packet, record a trusted outcome, and inspect performance without claiming card-network acceptance.
- Last reviewed: 2026-07-27

## VP-015: Identity, Session, Device, And Bot Intelligence

- Status: PARTIAL_NEXT_OWNER
- Vision promise: Decisions use safe device, session, IP, email, phone, address, account, login, shipping, and bot/friction context with explicit lineage and privacy.
- Current evidence: Chapter 29 captures allowlisted, HMAC-minimized RiskContext and Chapter 30 adds velocity/list evidence without persisting raw sensitive values.
- Remaining gap: Account/login history, device continuity lifecycle, shipping mismatch, behavioral/bot sequences, identity resolution, retention, consent, and cross-session explanations remain incomplete.
- Owner phase: Phase 8
- Owner slice: Advanced Identity, Session, And Bot Intelligence
- Primary DRI: Risk Platform and Security/Privacy
- Trigger: Start after Phase 7 proves feature governance and Phase 8 privacy scope defines permissible identity linkage.
- Done evidence: Versioned privacy review, minimization/rotation/retention, point-in-time feature lineage, bot/account-takeover scenarios, explanation, segment impact, deletion, and safe fallback proof pass.
- Merchant / analyst demo: Run account-takeover and bot scenarios whose safe session/device evidence changes an explainable decision without exposing raw identifiers.
- Last reviewed: 2026-07-27

## VP-016: Developer Product Quality

- Status: PARTIAL_NEXT_OWNER
- Vision promise: A merchant developer receives versioned APIs, OpenAPI, error catalogue, idempotency/webhook guidance, test cards, SDK/reference clients, and a usable sandbox.
- Current evidence: Chapters 20, 41, and 42 provide public APIs, request IDs/errors, test-card catalogue, scenario runner, webhook signing/verification/redelivery examples, and hardened loopback clients.
- Remaining gap: Generated OpenAPI, formal API version lifecycle, complete error catalogue, supported SDK, contract compatibility CI, onboarding quickstart, cohesive developer portal, and one supported PSP integration guide remain incomplete.
- Owner phase: Phase 6, Phase 9, and Phase 10
- Owner slice: Developer Contract, OpenAPI, SDK, And Sandbox; Release Compatibility And External Integration Hardening; External PSP Adapter And Provenance Contract
- Primary DRI: Developer Experience
- Trigger: Start the contract slice after Phase 5 stabilizes event/analytics APIs; start compatibility hardening before hosted or independently versioned clients.
- Done evidence: OpenAPI and examples match executable contracts, error catalogue is complete, one supported SDK/reference client passes generated contract tests, onboarding works without source reading, and compatibility/rollback CI rejects breaking changes.
- Merchant / analyst demo: A new merchant follows only published docs/SDK to onboard, run a payment-risk scenario, verify webhooks, inspect analytics, and recover a safe retry.
- Last reviewed: 2026-07-27

## VP-017: Durable Event, Analytics, And Replay Lineage

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: Committed payment/risk facts become publishable, consumable, freshness-aware, rebuildable analytics and point-in-time datasets without changing source truth.
- Current evidence: Phase 4.5 supplies durable source facts; V65–V85 now implement the Phase 5 event/outbox, exact event-to-decision/snapshot/payment lineage, Kafka, inbox, projection, losslessly paginated analytics, rebuild, and dataset boundaries. Every Chapter 44–52 matrix and dossier passes, real Kafka/PostgreSQL and product/browser evidence pass, and the public demo proves source non-interference.
- Remaining gap: None inside the frozen Phase 5 promise. Later scale, hosted multi-merchant, ML training/serving, and network capabilities retain their assigned owner phases.
- Owner phase: Phase 5
- Owner slice: Durable Events And Rebuildable Analytics
- Primary DRI: Data Platform
- Trigger: Satisfied; Chapters 44–52 are complete under their frozen `PRE_IMPLEMENTATION` contracts.
- Done evidence: Every Chapter 44–52 matrix/dossier passes, in-scope mutations emit atomically, consumers restart safely, analytics expose freshness/coverage, rebuild activation reconciles, and the public source-to-rebuild demo passes.
- Merchant / analyst demo: Create a risky payment, observe its projection, survive broker/consumer restart and duplicate delivery, rebuild a shadow generation, activate it, and prove source payment/fraud/ledger counts unchanged.
- Last reviewed: 2026-08-01

Phase 5 implementation status: COMPLETE

## VP-018: Honest Production-Shaped Simulator Boundary

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: THAAP teaches and demonstrates production payment-risk mechanics without falsely claiming real money movement, PCI certification, card-network acceptance, or network-data parity.
- Current evidence: The product stores no raw PAN/CVV, uses deterministic processor/network simulators, labels synthetic truth explicitly, and keeps dispute/webhook/developer demos inside declared local/test boundaries.
- Remaining gap: Every new model, graph, reserve, agent, document, external adapter, pilot metric, and scale feature must retain explicit synthetic, THAAP-derived, merchant-provided, and PSP-observed provenance and reject unsupported money, network, compliance, or causal claims.
- Owner phase: Every phase
- Owner slice: Integrated Phase Closure And Source-Of-Truth Reconciliation
- Primary DRI: Founding Staff Engineering
- Trigger: Review at every promise freeze, scope change, public demo, and phase closure.
- Done evidence: Current matrices, dossiers, demos, READMEs, and APIs name simulator/source boundaries; unsupported real-money/network/compliance claims are absent; any expansion has an approved security and truth boundary.
- Merchant / analyst demo: Every public workflow visibly distinguishes deterministic simulator facts, merchant facts, analyst actions, THAAP decisions, and authenticated PSP/network facts THAAP actually received.
- Last reviewed: 2026-07-27

## VP-019: Staff-Engineering And Domain-Mastery Apprenticeship

- Status: PROVEN_CURRENT_SCOPE
- Vision promise: Building THAAP teaches payment, fraud, ML, security, data, product, and operations from first principles with executable proof rather than code delivery alone.
- Current evidence: Chapter plans, invariant matrices, closure dossiers, concept notes, daily/deep-dive/weekly learning trails, public demos, and hostile reviews are maintained through Phase 4.5 and Phase 5 planning.
- Remaining gap: Phase 5 event, Phase 6 UI/developer, Phase 7 ML, Phase 8 graph/network, Phase 9 agent/scale, and Phase 10 real-merchant pilot slices must preserve the same explain-back, code-reading, failure-model, and real-boundary proof discipline.
- Owner phase: Every phase
- Owner slice: Daily Learning, Deep Dive, Concept, Weekly Review, And Integrated Closure
- Primary DRI: Founding Staff Engineering and Founder
- Trigger: Execute for every non-trivial plan, implementation slice, escaped finding, and phase closure.
- Done evidence: Learning gates pass, durable notes explain first principles and tradeoffs, tests map to invariants, and the founder can explain the system in a Stripe/Mastercard-style review.
- Merchant / analyst demo: Explain one end-to-end payment-risk workflow, its failure modes, security boundaries, data lineage, tests, and intentional exclusions without relying on chat history.
- Last reviewed: 2026-07-27

## VP-020: External PSP Integration And Source Provenance

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: THAAP integrates with at least one existing PSP through a versioned, authenticated, reconciled adapter while preserving exact provenance for PSP-observed, merchant-provided, THAAP-derived, and synthetic facts.
- Current evidence: V1 has a ProcessorAdapter seam, stable payment/risk identities, signed webhooks, idempotency, event lineage, simulator fixtures, and Phase 9 compatibility ownership; no real PSP traffic or contract is currently integrated.
- Remaining gap: PSP selection, legal/data-purpose boundary, authentication, webhook/API grammar, external identity mapping, late/corrected events, rate limits, replay, reconciliation, sandbox/live separation, and retained upgrade proof are not implemented.
- Owner phase: Phase 10
- Owner slice: External PSP Adapter And Provenance Contract
- Primary DRI: Payments Core and Developer Experience
- Trigger: Start after Phase 9 production-trust closure and approval of one design partner, PSP, permitted data boundary, incident owner, and rollback plan.
- Done evidence: Contract fixtures plus PSP sandbox and controlled live evidence prove authenticated ingestion, idempotency, duplicate/out-of-order/correction handling, reconciliation, rate-limit recovery, version compatibility, replay, no raw PAN/CVV expansion, and explicit fact provenance.
- Merchant / analyst demo: Connect one merchant PSP account, ingest a real eligible payment event, reconcile its external identity and lifecycle, and trace every displayed fact to PSP, merchant, analyst, THAAP, or simulator origin.
- Last reviewed: 2026-07-27

## VP-021: Hosted Merchant Risk Control Plane

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: A real merchant can securely onboard, connect a PSP, configure risk appetite, operate analysts, inspect audit and service health, obtain support, and offboard without THAAP becoming the money-moving PSP.
- Current evidence: Merchant tenant roots, API credentials, operator roles, analyst workflows, policy governance, audit, cockpit surfaces, and Phase 9 hosted identity/recovery plans provide prerequisites; no production merchant lifecycle exists.
- Remaining gap: Hosted tenant provisioning, SSO and lifecycle, connection verification, environment/mode status, merchant-owned configuration, audit export, usage/support visibility, privacy requests, deletion, incident communication, and offboarding are not implemented.
- Owner phase: Phase 10
- Owner slice: Hosted Merchant Onboarding And Risk Control Plane
- Primary DRI: Product, Security/Platform, and Developer Experience
- Trigger: Start after Phase 9 proves hosted identity, platform RBAC, SLO telemetry, backup/restore, release rollback, retention/deletion, and incident readiness.
- Done evidence: A two-tenant hosted pilot proves least privilege, connection verification, configuration approval, support/audit access, SLO and incident communication, retention/deletion, offboarding, tenant non-disclosure, and no THAAP custody or settlement claim.
- Merchant / analyst demo: A merchant risk leader onboards, connects its PSP, invites an analyst, configures a bounded policy, inspects health/audit evidence, opens support, and safely disconnects or offboards.
- Last reviewed: 2026-07-27

## VP-022: Safe Shadow, Advisory, And Enforcement Progression

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: Real traffic begins in shadow mode and can progress to advisory or narrowly enforced influence only through explicit merchant approval, measurable safety gates, bounded scope, safe fallback, kill switch, and rollback.
- Current evidence: Historical simulation, dual control, canary/rollback concepts, deterministic decisions, model timeout fallback, audit, and Phase 9 deployment hardening provide prerequisites; no production decision influences real merchant traffic.
- Remaining gap: Deployment-mode state, eligible traffic and exclusion contract, merchant approval, mode-specific APIs, counterfactual reconciliation, traffic/segment caps, latency/error budgets, automatic fallback, kill switch, rollback rehearsal, and immutable mode-at-decision evidence are not implemented.
- Owner phase: Phase 10
- Owner slice: Shadow Baseline And Decision Reconciliation; Advisory And Controlled Enforcement Progression
- Primary DRI: Fraud/Risk Product, Payments Core, and Risk Operations
- Trigger: Enter SHADOW only after VP-020 ingestion reconciliation passes; consider ADVISORY or ENFORCED only after the approved observation window, safety budget, outcome baseline, and rollback rehearsal pass.
- Done evidence: Forced failures and a controlled pilot prove default SHADOW, complete eligible/excluded coverage, mode-bound decisions, merchant dual approval, caps, safe fallback, kill switch, rollback, unchanged PSP authority when not enforced, and no cross-mode historical rewrite.
- Merchant / analyst demo: Observe the same eligible cohort in SHADOW, approve a bounded ADVISORY or ENFORCED slice, trigger dependency failure and kill switch, and prove the merchant returns safely to the prior mode with full audit.
- Last reviewed: 2026-07-27

## VP-023: Measured Merchant Outcomes And Feedback Flywheel

- Status: PLANNED_OWNER_ASSIGNED
- Vision promise: THAAP proves customer value with denominator-correct fraud, approval-quality, workload, and latency evidence, then feeds governed real outcomes back into rules, models, and privacy-safe intelligence without overstating causality.
- Current evidence: Immutable decisions, reviews, labels, disputes, simulations, analytics/dataset plans, model-governance ownership, and synthetic outcome reports provide the lineage prerequisites; no real merchant baseline or product-impact evidence exists.
- Remaining gap: Pilot hypothesis, baseline window, eligibility denominator, fraud-loss/rate definition, false-positive proxy, approval rate, review cost, label delay, confidence, seasonality, segment impact, support burden, experimental design, merchant acceptance, and real-outcome feedback governance are not frozen or implemented.
- Owner phase: Phase 10
- Owner slice: Pilot Outcome Measurement And Causal Honesty; Merchant Feedback And Intelligence Flywheel; Real-Merchant Pilot Closure
- Primary DRI: Product Analytics, ML Architecture, and Founder
- Trigger: Freeze before pilot data collection; publish impact only after source reconciliation, outcome-maturity, coverage, baseline-comparability, and privacy gates pass.
- Done evidence: A design-partner report exposes definitions, denominators, windows, coverage, unresolved and delayed labels, exclusions, confidence, segment impact, operational cost, and causal limitations; accepted outcomes feed sealed datasets and governed rule/model evaluation with no leakage or historical rewrite.
- Merchant / analyst demo: Trace one real cohort from PSP events through THAAP decisions, analyst actions and mature outcomes into a reproducible impact report and governed improvement candidate, including an honest negative or inconclusive result.
- Last reviewed: 2026-07-27

## Phase Rebaseline Summary

| Phase | Product outcome | Vision coverage |
|---|---|---|
| Phase 5 | Durable events, freshness-aware analytics, rebuild, and offline dataset lineage | VP-017 plus prerequisites for VP-002, VP-003, VP-004, VP-005, VP-008, VP-009, VP-012, VP-014, VP-016, VP-020, VP-021, VP-022, VP-023 |
| Phase 6 | Unified decision/analyst workspace and developer contract | VP-004, VP-005, VP-016 |
| Phase 7 | Governed ML baseline, serving, release governance, and drift | VP-002, VP-007, VP-008 |
| Phase 8 | Network/graph intelligence, merchant exposure, advanced identity, post-authorization, and disputes | VP-003, VP-006, VP-009, VP-012, VP-013, VP-014, VP-015 |
| Phase 9 | Agents, hosted trust, observability, release compatibility, incident recovery, and justified scale | VP-001, VP-010, VP-011, VP-013, VP-016 |
| Phase 10 | External PSP adapter, hosted merchant control plane, safe deployment progression, pilot outcomes, and feedback flywheel | VP-011, VP-016, VP-020, VP-021, VP-022, VP-023 |
| Every phase | Honest simulator boundary and apprenticeship proof | VP-018, VP-019 |

## Phase Entry And Closure Rule

Before a phase begins:

1. Review every `VP-NNN` owned or affected by the phase.
2. Freeze the exact observable merchant, analyst, payment, fraud, data/ML,
   security, and operational promise.
3. Convert each remaining gap into a named chapter/slice with DRI, trigger,
   demo, and done evidence.
4. Reject duplicate work already proven by an earlier phase; extend the product
   from current truth instead.

A phase cannot close when:

- an owned `VP-NNN` has no executable product evidence
- a remaining gap has an unassigned or vague owner
- infrastructure works but the required merchant/analyst demo does not
- a deferral inside a frozen promise lacks a valid `TD-NNNN`
- README, vision matrix, master plan, detailed plan, matrices, dossiers, debt,
  escaped-review, or learning sources disagree

The vision matrix is reviewed at every phase promise freeze and closure and at
least once every 35 days while any `PARTIAL_NEXT_OWNER` or
`PLANNED_OWNER_ASSIGNED` entry remains.
