# THAAP Learning Notes

This folder is for implementation-time learning notes.

Learning docs are not implementation source of truth. They capture what we learned while building, questions that came up, tradeoffs we considered, and links back to the authoritative docs.

Authoritative implementation decisions belong in:

- `docs/IMPLEMENTATION_MASTER_PLAN.md`

Architecture decisions and structure belong in:

- `docs/ARCHITECTURE.md`
- `docs/architecture/`

## Structure

```text
docs/learning/
  README.md
  DAILY_NOTE_TEMPLATE.md
  daily/
    YYYY-MM-DD.md
```

## Daily Note Naming

Use:

```text
docs/learning/daily/YYYY-MM-DD.md
```

Example:

```text
docs/learning/daily/2026-05-29.md
```

## What To Capture

Each daily note should include:

- what we built
- payment concepts learned
- fraud concepts learned
- security concepts learned
- architecture decisions touched
- tests or verification done
- bugs or surprises
- open questions
- next steps

If a learning note discovers a decision that changes implementation, update `docs/IMPLEMENTATION_MASTER_PLAN.md` rather than leaving the decision only in learning notes.
