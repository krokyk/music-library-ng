# Domain Docs

How engineering skills should consume this repository's domain documentation.

## Before exploring, read these

- `docs/current-application.md` for current behavior, data model, API semantics, and constraints.
- `docs/ui-guide.md` for visual and interaction rules.
- `docs/ui-workflow-guide.md` for frontend workflow and verification rules.
- `CONTEXT.md`, if it exists, for domain vocabulary only.
- Relevant ADRs under `docs/adr/`, if that directory exists.

If `CONTEXT.md` or `docs/adr/` does not exist, proceed silently.
Create them lazily only when domain terminology is resolved or a durable architectural decision needs recording.

## Document responsibilities

- `AGENTS.md` contains stable repository and agent rules.
- `docs/current-application.md` is the current application source of truth.
- `docs/ui-guide.md` is the visual and interaction contract.
- `docs/ui-workflow-guide.md` is the frontend workflow and verification contract.
- `CONTEXT.md` is an optional glossary and must not duplicate implementation details or application behavior.
- `docs/adr/` contains durable architectural decisions that should not be silently contradicted.

## Domain layout

This is a single-context repository.
Do not create `CONTEXT-MAP.md` or per-module context documents unless the repository genuinely becomes a multi-context system.

## Vocabulary and decisions

Use terminology defined in `CONTEXT.md` when it exists.
Surface conflicts with existing ADRs instead of silently overriding them.
