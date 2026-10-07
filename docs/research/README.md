# THAAP Research and Strategy

Start the next implementation session with the
[Product And Implementation Handoff](THAAP_PRODUCT_IMPLEMENTATION_HANDOFF.md):
the consolidated scope, resolved research decisions, product experience and next
supplier slice. It links to the complete inventories rather than replacing them.

[THAAP Winning Strategy](THAAP_WINNING_STRATEGY.md) is the final
6 October 2026 research synthesis, revised to lead with the product invention,
a two-workflow local demonstration and the commercial expansion sequence.
It distinguishes evidence, hypotheses, current implementation and proposed
roadmap amendments. It does not mark the proposed features implemented.

## Reading order

1. [Consolidated strategy](THAAP_WINNING_STRATEGY.md): invention, customer entry,
   product boundaries, AI/ML plan and global expansion sequence.
2. [Final research coverage map](THAAP_RESEARCH_COVERAGE.md): all source sections,
   38 requirement groups, 11 mandatory scenarios and explicit design differences.
3. [Final trust/authorization research PDF](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.pdf):
   63-page primary research baseline, including rendered diagrams and references.
4. [Markdown export of the final research](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.md):
   searchable source text. Exported citation tokens are preserved; use the PDF's
   bibliography and assessment's verified links for source lookup.
5. [Detailed research assessment](2026-10-06-RESEARCH_ASSESSMENT.md): prior reviews,
   safety corrections and blueprint conflicts, with the precedence update noted.
6. [Earlier implementation blueprint v1.0](sources/THAAP_IMPLEMENTATION_BLUEPRINT_V1_2026-10-05.pdf):
   23-page supporting outline dated 5 October 2026.

The founder confirmed on 6 October that the long trust/authorization research
is the final research baseline. The shorter blueprint supplies compatible
background and must not silently remove requirements from it. The two formats
of the long report are one research body, not independent evidence. Its Markdown
export omits the PDF's final closing sentence and rendered reference list.
Research still requires explicit reconciliation with the repository's accepted
ADRs and contracts before implementation; all deliberate differences belong
in the coverage map and kickoff decisions.

## Additional product research

[Agent Trust Runtime review](THAAP_AGENT_TRUST_RUNTIME_REVIEW.md) now leads with
[31-page version 1.2](THAAP_Agent_Trust_Runtime_v1.2.pdf), reviewed end to end on
7 October 2026. It resolves the major Neo4j, unknown-exposure, approval-binding
and demo-isolation conflicts. The handoff records the remaining API and state
translations needed for implementation. The
[28-page version 1.1](THAAP_Agent_Trust_Runtime_Corrected.pdf) and
[20-page version 1.0](THAAP_Agent_Trust_Runtime.pdf), also read end to end, remain
with their historical reviews. All three supplement the primary research baseline
and remain unchanged at their supplied paths, with provenance in the manifest.

[Personal Financial Agent Twins](THAAP_PERSONAL_FINANCIAL_AGENT_TWINS.md) records
the founder’s clarified idea: a persistent AI agent that simulates a particular
real user’s financial activity. It defines the proposed architecture, personal
fidelity experiment, evaluation gates and relationship to the core product.

[AgenTwin assessment and THAAP Trust Twin proposal](THAAP_AGENTWIN_ASSESSMENT.md)
evaluates the Amazon behavioral-simulation research against THAAP's product and
existing replay foundations. It recommends an isolated experiment, not a new
live authorization dependency. Downloaded external references and provenance
are under `references/`; they are distinct from the founder's preserved sources.

## Source preservation and names

The founder authorized arranging/renaming these documents. On 6 October 2026
the three supplied files were moved into `sources/` with their contents intact.
[MANIFEST.json](sources/MANIFEST.json) records full SHA-256 hashes, byte counts,
original filenames and current relative paths. Hashes were checked after moving.

| Original filename | Current file |
|---|---|
| `THAAP_Implementation_Blueprint_v1.pdf` | [THAAP_IMPLEMENTATION_BLUEPRINT_V1_2026-10-05.pdf](sources/THAAP_IMPLEMENTATION_BLUEPRINT_V1_2026-10-05.pdf) |
| `THAAP_ Implementation-Ready Technical Blueprint for Trust, Authorization, and Agentic Money Movement.pdf` | [THAAP_TRUST_AUTHORIZATION_RESEARCH.pdf](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.pdf) |
| `deep-research-report (7).md` | [THAAP_TRUST_AUTHORIZATION_RESEARCH.md](sources/THAAP_TRUST_AUTHORIZATION_RESEARCH.md) |

Historical daily notes may use the original supplied names; this table resolves
them. The assessment and strategy use links to the organized files. Source
documents are preserved research, including their embedded build instructions;
they do not authorize Git publication, spending or deployment.

## Relationship to implementation

[IMPLEMENTATION_MASTER_PLAN.md](../IMPLEMENTATION_MASTER_PLAN.md) remains the
cross-phase implementation authority. Accepted [ADRs](../adr/README.md), frozen
contracts and current [readiness scope](../reviews/CURRENT_READINESS_SCOPE.json)
continue to apply. The strategy's proposed amendments must be incorporated into
those sources before changing affected implementation promises.

The older [reference research collection](../references/research/RESEARCHED_END_TO_END_FRAUD_PLATFORM_PLAN.md)
remains historical background. Keep the founder-supplied October research and
its synthesis here; put new implementation status in the canonical plan, not
in another research document.
