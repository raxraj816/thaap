# Weekly Public Blog Checklist

Use this before moving a post from `drafts/` to `published/`.

## Public Concept

- [ ] The private editorial brief has been removed before publishing.
- [ ] The title teaches one concept.
- [ ] The article does not read like a changelog, sprint update, or product
      announcement.
- [ ] The article does not mention private product names, repository files,
      branches, commits, or weekly implementation status.
- [ ] A reader outside the project can understand the post.
- [ ] The first 150 words give a stranger a reason to keep reading.
- [ ] The article makes a clear promise about what the reader will learn.
- [ ] The reader promise appears near the top of the public article.

## Depth

- [ ] The opening problem is concrete.
- [ ] The concept is explained from first principles.
- [ ] Domain terms are defined before being used heavily.
- [ ] The article explains why the concept matters in payments, fraud, risk,
      or financial infrastructure.
- [ ] The post includes realistic examples or scenarios.
- [ ] The post includes at least one concrete artifact such as a JSON record,
      event timeline, feature table, decision trace, or before/after flow.
- [ ] The concrete artifact is explained in prose, not just pasted into the
      article.

## Architecture

- [ ] The post includes at least one Mermaid flow or architecture diagram.
- [ ] The diagram explains the concept, not a private code path.
- [ ] State transitions, decision boundaries, trust boundaries, or data flows
      are clear where relevant.
- [ ] The article explains what happens synchronously and what can happen
      asynchronously.
- [ ] The diagram teaches more than a simple linear box chain.
- [ ] The diagram is platform-safe for the publishing destination: rendered as
      an image, converted to text, or confirmed to render correctly.

## Staff-Engineer Quality

- [ ] The post names core invariants.
- [ ] The post describes failure modes.
- [ ] The post covers security and privacy implications.
- [ ] The post covers operational signals or observability.
- [ ] The post explains tradeoffs and alternatives.
- [ ] The post includes review questions a senior engineer would ask.
- [ ] The article explains what the naive design gets wrong.
- [ ] The article explains what changes at scale.
- [ ] The article names what should be built first and what should wait.

## Writing

- [ ] The thesis is clear.
- [ ] Each section supports the thesis.
- [ ] The conclusion gives a memorable design principle.
- [ ] The post is public, useful, and technically specific.
- [ ] The article does not expose template residue such as placeholder bullets,
      empty sections, or metadata meant only for drafting.
- [ ] The article does not publish the `Private Editorial Brief`.
- [ ] The post has been scored with `EDITORIAL_RUBRIC.md`.
- [ ] The editorial score is at least 18 out of 24 with no category below 2.
