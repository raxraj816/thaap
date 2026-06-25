# Public Blog Editorial Rubric

Use this before publishing. A draft can pass the mechanical checklist and still
fail this rubric.

Score each category from 0 to 3.

- 0: missing or weak
- 1: present but generic
- 2: useful and clear
- 3: strong enough for a serious public technical article

Do not publish unless the post scores at least 18 out of 24 and no category is
below 2.

## 1. Reader Hook

Does the first 150 words create a concrete reason to keep reading?

Weak: starts with definitions or abstract claims.

Strong: starts with a realistic failure, investigation, decision, or operational
pressure.

## 2. Reader Promise

Is it clear what the reader will understand or be able to do after reading?

Weak: "This post explains explainability."

Strong: "You will understand how to design fraud decisions so they can be
reconstructed later without leaking sensitive data."

The promise should appear near the top of the public article, not only in the
private brief.

## 3. Technical Specificity

Does the post include concrete artifacts such as records, examples, timelines,
tables, diagrams, or decision payloads?

Weak: mostly conceptual bullets.

Strong: shows an example decision record, feature snapshot, event flow, or
failure timeline.

The artifact must be explained. A pasted JSON block without interpretation does
not count as strong specificity.

## 4. Narrative Flow

Does the article read like an essay rather than a template?

Weak: numbered headings that feel like a checklist.

Strong: each section naturally answers the question raised by the previous
section.

The public article should not expose private planning notes or template prompt
text.

## 5. Architecture Quality

Does the diagram teach something meaningful?

Weak: a simple box chain that repeats the prose.

Strong: shows boundaries, sync vs async flow, durable evidence, privacy
boundaries, replay paths, or failure points.

## 6. Staff-Level Judgment

Does the post explain tradeoffs and failure modes with production taste?

Weak: says what should exist.

Strong: explains why the obvious solution is insufficient, what to build first,
what to defer, and what breaks at scale.

## 7. Public Credibility

Would a senior engineer, risk analyst, or payments product person respect the
piece?

Weak: generic advice they have already seen.

Strong: clear, practical, and specific enough to feel learned from real system
design.

## 8. Publication Fit

Would the post work on LinkedIn or Medium without extra context?

Weak: depends on private project context or internal framing.

Strong: standalone, skimmable, useful, and polished for a public reader.

## Publish Decision

Record this before publishing:

```text
Reader hook:
Reader promise:
Technical specificity:
Narrative flow:
Architecture quality:
Staff-level judgment:
Public credibility:
Publication fit:
Total:
Decision: publish / rewrite
Main rewrite needed:
```
