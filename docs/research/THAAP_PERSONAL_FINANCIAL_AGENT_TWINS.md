# THAAP Personal Financial Agent Twins

**6 October 2026 — research recommendation, not an implemented capability.**

## The founder's intended invention

One persistent AI agent for each real user, capable of simulating that particular
person's financial activity. This clarification supersedes interpreting the idea
only as a fraud investigator, behavioral profile or generic workflow simulator.
The earlier [AgenTwin assessment](THAAP_AGENTWIN_ASSESSMENT.md) remains supporting
research; its Trust Twin is the simulation environment, not the whole invention.

**Recommendation: pursue a person-specific financial simulation prototype inside
THAAP.** Build the agent architecture and test individual fidelity as separate
claims. The architecture is feasible; reliable financial imitation across people,
contexts and countries remains a hypothesis that THAAP must demonstrate.

One line: **A THAAP Financial Agent Twin is a permissioned, evolving AI model of a
specific person's financial behavior that can act in simulated situations and
estimate what that person might do next.**

“Clone” describes the ambition. It is not a literal copy of a mind, an identity
credential, or evidence that the person consented to an action.

## Research and its actual limits

| Primary source | Relevant evidence | Implication for THAAP |
|---|---|---|
| [Amazon AgenTwin](https://www.amazon.science/publications/agentwin-an-end-to-end-framework-for-building-agentic-twins-of-mobile-users) | Combines individual personas, memory, choice simulation and device actions; evaluates shopping and movie behavior | Direct architectural inspiration for a user-specific agent; financial fidelity needs a new experiment |
| [Park et al., version 3, 28 June 2026](https://arxiv.org/abs/2411.10109v3) | Models 1,052 people using interviews and/or surveys; reports 82–86% of participants' own repeat-answer consistency on held-out GSS questions | Personal evidence can help, but these are normalized survey results, not payment prediction accuracy |
| [The same study's results and discussion](https://arxiv.org/pdf/2411.10109v3) | No significant differences among agent types on incentivized economic games, including comparisons with demographic baselines | Rich personal context alone does not establish superior financial prediction |
| [TwinMarket, version 5](https://arxiv.org/html/2502.01506v5) | Simulates interacting trading agents; validation includes wealth inequality, trading patterns and market regularities | Useful sandbox inspiration; population realism does not prove that Raj's agent reproduces Raj |
| [Amazon agentic A/B validation study](https://www.amazon.science/publications/can-ai-agents-simulate-a-b-test-outcomes-a-validation-framework-for-agentic-experimentation) | Examines simulation against historical experiments and reports calibration/directional-signal limitations | Validate against external outcomes, not an agent's convincing explanation |

Review scope: AgenTwin's full paper was reviewed in the preceding assessment.
This extension checked the Stanford-led paper's current version, abstract,
methods/results excerpts and discussion; TwinMarket's architecture, validation
and limitations sections; and the Amazon A/B study's publication abstract.
Benchmarks were not reproduced. Older summaries of the Stanford study use a
different title and 85% figure; this note pins the revised version rather than
mixing results. No reviewed source establishes globally reliable personal
financial clones or proves that THAAP's proposed combination is unique.

## What makes it an agent

The twin has persistent identity and memory, interprets a situation, chooses an
action, invokes sandbox tools, observes consequences and chooses again. A profile
stores facts; the agent uses those facts to produce a sequence of choices.

For example, Raj receives income and then faces rent, a planned purchase and an
unexpected bill. His twin can simulate paying a bill, postponing a purchase,
choosing a payment method or requesting more information. Different plausible
branches represent uncertainty. These actions change simulated balances and
obligations, so subsequent choices experience their consequences.

The objective is to approximate what Raj **would** do. An assistant optimized to
make ideal financial choices answers a different question. Realistic simulation
may include procrastination, inconsistent preferences and mistakes; it must not
be trained to automatically agree with THAAP's desired risk decision.

## Architecture proposal

```text
Permitted observations + user-declared context
                    |
       Versioned personal memory, as of time T
                    |
        Raj's persistent logical agent
                    |
      Scenario + available choices + constraints
                    |
      Choose -> sandbox action -> observe -> choose
                    |
       Alternative trajectories and uncertainty
                    |
 Compare with subsequently observed real behavior
```

One agent per user means separate durable state and an identity bound to the
authorized tenant and person. Shared model services and workers can run many
agents on demand. It does not require training a foundation model for each person
or keeping millions of processes continuously awake. Separate scenario branches
are temporary copies of the same versioned agent state.

| Component | Responsibility |
|---|---|
| Personal memory | Separate observed events, explicit preferences and inferred hypotheses; retain provenance, confidence, event time and availability time |
| State model | Represent known balances, obligations, calendar and goals; preserve unknowns instead of inventing a complete financial life |
| Behavioral model | Combine numerical/sequence models with language-based interpretation; estimate choices conditional on the situation |
| Agent loop | Retrieve relevant memory, choose a permitted simulated action, use a tool and react to its result |
| Financial sandbox | Enforce exact amounts/currencies, balance conservation, scheduling and legal action transitions through deterministic code |
| Evaluation service | Compare frozen predictions with later observations and baselines; track uncertainty, drift, cost and coverage |

Transactions reveal completed actions but often omit abandoned choices, competing
offers, intended purchases and constraints. A merchant integration sees only part
of a person's life. Initial structured questions and voluntary corrections may
fill specific gaps; more collected data is not automatically more predictive.
Data collection must be justified by the selected experiment and permissions.

## The first experiment worth building

Start with **personal payment choices**, not an unrestricted simulation of every
financial decision. Given a known payment opportunity and the information the
user actually had, predict pay now, defer or decline, and payment method where
observable. Preserve “no action” and the time window. A dataset containing only
successful payments cannot validate abandonment or choices never recorded.

1. Build a local sandbox with synthetic users to prove state transitions,
   isolation, memory updates and branching. Label it a mechanism demo.
2. With permitted participant data, freeze each twin at time T. Use earlier data
   for learning, a later validation window for configuration, and a still later
   untouched test window. Separate events by availability time as well as event
   time; later chargebacks or corrections must not leak backward.
3. Compare the same future opportunities using a frequency baseline, a personal
   sequence model, a generic agent, a profile-conditioned agent and the full
   memory/tool agent. Include removal of memory and tools to measure their value.
4. Run the **swapped-person test**: give the same scenario to Raj's twin and twins
   with other consenting participants' memories in the isolated evaluation
   environment. Match contextual constraints and remove identity shortcuts.
   Only authorized evaluation access may cross participant records; participants
   never receive one another's data. Raj's own twin should predict Raj better.
5. After retrospective evaluation, record prospective predictions without showing
   them to users or altering their options. Compare with subsequent actions.
   Hypothetical survey choices and observed real behavior get separate results.

Begin with a small consenting research cohort to debug the protocol; choose the
confirmatory sample size from pilot variance, a predefined minimum useful gain
and power analysis. Repeated choices from one person are not independent people.
Use uncertainty estimates clustered by participant. Freeze thresholds and
analysis before looking at confirmatory outcomes.

Primary score: a proper probability score such as log loss on a defined choice
set, compared with the strongest baseline. It rewards assigning probability to
what happened and penalizes confident errors. Also report calibration, abstention
coverage, per-person/cohort performance, amount/timing errors where applicable,
simulation validity and cost per useful prediction. LLM-written confidence is
not calibrated confidence; repeated rollouts measure model variability, not all
uncertainty about reality.

**Progress criterion:** a practically useful held-out improvement over the best
baseline, credible own-person advantage in the swap test, and stable calibration
at the coverage and cost chosen before the confirmatory run. If agent machinery
does not improve predictions, keep the useful behavioral model and investigate
the failed assumption. Narrative realism alone does not pass.

## Product value and THAAP integration

The first product candidate is a simulation workbench: select a consenting
participant, freeze an evidence cutoff, change one scenario condition, and
inspect predicted choices, supporting observations and uncertainty beside later
real outcomes. This makes the invention inspectable and falsifiable.

After fidelity is demonstrated, test whether it improves THAAP's payment-risk
decisions: fewer unnecessary challenges at a fixed measured fraud constraint,
or better detection at the same customer friction. This is a second experiment.
Behavioral prediction accuracy alone does not prove fraud utility.

For the unusual ₹2,70,000 payment, the twin could compare plausible behavior with
and without a verified travel plan or planned purchase. An unfamiliar action can
be legitimate; a familiar-looking action can be fraudulent. The system should
return evidence and uncertainty, not a fabricated percentage presented as proof.

Potential later uses include testing verification experiences and financial
scenarios. Simulating a changed policy does not establish its causal effect on
real users; intervention claims need an appropriate real-world evaluation.

The defensible product hypothesis is the combination of individual fidelity,
permissioned longitudinal memory, validated simulation and THAAP's auditable
trust/authorization flow. The existence of an agent per user is easy to copy.
Whether this combination earns adoption or beats competitors is unproven.

Keep the [final research coverage](THAAP_RESEARCH_COVERAGE.md) and
[strategy](THAAP_WINNING_STRATEGY.md) supplier demonstration intact. This personal
twin is an explicit research track, not a silent replacement with a supplier-only
twin. Local sandbox/protocol work can proceed independently; claiming fidelity
requires permissioned longitudinal data and unseen outcomes. Production risk use
requires the separate risk-utility experiment and repository closure process.

## Invariants and failure cases for implementation kickoff

- **Authority:** simulation credentials and tools cannot mint live permits, spend,
  impersonate a user, or satisfy a real authentication/consent requirement.
- **Tenant/privacy:** enforce access outside prompts; scope each twin to a
  permitted identity domain, with correction, retention, deletion and export
  controls. No inferred cross-merchant identity joining or unrestricted twin chat.
- **Lineage:** synthetic actions remain in branch storage, never in real memory,
  fraud labels or the ledger. Every run records input cutoff, model/prompt/tool
  versions, scenario, seed where supported and complete outputs. Replay retained
  outputs; a seed does not guarantee identical hosted-model regeneration.
- **Learning:** unknown or disputed actions cannot silently become trusted user
  preferences. Test compromised devices, forged context, repeated poisoning,
  delayed labels and a genuine change in habits.
- **Operations:** bounded steps, tokens, duration and queue usage; cancelable runs;
  per-tenant cost limits; stale/missing memory and out-of-domain inputs must be
  visible. No live payment latency dependency in the experiment.
- **Proof:** deterministic sandbox postconditions, cross-tenant substitution,
  concurrent memory-update checks, branch isolation, deletion propagation and
  temporal leakage tests accompany behavioral evaluation. Mock outputs cannot
  prove personal fidelity.

These are proposed promises to freeze in a pre-implementation invariant matrix
and closure dossier when implementation is commissioned. No chapter is closed,
no new technical debt is accepted, and no model, financial action or deployment
was executed by this research task.

Learning summary: building an agent proves it can act; building a personal twin
requires evidence that its actions predict the particular person it represents.
