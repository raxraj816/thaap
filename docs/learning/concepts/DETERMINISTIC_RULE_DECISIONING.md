# Deterministic Rule Decisioning

## Mental Model

A fraud decision is not only the winning action. It is an evidence bundle:

```text
decision-time inputs
  + versioned policy
  + one result for every evaluated rule
  + deterministic conflict resolution
  = explainable action
```

If THAAP stores only `BLOCK`, a reviewer cannot tell whether one rule matched,
three rules conflicted, a rule was skipped, or the engine failed halfway. An
explainable system therefore preserves both matches and non-matches.

## Rules Are Not Processor Truth

THAAP evaluates fraud risk before processor authorization. A THAAP `BLOCK` or
`REQUEST_3DS` is a fraud-engine action. An issuer decline or processor 3DS
response is later network/processor evidence. They must remain separate because
they answer different questions:

- FraudDecision: what THAAP chose from decision-time risk context
- Authorization: what the simulated processor/network returned

A fraud step-up must not create an Authorization row. Otherwise the system
would invent processor history that never happened.

## Complete Evaluation

Phase 3 evaluates the complete active ruleset and stores one immutable result
for every rule. It does not stop at the first match. Complete evaluation gives:

- stable explanations
- visibility into shadowed lower-severity matches
- evidence that a rule executed and did not match
- reproducible offline comparison
- safer policy debugging

The aggregator then selects one action using frozen precedence:

```text
BLOCK > REVIEW > REQUEST_3DS > ALLOW
```

Within one action, numeric priority and immutable rule code make tie-breaking
stable. The primary reason identifies the winner; the complete ordered reason
list preserves every match.

## A Rules Score Is Not Probability

Phase 3 maps actions to fixed severity scores. Those scores make sorting and
product presentation consistent, but they are not calibrated estimates of the
probability of fraud. Calling `0.9500` a 95% fraud probability would be false
because no labelled statistical calibration produced it.

Later ML scores require time-safe datasets, validation, calibration curves,
segment analysis, and model version lineage. Until then, documentation and APIs
must call the Phase 3 value a deterministic rules score.

## Failure Semantics

- Missing V1 input: fail the decision transaction and never default allow;
  Phase 3 has no optional rule inputs.
- Unsupported or corrupt rule configuration: fail readiness before traffic.
- Rule evaluation exception: roll back the entire confirm mutation.
- Multiple matches: persist all, then apply deterministic precedence.
- Idempotent replay: return the original decision evidence; do not re-evaluate
  against newer code or state.

## Payment And Data Invariants

- Only `ALLOW` reaches the processor.
- One PaymentAttempt owns one immutable FraudDecision.
- FeatureSnapshot preserves exact decision-time inputs.
- RuleExecutionResults preserve exact policy version and outcomes.
- Later payment, policy, or feature changes never rewrite old evidence.
- Tenant identity crosses every lineage edge.
- No raw PAN, CVV, PII, credential, or correlation secret is a rule feature.

## Design-Review Explanation

The important production claim is not “the rules are clever.” It is that the
decision is deterministic, tenant-safe, point-in-time correct, atomic with the
payment branch, explainable from durable evidence, and incapable of creating
processor or money truth for a non-allow action.

## THAAP Phase 3 Implementation

- V20 seeds the exact three-definition catalogue, result rows, and review cases.
- V21 makes rule definitions and execution results append-only.
- V22 forward-repairs feature type identity while preserving the checksum of
  the already-shipped V18 migration.
- V23 binds copied result action/reason/priority to its rule and binds a review
  case to the exact REVIEW decision, attempt, intent, tenant, reasons, and
  traffic origin. It also avoids silently subscribing previously selective
  webhook endpoints to new event types.
- V24 makes review identity, reasons, and origin immutable, rejects deletion or
  reopening, and allows only the one Phase 3 lifecycle step `OPEN -> CANCELED`.
- V25 recognizes that an old exact event set is not durable wildcard consent.
  It uses subscription creation cohorts to remove newly added events from
  legacy endpoints while retaining explicit post-Phase-3 nine-event sets.
- Startup rejects any missing, duplicate, extra, or incompatible rule catalogue
  before serving confirms.
- Phase 4 may add velocity and mutable policy authoring only after freezing the
  configuration, simulation, approval, rollback, and dependency-failure truth.
