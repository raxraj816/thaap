# Commit Readiness Checklist

Use this before saying a change is ready to commit.

## Required Checks

- `git status --short` reviewed.
- `git diff --stat` reviewed.
- Root `README.md` checked for stale status, commands, and documentation links.
- Service README checked when a service changed.
- `docs/IMPLEMENTATION_MASTER_PLAN.md` checked when scope, sequencing,
  invariants, or roadmap changed.
- Daily learning note exists for non-trivial implementation days.
- Deep dive exists for non-trivial implementation days.
- Relevant tests were run, or the reason they could not be run is recorded.
- Runtime smoke test was run when local startup behavior changed.
- Senior Staff adversarial review gate completed before handoff.

## Staff-Level Commit Questions

Before committing, answer:

1. Does the root project entry point still tell the truth?
2. Does the service entry point still tell the truth?
3. Did we update source-of-truth docs if an implementation decision changed?
4. Did we update learning docs if the change is non-trivial?
5. Do tests prove the riskiest invariant, not only the happy path?
6. Is any unrelated or accidental file mixed into the commit?
7. Would this diff make sense to a reviewer without our chat context?
8. What would a Stripe/Mastercard-style reviewer object to first?
9. Were cheap, valid review comments fixed before handoff?
10. Are true deferrals labeled with owner slice and unblock evidence?

## Senior Staff Adversarial Review Gate

Run this gate before saying work is ready. The goal is to catch review comments
while the implementation context is still fresh.

- **Payment/fraud invariant review:** Does the diff preserve payment lifecycle,
  fraud decision lineage, idempotency, and state-transition truth?
- **Tenant isolation and security review:** Does every merchant-owned path stay
  tenant-scoped, and are actor identities, credentials, and authorization
  boundaries explicit?
- **Sensitive-data/redaction review:** Could raw PAN, CVV, PII, API keys,
  tokens, fingerprints, bank data, tax IDs, idempotency secrets, or stored
  responses leak into tables, logs, metrics, events, audit payloads, tests, or
  docs?
- **Transaction boundary and rollback review:** Can partial writes, external
  side effects, retries, or idempotent replays create false payment, fraud,
  processor, ledger, webhook, or audit truth?
- **Observability and failure-mode review:** Are failures visible enough for
  debugging, and are missing metrics/logs/comments consciously deferred rather
  than forgotten?
- **Test evidence review:** Do tests prove the riskiest invariant, rollback
  path, tenant boundary, privacy boundary, and idempotency behavior touched by
  the diff?
- **Stripe/Mastercard reviewer pass:** What would a payments/fraud reviewer
  challenge first, and is the answer implemented, tested, documented, or
  explicitly deferred?
- **Cheap-fix pull-forward:** Fix valid low-cost comments before handoff instead
  of leaving obvious review work for someone else.
- **Deferral discipline:** Every true deferral names the exact reason, owner
  chapter or implementation slice, and evidence that would unblock it.

Classify review items with this disposition:

- `Fixed now`: Implemented and covered by tests/docs where relevant.
- `Accepted risk`: Known risk is acceptable for the current simulator/product
  slice, with the reason recorded.
- `Deferred`: Correct item, but blocked by a missing invariant, boundary,
  lineage source, or owner slice; include unblock evidence.
- `Rejected as not applicable`: Does not fit the current product truth,
  simulator scope, or architecture direction.

Final handoffs should include:

- `Adversarial review: completed`
- `Cheap fixes pulled forward: ...`
- `True deferrals: ...`
- `Proof: ...`

## Command

Run:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD
```

For non-trivial implementation days, include:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required --run-tests
```
