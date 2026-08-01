# Source-Reconciled Investigation And Executable API Contracts

## The Smallest Useful Mental Model

A fraud investigation is not one database row and it is not one timeline.
It is a carefully labelled composition of facts that have different owners:

```text
source domain facts      answer “what is true now?”
canonical domain events  answer “what committed, and in what business order?”
analytics projections    answer “what can I search or aggregate quickly?”
audit records            answer “who attempted or performed a sensitive action?”
webhook attempts         answer “what transport work reached a merchant endpoint?”
```

The product becomes unsafe when a dashboard flattens those records into one
unqualified list. A stale projection can look like current payment truth. A
timestamp can look like causal order. An audit entry can look like a completed
money movement. A webhook delivery can look like the underlying event happened
again.

Source reconciliation keeps the useful composition while preserving each
record's authority, time, version, freshness, coverage, and failure state.

## Why This Matters In Payment Fraud

An analyst deciding whether a payment is fraud may need to see:

- the PaymentIntent and each attempt
- processor approval or decline
- the exact feature snapshot used at decision time
- every rule/control evaluation, including non-matches and unavailable inputs
- the winning fraud action and precedence
- later review, label, dispute, refund, chargeback, and ledger evidence
- whether a webhook notified the merchant
- other same-merchant payments linked through safe identifiers

Those facts do not all become available at the same time. A decision can be
correctly stored before a label exists. A projection can lag behind the source.
A webhook can fail after the payment committed. A chargeback can arrive weeks
later without changing what the original decision knew.

The screen must therefore explain chronology without rewriting history.

## Source Truth Versus Read Model

A source table enforces business invariants. For example:

- an Authorization owns the processor outcome
- balanced LedgerEntry groups own accounting truth
- a FraudDecision owns the action taken at decision time
- a FraudLabel owns later classification evidence and availability time

A read model exists to answer a user question efficiently. It may join or
denormalize source facts, but it cannot authorize a mutation or replace the
source.

The founder-confirmed THAAP Phase 6 contract uses an API composition read
model:

1. read one payment's source facts under a bounded repeatable-read snapshot
2. read its immutable canonical stream for business ordering
3. attach active projection freshness/coverage for discovery and aggregates
4. attach separately qualified webhook, note, and insight sections
5. reauthorize every mutation against live source state

The response itself is not durable payment truth. It is an explanation of
durable facts.

## Why Stream Version Orders The Timeline

Timestamps are useful evidence, but weak identity:

- two commits may share the same microsecond
- clocks can be skewed
- an observed business time can predate database recording
- database conversion can lose precision
- a retry can preserve an original occurrence time

The payment stream already has a tenant-qualified, gap-free, monotonically
increasing version. That version expresses authoritative business order.

```text
streamVersion 7  fraud_decision.created
streamVersion 8  manual_review.opened
streamVersion 9  manual_review.assigned
```

The UI still shows `occurredAt` and `recordedAt`, but it sorts and paginates by
stream version. A cursor freezes the highest visible version so later appends
cannot make an older traversal skip or duplicate entries.

## Complete Decision Explanation

“Rule X fired” is not a complete explanation. The useful structure is:

```text
decision inputs
  -> point-in-time feature values and availability
  -> every evaluated control and version
  -> matched / not matched / unavailable / failed-safe outcome
  -> proposed action from each control
  -> deterministic precedence
  -> final winning action and reasons
```

Non-matches matter because they answer “why did a stronger control not win?”
Unavailable evidence matters because dependency failure must not masquerade as
a clean non-match. Version lineage matters because rerunning today's rules
would answer a different historical question.

Until a governed model exists, the honest model explanation is
`NOT_CONFIGURED`, not score zero and not an empty unexplained panel.

## Safe Related-Payment Pivots

Analysts often pivot on customer, card fingerprint, device, session, email, or
IP. Accepting a raw value in an investigation search creates several risks:

- sensitive input can leak into URLs, logs, analytics, or browser history
- a user can probe whether another tenant has the value
- normalization differences can create inconsistent matches
- key rotation can make old and new HMAC representations look unrelated

The safer pattern starts from an already authorized seed payment:

```text
authorized seed payment
  -> server loads persisted tenant-scoped HMAC aliases
  -> server searches only the same tenant and bounded time window
  -> response returns relation reason codes, never raw values or HMACs
```

Normalized anchor rows make key aliases queryable and preserve exact source
lineage. They are not fraud features and do not cross merchants. Phase 8 must
separately solve consent, anonymity, contribution limits, opt-out, deletion,
and reconstruction resistance before network intelligence exists.

## Why Bulk Actions Need Durable Item Truth

A 25-item assignment request can partially succeed because:

- another analyst assigns one case first
- a case resolves while the batch is running
- the initiating or target actor is disabled
- the process crashes after item 12
- a lease expires and another worker takes over

Returning one boolean for the batch would be false product truth. The durable
model needs one request plus immutable item outcomes. Every item rechecks live
authorization and expected version at the mutation boundary.

Bulk scope is intentionally narrow. Assignment distributes work; it does not
classify fraud or move money. Resolution, labels, cancellation, refunds,
disputes, and control activation remain case-specific approved workflows.

## Denominator-Honest Insights

A percentage without its denominator is an attractive ambiguity.

Examples:

```text
label coverage = labelled eligible payments / eligible payments
review yield = confirmed-fraud labelled resolved reviews
               / resolved reviews with a known label
```

If the denominator is zero, the answer is `NOT_COMPUTABLE`, not 0%. If the
projection is partial, counts may be qualified but money and dependent rates
may need to be withheld. Currency amounts are never summed across currencies.

Queue productivity and outcome analytics also have different time authority:

- current review facts can answer workload at a source `asOf`
- event projections answer outcome trends at a projection cutoff/freshness

The UI must show those separately rather than invent one shared “last updated.”

## Executable API Contract

Documentation becomes reliable when it is generated, diffed, and exercised:

```text
controller and schema behavior
  -> generated OpenAPI
  -> lint + semantic compatibility diff
  -> generated client operations/models
  -> supported transport/auth wrapper
  -> clean-environment quickstart
```

An executable contract still needs human product decisions:

- which routes are public
- which credential applies to each operation
- which mutations require idempotency
- which error codes are stable and retryable
- how versions are selected and retired
- which examples are safe

Generated OpenAPI cannot discover those intentions automatically. It can make
drift visible after they are declared.

## API Versions And Errors

A version header makes the resolved contract visible without duplicating every
route. The initial Phase 6 contract uses a date version and returns it on every
success or error.

A stable error code lets a client decide behavior without parsing prose:

```text
payment_intent_not_confirmable
  status: 409
  retryable: false until source state changes
  recovery: fetch the PaymentIntent and reconcile its lifecycle
```

The human message can improve without breaking clients. Database messages,
stack traces, credentials, and sensitive row values remain excluded.

## SDK Security Boundary

A generated SDK is still a protocol client and must be reviewed like one.
Important boundaries include:

- exact base URL and allowed schemes/hosts
- redirect behavior
- ambient proxy behavior
- connect/read timeouts
- unknown mutation outcomes
- stable idempotency identity across retries
- credential header separation
- safe error/repr/log behavior
- exact webhook body bytes before parsing

Generated operations/models reduce contract drift. A small handwritten wrapper
owns these security choices explicitly.

## Failure Modes To Remember

- one screen section times out while the rest is valid
- a projection generation changes during pagination
- a timeline append arrives between pages
- a related-anchor key alias is missing
- a saved filter schema changes
- a bulk worker loses its lease after the source lock
- a rate denominator is empty or partially observed
- an internal endpoint leaks into public OpenAPI
- a known domain conflict collapses into generic `409 conflict`
- an SDK follows a redirect with a credential
- a webhook verifier parses JSON before checking the exact signature bytes
- a browser stores credentials while implementing deep links

Each is a boundary family, not one test case.

## Proof Boundaries

- Unit/property tests: filter grammar, event mapper, precedence explanation,
  cursor signing, rate partitions, API version/error catalogues
- PostgreSQL: repeatable-read snapshot, tenant/source foreign keys, retained
  anchor backfill, immutable triggers, bulk leases/fencing and lock order
- Spring Batch: crash/restart, item partial completion and stale-worker proof
- Browser: routes, focus, memory-only secrets, safe links and independent
  loading/empty/partial/stale/denied/conflict states
- Real HTTP reference client: redirects, proxy environment, timeouts,
  idempotent unknown outcomes, error mapping and exact webhook bytes
- Contract tooling: generated/committed/served agreement and breaking-change
  rejection

## What Phase 6 Intentionally Does Not Prove

- active model quality or model influence
- cross-merchant network/graph intelligence
- hosted identity or production browser sessions
- deployed multi-version compatibility
- public package distribution
- real PSP integration
- causal prevented-fraud or profit lift

Those remain named Phase 7–10 responsibilities. Keeping them explicit prevents
a strong simulator/developer demo from becoming a false production claim.

## Explain-Back

1. Why can a projection help find a payment but not authorize its refund?
2. Why is stream version safer than timestamp for timeline order?
3. Why must unavailable control evidence differ from a non-match?
4. Why does a safe related-payment API start from a seed payment?
5. Why is one success boolean insufficient for a bulk assignment?
6. What makes an OpenAPI contract executable rather than decorative?
7. Which SDK behavior protects an idempotent mutation with an unknown result?
