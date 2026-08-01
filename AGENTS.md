# AGENTS.md

Project-specific instructions for AI agents and other contributors working in this repository.

## Startup Context

Every new agent session should read these shared source-of-truth files before broad work:

- `docs/current-application.md`
- `docs/ui-guide.md`
- `docs/ui-workflow-guide.md`

For small, targeted questions or one-file fixes, read those three files plus only the files needed for the task.
`docs/evolution-*.md` files are historical archaeology only, not current source-of-truth or required startup context.
When a proposal would reverse or remove a current architectural constraint, search the evolution documents for prior rationale and tell the user about any relevant earlier design before implementing the reversal.

## Agent skills

### Domain docs

See `docs/agents/domain.md` for domain-doc structure.

## Project Summary

See `README.md` for the project map and build/run, and `docs/current-application.md` (Product Boundary, Runtime And Configuration) for the stack, ports, data locations, and default-vs-DB preference rules.

Key code paths not called out in `README.md`'s project map:

- `src/main/java/org/kroky/musiclib/resource`: HTTP resources.
- `src/main/java/org/kroky/musiclib/repository`: SQLite access.
- `src/main/java/org/kroky/musiclib/scan`: filesystem scan logic.
- `src/main/java/org/kroky/musiclib/provider`: online provider checks.
- `frontend/src/views`: top-level Vue screens.
- `frontend/src/stores/library.ts`: main Pinia store.
- `frontend/src/styles.css`: shared app styling.

## Workflow And UI Rules

`ui-guide.md` owns frontend visual language, layout rules, control behavior, settings UI behavior, and scan/status UI behavior.
`ui-workflow-guide.md` owns working style, UI smoke-test workflow, and final verification rules.
Keep those rules there instead of duplicating them here.
`current-application.md` owns current app behavior, data model, API semantics, and current constraints.
Keep current behavior there instead of scattering it through evolution docs.
Do not design special UI, workflow branches, or tests for implausible single-user local races, such as a folder disappearing between listing and an immediate click.
Let existing generic error handling cover them unless the user reports the case or data loss is possible.

This is a single-user local app with one non-disabled user, so do not add explicit accessibility affordances.
Do not add ARIA attributes, `role`, `tabindex`, `:focus-visible` styling, or screen-reader-only text.
Rely on native element semantics and browser defaults instead of writing code to support accessibility.

If there is doubt about whether the requested action should remove data, delete data, mutate files on disk, or change a broader workflow than stated, ask before implementing.
Do not guess on destructive or domain-ambiguous actions.

## Documentation Expectations

Document behavior that a maintainer or user needs to know.

- Update `README.md` when setup, build, runtime behavior, native packaging, config, or user-visible workflows change.
- Update this file when repository conventions or agent workflow expectations change.
- Update each doc when its owned area changes, per the ownership map in "Workflow And UI Rules" above; `docs/ideas.md` owns its own lifecycle and removal rules.
- Keep docs practical.
  Prefer commands, file locations, and rules over broad explanation.
- Keep docs factual and maintainer-facing.
  Do not add narrative labels, session timing labels, literary phrasing, or conversation summaries.
- Do not document abandoned ideas, rejected alternatives, passing implementation ideas, or unrelated future work.
- Mention follow-up work only when it is an intentional current constraint or a concrete planned next step.
- Do not create an evolution document for every session.
- Create a design note only for a major decision that needs durable rationale beyond the current behavior reference.
- Do not leave `???`, open-ended brainstorming questions, or suggested future prompts in durable docs.
- Avoid hard-coded personal paths in docs.
  Use placeholders such as `<project-root>`, `<music-root>`, `<graalvm-jdk-21>`, or environment variables.
- If a feature adds a new configuration key, document the key where existing related keys are documented.
- If a setting has both an `application.properties` default and a DB override, clearly describe that relationship.

## Git Workflow

This is a solo, single-user hobby repository with no other contributors.
Commit straight to `main` with no feature branches or pull requests unless the user asks for one.

## Backend Rules

- Use Quarkus REST resources for API endpoints under `/api/...`.
- Keep frontend SPA fallback separate from API routes.
  Do not route `/api`, `/q`, or asset-like paths to `index.html`.
- Keep REST resources thin: parse request, log intent, call repositories/services, and return responses.
  Do not put SQL or workflow logic in resources.
- Put multi-step workflows in services; put direct SQLite access in repositories.
- Use repositories for database access.
  Avoid SQL in resources.
- Use structured SQL and prepared statements.
  Do not build SQL by concatenating user-controlled values.
- SQLite lock risk matters.
  Keep transactions and write windows small.
- Any multi-table write must use an explicit transaction with rollback on failure.
- Log normal user actions as `INFO` or lower.
  Use `WARN` only for conditions that need attention and are not normal user-controlled outcomes.
- Do not rely on cascade behavior blindly for domain deletes.
  Be explicit about which related records should be removed and which must remain.
- Database deletes must never delete files or folders on disk unless the request explicitly says it is a filesystem delete.
- Distinguish association removal from entity deletion:
  - remove/unlink endpoints remove membership or relationship rows only.
  - delete endpoints delete the entity from the library database.
- Long-running work, filesystem scans, and provider checks must go through job/service flows, not ad hoc blocking resource methods.
- Mutation endpoints should refresh or return canonical model state when the frontend depends on updated counts, memberships, or derived fields.
- Counts and presence flags must be scoped deliberately.
  Global counts and selected-collection counts are not interchangeable.
- Use existing normalization helpers such as `Names`, `ReleaseYears`, and parser utilities instead of duplicating normalization logic.
- For a fresh-start schema change, edit `V1__init.sql` when the current development state allows starting from scratch.
  Add migrations only when preserving existing DBs is required.

## Collection Types And Parsing

`docs/current-application.md` (Collection Types And Parsing) owns the current inference, locking, folder-shape, and release-year-storage behavior; the rules below are the design guardrails to preserve.

- Keep collection type separate from per-folder parser/layout detection.
- Do not normalize by renaming folders on disk.
  Keep raw folder/path values as evidence and normalize into DB metadata fields.
- Parsed metadata should be user-editable.
  Track whether metadata came from automatic parsing or manual override so later scans do not overwrite manual fixes.
- Title-centric collections such as soundtracks need a parser pipeline, not one regex.
  Parse the final metadata suffix first, e.g. `Title (Artist, Year)`, while preserving inner parentheses in titles.
- Album ownership is many-to-many through album/artist links.
  Do not reintroduce a single `artist_id` owner on albums.
- Title items also store `sortName`/`sort_name` for chronology/grouping.
  Auto sort names can be regenerated from parsed title metadata, but manually edited sort names must be preserved across scans.
- Ambiguous title folders should be stored with partial metadata and a parse status instead of forcing bad artist/release values.
- Future artist-centric layouts should be handled by extending per-folder detection while keeping type `ARTIST`.

## Build And Test Commands

`README.md` (Setup, Running, Build And Package) owns build/run commands and ports.
`docs/ui-workflow-guide.md` owns verification rules and the UI smoke-test workflow, including the `scripts/check-ui-layout.ps1` CDP check.

Useful dev-shell commands after sourcing `dev-shell.sh`:

```bash
kp
bapp
brun
freshrun
runjar
```

Use `freshrun` when DB state may be invalid or stale; it rebuilds from a deleted `data/` and runs the packaged jar.
