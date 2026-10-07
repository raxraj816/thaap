# Behavioral Simulation And Payment Authority

A state twin represents a system at a known point: which destination version,
policy, evidence and authority existed. A behavioral simulator predicts possible
next actions. An authorization system enforces what an identified actor may do.
These are different responsibilities.

Suppose a model predicts that an AP employee would approve a changed supplier
account. That prediction supplies neither their actual approval nor independent
proof of the account's ownership. THAAP must still enforce the explicit approval,
verification, amount, destination and execution conditions.

Historical replay asks what a policy decides on stored evidence. A hypothetical
policy replay changes that policy while holding evidence fixed. Neither alone
answers how real people would change their behavior under that new policy.
That prediction needs its own data and evaluation; causal claims need suitable
experimental evidence or justified identification assumptions.

Use simulation to expose possible failures, compare policy decisions and explore
workflow assumptions. Begin with scripted/state-machine baselines. Add learned
behavior only if it improves a measured task on held-out real examples. Distinguish
synthetic, observed, inferred and unknown outcomes. Repeated simulations estimate
variation within a model; they do not by themselves measure model error.

An isolated simulator must have no production payment credentials, no live permit
signing authority and no write route to the original evidence ledger. Assert
outcomes against durable state rather than the generating agent's explanation.
Retain versioned snapshots, sampled outputs and provenance for review.

See the [AgenTwin assessment](../../research/THAAP_AGENTWIN_ASSESSMENT.md) for
primary sources, proposed architecture and experiment gates. This concept note
records a proposed design, not an implemented control.

Explain back: what can a simulation prove about a particular failure sequence,
and what additional evidence is needed to claim a reduction in real fraud losses?
