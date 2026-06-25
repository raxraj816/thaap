# Public Technical Concept Blog System

This folder supports one detailed public technical essay per week.

The public post should teach one concept from payments, fraud detection,
financial infrastructure, ML systems, or AI-assisted investigations. It should
not read like a product update, changelog, repository walkthrough, or build log.

Good post topics look like:

- Why fraud systems need explainable intelligence
- How real-time risk scoring works
- Building idempotent payment APIs
- Rule engines vs ML models in fraud detection
- How AI agents can help fraud investigators
- Designing audit replay for financial decisions
- Event-driven architecture for risk systems
- What I learned building an AI-native risk platform

## Core Rule

Write for a public technical reader.

Do not mention private product names, repository paths, internal weekly notes,
source files, branches, commits, or implementation status in the article body.

The private project can inspire the topic. The public article should stand on
its own as a clear, detailed explanation of the concept.

## Weekly Flow

1. Pick one concept that was relevant to the week's learning or building.
2. Write the concept as a public title.
3. Create a draft:

   ```bash
   scripts/blog-week-start.sh YYYY-WNN short-slug
   ```

4. Explain the concept from first principles.
5. Fill the private editorial brief first.
6. Write the public article below it.
7. Add at least one Mermaid architecture or flow diagram.
8. Add realistic examples, failure modes, design tradeoffs, and operational
   concerns.
9. Convert diagrams into a platform-safe format for the publishing destination.
10. Remove the private editorial brief and all template prompt text before
   publishing.
11. Run `WEEKLY_BLOG_CHECKLIST.md`.
12. Score the draft with `EDITORIAL_RUBRIC.md`.
13. Run the public-language check:

   ```bash
   scripts/blog-public-check.sh
   ```

14. Move the post from `drafts/` to `published/` only when it reads like a
    public technical essay.

## Folder Structure

```text
docs/blog/
  README.md
  POST_TEMPLATE.md
  TOPIC_BACKLOG.md
  WEEKLY_BLOG_CHECKLIST.md
  drafts/
    YYYY-WNN-short-slug.md
  published/
    YYYY-WNN-short-slug.md
```

## Public Post Shape

Every post should have this spine:

1. A concrete opening problem
2. A first-principles definition
3. An architecture or flow diagram
4. A detailed walkthrough of the system design
5. Invariants and failure modes
6. Security, privacy, reliability, and operational concerns
7. Tradeoffs and alternatives
8. What a Staff engineer would ask in review
9. A concise conclusion that teaches the core lesson

## Tone

Write like a Senior Staff Engineer teaching a sharp engineer who is new to
payment-risk systems.

Be specific, technical, and public. Avoid vague inspiration. Avoid private build
details.

## Editorial Standard

Structure is not enough.

A post is not ready just because it has sections, a diagram, and correct
definitions. It must earn attention from a public reader. Before publishing,
score it with `EDITORIAL_RUBRIC.md`; drafts below the threshold should be
rewritten, not polished.

The private editorial brief is planning material only. It should never appear in
the published article.
