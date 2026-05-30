# THAAP Docs

This directory is organized to avoid conflicting plans during implementation.

## Read First

- [IMPLEMENTATION_MASTER_PLAN.md](./IMPLEMENTATION_MASTER_PLAN.md)

This is the authoritative implementation source of truth. If any reference document disagrees with it, follow the master plan.

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

## Reference Material

Use these only for background, deeper reasoning, or source traceability:

- [Researched end-to-end fraud platform plan](./references/research/RESEARCHED_END_TO_END_FRAUD_PLATFORM_PLAN.md)
- [Original 180-day implementation plan](./references/research/ORIGINAL_180_DAY_IMPLEMENTATION_PLAN.md)
- [Stripe Radar fraud platform prompt](./references/research/STRIPE_RADAR_FRAUD_PLATFORM_PROMPT.md)
- [Useful links](./references/source-materials/useful_links.txt)
- [Mastercard advanced AI technology datasheet](./references/source-materials/mastercard_advanced_ai_technology_datasheet.pdf)
- [AWS GenAI fraud detection reference architecture](./references/source-materials/aws_genai_fraud_detection_reference_architecture.jpg)

## Rule For Future Docs

- Put implementation decisions in `IMPLEMENTATION_MASTER_PLAN.md`.
- Put product intent in `VISION.md`.
- Put system structure and design rationale in `ARCHITECTURE.md`.
- Put detailed flows and subsystem architecture under `architecture/`.
- Put durable architecture decisions under `adr/`.
- Put Mermaid diagram sources under `diagrams/`.
- Put daily implementation learning notes under `learning/daily/`.
- Put research notes under `references/research/`.
- Put PDFs, images, exported link lists, and other source artifacts under `references/source-materials/`.
- Do not create competing implementation plans outside the master plan.
