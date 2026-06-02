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

## Staff-Level Commit Questions

Before committing, answer:

1. Does the root project entry point still tell the truth?
2. Does the service entry point still tell the truth?
3. Did we update source-of-truth docs if an implementation decision changed?
4. Did we update learning docs if the change is non-trivial?
5. Do tests prove the riskiest invariant, not only the happy path?
6. Is any unrelated or accidental file mixed into the commit?
7. Would this diff make sense to a reviewer without our chat context?

## Command

Run:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD
```

For non-trivial implementation days, include:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required --run-tests
```
