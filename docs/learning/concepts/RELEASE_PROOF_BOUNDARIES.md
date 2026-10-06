# Release proof boundaries

A running container proves startup, not a working payment-risk product. A useful release
claim names the promise, its enforcement boundary, the failure sequence and the observed
postcondition. Each stronger claim needs its own proof:

| Claim | Appropriate evidence | Cannot substitute |
|---|---|---|
| Payment state/tenant truth | Real PostgreSQL transaction and concurrency tests | Mock repository |
| HTTP route/credential preservation | Real proxy sockets plus representation partitions | Matching prefix strings |
| Broker durability | Remove/replace container, retain records and committed offsets | Mounted volume declaration |
| Memory sizing floor | Observed JVM options under actual container memory ceiling | Unconstrained laptop startup |
| Terraform evaluation | Mocked computed-value plans and provider validation | Formatting alone |
| Deployed exposure | Allowed and genuinely external denied probes against AWS | Mocked WAF plan |
| Qualified insight | Published freshness, coverage and aligned time window | Application readiness |
| Rule promotion | Real population and workflow labels satisfying unchanged policy | One successful simulation |

Proof identity must be independent: compare credentials against a pre-mutation copy, not
against the same object the handler mutated; derive published route coverage from OpenAPI,
not four equally incomplete routing lists. Cleanup may touch only owned proof resources and
must refuse occupied namespaces before registering a destructive exit trap.

Historical evidence keeps its date. The October 6 audit's local repair matrix does not close
Phase 6.5's integrated AWS matrix. A completed-day insight window cannot include today's new
fixtures; report that limit instead of weakening the time contract or fabricating timestamps.

Explain back: why can startup, formatting and 100 unlabelled payments all be green while the
promised release remains unproven?

Readiness must select current proof on the machine path. CI/pre-push argument omission may
not silently select historical matrices or skip the dossier. Register the exact scope and
reject stale selectors. A completed local recovery slice remains distinct from its open
deployment parent; changing a coordinate cannot manufacture missing cloud evidence.
