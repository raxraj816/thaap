# Vision-To-Execution Traceability

## The Failure This Prevents

Long product roadmaps commonly lose their original purpose without anyone
explicitly deciding to change the vision.

The drift looks like this:

```text
vision promise
  -> broad roadmap bullet
  -> infrastructure milestone
  -> green tests
  -> phase marked complete
  -> merchant or analyst capability still missing
```

Nothing is obviously broken. The team simply measures completion at the wrong
boundary.

For THAAP, “Kafka publishes” is not the Phase 5 product promise. The promise is
that a committed payment-risk fact becomes trustworthy, freshness-aware,
rebuildable analytics and point-in-time downstream evidence. Kafka is one
enforcement component.

## Coverage Record

Each durable vision promise needs:

- stable `VP-NNN` identity
- observable product promise
- current executable evidence
- exact remaining gap
- owner phase and named slice
- primary DRI
- trigger that permits the work to start
- done evidence
- merchant or analyst demo
- review date

The remaining gap is important even when the current scope is proven. “Core
payment lifecycle complete” must not silently erase partial capture, exposure,
or integration-readiness work if those remain part of the challenger path.

## Status Is Coverage, Not Emotion

The coverage status describes evidence:

- `PROVEN_CURRENT_SCOPE`: the currently promised boundary is proven
- `PARTIAL_NEXT_OWNER`: useful truth exists and a named gap remains
- `PLANNED_OWNER_ASSIGNED`: prerequisites and ownership exist, implementation
  does not
- `INTENTIONALLY_EXCLUDED`: the capability conflicts with the declared product
  boundary and has an explicit reconsideration trigger

“Done forever” is not a valid vision status. Product capabilities deepen as
new dependencies become available.

## Phase Entry Rule

Before implementation begins:

1. Identify every `VP-NNN` the phase owns or affects.
2. Reconcile current product truth so the phase does not rebuild an existing
   feature under a new name.
3. Freeze the next observable merchant, analyst, payment, fraud, data/ML,
   security, and operational promise.
4. Name the implementation slice, DRI, trigger, demo, and exact proof.
5. Register genuine frozen-promise deferrals under `TD-NNNN`.

This is why Phase 6 changed from “Dashboard MVP” to decision intelligence and
developer product: Phase 4.5 already has a cockpit, queue, monitoring,
simulation, controls, dispute, webhook, and developer workflows. Rebuilding
those surfaces would produce activity without advancing the vision.

## Phase Closure Rule

A phase cannot close because:

- a framework is installed
- a database table exists
- Kafka, ClickHouse, Neo4j, MLflow, or an LLM responds
- unit tests are green
- a broad dashboard renders

Closure requires either:

1. the named merchant/analyst workflow works end to end with durable evidence,
   or
2. a critical payment/security/data boundary is proven and directly unlocks
   the next named product promise.

The phase matrix and closure dossier prove implementation correctness. The
vision matrix proves that the phase implemented the right product capability.
Both are required.

## Sequencing Versus Compromise

Deferring a tool or scale mechanism can preserve the vision:

- PostgreSQL projections before measured need for ClickHouse
- canonical JSON before independent consumers justify Schema Registry
- local operation before hosted multi-region deployment

Deferring a product capability without ownership compromises the vision:

- merchant underwriting with no owner
- model drift with no phase
- friendly-fraud operations hidden behind “disputes later”
- OpenAPI/SDK quality treated as polish

The distinction is whether the user outcome remains owned, triggered, and
provable.

The same distinction applies to product stages. “V1 does not integrate with a
PSP” is disciplined sequencing only when real integration retains an exact
owner, entry gate, safety progression, and customer-value proof. Without that
downstream commitment, a truthful simulator boundary can quietly become an
unintended product ceiling.

## THAAP Rebaseline Mental Model

```text
Phase 5: trustworthy data movement and rebuildable analytics
  -> Phase 6: coherent analyst and developer product
  -> Phase 7: governed model lifecycle and real-time safety
  -> Phase 8: network, graph, merchant, identity, dispute, and outcome intelligence
  -> Phase 9: agents, hosted trust, compatibility, recovery, and justified scale
  -> Phase 10: external PSP integration, merchant pilot, and measured validation
```

Every phase continues to preserve:

- payment and ledger correctness
- fraud and label lineage
- tenant/security/privacy boundaries
- explainability and human authority
- honest simulator versus external-network truth
- apprenticeship and explain-back evidence

## Executable Governance

Prose can drift. `scripts/vision-coverage-check.py` rejects:

- missing or duplicate `VP-NNN` entries
- invalid status
- missing required fields
- vague owner/trigger/done assignments
- missing merchant/analyst demo
- stale review dates
- master-plan promises not present in the matrix
- loss of the Phase 6–10 rebaseline markers or the merchant-facing platform
  destination

The checker does not decide whether a promise is strategically correct. Founder
review and hostile product review do that. The checker ensures the decided
promise cannot quietly become ownerless or disappear.

## Explain-Back

1. Why can a phase be technically successful but strategically wrong?
2. What is the difference between deferring ClickHouse and deferring merchant
   underwriting?
3. Why is “current scope proven” different from “finished forever”?
4. Why do the invariant matrix and vision matrix answer different questions?
5. Which product demo would prove Phase 5 advanced THAAP rather than merely
   installing Kafka?
6. Why must a working PSP adapter and a favorable dashboard still be
   insufficient to close the real-merchant validation phase?
