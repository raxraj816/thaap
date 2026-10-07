# THAAP Research Assessment — 2026-10-06

Consolidated recommendation: [THAAP Winning Strategy](THAAP_WINNING_STRATEGY.md).
This assessment preserves the earlier reviews and their design findings.

**Subsequent founder clarification:** the long trust/authorization report is the
final research baseline; the shorter blueprint is supporting material. The
[coverage map](THAAP_RESEARCH_COVERAGE.md) records the end-to-end cross-check and
corrections, including Neo4j/OPA in the first complete supplier demonstration.
Earlier precedence/selection discussion below is retained as review history;
the consolidated strategy v1.2 and coverage map contain the current recommendation.

Disposition: research review and proposed product direction, not an adopted
roadmap, implementation closure, production-readiness claim, or permission to
deploy. The implementation master plan and accepted ADRs remain authoritative.

## Executive judgment

The research's strongest proposal is to make THAAP independently verify the
trust and authority behind an economic action before execution. Its first
customer hypothesis is accounts-payable/treasury teams exposed to supplier
bank-account-change fraud. This is a material expansion from the current
merchant/card-fraud destination, not a renamed version of the existing product.

Recommend validating this focused workflow while preserving the existing
payment-risk simulator and operating workspace. A global ambition needs a
specific buyer, accessible evidence, an enforceable integration point, and a
measurable customer improvement. Architecture alone establishes none of these.

## Material reviewed and source confidence

- Follow-up: read all 23 pages of [THAAP_Implementation_Blueprint_v1.pdf](sources/THAAP_IMPLEMENTATION_BLUEPRINT_V1_2026-10-05.pdf)
  (Version 1.0, dated 5 October 2026). See the comparison addendum below;
  newly added does not establish precedence over the other blueprint.
- Read the complete 2,397 logical lines (2,396 newline characters) of [deep-research-report (7).md](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.md).
- Extracted all 63 pages of [THAAP_ Implementation-Ready Technical Blueprint for Trust, Authorization, and Agentic Money Movement.pdf](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.pdf).
  Compared normalized text with the Markdown: substantial differences concern
  rendered diagrams and the PDF's reference list. The PDF also contains the
  closing sentence missing after the Markdown's final colon. Visually inspected
  PDF page 60, containing the proposed first vertical slice.
- Checked the master plan, current learning/release records, selected payment
  and replay source, the console package manifest, and model-service contents.
  This was a focused source inspection, not a fresh runtime/security audit.
- The Markdown contains exported citation tokens, not usable source links.
  Recovered primary-source URLs from the PDF. Verified selected load-bearing
  claims below; the entire bibliography has not been independently validated.

| Primary source checked | Supported observation | Limit |
|---|---|---|
| [J.P. Morgan: B2B trust fraud, September 14, 2026](https://www.jpmorgan.com/insights/fraud/fraud-prevention/ai-powered-b2b-fraud-is-forcing-companies-to-rethink-trust) | Fraudulent instructions can pass ordinary approvals; material changes need independent verification. | Direct fetch returned 403; the search-indexed primary page supplied the relevant text. This validates a problem, not THAAP demand or uniqueness. |
| [J.P. Morgan: agent controls, September 29, 2026](https://www.jpmorgan.com/payments/newsroom/ai-agent-controls-payments) | Enforce authority outside prompts and count pending as well as completed exposure. | Direct fetch returned 403; checked indexed primary text. No independent performance evidence for THAAP. |
| [Stripe: Link update, September 29, 2026](https://stripe.com/blog/helping-personal-agents-shop-more-intelligently-and-reliably-with-link) | Incremental authorization is described as available; broader task spending controls are described as planned. | The reported 38x growth is Link-specific and supplies neither an absolute market size nor THAAP's opportunity size. |
| [AP2 specification](https://github.com/google-agentic-commerce/AP2/blob/main/docs/ap2/specification.md) | The checked v0.2 text describes checkout/payment mandates and key-bound autonomous authorization. | Agent-to-agent mandate delegation is explicitly outside the current specification. A native THAAP delegation design is not AP2 conformance. Pin a revision before implementation. |

## First-principles product model

Authentication establishes who presented a credential. Authorization establishes
what that actor may do. Instruction verification establishes whether the proposed
recipient and instruction have trustworthy provenance. Fraud assessment estimates
the remaining risk. Execution establishes what actually happened externally.
These are separate facts.

Example: an employee approves a real INR 80,000 invoice from a familiar supplier.
An attacker changed the supplier's bank account earlier. Correct login, a valid
invoice, and ordinary approval do not prove that the new destination belongs to
the supplier. THAAP should surface the change and withhold execution until
independent evidence verifies that exact destination version.

The learning point is that a low fraud score cannot grant missing authority,
and an authorization record cannot prove external settlement.

## Fit with the implementation

| Boundary | Evidence inspected | Consequence for adoption |
|---|---|---|
| Product scope | Master plan sections 1, 2 and Phase 10; ADR 0004 limits V1 to cards. | B2B treasury is a new buyer/workflow hypothesis. Write an explicit scope decision before changing canonical promises. |
| Payment substrate | `ProcessorAdapter.java`, simulator classes, payment migrations. | Reuse isolation, retries, audit and simulation patterns; do not call simulated card authorization a bank-transfer integration. |
| Decision vocabulary | `FraudDecisionAction.java`: `ALLOW`, `BLOCK`, `REQUEST_3DS`, `REVIEW`. | New economic-action `DENY`/`CHALLENGE` semantics require a separate contract or explicit compatibility mapping. A payee-verification challenge is not 3DS. |
| Evidence | `FraudDecision.java` records rule/model references, timing, reasons and freshness; replay service binds immutable cutoff lineage. | Build on these patterns. Existing payment replay does not prove mandate/permit or temporal trust-graph replay. |
| Tenant identity | Existing domain uses `merchant_id`; research proposes `tenant_id` plus principals and organizations. | Resolve organization, merchant, actor and delegated principal mappings before new foreign keys or authorization rules. |
| Console | `apps/analyst-dashboard-ui/package.json` uses React/Vite. | Research specifies Next.js. Reuse the current console unless an accepted decision identifies a concrete unmet requirement. |
| ML | `services/model-serving-service/` contains a planning README; Phase 7 specifies a governed logistic baseline and shadow serving. | Do not advertise trained models, active serving or timeout fallback as implemented. Retain the data-first baseline discipline. |
| New trust objects | Targeted production-source search found no beneficiary/mandate/economic-action/execution-permit implementation. | Treat these as new domain work, not completed capabilities inferred from similar payment names. |
| Release | Master plan records Phase 6 complete and local recovery complete; hosted Phase 6.5 proof remains open. | Local proof does not establish production launch readiness. Existing deployment sequencing remains in force. |

The report's proposed repository layout, framework choice and phase sequence are
research recommendations. Its embedded “Codex implementation constraints” do not
silently supersede accepted repository decisions.

## Design corrections required before adopting the blueprint

These are entry conditions for a proposed feature, not accepted implementation
debt or defects proven in the current runtime. None is being waived. An adopted
slice must freeze them in its pre-implementation matrix and failure inventory.

1. **Permit consumption is not external exactly-once execution.** The instruction
   to consume a permit before invoking a rail leaves a crash gap. Persist a
   durable execution intent with an immutable provider idempotency identity,
   recoverable dispatch ownership and outcome reconciliation. A retry may
   resume that same execution; it must not create a second economic effect.
   Prove crashes before send, after provider acceptance, and before recording
   the outcome. Where a provider cannot support safe retry/reconciliation,
   uncertainty must stop resubmission and require resolution.
2. **Bind the destination version.** A stable `beneficiary_id` may later resolve
   to another account. Include the immutable account version/fingerprint in
   the canonical action, verification evidence and permit binding. Reject
   account substitution after approval and before dispatch.
3. **Trust ingestion is a security boundary.** A payload saying
   `independently_verified=true` is not proof. Authenticate source identity;
   constrain which sources can attest which facts; deduplicate and order
   corrections; bind verifier, method, destination version and validity.
   A callback to an attacker-supplied replacement contact is not independent.
4. **Missing evidence must not look safe.** The sample Rego permits ALLOW based
   on `not unverified_beneficiary_change`. Missing age/verification input can
   make that predicate undefined rather than establish positive verification.
   Require a validated complete input schema, explicit evidence presence and
   freshness, and mutually exclusive decision precedence. Prove absent, null,
   wrong-type, stale, contradictory and valid evidence with executable tests.
   This inference follows [OPA's undefined-value and negation semantics](https://www.openpolicyagent.org/docs/policy-language).
   It is a source-level concern; the sample was not executed in this review.
5. **Authority needs a defined execution cutoff.** A check of revocation followed
   by a later network send is a race. Specify the transaction that irreversibly
   commits execution authority, serialize it with revocation and goal limits,
   and explain what revocation can stop after that point. Recheck action/evidence
   versions there. Do not promise recall of a transfer already accepted by a rail.
6. **Uncertain exposure cannot expire as if nothing happened.** Release an unused
   reservation only when dispatch is durably fenced out. A sent/unknown transfer
   retains exposure until authoritative reconciliation. Enforce aggregate parent,
   sibling mandate and goal-count limits with deterministic lock ordering. A
   “buy one” goal needs a pending slot, not merely a successful-count check.
   Mandatory exposure controls belong in the first spending slice, despite the
   research roadmap placing them in a later phase.
7. **Graph lag cannot hide a new account change.** Check critical beneficiary
   version and revocation facts against the authoritative transaction state.
   Derived graph/cache absence is not evidence of safety. Historical replay
   needs both event time and knowledge/ingestion cutoff; a backdated late event
   must not appear in the original decision's evidence.
8. **Recovery must preserve one-time authority.** A five-minute RPO could lose
   permit consumption or accepted external outcomes. After restore, fence
   dispatch and reconcile missing execution evidence before re-enabling money
   movement. A generic backup target does not prove economic safety.
9. **Privacy needs more than hashes and append-only tables.** Bank-account
   fingerprints can be guessable/linkable; use scoped keyed identifiers or
   tokenization with rotation and access controls. Define retention, deletion
   and external evidence storage before placing PII in immutable ledgers.
   Tenant configuration alone does not establish jurisdictional compliance.

## Competitive hypothesis and customer proof

Recommend testing a provider-independent trust and authorization layer for
supplier-payment changes. Stripe can be an execution integration and a competing
control provider. Its published agent controls mean THAAP cannot claim that
permits or budgets alone create a unique advantage.

The proposed advantage is connecting upstream change evidence to exact execution
and an investigator's explanation across systems. It becomes defensible only
if customers can supply those facts, THAAP can enforce the resulting decision,
and integration/support effort is justified by measured value.

Founder owns design-partner access, buyer selection and commercial validation.
The AI CTO working role owns the technical proposal, implementation and proof
within authorized scope; the founder owns production risk acceptance. Proposed
discovery target: three to five relevant AP/treasury conversations and one partner
willing to supply safely scoped historical examples and a shadow integration.
This is a proposed experiment, not a market fact or an instruction to contact anyone.

Before freezing a pilot, establish:

- Who controls beneficiary changes and payment release; who owns the budget?
- Can we receive trustworthy change, verification and outcome evidence in time?
- Can the customer prevent all bypass paths for the selected enforced workflow?
- What do current controls catch, miss and cost in review effort/delay?
- What improvement would justify purchase, and what would falsify this focus?

Pilot metrics need denominators: eligible payments/change events, independently
confirmed suspicious changes, legitimate challenges, verification completion
time, analyst effort, evidence coverage, integration effort and decision latency.
Shadow alerts do not prove prevented loss. Unknown outcomes remain unknown;
blocked payments do not automatically become fraud labels. Compare outcomes
against a pre-agreed baseline with label-maturity and selection limitations.

## Proposed next demonstration and sequencing

First reconcile the B2B scope, API compatibility, evidence authority, console
reuse and policy-engine adoption in a decision record. Preserve historical
chapter numbering and completion evidence. OPA and Neo4j are candidates with
specific responsibilities, not reasons to restart the repository from scratch.

Then freeze a bounded local demonstration:

`supplier account change -> trustworthy event -> payment proposal -> challenge
-> independent verification of exact account version -> fresh authorization
-> durable execution/permit consumption -> simulated bank outcome -> timeline/replay`

Required proof spans tenant substitution, forged evidence, a second account
change, policy errors, graph lag, duplicate/changed idempotent requests, concurrent
budget/goal exhaustion, revocation orderings, signing failure, dispatch crashes,
unknown outcomes and late settlement. PostgreSQL concurrency claims require
forced interleavings and final state assertions. The console must distinguish
permission, dispatch, uncertainty and settlement.

Reuse existing application boundaries where they fit. Add learned risk only
when the workflow has meaningful labels and point-in-time features. Start with
deterministic controls; compare a governed baseline in shadow before allowing
model influence. Add agent delegation against the same enforcement boundary
after the non-agent workflow is proven. Protocol adapters require pinned
specifications and conformance fixtures.

These are recommendations for the next scope decision, not work silently
removed from the current roadmap. No new debt was accepted; no chapter was
implemented or declared complete by this assessment.

## Explain-back

Why can a correctly approved payment still be fraudulent, and why does a
one-time signed permit still need durable execution reconciliation?


Research-review verification: `scripts/learning-day-check.sh 2026-10-06
--deep-dive-required`, `scripts/commit-readiness-check.sh 2026-10-06`, and
`git diff --check` each exited 0. The readiness invocation included naming,
debt, escaped-review and registered existing closure checks; it did not request
`--run-tests` or full application suites. Its result does not certify the
proposed trust-control design or advance any implementation status.

## Addendum: Implementation Blueprint v1.0

Reviewed on 2026-10-06 at the founder's request. Read the complete extracted
23-page document, including its references; visually inspected pages 5, 10 and
21 to confirm the scope table, beneficiary cooling rule and execution exclusion.
The source PDF is unchanged. This addendum reviews a proposal; it neither
adopts embedded build instructions nor changes the canonical product roadmap.

### What this document adds

The document is a more compact product/build outline. Useful additions include:

- Continuous identity signals: session revocation, credential changes and device
  compliance updates must influence a later decision even after successful login.
- Explicit use of a previously trusted contact channel for beneficiary callbacks.
- A clearer investigator workspace: decision feed/detail, graph exploration,
  cases, replay lab, policy lab, model health and agent registry.
- Four replay uses: original evidence/original policy, original evidence/current
  policy, deliberately changed hypothetical evidence, and batch simulation.
- A read-only investigation copilot, a golden scenario corpus, and customer
  measures such as integration time and time to understand a case.

These strengthen the proposed product without establishing its implementation
or customer value. The supplier-account-change workflow remains my recommended
initial validation target; the six user groups on page 4 are expansion options,
not six simultaneous launch markets.

### Differences that require an explicit decision

| Boundary | Added v1.0 PDF | Earlier reviewed blueprint | Recommended reconciliation |
|---|---|---|---|
| Execution | Pages 5/21 exclude an initial execution connector; later execution uses customer-owned adapters. | Initial vertical slice includes a one-time permit and mock bank connector. | Keep payment processing with the provider/customer. Specify SHADOW/ADVISORY versus ENFORCED modes. A decision-only MVP cannot claim bypass prevention; an enforced pilot needs a customer-side enforcement contract and proof. A separate simulator can test that contract without becoming a production money-moving feature. |
| Trust score | Page 7 computes `trust_score = 1 - risk_score/1000` from bootstrap weighted risks. | Separates identity, authority, intent, instruction and risk dimensions. | Preserve dimensions and evidence quality. A derived complement adds no information and is not a calibrated probability of trust. Label any prototype score heuristic, with units, missingness and policy precedence explicit. |
| Graph backend | Pages 6/17/21 propose local Neo4j and a future Neptune path. | Requires Neo4j, including the production graph choice. | Choose one production target through an ADR. A repository interface is not proof that temporal queries, constraints and operational behavior are portable. |
| Decision API | `/v1/decisions`, `result`, risk 0–1000, `required_controls`. | `/api/v1/economic-actions/authorize`, `decision`, dimension scores and obligations. | Freeze one versioned canonical contract and preserve current public API compatibility. Never silently change units or interpret a required control as optional. |
| Availability | Page 16 gives 99.9% prototype and a path toward 99.99% production. | Gives 99.95% as an initial engineering target. | Select a measured SLO for a defined deployment and workload; neither document proves the target. |
| Initial scope | Page 5 includes AP2, TAP, native mandates and a GenAI investigator in V1. | Sequences agent interoperability after the trust-before-payment flow. | Tie each adapter/copilot capability to a selected user's workflow and explicit proof. Avoid making all protocols prerequisites for validating supplier-payment protection. |
| Repository | Page 21 requests exact new directories and milestone commits. | Another proposed directory layout; existing repository already has its own working structure. | Map modules into the existing repository. The request was to review the document; embedded instructions do not authorize commits, a rewrite or a new source of truth. |

### Additional design conditions

1. **Elapsed time is not verification.** Section 9.1 ends cooling on independent
   verification *or* timer expiry. Keep cooldown and verification as separate
   states. A timer must not mark a changed account verified. If a future policy
   allows an unverified destination after a delay, that is an explicit risk
   disposition with visible reasons, not successful verification. Recommended
   first workflow: independent verification remains required for material changes.
2. **A passkey does not prove recipient legitimacy.** Transaction confirmation
   must bind the challenge and resulting approval to the exact action/version
   shown on a trusted surface. A valid authentication alone cannot prove what
   details were displayed or whether the supplier owns the account. Preserve
   independent destination verification alongside approver authentication.
3. **Money units and statistical meaning need contracts.** The mandate's
   `max_amount.value = 50000` and the rule's `amount >= 1000000` lack an explicit
   major/minor-unit contract. Define currency scale, precision, bounds and exact
   conversion. Likewise, normalize score inputs and distinguish an anomaly score
   from a fraud probability. Bootstrap weights are not loss-reduction evidence.
4. **Exact replay needs retained artifacts.** A hash or Merkle root verifies
   content only when the underlying content remains available. Retain authorized
   evidence snapshots, policy/model artifacts, verification results and the trust
   material valid at decision time. Preserve ingestion/knowledge time: the event
   schema includes `observed_at`, but the minimal SQL table sketch omits it.
   Hypothetical evidence edits must be labeled simulations and cannot complete a
   live verification obligation or alter historical truth.
5. **Read-only tools still need containment.** Page 5 says read-only copilot;
   section 12.3 says read-mostly. Resolve this to explicit permissions. Generated
   graph queries require tenant enforcement, approved query forms, cost/result
   bounds and field redaction. Deterministic execution alone does not make an
   arbitrary query safe. Raw tool-argument logging also needs minimization.
6. **Signed events do not settle freshness or source authority.** Derive source
   assurance from a trusted integration configuration, not a caller-supplied enum.
   Define issuer/subject-to-tenant mapping, signature and key handling, duplicate
   and late-event behavior, outage handling and ordering against authorization.
   A forged or misrouted revocation could otherwise become a denial-of-service
   tool; a delayed genuine revocation must not be claimed as instantly enforced.

The earlier nine design conditions remain applicable to any enforced integration.
No proposed gap has been accepted as technical debt or waived by this review.
They belong in the pre-implementation promise/failure inventory of the slice
that adopts the corresponding capability.

### Primary-source checks for the additions

- [Mastercard's September 30, 2026 framework overview](https://www.mastercard.com/global/en/news-and-trends/Insights/2026/mastercard-agentic-commerce-trust-white-paper.html)
  confirms the five pillars: identity, intent, controls, trusted execution and
  intelligence. This supports the category direction; it also demonstrates
  competitor overlap. It does not validate THAAP's distinctiveness or demand.
  The linked white paper itself was not reviewed in this follow-up.
- [OpenID CAEP 1.0](https://openid.net/specs/openid-caep-1_0.html), published
  29 August 2025, specifies events including session revocation, credential and
  device-compliance changes. This supports the proposed signal vocabulary,
  not an assumption of instantaneous delivery or automatic enforcement.
- [NIST SP 800-63-4](https://csrc.nist.gov/pubs/sp/800/63/4/final) is a final
  July 2025 publication covering identity proofing, authentication and federation.
  Its publication page was checked; this is not an audit of every NIST claim
  in the blueprint or certification of a THAAP approval design.

### Updated recommendation

Use both research documents as inputs to one reconciled specification. Preserve
the narrower customer workflow, versioned evidence, independent verification and
investigator experience. Define execution ownership and product mode before
claiming fraud prevention, and require measured outcomes before claims of model
or market superiority. The next scope decision should resolve decision-only
versus enforced integration explicitly; the current canonical roadmap remains
authoritative until that decision is adopted.

Explain back: if THAAP returns DENY but the customer's payment system ignores
the response, what has THAAP actually prevented?


Follow-up review checks: learning-day check with deep-dive required, ordinary
`commit-readiness-check.sh 2026-10-06` and `git diff --check` each exited 0.
Readiness included the naming/debt/escaped-review and registered closure checks;
full application suites were not requested or rerun. These checks cover repository
consistency, not validation of the proposed blueprint implementation.
