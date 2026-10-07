# THAAP Final Research: Coverage And Reconciliation

**Reviewed 6 October 2026 — research-to-plan coverage, not implementation proof.**

The founder has clarified that [THAAP_TRUST_AUTHORIZATION_RESEARCH](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.md)
is the final research document. Its [63-page PDF](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.pdf)
and 2,397-line Markdown export form the primary research baseline. The shorter
23-page blueprint is supporting background where compatible. Existing accepted
ADRs and API contracts still require explicit reconciliation before code changes;
source samples do not automatically become production contracts.

This pass reread all 2,397 Markdown lines, including the implementation constraints,
mandatory scenarios and final definition. The previous review extracted the whole
PDF and compared its text; its bibliography and missing Markdown closing sentence
remain preserved in the PDF. The file has 2,396 newline characters and no final newline; its last logical
line is 2,397. Earlier notes used the newline count. This pass did not independently validate every cited
external source, execute source code samples, or repeat an application audit.

The [strategy](THAAP_WINNING_STRATEGY.md) is a synthesis, not a substitute for this
source. The [assessment](2026-10-06-RESEARCH_ASSESSMENT.md) records the detailed
safety corrections. Line coordinates below refer to the preserved Markdown export.

The [7 October implementation handoff](THAAP_PRODUCT_IMPLEMENTATION_HANDOFF.md)
resolves the current planning choices without dropping this inventory. In
particular it selects the new trust decision mapping, exact money units, existing
merchant boundary, Neo4j/OPA demonstration and execution/recovery semantics.
Rows marked Reconcile still require their concrete ADR/schema/API proofs at
implementation kickoff; they no longer mean those product choices are undecided.

## Corrections found in this cross-check

1. **Research precedence was previously unresolved.** The founder has now resolved
   it in favor of the long trust/authorization report. The short report's omission
   of execution connectors must not remove the final report's enforced local flow.
2. **The graph was sequenced too loosely in the strategy.** The final source requires
   Neo4j and OPA within the first complete supplier demonstration. Retain that
   target: a bounded, tenant-scoped, temporal projection with rebuild and lag proof,
   not a production GNN. PostgreSQL remains authoritative. This changes the proposal,
   not a historical chapter's status or an already accepted runtime contract.
3. **Several agent requirements were compressed into generic authority language.**
   Explicitly preserve proof of key possession, full delegation attenuation,
   material-change reauthorization, cumulative incremental limits, goal completion,
   tool identity/version, cost/retry breakers and inter-agent confused-deputy tests.
4. **Mandatory source scenarios lacked a complete synthesis checklist.** All 11
   are mapped below. Two flagship stories demonstrate the product; they do not
   replace the full acceptance suite.
5. **Operational detail needs an explicit handoff.** Policy lifecycle, per-dependency
   degradation, signing failure, observability, API/event compatibility and source
   performance targets are now mapped to owning slices and required evidence.

These are strategy coverage corrections. No affected capability is declared
implemented, tested, deployed or exempt from the repository proof process.

## Complete source section inventory

| Source lines | Section | Treatment |
|---|---|---|
| 1–87 | Executive summary and mandatory design rules | Product thesis and foundation/authority rows below |
| 88–267 | Position, workflow, vocabulary, trust dimensions and operating model | Product/contract and authority rows |
| 268–783 | Five trust primitives, continuous intent, mandates and replay | Trust/graph, authority and replay rows |
| 784–1138 | Persistence, REST, permits, errors and events | Contract, execution and consistency rows |
| 1139–1603 | Runtime, policy, economic controls and ML | Policy, authority, execution and AI rows |
| 1604–1949 | Threats, identity, privacy, telemetry, SLOs and deployment | Security and operational rows |
| 1950–2343 | Roadmap, acceptance, test pyramid, scenarios and build constraints | Proof inventory, scenario map and sequencing corrections |
| 2344–2397 | Final V1 definition | Final capability checklist below |

## Proposed owning slices

These are named planning responsibilities to map into the canonical master plan
at implementation kickoff, not new chapter numbers or claims of completed work.
The AI CTO working role owns technical mapping/proof; the founder owns business
scope and deployment decisions. Security/operations reviewers need named humans
before live rollout.

| Slice | Responsibility and dependency |
|---|---|
| Contract reconciliation | Map final research into ADRs, current tenant/API semantics and the implementation plan before changing runtime promises |
| Trust and verification | Authenticate evidence sources; preserve account/contact versions, two times, provenance and independent verification |
| Supplier decision and policy | Four-action trust vocabulary, OPA, obligations, investigator workflow and governed policy lifecycle |
| Temporal graph and replay | Neo4j projection/rebuild, point-in-time features, decision artifacts and exact/hypothetical replay |
| Execution and exposure | One-time authority, durable dispatch, amount/goal reservations, reconciliation, recovery and fake executor |
| Agent authority | Structured intent, actor keys, mandates, attenuation, continuous authorization and economic breakers; extends the complete supplier demonstration |
| Model and investigation assistance | Governed datasets/features/models, shadow evaluation and bounded GenAI assistance when evidence supports the experiment |
| Protocol adapters | Version-pinned standards integrations with conformance fixtures and preserved internal semantics |
| Production trust and operations | Security/privacy, dependency behavior, telemetry, measured performance and recovery proof before live rollout |

## Requirement coverage

**Retain** means retained target behavior, not implemented. **Correct** means retain
the purpose with the stated safety correction. **Reconcile** means an explicit
contract/ADR choice is required. **Sequence** names the dependency for later
adoption; it does not erase a source requirement. Real debt against an accepted
runtime promise must enter the canonical TD/ER process, not this table.

| ID / source lines | Research requirement | Disposition and owner | Required proof or design decision |
|---|---|---|---|
| RC-01 / 3–144 | Independent economic authorization; B2B beneficiary-change first; no rail/wallet/ERP replacement | Retain — Contract reconciliation | Distinguish simulation, advisory and enforced paths; exact customer release boundary; supplier flow before agent extension |
| RC-02 / 145–217 | ALLOW, CHALLENGE, REVIEW, DENY; no ALLOW_WITH_WARNING; reasons, obligations and distinct trust dimensions | Reconcile — Supplier decision and policy | Preserve existing card BLOCK/REQUEST_3DS API; no permit with outstanding required obligation; calibrated probability separate from factual evidence |
| RC-03 / 218–267 | Principal, agent, credential owner and final authority are distinct | Retain — Agent authority | No credential inheritance; principal binding and task scope; meaningful step-up without approval spam |
| RC-04 / 268–415 | Selective event sourcing; append-only trust events, superseding correction, occurred/ingested times and source provenance | Correct — Trust and verification | Authenticated attesters, allowed fact types, duplicates/conflicts, backdated events and privacy-safe correction/retention; payload assertions are not verification |
| RC-05 / 416–504, 840–865 | Neo4j derived via outbox; tenant-scoped nodes/edges, temporal features and rebuild | Retain — Temporal graph and replay | Include bounded projection in first complete demo; no PG/graph dual-write; idempotent replay/rebuild, temporal cutoffs and stale graph cannot conceal critical changes |
| RC-06 / 505–591 | Approved/versioned canonical intent; intent differs from plan and action | Retain — Agent authority | Exact currency/amount/counterparty/destination/invoice/rail/time/count constraints; untrusted language extraction never signs or grants authority |
| RC-07 / 592–615 | Material changes trigger fresh authorization; completed goal loses authority | Retain — Agent authority + Execution and exposure | Full trigger list: amount, destination, counterparty, product/category, currency, rail/instrument, downstream actor, tool/action, material failure, cumulative increments, policy/mandate revocation, completed goal, worsened risk |
| RC-08 / 616–716 | Signed mandates, issuer/audience/expiry/nonce, key possession, parent chains and attenuation | Correct — Agent authority | Mature crypto; bind verified request key to mandate; child cannot widen any action/value/aggregate/counterparty/time/rail/data/depth dimension; root/sibling limits atomic, not merely checked at issuance |
| RC-09 / 717–733 | AP2, Visa TAP, Mastercard, OAuth RAR, AuthZEN and Stripe adapters | Sequence — Protocol adapters | Inventory retained; pin spec and conformance fixtures for each adopted adapter; preserve principal/action lineage; native delegation is not automatically AP2 conformance |
| RC-10 / 734–783 | Ledger for every authorization result, exact and counterfactual replay | Correct — Temporal graph and replay | Retain underlying permitted inputs/artifacts, not hashes alone; denied domain decisions durable; unknown-tenant/malformed ingress uses separate safe audit path, not attacker-chosen tenant records |
| RC-11 / 788–839 | Typed relational core, tenant keys, UUIDs, evidence JSON, exposure, cases and registries | Reconcile — Contract reconciliation | Review entire source table inventory; existing merchant mapping; tenant-composite references and money precision; migrations/constraints before implementation; indexes/partitioning justified by workload |
| RC-12 / 866–1067 | Versioned REST lifecycle, idempotency, authenticated tenant, exact action and permits | Correct — Contract reconciliation + Execution and exposure | Map every source endpoint; immutable destination version; benign retry versus malicious replay; proof of possession; trace input not authority; short-lived permit bound to exact execution audience |
| RC-13 / 1068–1084 | Structured problem responses and non-disclosing errors | Reconcile — Contract reconciliation | Preserve existing API compatibility; reasons useful to authorized users without exposing relationships, thresholds, tokens or model internals |
| RC-14 / 1085–1138 | Versioned event envelope/topics, schemas, outbox and idempotent consumers | Retain — Trust and verification | Use existing accepted event-byte semantics; catalog all proposed event families; optional/enum/breaking/unit changes tested; tenant-safe partitioning, deduplication and correction ordering |
| RC-15 / 1139–1205 | Ordered decision pipeline; hard constraints cannot be relaxed downstream | Retain — Supplier decision and policy | Domain failure paths and decision precedence; atomic final exposure check closes race after enrichment; no ML/OPA result can override invalid authority |
| RC-16 / 1206–1298 | OPA verified inputs, immutable bundle revision, no provider/crypto discovery in policy | Correct — Supplier decision and policy | Typed complete input and explicit missingness; source sample negation must not imply positive trust; deterministic precedence and policy-error behavior; ADR for OPA integration |
| RC-17 / 1299–1316 | Lint/test/replay/review/sign/shadow/activate/monitor/rollback policy lifecycle | Retain — Supplier decision and policy | Reject tampered bundles; archive exact artifacts and approval; scoped deployment delta, rollback and policy-revocation effect on outstanding permits |
| RC-18 / 1317–1376 | Principal/mandate/agent/task/counterparty/beneficiary/rail/tool circuit breakers and concurrent exposure | Correct — Execution and exposure + Agent authority | Pending plus completed value and goal slots; deterministic locks; retry, incremental, rate and paid-API loop controls; TTL cannot release exposure after uncertain dispatch |
| RC-19 / 1377–1446 | Payment, behavior, beneficiary-change, agent and optional intent-drift models | Sequence — Model and investigation assistance | Preserve model-family inventory; implement only against adequate data and baseline; model separation need not mean five premature services; learned scores do not grant authority |
| RC-20 / 1447–1555 | Shared online/offline feature specification, temporal data, training pipeline and model record | Retain — Model and investigation assistance | Identity/counterparty/payment/graph/agent/intent/economic families; time/label leakage checks, immutable artifacts, calibration, reproducible data and segment evaluation |
| RC-21 / 1556–1575 | Drift/availability/missingness and human promotion | Retain — Model and investigation assistance | Mature labels, uncertainty and selection bias; missing evidence not zero risk; rollback and deterministic fallback; no fabricated loss-prevention metric |
| RC-22 / 1576–1603 | Candidate intent, extraction, summaries and query suggestions; no LLM authorization | Retain — Model and investigation assistance | Allowlisted read tools and field/tenant boundaries; evidence citations, abstention, prompt injection tests; cannot mark verification, consume permits or invoke payment rails |
| RC-23 / 1604–1635 | Full fraud, agent, graph, model, supply-chain, exfiltration, insider and bypass threat model | Retain — Every adopted slice | Translate each threat row into failure sequences; include malicious memory/messages, tool identity/version, graph poisoning, approval fatigue and ledger tampering, not only transaction fraud |
| RC-24 / 1636–1672 | Human SSO/MFA, distinct workload identities, agent key lifecycle and managed signing | Retain — Agent authority + Production trust and operations | Role/attribute boundaries, short sessions, key enrollment/rotation/revocation, in-flight key change; KMS/HSM signing and no exportable private keys in app DB |
| RC-25 / 1673–1696 | PAN/SAD-free default; payment-token references and actual PCI scope review | Retain — Production trust and operations | No raw PAN/CVV/track/PIN/wallet secrets in APIs, logs or artifacts; tokenization is not a scope certification; assess selected deployment before claims |
| RC-26 / 1697–1732 | NIST governance mappings, tenant region/retention, no default cross-tenant graph | Retain — Production trust and operations | Map governance to evidence; enforce region placement/export/replication boundaries; data rights and deletion across artifacts; no certification from framework naming |
| RC-27 / 1733–1772 | Tracing across decision stages; authorization/policy/ML/graph/event/exposure/security/business/reliability metrics | Retain — Production trust and operations | Trace correlation and redaction; source/graph lag, outbox backlog, reservation contention, signing failures, unknown outcomes and actionable alert/runbook ownership |
| RC-28 / 1773–1794 | Availability/latency/freshness/durability/RTO/RPO engineering targets | Reconcile — Production trust and operations | Preserve complete source target table as benchmark inputs, not commitments; synchronously durable decisions; backup RPO cannot justify resurrecting spent authority; recovery fencing/reconciliation |
| RC-29 / 1795–1807 | Defined OPA/PG/Redis/graph/ML/Kafka/verifier/KMS failure behavior | Retain — Production trust and operations | Test each dependency; explicit last-known-good policy conditions; Redis fallback retains authority; no signing means no fresh permits; stale/missing evidence never silently safe |
| RC-30 / 1808–1949 | AWS/local topology, modular monolith and enforced module boundaries | Reconcile — Contract reconciliation + Production trust and operations | Reuse existing Java 25/Maven/React-Vite layout rather than source Java 21/Next.js sketch; preserve PG/Redis/Kafka/Neo4j/OPA/OTel responsibilities; module rules and supported regional hosting |
| RC-31 / 1950–1979 | Phased roadmap and real phase acceptance | Reconcile — Contract reconciliation | Map into existing master plan without renumbering historical chapters; move exposure before any budget-enforced action; supplier demo includes graph/OPA before agent extension |
| RC-32 / 1980–2024 | Table/property tests, critical-module coverage target, real dependency proof | Retain — Every adopted slice | All listed authority/canonicalization/goal/reservation/replay properties; >=90% branch coverage is a source target, not a substitute for forced boundary proof; graph semantics need real Neo4j |
| RC-33 / 2025–2043 | Versioned API/event contracts and connector outcome fixtures | Retain — Execution and exposure | Success, decline, pre/post-acceptance timeout, retry, duplicate callback, out-of-order settlement and reversal; a caller timeout alone does not prove non-acceptance |
| RC-34 / 2044–2100 | Build/pre-release security checks and 14 agent red-team scenarios | Retain — Production trust and operations + Agent authority | Preserve scans, fuzzing, malformed signatures, JWKS, mTLS, tenant/injection/clock/SSRF/egress/recovery cases and all source red-team rows; scope to components actually adopted |
| RC-35 / 2101–2134 | Capacity curves, hot-key contention, backlog/degraded benchmarks and chaos | Retain — Production trust and operations | No fabricated TPS commitment; kill/fail dependencies, replica lag, key rotation and revocation race; genuine boundary postconditions, not simultaneous-start tests |
| RC-36 / 2135–2268 | Eleven mandatory end-to-end scenarios | Retain — Scenario map below | Each gets its own implementation/proof reference before claiming full research V1 completion |
| RC-37 / 2269–2343 | Architecture constraints, ADR-to-proof sequence and first complete vertical slice | Reconcile — Contract reconciliation | Explicit differences below; no silent stack rewrite; first supplier journey includes API, PG, graph, OPA, UI, permit, fake rail, outcome and replay |
| RC-38 / 2344–2397 | Full final V1 definition and economic authorization purpose | Retain — All slices | Capability checklist below; first demo completion is not full V1 or global production readiness |

## Eleven mandatory end-to-end scenarios

All entries are **PLANNED; not certified by this review**. The owning slice must
supply exact test/demo references in its matrix and closure dossier. Source line
coordinates prevent similar-sounding scenarios from disappearing into one test.

| Source scenario / lines | Owning slice | Required behavior |
|---|---|---|
| Routine B2B / 2139–2155 | Supplier decision + Execution | Valid known supplier passes policy, exact permit, simulated execution/outcome and replay |
| Compromised instruction / 2156–2171 | Trust and verification + Supplier decision | Unverified changed destination challenges despite ordinary approvals; no permit; legitimate independent resolution also demonstrated |
| Prompt-injected agent / 2172–2181 | Agent authority | Hostile content cannot replace the approved goal; incompatible action receives no authority |
| Valid autonomous purchase / 2182–2193 | Agent authority + Execution | Correct actor key, mandate, intent, destination, amount and scoped instrument permit the exact valid action |
| Incremental authorization / 2194–2208 | Agent authority + Execution | Valid new total gets fresh authorization; previous unconsumed authority unusable; reservation adjustment atomic; distinguish amendment from an additional payment |
| Incremental abuse / 2209–2218 | Agent authority | Cumulative amount and frequency controls catch repeated increases; no deterministic-limit escape via small deltas |
| Concurrent overspend / 2219–2228 | Execution and exposure | Two 80k requests under a 100k shared budget cannot both reserve; force both orderings and prove postconditions |
| Goal-bound authority / 2229–2239 | Agent authority + Execution | Completion blocks further use; pending goal slot prevents duplicate purchases before completion is recorded |
| Permit replay / 2240–2248 | Execution and exposure | Reuse cannot create another effect; distinguish malicious replay from authorized recovery of the same execution |
| Confused deputy / 2249–2259 | Agent authority | More powerful agent cannot launder the originating task into a broader authority chain |
| Decision replay / 2260–2268 | Temporal graph and replay | Historical artifacts reproduce original decision; candidate policy is clearly hypothetical and cannot mutate execution authority |

## Explicit differences from the final research

The final research is the baseline; these are reviewable engineering corrections,
not unnoticed omissions or automatic permission to change established contracts.

- Keep the actual Java 25/Spring Boot/Maven and React/Vite implementation. The
  source's Java 21/Next.js repository sketch is not a reason to discard working
  foundations. Record the compatibility decision in the kickoff ADR.
- Restore **Neo4j and OPA to the first complete local supplier demonstration**.
  They are bounded components with real failure/rebuild proof. Reuse existing
  rules for existing card APIs; define new trust-policy authority explicitly.
- Use the long report's enforced permit/fake-executor flow over the short report's
  initial connector exclusion. A decision-only stage remains useful, but cannot
  claim the first full enforced demonstration is complete.
- Preserve immutable destination versions and trustworthy verification provenance.
  Neither a mutable beneficiary ID nor a caller-supplied verification boolean
  can establish the exact recipient's authenticity.
- Add durable execution intent and reconciliation to the permit-consumption sketch.
  Signing/consuming a token cannot create an atomic transaction with a bank.
  Include permit issuance/signing failure recovery as well as dispatch failure.
- Count parent/sibling pending exposure and pending goal slots before authorization.
  Pull these forward from the source's later economic-runtime phase wherever the
  earlier flow claims corresponding spending limits. Freeze monetary units and
  incremental reservation adjustment rather than copy sample representations.
- Specify revocation's serialized execution boundary and restore fencing. Neither
  token TTL nor backup RPO permits forgetting potentially executed money movement.
- Correct incomplete policy inputs and preserve distinct trust evidence; a low
  risk score or expired cooldown never establishes verified account ownership.
- Retain protocol families, model families and full source performance targets as
  mapped work. Qualify each before deployment; do not claim conformance, efficacy,
  certification, cross-tenant data rights or SLO achievement from this document.

## Full research V1 capability checklist

The final source requires identity, authority, intent integrity, trust provenance,
temporal graph intelligence, economic safety, independent policy, enforcement,
replayability, operational safety, governance and payment-data discipline.
All twelve remain in the target. Strategy sequencing does not delete them.

Before calling that V1 complete, map each requirement and each mandatory scenario
to an adopted plan/ADR, frozen promise, exact implementation and real-boundary
proof. Reconcile omissions or explicit scope amendments openly. This coverage map
is a handoff into that process, not a replacement for it.

## Next-session entry

Start with this map and the final research. Prepare the contract/ADR and master-plan
amendments, then freeze the first supplier slice and its failure inventory. The
first slice may be smaller than the complete demonstration; its closure must
state exactly what it proves. Pull dependency-ready work through graph, policy,
UI, permit, fake execution and replay before the agent extension. Keep the global
invention objective and commercial learning running alongside that sequence.


## Verification of this document

Source SHA-256/byte preservation, 38 unique coverage IDs, 11 scenario rows,
source-range bounds and 29 local research-document links passed (exit 0).
The first validation caught a line-count convention error: 2,396 newline
characters represent 2,397 logical lines because the source lacks a final newline.
Corrected the coverage coordinates/count description; source bytes are unchanged.
Learning checks and ordinary repository readiness exited 0. No full application
suite was rerun, and these structural checks do not prove implementation coverage.
