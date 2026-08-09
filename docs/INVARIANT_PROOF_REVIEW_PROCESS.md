# Invariant-Proof Review Process

This is THAAP's completion protocol for every non-trivial chapter or
implementation slice. A green suite is necessary, but it is not sufficient.
Completion means the planned product truth has been traced to implementation
and adversarial proof.

## Why This Process Exists

Chapter 19 exposed five repeatable review failures:

1. Tests were selected from the implementation that existed instead of from
   every invariant and failure sequence in the frozen plan.
2. Concurrent tests started threads together but did not force the dangerous
   lock ordering, so they proved a likely schedule rather than every critical
   postcondition.
3. Documentation and the callback fixture were reviewed as prose and tooling,
   not as security-sensitive reference clients.
4. Protocol boundaries were tested on common values but not on representation
   edges such as hostname-versus-resolved-address identity or database timestamp
   precision.
5. Chapter completion was declared from test count and broad review passes
   before a line-by-line plan-to-proof reconciliation was durable.
6. Escaped findings were repaired as individual examples without reopening the
   full equivalence class of malformed inputs, numeric conversions, or
   interleavings that produced them.

The correction is a proof system, not another reminder.

## Required Artifact

Create `docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md` before implementation
for every non-trivial chapter. Start it with `Scope status: DRAFT` and
`Review status: IN_PROGRESS`. Record `Matrix provenance: PRE_IMPLEMENTATION`.

**Commit it in that state.** Every row starts at `Status: PENDING`, and the three
review verdicts and the escaped-review assessment start at `PENDING` too; the
checker accepts all of them while `Review status: IN_PROGRESS`. `Promise freeze`
and `Failure inventory` are required from the first commit in every state,
because they are inputs to drafting rather than outputs of implementation — a
matrix drafted without them was reconstructed from something, and the only thing
available to reconstruct from is the code.

A `PENDING` row names the proof it intends to build, so that proof does not have
to exist yet; the `ER-0099` rule that a row must name an artifact that exists
applies from the moment the row closes. `PENDING` is rejected at phase closure
alongside `PARTIAL`, a matrix holding one may not claim `Review status:
VERIFIED`, and `scripts/chapter-closure-check.py` refuses any dossier whose
matrix is still `IN_PROGRESS` or still holds an unproven row.

This exists because it did not. Until `ER-0105` the checker had no way to express
a frozen, unproven matrix: rows had to be `PASS` or `PARTIAL` and every verdict
had to be `PASS`. A genuinely pre-implementation matrix therefore could not be
committed, so it lived in an uncommitted working tree where nobody could review
it and where a matrix written afterwards was indistinguishable from one written
first — the precise substitution this process forbids.
Chapter 19 is the one explicitly recorded legacy recovery case and uses
`RETROACTIVE_RECOVERY`; that label is evidence of the process failure, not an
exception future chapters may copy. The matrix must cover these lenses:

- `PAYMENT_FRAUD`
- `TENANT_ISOLATION`
- `SECURITY_PRIVACY`
- `TRANSACTION_CONCURRENCY`
- `DATA_ML_LINEAGE`
- `API_PRODUCT`
- `OBSERVABILITY`
- `OPERATIONS_RESILIENCE`

An unaffected lens still needs an explicit invariant, failure sequence, and
proof of non-impact. Silence is not a review disposition.

Each invariant entry must name:

- the invariant in observable product language
- the concrete failure sequence that could violate it
- the production boundary that enforces it
- the automated proof, including the test method or executable check
- the adversarial evidence that distinguishes the proof from a happy path
- a final `PASS` status

The matrix becomes `FROZEN` once scope and failure sequences are agreed. A scope
change reopens it before code changes. Set `Review status: VERIFIED` and
`Fresh hostile review: PASS` only after the two review passes below. Do not label
a self-review as independent. When an organizationally independent reviewer is
available, record that reviewer separately in the handoff; the mandatory gate
asserts a temporally separate fresh hostile pass, not a false independence
claim.

Required completion metadata is:

- `Matrix provenance: PRE_IMPLEMENTATION`
- `Promise freeze: CONFIRMED`
- `Failure inventory: COMPLETE`
- `Traceability review: PASS`
- `Boundary family review: PASS`
- `Custom infrastructure review: PASS` or `NOT_APPLICABLE`
- `Escaped review assessment: COMPLETE`

## Execution Sequence

### 1. Freeze Product Truth

Read the master plan, architecture, API contract, schema, debt register, and
existing implementation. Translate every promise into an observable invariant.
Record exact deferrals with their `TD-NNNN` ownership before coding.

### 2. Pre-Mortem Every Boundary

For each invariant, write the shortest sequence that makes it false. Include:

- transaction rollback and partial-write sequences
- lease expiry, retries, stale workers, and kill-switch ordering
- cross-merchant object substitution
- secret, PII, and payment-data disclosure
- malformed and ambiguous protocol representations
- database precision, serialization, migration, and replay differences
- process crash, timeout, DNS change, and dependency failure
- analyst or merchant behavior induced by documentation and fixtures

This sequence drives the test. Do not derive the sequence after implementation.

### 3. Make Concurrency Proof Deterministic

For every shared mutable state transition:

1. Document a global lock order.
2. Enumerate both orderings of every conflicting transition.
3. Use barriers or latches to place transactions at the dangerous boundary.
4. Prove the intended lock is actually held when the competing transaction
   starts; PostgreSQL `NOWAIT` lock probes are acceptable test evidence.
5. Assert the durable postcondition and absence of claimable/duplicate work.
6. Run the proof on the production database, not only H2 or mocks.

Simultaneous thread start is a stress test, not a forced-interleaving proof.

### 4. Test Protocols As Cross-Product Boundaries

For every external or persisted protocol, build a boundary table covering:

- authenticated versus unauthenticated fields
- exact bytes versus parsed values
- header/body identity agreement
- hostname, IP literal, resolved address, port, and TLS identity
- minimum/maximum length, malformed syntax, Unicode, and case behavior
- request-target percent encoding and existing-escape stability
- database precision versus serialized precision
- retry/replay stability
- reference documentation and fixtures

Executable fixtures must reject unsafe examples. A reference client that teaches
an insecure integration is a product defect even when the server is correct.

### 4A. Expand Every Escaped Finding To Its Boundary Family

An escaped finding invalidates the proof for its whole input family, not only
the observed example. Before changing production code:

1. Name the equivalence class: grammar component, numeric conversion,
   transaction ordering, dependency result, migration state, or authorization
   substitution.
2. Enumerate every valid and invalid partition for that class.
3. Add generative, property-style, or table-driven proof across the partitions,
   plus a real-boundary regression for the observed failure.
4. Inspect adjacent conversions and state transitions that share the same
   parser, representation, lock, or configuration source.
5. Record `Boundary family review: PASS` only when the family proof passes and
   the matrix links it to the frozen promise.

For migrations, include every legacy enum/string domain that lacked a database
constraint and prove rejection occurs before the first DDL statement.

Example-by-example regression tests are necessary but cannot close an escaped
protocol, concurrency, or configuration finding.

### 5. Use A Proof Ladder

Use the least expensive layer that can prove each claim, but do not substitute a
lower layer for a production-boundary claim:

1. pure unit and state-machine tests
2. service contract, rollback, tenant, and privacy tests
3. schema constraints and forward/upgrade migration tests
4. forced PostgreSQL concurrency tests
5. real socket/HTTP protocol tests
6. public-API demo with durable-state reconciliation

Test names should state the invariant and failure outcome, not an implementation
detail.

### 6. Perform Two Separate Review Passes

**Traceability pass:** walk every frozen matrix entry from plan to production
boundary to proof. A test count is never evidence for an untraced invariant.

**Hostile pass:** after focused tests pass, start from the frozen failure
sequences and a fresh diff without reusing the implementation narrative. Ignore
the happy path and attack transaction order, stale state, authenticated-data
boundaries, representation conversion, tenant substitution, failure recovery,
resource exhaustion, and operational visibility. Review docs, scripts,
fixtures, migration backfills, and configuration as product code.

The hostile pass must happen after focused tests pass, using a fresh diff and
without relying on the implementation author's earlier conclusions.

### 7. Gate Completion

Run:

```bash
scripts/invariant-proof-check.py
scripts/escaped-review-check.py
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required \
  --invariant-matrix=docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md \
  --closure-dossier=docs/reviews/CHAPTER_N_CLOSURE_DOSSIER.md
```

The explicit matrix argument is mandatory for non-trivial work. Validating old
matrices without naming the current one is not completion evidence.
`--deep-dive-required` automatically executes both backend and repository
Python tests.

The chapter cannot be marked complete when:

- a matrix field or required lens is missing
- any invariant is not `PASS`
- scope is not `FROZEN`
- either review pass is incomplete
- the boundary-family review is absent or incomplete
- an accepted deferral lacks a valid `TD-NNNN`
- a concurrency claim has only timing-dependent thread-start evidence
- protocol documentation or fixtures contradict authenticated server truth
- matrix provenance is absent or falsely claims pre-implementation preparation
- an unresolved `P0`/`P1`, or `P2` affecting a frozen promise, exists
- custom infrastructure lacks an accepted ADR and protocol boundary table
- the closure dossier, README, and implementation master plan disagree about
  Chapter status

### 8. Record Escaped Review Quality

When a finding survives an earlier review or completion claim, register it in
`docs/ESCAPED_REVIEW_REGISTER.md`. Do not hide it in chat or count it only as
another patch. Classify the primary reason the process missed it:

- `MISSING_PROMISE`
- `MISSING_FAILURE_SEQUENCE`
- `WEAK_PROOF`
- `IMPLEMENTATION_DEFECT`
- `DOCUMENTATION_OVERCLAIM`

Record severity, affected frozen promise, failed gate, resolution evidence, and
the process correction. Repeated categories must change the process or its
executable gate. The register measures review-system quality; it is not a
developer scorecard.

Register the finding before fixing it. A blocking `OPEN` or `IN_PROGRESS`
finding automatically invalidates any `READY_TO_MERGE`/`MERGE` dossier through
the closure checker. Reconcile README and master-plan status before making
another completion claim.

### 9. Produce A Closure Dossier

Complete `docs/reviews/CHAPTER_N_CLOSURE_DOSSIER.md` from
`docs/reviews/CHAPTER_CLOSURE_DOSSIER_TEMPLATE.md`. It is the final
chapter-level claim and must contain:

- observable promises and hostile sequences
- boundary-family partitions with generative/property and real-boundary proof
- production enforcement boundaries
- exact automated proof and results
- migration and demo evidence
- operational signals
- known debt and severity disposition
- custom-infrastructure decision
- escaped-review assessment
- README/master-plan reconciliation
- explicit `MERGE` or `DO_NOT_MERGE` recommendation

The dossier does not replace the matrix. The matrix is claim-to-proof
traceability; the dossier is the integrated release argument.

## Reviewer Handoff

The final handoff names:

- the highest-risk invariant and its forced failure sequence
- production enforcement and exact proof
- test and demo commands with results
- every fixed review finding
- every accepted deferral by `TD-NNNN`
- whether traceability and hostile passes passed

This turns review memory into repository state. The process is successful when a
future reviewer can challenge the design without needing the original chat.
