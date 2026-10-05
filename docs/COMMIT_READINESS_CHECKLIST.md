# Commit Readiness Checklist

The common readiness gate now always validates the current matrix/dossier registered in
`docs/reviews/CURRENT_READINESS_SCOPE.json`; missing selectors do not mean trivial work.
Explicit selectors must match this current registration. A local recovery slice does not
close its parent phase: retain all unexecuted cloud promises and the owner's launch gate.

Governance review dates use the Asia/Kolkata calendar on all hosts, resolved from an aware
current instant by `scripts/review_calendar.py`. UTC runners must not backdate evidence or
waive future/stale checks. `--as-of` remains an explicit historical replay option, not the
default clock source. This policy does not change payment timestamps or UTC insight windows.

Use this before saying a change is ready to commit.

## Required Checks

- `git status --short` reviewed.
- `git diff --stat` reviewed.
- Root `README.md` checked for stale status, commands, and documentation links.
- Service README checked when a service changed.
- `docs/IMPLEMENTATION_MASTER_PLAN.md` checked when scope, sequencing,
  invariants, or roadmap changed.
- `docs/VISION.md` and `docs/THAAP_VISION_COVERAGE_MATRIX.md` reviewed for
  every affected `VP-NNN`; owner phase/slice, DRI, trigger, product demo, done
  evidence, and review date remain concrete, the V1 simulator remains a
  proving ground rather than THAAP's final destination, and
  `scripts/vision-coverage-check.py` passes.
- `docs/TECHNICAL_DEBT_REGISTER.md` updated for every accepted or deferred
  technical, implementation, security, data/ML, or operational debt.
- The chapter invariant-proof matrix was drafted from the frozen plan and
  failure sequences, then updated with exact implementation and proof.
- The matrix records honest provenance. `RETROACTIVE_RECOVERY` is never
  rewritten as `PRE_IMPLEMENTATION`.
- The matrix records `Boundary family review: PASS`; escaped grammar, numeric,
  concurrency, dependency, migration, and authorization findings have
  partition proof plus a real-boundary regression.
- `scripts/invariant-proof-check.py` passes with every required lens and entry
  at `PASS`.
- `scripts/escaped-review-check.py` passes, and every blocking-severity escaped
  finding for the chapter is resolved with product and process evidence.
- `scripts/executable-artifact-naming-check.py` passes. Executable artifacts use
  durable capability names rather than temporary chapter identifiers.
- The chapter closure dossier passes `scripts/chapter-closure-check.py` and
  reconciles README and master-plan status.
- Every custom infrastructure boundary has an accepted ADR and protocol
  boundary table; otherwise the mature component is used.
- `scripts/technical-debt-check.py` passes with no missing fields, vague owners,
  vague resolution targets, stale reviews, or dangling debt references.
- Daily learning note exists for non-trivial implementation days.
- Deep dive exists for non-trivial implementation days.
- Relevant tests were run. Non-trivial readiness executes backend and
  repository Python suites automatically.
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
10. Does every true deferral have a registered `TD-NNNN` ID, exact owner slice,
    DRI, trigger/deadline, done criteria, and master-plan references?
11. Can every frozen plan promise be traced to a production boundary and an
    adversarial proof in the invariant matrix?
12. Does each concurrency test force the dangerous ordering and prove the lock
    or state boundary, rather than merely starting threads together?
13. Do protocol tests cover authenticated identity, exact bytes, representation
    edges, persistence precision, and the documented reference client?
14. Is custom HTTP, crypto, queueing, scheduling, or concurrency machinery
    justified by an accepted ADR rather than implementation convenience?
15. Are all `P0`/`P1` and frozen-promise `P2` findings fixed and proven?
16. Does the escaped-review register explain why prior review missed each
    escaped finding and what process changed?
17. Does the closure dossier reconcile promises, evidence, debt, demo,
    operations, and merge recommendation without contradicting source docs?
18. Did every escaped finding reopen and prove its entire boundary family
    instead of adding only the observed regression example?
19. Which `VP-NNN` product promises does this change advance, and does its
    merchant/analyst demo prove product leverage rather than infrastructure
    activity alone?

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
- **Forced-interleaving review:** Do production-database tests place each
  conflicting transaction at the dangerous boundary, prove lock ownership, and
  assert the durable postcondition for both orderings?
- **Protocol-boundary review:** Do tests distinguish authenticated body fields
  from advisory headers, original host identity from resolved addresses, and
  serialized precision from database precision?
- **Reference-client review:** Do READMEs, fixtures, scripts, and demos teach
  exactly the secure integration contract that the server enforces?
- **Stripe/Mastercard reviewer pass:** What would a payments/fraud reviewer
  challenge first, and is the answer implemented, tested, documented, or
  explicitly deferred?
- **Cheap-fix pull-forward:** Fix valid low-cost comments before handoff instead
  of leaving obvious review work for someone else.
- **Deferral discipline:** Every true deferral names the exact reason, owner
  chapter or implementation slice, and evidence that would unblock it; every
  accepted debt is registered under an immutable `TD-NNNN` ID.
- **Vision coverage:** Every affected `VP-NNN` retains current evidence, an
  exact next owner, and a product demo; already-proven work is extended rather
  than rebuilt under a later phase name.

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
- `Invariant matrix: VERIFIED`
- `Traceability pass: PASS`
- `Hostile failure-sequence pass: PASS`

## Command

Run:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD
```

For non-trivial implementation days, include:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required \
  --invariant-matrix=docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md \
  --closure-dossier=docs/reviews/CHAPTER_N_CLOSURE_DOSSIER.md
```

For non-trivial work, omitting either current artifact fails the gate. This
prevents an old verified matrix or dossier from masking an incomplete current
chapter. `--deep-dive-required` also runs both test suites; `--run-tests` is
only needed when a trivial invocation should run them too.
