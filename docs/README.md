# THAAP Docs

This directory is organized to avoid conflicting plans during implementation.

## Read First

- [IMPLEMENTATION_MASTER_PLAN.md](./IMPLEMENTATION_MASTER_PLAN.md)
- [THAAP_VISION_COVERAGE_MATRIX.md](./THAAP_VISION_COVERAGE_MATRIX.md)
- [PHASE_3_DETAILED_IMPLEMENTATION_PLAN.md](./PHASE_3_DETAILED_IMPLEMENTATION_PLAN.md)
- [PHASE_4_DETAILED_IMPLEMENTATION_PLAN.md](./PHASE_4_DETAILED_IMPLEMENTATION_PLAN.md)
- [PHASE_4_5_DETAILED_IMPLEMENTATION_PLAN.md](./PHASE_4_5_DETAILED_IMPLEMENTATION_PLAN.md)
- [PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md](./PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md)
- [PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md](./PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md)

The master plan is the authoritative cross-phase implementation source. When it
incorporates a detailed phase plan, that incorporated plan controls the exact
frozen contract for its phase and the two documents must agree. The Phase 5
detailed plan is incorporated, not a non-authoritative companion reference;
the founder-confirmed Phase 6 detailed plan now has the same relationship to
the master plan for Chapters 53–59.
The completed Phase 3 detailed plan defines Chapters 22–25. The confirmed Phase
4 detailed plan is incorporated by reference and defines its review/label,
RiskContext, Redis, policy, authentication, dispute, failure, proof, and
Chapter 26–35 boundaries. Founder/domain review confirmed it on 2026-07-13;
Chapters 26–35 and Phase 4 are verified complete. The integrated Chapter 35
matrix/dossier, 629 Java tests, 114 Python tests, 14 migration contracts,
retained V48-to-V49 upgrade proof, and all Chapter 26–34 public demos pass
together.

Phase 4.5 is complete through Chapter 43R with all inherited closure evidence,
759 backend tests, 140 repository tests, 20 UI tests, and the production UI
build reconciled. The Phase 5 detailed plan is implemented through Chapter 52
for durable events and rebuildable analytics. Founder
implementation authorization on 2026-07-27 confirmed all sixteen decisions as
one product set. All Chapter 44–52 pre-implementation contracts are
`FROZEN`/`VERIFIED`/all-`PASS`, with exact matrix/dossier links in the detailed
plan, and all dossiers recommend `MERGE`.
Phase 5 implementation status: COMPLETE
The Phase 6 detailed plan was founder-confirmed on 2026-08-02 and freezes
Chapters 53-59. Its source-reconciled investigation, timeline/relationship,
saved-view/bulk-workflow, insight, API contract, SDK, and integrated-trust
decisions are implemented and proven. Every Chapter 53-59 matrix is
`FROZEN`/`VERIFIED`/all-`PASS`, every dossier recommends `MERGE`, and the public
contract, generated client, migrations, routed workspace, and both public
journeys are reconciled.

Phase 6 implementation status: COMPLETE

## Companion Docs

These explain intent and structure. They should support the master plan, not override it:

- [VISION.md](./VISION.md)
- [ARCHITECTURE.md](./ARCHITECTURE.md)
- [GLOSSARY.md](./GLOSSARY.md)
- [Architecture documents](./architecture/README.md)
- [Architecture decision records](./adr/README.md)
- [Diagrams](./diagrams/README.md)

## Learning Notes

Daily implementation learning notes live here:

- [learning/README.md](./learning/README.md)
- [learning/DAILY_NOTE_TEMPLATE.md](./learning/DAILY_NOTE_TEMPLATE.md)

## Technical Blog

Weekly public-facing technical posts are drafted from the learning trail here:

- [blog/README.md](./blog/README.md)
- [blog/POST_TEMPLATE.md](./blog/POST_TEMPLATE.md)
- [blog/EDITORIAL_RUBRIC.md](./blog/EDITORIAL_RUBRIC.md)
- [blog/TOPIC_BACKLOG.md](./blog/TOPIC_BACKLOG.md)

## Reference Material

Use these only for background, deeper reasoning, or source traceability:

- [Researched end-to-end fraud platform plan](./references/research/RESEARCHED_END_TO_END_FRAUD_PLATFORM_PLAN.md)
- [Original 180-day implementation plan](./references/research/ORIGINAL_180_DAY_IMPLEMENTATION_PLAN.md)
- [Stripe Radar fraud platform prompt](./references/research/STRIPE_RADAR_FRAUD_PLATFORM_PROMPT.md)
- [Useful links](./references/source-materials/useful_links.txt)
- [Source-materials index](./references/source-materials/README.md)
- [Mastercard advanced AI technology datasheet](./references/source-materials/mastercard_advanced_ai_technology_datasheet.pdf)
- [AWS agentic GenAI fraud detection reference architecture](./references/source-materials/aws-agentic-genai-fraud-detection-reference-architecture.jpg)

## Rule For Future Docs

- Put implementation decisions in `IMPLEMENTATION_MASTER_PLAN.md`.
- Put product intent in `VISION.md`.
- Put system structure and design rationale in `ARCHITECTURE.md`.
- Put detailed flows and subsystem architecture under `architecture/`.
- Put durable architecture decisions under `adr/`.
- Put Mermaid diagram sources under `diagrams/`.
- Put daily implementation learning notes under `learning/daily/`.
- Put weekly public technical writing under `blog/`.
- Put research notes under `references/research/`.
- Put PDFs, images, exported link lists, and other source artifacts under `references/source-materials/`.
- Do not create competing implementation plans outside the master plan.
