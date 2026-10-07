# THAAP Agent Trust Runtime: Review And Reconciliation

**7 October 2026. Research review, not an implementation acceptance.**

The [implementation handoff](THAAP_PRODUCT_IMPLEMENTATION_HANDOFF.md) governs
consolidated planning decisions. Version 1.2 now aligns with its major corrections.
The current review below distinguishes source improvements from implementation
contracts and proof; earlier reviews remain historical records.

## Current assessment: version 1.2

**Reviewed 7 October 2026.** Read all **31 pages**, all 22 sections, examples and
references of [THAAP_Agent_Trust_Runtime_v1.2.pdf](THAAP_Agent_Trust_Runtime_v1.2.pdf).
Rendered every page and visually inspected all eight contact sheets. The supplied
PDF remains unchanged; its size, page count and SHA-256 are in the
[source manifest](sources/MANIFEST.json).

**Recommendation:** adopt v1.2 as the latest supplemental agent-runtime research
input. Its transaction-state and Neo4j corrections align with the handoff's
selected direction. Proceed to implementation contracts and invariant proof;
another research revision is not a prerequisite. The 63-page primary baseline,
supplier-first sequence, accepted ADRs and separate Personal Financial Agent Twin
experiment retain their roles. This is design alignment, not production acceptance.

### What v1.2 resolves

| Concern | Source evidence | Disposition against the handoff |
|---|---|---|
| Graph technology | pp. 7, 9, 11, 23–24, 29–30 explicitly require Neo4j in the first demo/MVP, with PostgreSQL authoritative and durable projection | Resolves the relational-first conflict; aligns with HD-01. Critical PostgreSQL checks must still survive graph lag |
| Unknown payment exposure | pp. 10, 16–17, 27–28, 30 prohibit TTL release after possible submission; distinguish unused expiry, unknown outcomes, cancellation and refund | Resolves the expiry contradiction; aligns with HD-08. Refunds restore budget only under explicit policy, not by assuming the purchase never happened |
| Laptop scenarios | pp. 19–23, 30 use separate mandates/fixtures for the below-threshold and step-up examples | Resolves the misleading sequential-purchase example; aligns with HD-11. Retain a negative test for a second purchase under the same exhausted or reserved mandate |
| Approval scope | pp. 10, 16, 20–21 add ApprovalArtifact, exact action hash, mandate version, approver, nonce, expiry, single use and re-evaluation | Substantially resolves approval-binding ambiguity; aligns with HD-10. Canonical encoding and replay/concurrency proof remain implementation work |
| Decision/execution branches | p. 16 separates DENY, REPLAN, rejected/expired approval, failed re-evaluation, success, definite failure and pending reconciliation | Corrects the earlier misleading success path. Use the complete handoff outcome mapping and provider-specific lifecycle contract rather than treating this drawing as executable code |

### Contract translations to preserve during implementation

These are settled planning choices or required proof, not new unresolved product
questions. HD references identify the exact governing decision in the handoff.

1. **One API and decision vocabulary (HD-03–HD-05).** The examples still use
   `/v1/authorize` and the seven source outcomes. Implement the adopted
   `/api/v1/economic-actions` planning namespace with ALLOW, CHALLENGE, REVIEW,
   DENY after freezing OpenAPI compatibility. STEP_UP/REQUEST_EVIDENCE become
   typed CHALLENGE obligations; REPLAN denies the current action; quarantine
   remains a separate containment state. An exact scoped ALLOW replaces
   ALLOW_WITH_LIMITS. Only eligible ALLOW can produce usable authority.
2. **Exact money and identity (HD-02, HD-06).** Bare `amount` and display merchant
   names in pp. 19–21 remain illustrative. Use integer `amount_minor`, explicit
   currency scales, authenticated tenant scope, stable counterparty IDs and
   versioned destinations. INR 146,999 is 14,699,900 minor units.
3. **Immutable decision history (HD-10, HD-13).** The step-up and subsequent ALLOW
   examples reuse a decision ID. Preserve a stable flow ID but issue a new
   immutable decision record for each evaluation. Persist denied/held decisions
   before responding; append execution outcomes later. Page 9's final evidence
   step must not postpone recording the decision until execution succeeds.
4. **Atomic authority and fenced dispatch (HD-07–HD-09).** The p. 16 sequential
   AUTHORIZATION_COMMITTED/BUDGET_RESERVED labels do not authorize separate usable
   commits. Final validity checks, shared budget/quantity reservation, usable
   authority and local decision evidence/outbox belong in one PostgreSQL
   transaction. Credential delivery is recoverable. A durable dispatch fence,
   not merely an absent local “submitted” flag, must prove safe release and
   serialize revocation with the chosen dispatch cutoff.
5. **Provider outcomes and accounted resolution (HD-08, HD-13).** The source's
   SUCCEEDED/SETTLED shorthand is not a universal rail-state contract. Distinguish
   provider acceptance, authorization, capture and settlement where applicable;
   the UI cannot say paid merely because THAAP allowed an action. An operator
   resolution of UNKNOWN must be evidence-backed and explicitly accounted; it
   cannot silently restore spending capacity by relabeling uncertainty as failure.

Freeze the remaining field schemas, canonical action bytes, lock order,
idempotency ownership, credential/key boundaries and adapter outcome mappings
in the adopted implementation slice. Prove changed-action approval rejection,
concurrent use, stale dispatch workers, revocation races, lost responses, late
success, graph lag and cross-tenant substitution at their actual enforcement
boundaries. These are requirements to implement, not accepted technical debt.

### End-to-end coverage

| Sections | v1.2 pages | Review focus |
|---|---|---|
| Cover, 1–4 | 1–5 | Product definition, guarantees and qualified recovery claims |
| 5, including 5.3 | 6–9 | Tenant-first flow, graph sequence and explicit Neo4j reconciliation |
| 6–7 | 9–12 | Domain entities, ApprovalArtifact, mandate constraints and monetary scope |
| 8–9 | 13–14 | Decision outcomes, explanation and independent threat attribution |
| 10, including 10.1–10.3 | 15–17 | Execution branches, exact binding, unknown exposure, cancellation and refunds |
| 11–12 | 17–19 | Evidence/replay and protocol integration boundaries |
| 13–14 | 19–23 | API samples, independent laptop fixtures and complete MVP scope |
| 15–18 | 23–27 | Architecture, positioning, buyers and measurable outcomes |
| 19–22 | 27–30 | Exclusions, risks, delivery sequence, demo and product vision |
| References and closing note | 30–31 | Compared with v1.1; unchanged after page-header/whitespace normalization |

The reference comparison does not constitute a fresh external protocol or vendor
review. No source examples, models, provider integrations or application tests
were executed. Personal twins remain a separate simulation-agent experiment;
this document neither proves individual behavioral fidelity nor lets a prediction
grant consent or spending authority.

This review updates the existing review/index/strategy/handoff and learning
trail. It adds no runtime behavior, new accepted debt or chapter closure.

Verification for v1.2: six preserved source hashes/sizes, 47 local links across
the four updated research documents, 38 research coverage IDs and 13 handoff
decisions verified (exit 0). Learning/deep-dive, executable naming and whitespace
checks exited 0. Overall readiness for 7 October exited 1 because the registered
current implementation scope still names 6 October. No registration was changed
and no application-test or overall commit-readiness result is claimed.

## Historical review: corrected version 1.1

**Reviewed 7 October 2026.** The new
[THAAP_Agent_Trust_Runtime_Corrected.pdf](THAAP_Agent_Trust_Runtime_Corrected.pdf)
is 28 pages, version 1.1. Read all pages and all 22 sections, examples and
references; rendered all pages and inspected seven four-page contact sheets.
The original 20-page review is retained below as historical analysis of v1.0.
Both PDFs remain unchanged. This was the disposition before v1.2; use the
current assessment above for the latest review.

**Recommendation:** use v1.1 as the newer agent-runtime research input, with the
remaining adoption conditions below. It substantially improves the design but
is not yet a complete executable specification. It does not supersede the
previously confirmed 63-page trust/authorization baseline, supplier-first flow,
accepted ADRs or personal financial twin research.

### What the correction resolves

These dispositions concern the document, not implemented or tested behavior.

| Earlier finding | v1.1 evidence | Disposition |
|---|---|---|
| INR 146,999 allowed without the required approval | pp. 17–20: STEP_UP above INR 140,000; verified approval, re-evaluation and then authority; direct ALLOW example is INR 136,999 | Threshold contradiction corrected; exact approval-binding contract still needs definition |
| Credentials issued before durable authority/reservation | pp. 8–10, 15, 19–20: explicit Authorization, BudgetReservation and issuance preconditions | Required ordering added; transaction/crash boundaries need freezing |
| Signed permission treated as enough | pp. 8–9, 15–16, 22–23: enforcing adapter and execution state machine | Architectural requirement corrected; enforcement protocol needs proof |
| Tenant isolation postponed | pp. 8–10, 20–23: tenant scope across domain, storage, graph, caches and evidence from first slice | Sequencing corrected; trusted ingress and cross-tenant tests still required |
| Step-up/executor missing from initial complete slice | pp. 20–21: both included with a reconciliation worker and mock executor | MVP scope corrected; external protocol work remains separate |
| Gift-card rejection called prompt-injection detection | pp. 14, 21, 24–25, 27: separates policy rejection from independently evidenced detection | Claim corrected; detector precision/recall and causal attribution remain unproven |
| Cache treated as durable authority | p. 22 explicitly excludes cache-only authority/budget truth | Architectural requirement corrected |
| Payment timeout treated loosely | pp. 9, 16, 22–23 require UNKNOWN/reconciliation and stable identifiers | Improved but not fully resolved: expiry exception on p. 26 conflicts with this rule |
| Graph placement inconsistent | pp. 7–10, 20, 22, 27 consistently place logical graph context before policy/risk and step-up | Internal logical sequencing clarified; relational-first conflicts with the agreed Neo4j baseline |
| Money/identity contracts, decision vocabulary, semantic certainty, recovery scope | Examples still use bare amounts/display names; summary lists five outcomes while path/table list seven | Previous adoption conditions remain; the correction is not blanket closure |

### Remaining adoption conditions

**1. Unknown execution must not lose its reserved exposure through expiry.**
Page 26 permits holding budget until terminal resolution *or policy-defined
expiry*. Page 16 also shows release on failure/expiry. Neither establishes that
an expired capability cannot already have produced an external payment.
Example: the rail accepts INR 146,999, its response is lost, the reservation
expires, a new purchase consumes the restored budget, and the first success
arrives later. The policy has now permitted excess exposure.

Required rule: release an unused reservation only after durable fencing proves
that no dispatch can occur and no existing attempt could have taken effect.
After possible dispatch, keep the exposure until authoritative reconciliation
or an explicit accounted resolution that does not silently restore spending
capacity. Expiry can block new use or escalate review; it cannot turn UNKNOWN
into failed. Reconciliation also needs bounded retries/backoff, operator
escalation and a visible unresolved state when the provider cannot answer.
Test late success after expiry, delayed not-found responses and stale workers.

**2. The demo must distinguish independent scenarios from one continuing mandate.**
Pages 21 and 27 first reserve INR 136,999, then propose INR 146,999 under the
one-laptop mandate. If these are consecutive live actions, the first consumes
or holds the quantity slot and leaves only INR 13,001 of a cumulative INR 150,000
budget. Step-up approval alone cannot grant a second laptop or replenish it.
Label the examples as isolated fixtures with fresh mandates, or explicitly cancel
and safely release an undispatched first attempt before the second. If the first
executed, a fresh mandate is required. Include the consecutive-attempt rejection
as a negative test rather than resetting state invisibly.

**3. Define authorization, reservation and dispatch boundaries precisely.**
Page 9 describes committing authorization and atomically reserving budget;
pages 15 and 19 show sequential states. Separate states are acceptable only if
an intermediate committed authorization is unusable and recovery is specified.
Prefer one PostgreSQL transaction for final validity checks, shared ancestor/
sibling budget and quantity reservation, the usable authorization record and
its local evidence/outbox. Sign or issue externally through a recoverable process
that cannot expose an unrecorded grant. Freeze canonical lock order, crash
recovery, signing ambiguity, changed-request idempotency and provider dispatch
ownership. Serialize revocation with the defined execution cutoff. A cached
check followed by a later send is not sufficient.

**4. The state machine needs all denial and terminal branches.**
The p. 15 diagram shows the STEP_UP loop and a downward path from preliminary
decision, but omits explicit no-issuance exits for DENY, REPLAN,
REQUEST_EVIDENCE, QUARANTINE, rejected/expired approvals and failed re-evaluation.
Page 16 funnels a terminal result into EXECUTED before success/failure handling.
Separate success, definite no-effect failure, undispatched cancellation and
unknown outcome. Only eligible allow decisions with all obligations satisfied
may create usable authority. List contradictory/late receipt handling and legal
state transitions. Do not translate the abbreviated drawing directly into code.

**5. Approvals, API identity and replay need immutable event semantics.**
Persist the action hash, mandate version, tenant/principal, verified challenge,
approver, freshness and approval scope. Re-evaluation must reject changes outside
that scope. Bind tenant context to authenticated identity; an absent body
`tenant_id` is acceptable if trusted ingress supplies it, but a caller-selected
ID alone is not authority. Freeze money units, stable merchant identifiers,
request/capability schemas and the canonical API route.

Page 19 reuses one decision ID for STEP_UP and ALLOW. This may correlate a journey,
but must not overwrite the original decision: use immutable decision revisions
or distinct event IDs beneath a stable authorization-flow ID. Store rejected
and held decisions too. Page 9's final evidence step must mean appending outcomes,
not postponing all input/decision evidence until after external execution.

**6. The graph technology conflict is still open.**
The corrected document's relational-first proposal is internally coherent. Its
claim that this reconciles prior research does not resolve the actual backend
choice: the existing primary baseline and agreed first complete demonstration
include temporal Neo4j and OPA. Preserve that direction unless deliberately
changed through a reconciled decision. Critical authorization/budget/revocation
state remains authoritative in PostgreSQL; graph lag cannot erase a denial fact.
Do not convert a new PDF recommendation into an unannounced roadmap change.

**7. Retain the other previously identified boundaries.**
High semantic scores are advisory, not proof that an itinerary or purchase
satisfies consent. Scope quarantine separately from decision permission. Keep
historical inputs/model outputs and trustworthy evidence; no claim that hashing
alone makes the record complete or undeletable. Explain that recovery cannot
recall settled funds. Cross-tenant intelligence, human fallback and delegated
subagents require explicit authorization/provenance. An AP2 demo demonstrates
its tested flow, not universal protocol conformance. Personal-twin predictions
remain separate from explicit delegation and their own fidelity experiments.

### Full corrected-document coverage

| Sections | Corrected pages | Review focus |
|---|---|---|
| Cover, 1–4 | 1–5 | Product thesis and five guarantees; preserve qualified recovery claims |
| 5, including new 5.3 | 6–9 | Tenant-first flow, graph before step-up, durable authority and enforcement |
| 6 | 9–10 | New Tenant, Authorization, BudgetReservation and ExecutionAttempt entities |
| 7 | 11–12 | Mandate example, threshold, numerical/identity and semantic constraints |
| 8–9 | 13–14 | Outcome vocabulary, explanations and corrected threat-attribution claims |
| 10–11 | 15–17 | Capability preconditions, state machine, unknown outcomes and revised replay |
| 12–13 | 17–20 | Protocol mappings and below/above-threshold API examples |
| 14–15 | 20–23 | MVP sequence, demo isolation, storage, graph and nonfunctional promises |
| 16–19 | 23–25 | Positioning, buyers, refined metrics and excluded scope |
| 20–22 and references | 26–28 | Expiry contradiction, delivery/demo plan, vision and external references |

The eight references and closing note match v1.0 after whitespace normalization.
The prior primary-source checks are retained with their original scope; this
comparison did not repeat external protocol research or execute source samples.
Visual inspection confirms the relevant tables and diagrams are present. The
PDF has sparse continuation pages (5 and 12) and split tables/state diagrams;
these are presentation limitations, not reasons to rewrite the supplied source.

This review adds no runtime behavior, accepted debt or chapter closure. The
remaining items are required design/proof conditions for an adopted slice, not
permissions to ship incomplete controls. Next implementation work should freeze
those conditions in the contract and pre-implementation invariant matrix.

Verification for v1.1: five preserved source hashes/sizes and 34 local links
passed, as did learning/deep-dive, executable naming and whitespace checks (exit
0). Overall readiness for 7 October exited 1 because the registered current
implementation scope still names 6 October. That registration was not changed;
no overall commit-readiness claim or application-test result is made.

## Historical review: original version 1.0

Source: [THAAP_Agent_Trust_Runtime.pdf](THAAP_Agent_Trust_Runtime.pdf), version 1.0,
October 2026, 20 pages. Read the complete extracted text, including all 22
sections, examples and references; rendered every page and inspected all five
four-page contact sheets. The supplied PDF remains unchanged at its original
path. Its hash and size are recorded in [the source manifest](sources/MANIFEST.json).

## Assessment

This is a useful agent-focused product strategy. Its strongest contribution is
an explicit boundary between an agent proposing an economic action and the
system granting executable authority. Identity, delegation, constraints,
adaptive risk, evidence and containment form a coherent product story.

Recommended interpretation: the **Agent Trust Runtime** is THAAP's proposed
authorization and enforcement layer; the **Personal Financial Agent Twin** is
the research capability that simulates a particular person's behavior. They can
coexist. This PDF does not describe or validate personal financial clones, and
its advice against building a consumer shopping assistant does not cancel the
founder's separate simulation-agent proposal.

Preserve the existing [final trust/authorization coverage](THAAP_RESEARCH_COVERAGE.md)
and [strategy](THAAP_WINNING_STRATEGY.md). Adding a research file is not an
instruction to silently replace the primary research baseline or accepted
contracts. Use this review to reconcile the agent extension at implementation
kickoff. No existing chapter status or production capability changes here.

## Complete section coverage

| Source sections / pages | Contribution and review disposition |
|---|---|
| Cover, 1 / pp. 1–2 | Five desired guarantees and independent authorization boundary; adopt as product framing, qualify recoverability |
| 2 / p. 3 | Wajo/Fo as an example of upstream action agents; vendor capabilities are market context, not a partnership |
| 3 / pp. 3–4 | Principal/agent separation and delegated authority; retain existing fraud controls for human and recurring payment paths |
| 4 / pp. 4–5 | Deterministic enforcement, least privilege, privacy and protocol neutrality; carry into implementation promises |
| 5 / pp. 6–7 | Gateway, services and decision path; correct evidence durability and reconcile MVP boundaries |
| 6 / pp. 7–8 | Canonical entities and relationship graph; add explicit tenant, temporal and evidence provenance semantics |
| 7 / pp. 8–9 | Mandate constraint families and laptop example; clarify units, stable IDs, semantic interpretation and approvals |
| 8 / p. 10 | Rich outcomes and explanations; freeze one taxonomy and distinguish decisions from containment operations |
| 9 / p. 11 | Prompt injection, tool hijack, update risk, collusion and human fallback; useful failure inventory, not proven detectors |
| 10 / pp. 11–12 | Scoped capability/credential; require an enforcing consumer and exact action binding |
| 11 / p. 12 | Decision/evidence replay; resolve the sample approval contradiction and preserve historical outputs |
| 12 / p. 13 | Protocol adapters; mapping and conformance need pinned specifications and boundary tests |
| 13 / pp. 13–14 | Simple developer API; samples are incomplete proposals, not compatible replacements for current APIs |
| 14 / pp. 14–15 | Staged build and shopping demonstration; reconcile with supplier-first graph/OPA demonstration |
| 15 / pp. 15–16 | Technical components and nonfunctional targets; preserve repo choices, durable authority and measured costs |
| 16 / pp. 16–17 | Neutrality and potential data advantage; commercial and technical hypotheses, not a proven moat |
| 17 / p. 17 | Candidate buyers and charging models; requires buyer evidence, integration rights and willingness to pay |
| 18 / p. 18 | Risk/friction/calibration/containment metrics; define labels, denominators, observation windows and confidence |
| 19 / p. 18 | Product scope discipline; consistent with a bounded personal simulation experiment |
| 20 / pp. 18–19 | Standards, privacy, latency and liability risks; evidence cannot guarantee legal outcomes or reversal |
| 21 / pp. 19–20 | 90-day proposal and demonstration; translate into dependency-ready chapters rather than a delivery promise |
| 22 and references / p. 20 | Autonomous-economy vision and eight references; selected primary claims checked below |

## Corrections before implementing these examples

These are source-design findings and adoption conditions. They are not observed
defects in current runtime code, accepted debt or escaped implementation reviews.

1. **The laptop example bypasses its own step-up threshold.** Page 9 requires
   step-up above INR 140,000. Pages 12–14 show INR 146,999 with policy PASS and
   ALLOW, without approval evidence. Either start below the threshold or show
   STEP_UP, authenticated approval bound to the exact action/mandate, fresh
   re-evaluation and then ALLOW. A successful authentication alone does not prove
   approval of the displayed purchase. Amount, destination or mandate changes
   invalidate the applicable approval.
2. **Money units and merchant identity need a real contract.** The samples use
   bare integers and merchant display names. Freeze minor-unit/currency rules,
   exact arithmetic, range limits, stable merchant/payee identifiers and
   immutable destination/product versions. Do not interpret `146999` differently
   at the API, policy and payment adapter boundaries.
3. **Record durable authority before exposing a usable capability.** Page 7
   lists issuance before evidence recording. Reserve budget and the one-item
   goal, persist the decision and grant intent durably, and release a usable
   credential only through a recoverable issuance process. Define signing
   failures, lost responses and retries. Persist outcomes asynchronously only
   after the corresponding action; this does not make original authorization
   evidence optional or eventually consistent.
4. **A signed token does not enforce itself.** Pages 11–14 need an explicit
   integration contract: which executor validates issuer, audience, tenant,
   proof of possession, action hash, expiry and revocation, and atomically
   consumes authority? For the exact purchase, bind the exact action rather than
   only the broader INR 150,000 mandate ceiling. If an executor can bypass THAAP,
   the integration is advisory and must be described that way.
5. **Single use and idempotency need durable concurrency proof.** Redis expiry
   alone cannot be the authority for spent grants or remaining budget. Concurrent
   requests and sibling delegations must share parent budgets and quantity
   reservations. A provider timeout is UNKNOWN, not failure: retain exposure,
   reconcile with the same provider identity, and never issue a fresh spend
   merely because the original capability expired. Define revocation's exact
   enforcement cutoff and what it cannot stop after external acceptance.
6. **A deterministic wrapper cannot repair uncertain intent.** A semantic score
   of 0.99 is not proof that a laptop, itinerary or recipient satisfies consent.
   Translate language into explicit constraints with provenance and necessary
   confirmation. Enforce them against trustworthy structured facts. Unsupported,
   missing or contradictory constraints cannot silently pass.
7. **Threat detection claims exceed the proposed evidence.** Blocking gift cards
   under a laptop-only mandate demonstrates constraint enforcement. It does not
   prove that prompt injection was detected as the cause. Test malicious actions
   that remain semantically close to the goal, normal-looking credential misuse,
   benign novelty and false quarantine. Agent version signatures or attestations
   establish specific provenance properties, not the absence of compromise.
8. **Freeze one decision vocabulary.** The summary has five outcomes, the path
   six, and the table seven. Define precedence and transitions. Recommended
   separation: a decision plus mandatory obligations, with quarantine as an
   auditable containment state/action whose completion is confirmed separately.
   ALLOW_WITH_LIMITS must not authorize a changed purchase without binding and
   rechecking it. REPLAN and REQUEST_EVIDENCE create no spending permission.
9. **Tenant isolation cannot wait for Phase 5.** Apply it to identity, mandates,
   API authorization, storage, graph edges, evidence, caches and inference from
   the first slice. Cross-ecosystem intelligence needs explicit data-sharing
   rights and privacy controls; there is no default global person graph.
10. **Recoverability and replay need precise claims.** Revocation cannot recall
    settled money. Compensation/refund is a separate authorized operation.
    Retain historical inputs, model outputs, policy and cryptographic context
    for reconstruction. A new model invocation is a counterfactual evaluation.
    Append-only storage and hashes alone do not prove completeness or prevent
    privileged rewriting; define integrity verification and privacy retention.

Also tighten human fallback: a support operator needs attributable, constrained
delegation. Human participation must not bypass the original user's limits.
Service names describe responsibilities, not a requirement for a distributed
microservice deployment. Reuse the repository's established foundations.

## Sequencing conflicts and recommended resolution

| New PDF | Existing direction / resolution |
|---|---|
| Agent shopping is the first demonstration | Keep the previously agreed complete supplier flow; use the shopping story to specify the next agent extension, not to erase supplier requirements |
| Trust Graph is Phase 2 on p. 7, Phase 4 on p. 14, and relational-first on p. 16 | Preserve temporal Neo4j and OPA in the first complete supplier demonstration; expand agent graph features separately. Reconcile any backend change explicitly |
| Step-up is MVP on pp. 6–7 but Phase 2 on p. 14 | Complete the challenge lifecycle before claiming the threshold-sensitive shopping flow works |
| Capabilities/receipts arrive in Phase 3, while MVP returns them | Include a validating fake executor and durable outcome/receipt path in the first enforced local agent slice; external protocol integrations can follow |
| Multi-tenancy appears in the final commercial phase | Tenant enforcement is foundational; commercial packaging and scale can grow later |
| A new `/v1/authorize` endpoint | Reconcile with existing public contracts and the earlier economic-action proposal before implementation; no silent API replacement |

The [personal twin proposal](THAAP_PERSONAL_FINANCIAL_AGENT_TWINS.md) remains an
explicit research track. A twin estimates likely behavior; the runtime checks
actual authority and decides whether a proposed action may execute. Twin outputs
may become evaluated risk evidence, but may never invent delegation, approve a
step-up, or certify their own predictions. Neither capability needs to pretend
the other is already implemented.

## External claim checks

Checked on 7 October 2026. Vendor descriptions are not independent security
evaluations, integration approval or proof of demand for THAAP.

- [Wajo's product page](https://wajo.ai/) describes calls/email, human assistance
  and single-use purchase cards. Its [launch article](https://wajo.ai/blog/unveiling-wajo/)
  supplies the broader agent framing. No Wajo API, partnership, agreement or
  production payment integration was established in this review.
- [AP2's current specification](https://raw.githubusercontent.com/google-agentic-commerce/AP2/main/docs/ap2/specification.md)
  supports deterministic validation, open authority and closed checkout/payment
  mandates. It explicitly places agent-to-agent mandate delegation outside its
  current scope. THAAP's internal delegation tree therefore cannot automatically
  be called AP2-conformant. This mutable reference must be pinned and its related
  schemas/verification rules tested when implementing an adapter.
- [Mastercard's Verifiable Intent article](https://www.mastercard.com/global/en/news-and-trends/stories/2026/verifiable-intent.html)
  supports the identity/intent/action evidence framing. The cited US URL failed
  direct retrieval; the official global edition was available.
- [Visa Intelligent Commerce](https://www.visa.com/en-us/solutions/intelligent-commerce)
  describes authentication, spending controls and agentic payment infrastructure.
  This landing-page check does not verify a specific credential API or access.
- [Stripe's Agentic Commerce Suite](https://stripe.com/blog/agentic-commerce-suite)
  describes Shared Payment Tokens scoped by seller, amount and time. These are
  relevant downstream primitives and overlapping capabilities; neutrality alone
  is not a demonstrated competitive advantage.

Wajo terms, the TechCrunch article, full protocol dependency documents and
commercial/legal eligibility were not independently audited. No source claim
about third-party liability or universal rail support is certified here.

## The agent demonstration this adds

After the shared foundations and supplier demonstration, show a fake purchasing
agent with a signed one-laptop mandate. Allow an exact purchase below the
step-up threshold; reject an unrelated gift-card purchase; challenge the
INR 146,999 purchase and require action-bound approval. Force concurrent attempts
against the one-item goal, revoke before the execution cutoff, and lose a fake
executor response after acceptance. Replay the authority, obligations, actual
execution and unresolved uncertainty for each case.

This would demonstrate a trustworthy execution boundary. Prompt-injection
detection, real-rail enforcement, AP2 conformance and personal behavioral fidelity
retain separate evidence requirements. A polished five-minute story is useful;
it does not replace forced failure-sequence proof.

No runtime implementation, protocol conformance test, model experiment or full
application test suite was performed in this review. No new debt was accepted
and no chapter was closed. The implementation owner must freeze these adoption
conditions in the relevant invariant matrix and reconcile the canonical plan
before changing code.


Review verification: source hashes/sizes, local links, learning/deep-dive, naming
and whitespace checks passed (exit 0). Repository readiness for 7 October exited
1 because its registered current implementation scope still names 6 October.
That registration was preserved; this research review does not claim overall
commit readiness or a new implementation closure.
