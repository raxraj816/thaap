# THAAP Product And Implementation Handoff

**7 October 2026. Consolidated planning decisions for the next implementation
session. No new runtime capability or production readiness is certified.**

## Product we are building

THAAP turns a proposed financial action into explicit, constrained permission
to execute, with evidence connecting intent, authority, risk and actual outcome.
The ambition is a globally useful payment-trust product that earns adoption
through useful decisions, understandable controls and reliable operations.

The initial commercial entry is supplier/payment-instruction trust. It is an
entry point into the wider product, not its ceiling. The agent runtime extends
the same core to delegated economic actions. Personal Financial Agent Twins
are a distinct invention track: a persistent agent per person that simulates
that person's financial choices, tested against unseen real behavior.

| Product capability | Observable experience | Evidence needed |
|---|---|---|
| Trust and authorization | Know whose authority applies, what changed and whether this exact action may proceed | Verified identity/evidence, versioned intent, deterministic policy and negative-path proof |
| Controlled execution | Permission follows the exact amount, destination and task through execution | Atomic exposure/goal limits, enforced permits, revocation ordering and uncertain-outcome reconciliation |
| Investigator and operator workspace | Resolve a legitimate hold, inspect a blocked attempt, reconstruct a decision and see unresolved execution | Complete user journeys, immutable evidence, permission boundaries and distinct decision/execution states |
| Agent security | Constrain delegation and tool use; investigate suspected compromise | Key possession, attenuation, bounded tools, independent threat evidence and evaluated detectors |
| Developer platform | Integrate, understand errors, reproduce sandbox cases and inspect outcomes | Versioned API/event contracts, SDK examples and adapter-specific enforcement/conformance proof |
| Financial Agent Twins | Run a person's agent through alternative financial situations and compare predictions with real choices | Personal-memory isolation, financial sandbox invariants, held-out fidelity and own-versus-swapped-person evaluation |
| Learning and model governance | Improve decisions without silently changing authority or learning fabricated truth | Permitted data, mature labels, versioned features/models, calibrated evaluation, shadow comparison and rollback |

Fraud/risk capabilities and the existing card simulator remain foundations.
This plan does not replace them with a shopping assistant or claim a new payment
rail. Real providers retain their money movement and settlement roles.

## Source precedence and completeness

The [implementation master plan](../IMPLEMENTATION_MASTER_PLAN.md) and accepted
ADRs govern runtime changes. This handoff supplies the consolidated planning
choices; schema, API and enforcement decisions must be recorded in the adopted
slice's ADR/contracts before code. Historical scopes and closures remain intact.

1. [Primary research coverage](THAAP_RESEARCH_COVERAGE.md) retains all **38
   requirement groups and 11 mandatory scenarios** from the 63-page final
   trust/authorization research. A shorter handoff cannot drop those requirements.
2. [Winning strategy](THAAP_WINNING_STRATEGY.md) supplies the invention thesis,
   commercial entry, experience, metrics and expansion hypotheses.
3. [Agent Runtime review](THAAP_AGENT_TRUST_RUNTIME_REVIEW.md) preserves v1.0,
   v1.1 and v1.2 findings. Version 1.2 aligns with the major decisions below;
   the source PDFs remain unchanged and implementation proof is still required.
4. [Personal Financial Agent Twins](THAAP_PERSONAL_FINANCIAL_AGENT_TWINS.md)
   defines the founder's actual per-person simulation agent and its experiment.
   [AgenTwin assessment](THAAP_AGENTWIN_ASSESSMENT.md) supplies supporting research.
5. [Source manifest](sources/MANIFEST.json) preserves all supplied source hashes.
   External papers and vendor material motivate experiments, not performance claims.

Version 1.2 adoption note: Neo4j in the first demo, retained unknown exposure,
independent laptop fixtures and exact-action approval now agree with this
handoff. Its illustrative route names, money fields, decision IDs and abbreviated
state diagram still translate through HD-03–HD-10 and HD-13. This source revision
changes no selected product scope and does not establish runtime proof.

## Resolved planning decisions

These decisions replace ambiguous source examples for planning. They are not
waivers of accepted contracts or claims that tests already exist.

| ID | Decision | Implementation/proof owner slice |
|---|---|---|
| HD-01 | Preserve Java/Spring/Maven, React/Vite and the modular monolith. PostgreSQL owns transactional truth. Include temporal Neo4j and OPA in the first complete supplier demonstration; reject the supplemental relational-only graph substitution for that demonstration | Contract reconciliation; temporal graph and policy |
| HD-02 | Preserve the existing merchant security boundary and API contracts. The first local trust slice uses the authenticated merchant as its tenant root; principals and suppliers are scoped beneath it. No automatic identity union across merchants or invented organization authority. A future organization root requires explicit membership/migration design | Contract reconciliation; trust ingestion |
| HD-03 | Use one new economic-action contract, with the primary research's `/api/v1/economic-actions` namespace as the planning target. Do not add a competing `/v1/authorize` family. Freeze deployment-prefix/OpenAPI compatibility before runtime implementation | Contract reconciliation |
| HD-04 | New trust decisions use ALLOW, CHALLENGE, REVIEW, DENY. STEP_UP and REQUEST_EVIDENCE are typed CHALLENGE obligations; REPLAN means DENY for the exact proposed action with a structured replan reason. A revised action gets a fresh evaluation. Quarantine is a separate containment state that prevents new authority | Policy and agent authority |
| HD-05 | Do not expose ALLOW_WITH_LIMITS as an ambiguous authorization. An ALLOW binds the exact approved action and constraints. Any material change requires fresh authority. Existing card BLOCK/REQUEST_3DS vocabulary remains unchanged | Contract reconciliation; execution |
| HD-06 | New monetary fields use exact integer `amount_minor` plus currency; never infer units from a display amount. INR 146,999 is 14,699,900 minor units. Freeze currency scale, supported ranges and overflow rules in the schema. Use stable tenant-scoped counterparty IDs and immutable destination versions, not display names | Contract reconciliation; trust domain |
| HD-07 | A final authorization becomes usable only after one PostgreSQL transaction rechecks critical versions/authority, reserves all applicable shared budgets and goal slots, and commits the authorization plus decision evidence/outbox. Signing/credential delivery happens through a recoverable process after this transaction | Execution and exposure |
| HD-08 | UNKNOWN external outcomes retain exposure. TTL alone cannot release it. Unused reservation release requires durable fencing against dispatch and proof that no attempt could have taken effect. Definite no-effect failure can release; success consumes; unresolved exposure escalates visibly | Execution and reconciliation |
| HD-09 | Serialize revocation with the transaction that commits authority to dispatch. Revocation before that cutoff prevents dispatch. After it, stop unused authority and reconcile committed attempts; never promise that a settled payment can be recalled | Execution and agent authority |
| HD-10 | Bind approval to tenant, principal, exact action hash, mandate/evidence versions, challenge, approver and validity. Re-evaluate after approval. Each evaluation gets an immutable decision record under a stable flow ID; never overwrite STEP_UP/CHALLENGE with ALLOW | Approval and replay |
| HD-11 | Each positive laptop demonstration gets a fresh mandate/fixture. Separately prove that a second purchase under the same one-item mandate cannot pass while the first is reserved or spent. Step-up does not replenish authority | Agent demonstration |
| HD-12 | Policy rejection proves constraint enforcement. Prompt-injection attribution needs separate telemetry/provenance and evaluated detection. Semantic scores and personal twins may contribute evaluated signals but cannot create consent, verification or spending authority | Model/agent security; personal twin experiment |
| HD-13 | Retain underlying permitted evidence, occurred/known times, exact policy/model artifacts and outputs. Record held/denied decisions before responding; append execution outcomes later. Historical replay and hypothetical simulation are distinct, access-controlled operations | Trust ingestion; replay |

Decision precedence: ingress failures never enter a caller-chosen tenant domain;
invalid/revoked authority, active containment or a known hard violation produces
no permit. Missing evidence produces an actionable CHALLENGE when resolvable;
conflicting evidence or required analyst judgment produces REVIEW. Only ALLOW
with satisfied obligations may reach final reservation/authorization. Recheck
critical facts at that boundary even when earlier enrichment passed.

## Execution lifecycle to implement

```text
Proposed action -> immutable decision
  DENY ------------------------> no authority for that action
  REVIEW ----------------------> analyst work; fresh evaluation required
  CHALLENGE -> evidence/approval -> fresh evaluation
  ALLOW -> final atomic checks + exposure/goal reservation + authority
        -> recoverable credential issuance
        -> fenced dispatch commitment
        -> external attempt
             success ---------> consume reservation; record verified outcome
             definite no-effect failure -> release safely; record failure
             ambiguity -------> UNKNOWN; retain exposure; reconcile/escalate
```

Before dispatch commitment, cancellation/expiry/revocation must atomically
prevent later dispatch before releasing resources. After possible dispatch,
only authoritative outcome handling can resolve the exposure. No generic
TERMINAL_RESULT-to-EXECUTED transition. Refunds/compensation require separate
authority and do not erase history or automatically reopen a completed goal.

Reuse the same external idempotency identity for recovery of the same attempt;
a timeout is not authorization to invent a new payment. Provider-specific
idempotency and lookup behavior must be validated. A late not-found lookup is
not automatically proof of non-execution. Force concurrency, dispatch-crash,
signing-failure, stale-worker, late-success and contradictory-receipt sequences.

## Product design contract

The operator's primary screen must answer: what is proposed, what changed, what
evidence applies, what is required next, and what actually happened. It must:

- Show amount/currency, counterparty and destination version with source/freshness.
- Separate authorization, approval, dispatch, unknown outcome and confirmed
  execution. A green permission badge must never imply that money moved.
- Make a legitimate hold resolvable through an assigned, specific obligation;
  give authorized users actionable reasons without revealing another tenant's data.
- Preserve prior decisions when evidence changes; provide a readable timeline,
  original replay and a visibly separate hypothetical comparison.
- Make invalid, stale, loading, unavailable and recovery states as deliberate as
  the happy path. Use accessible labels, keyboard paths and exact currency/time
  presentation; do not rely on color alone.
- Give developers the same semantics through the API, examples and sandbox.

The twin workbench identifies the person and permitted data scope, freezes a
memory cutoff, shows scenario assumptions and alternatives, and compares
predictions with later observed choices. Synthetic actions never enter real
memory, fraud labels, authority or ledger truth. Shared model workers may serve
many durable logical agents; no foundation model per person is required.

## Build sequence and next session

1. **First chapter: supplier instruction and evidence truth.** Record the scope
   ADR and typed contracts, freeze the first promises/failure inventory, and
   implement a tenant-scoped supplier/destination-version and authenticated
   change-evidence slice. Include duplicate/conflicting events, occurred/known
   times and substitution/isolation proof. Map it into the canonical plan without
   renumbering historical chapters. Start from the current checkout state.
2. **Complete the supplier journey.** Add bounded Neo4j projection, OPA decisions,
   independent verification obligations, investigator UI, exact permit, durable
   fake execution/outcome and replay. Pull each dependency-ready vertical step
   forward; no one-chapter-per-day restriction.
3. **Extend with agent authority.** Key-bound mandates, attenuated delegation,
   shared amount/quantity limits, step-up, tool restrictions, revocation and the
   corrected purchase/attack/recovery scenarios reuse the shared core.
4. **Run the personal-twin experiment.** Build the synthetic financial sandbox and
   evaluation protocol as independent work when capacity allows. Personal fidelity
   claims require permitted longitudinal data, strong baselines, own-versus-swapped
   memory controls and unseen future choices. Risk utility is a further experiment.
5. **Adopt external adapters and real pilots through evidence.** Pin each adopted
   protocol, prove its specific integration, then qualify SHADOW, ADVISORY and
   ENFORCED deployment paths. The core invention must be inspectable locally
   before external access is available.

The next session begins implementation, not another open-ended research cycle.
Contract/proof preparation is the first part of that chapter. Do not invent
credentials, customer access or deployment evidence to accelerate it. No code
implementation, branch creation, commit, push or deployment is requested today.

The existing readiness registration still points to the 6 October Chapter 60R
review. At the next adopted implementation slice, establish its honest
pre-implementation matrix/dossier and governed scope rather than relabeling the
old closure. Completion requires the new slice's real proof and passing gates.
Today's documentation does not claim to have repaired that tooling workflow.

## Global usefulness and the questions experiments must answer

Build tenant isolation, exact multi-currency representation, explicit time zones,
versioned integrations, data minimization and accessible workflows into the
foundations. Add actual rails, regional hosting and regulatory commitments only
with the corresponding evidence and accountable owners. Global ambition does
not mean universal support in the first release.

The founder owns buyer priorities, product review and commercial access. The AI
CTO/ML/product-design role owns executable proposals, implementation, validation
and the learning trail within authorized scope. Named human operational/security
accountability is required for a live rollout.

Measure safe completion of legitimate actions as well as rejection of invalid
ones; verification effort, integration effort, replay completeness, latency,
unknown exposure and recovery. Compare risk quality at matched friction or loss
constraints. Measure personal-twin fidelity, uncertainty, coverage and cost
separately. Do not count synthetic blocked attempts as prevented real fraud.

Unresolved empirical inputs are recorded rather than guessed: committed team
capacity/budget, design-partner access, willingness to pay, real-data coverage,
personal-twin accuracy, production SLOs and international rollout eligibility.
These do not block the local supplier slice. Discovery, evaluation and scoped
rollout planning own the evidence. They are not accepted defects in an existing
runtime promise and no new technical debt is accepted by this handoff.

Learning summary: product direction and safety decisions can be clear before
implementation; correctness, predictive quality and market success must still
be earned through their respective tests and customer outcomes.
