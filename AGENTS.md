# AGENTS.md

Project-specific instructions for AI agents and other contributors working in this repository.

## Startup Context

Every new Codex session should read these shared source-of-truth files before broad work:

- `docs/current-application.md`
- `docs/codex-ui-workflow-guide.md`

For small, targeted questions or one-file fixes, read those two files plus only the files needed for the task.
`docs/evolution-*.md` files are historical archaeology only.
Consult an evolution document only when the current source-of-truth docs and code do not explain why an older decision exists.

## Project Summary

Music Library NG is a local-first music collection app for one user running on one PC at a time.

- Backend: Quarkus, Java 21, Gradle.
- Database: SQLite, schema initialized by Flyway.
- Frontend: Vue 3, Pinia, Vuetify, Vite.
- Frontend assets are built into Quarkus resources and served by the backend.
- Default app port: `8795`.
- Runtime data lives under `data/`.
- User-adjustable UI state belongs in the DB when it is a runtime preference.
- First-run/default values belong in `src/main/resources/application.properties`.

Important paths:

- `docs/current-application.md`: current app behavior, model, API, and constraints.
- `docs/codex-ui-workflow-guide.md`: current UI, workflow, and verification rules.
- `docs/ideas.md`: unimplemented ideas only.
- `src/main/java/org/kroky/musiclib/resource`: HTTP resources.
- `src/main/java/org/kroky/musiclib/repository`: SQLite access.
- `src/main/java/org/kroky/musiclib/scan`: filesystem scan logic.
- `src/main/java/org/kroky/musiclib/provider`: online provider checks.
- `src/main/resources/db/migration/V1__init.sql`: current fresh DB schema.
- `frontend/src/views`: top-level Vue screens.
- `frontend/src/stores/library.ts`: main Pinia store.
- `frontend/src/styles.css`: shared app styling.
- `dev-shell.sh`: local helper functions.

## Workflow And UI Rules

`codex-ui-workflow-guide.md` owns working style, frontend layout rules, UI smoke-test workflow, settings behavior, scan/status UI behavior, and final verification rules.
Keep those rules there instead of duplicating them here.
`current-application.md` owns current app behavior, data model, API semantics, and current constraints.
Keep current behavior there instead of scattering it through evolution docs.

If there is doubt about whether the requested action should remove data, delete data, mutate files on disk, or change a broader workflow than stated, ask before implementing.
Do not guess on destructive or domain-ambiguous actions.

## Documentation Expectations

Document behavior that a maintainer or user needs to know.

- Update `README.md` when setup, build, runtime behavior, native packaging, config, or user-visible workflows change.
- Update this file when repository conventions or agent workflow expectations change.
- Update `docs/current-application.md` when current behavior, data model, API surface, provider behavior, scan behavior, settings behavior, or current constraints change.
- Update `docs/codex-ui-workflow-guide.md` when UI rules, workflow rules, verification expectations, or reusable layout contracts change.
- Update `docs/ideas.md` when an unimplemented idea is implemented, rejected, split, or materially redefined.
- Remove implemented or rejected ideas from `docs/ideas.md` instead of leaving stale backlog text behind.
- Keep docs practical.
  Prefer commands, file locations, and rules over broad explanation.
- Keep docs factual and maintainer-facing.
  Do not add narrative labels, session timing labels, literary phrasing, or conversation summaries.
- Do not document abandoned ideas, rejected alternatives, passing implementation ideas, or unrelated future work.
- Mention follow-up work only when it is an intentional current constraint or a concrete planned next step.
- Do not create an evolution document for every session.
- Create a new evolution or design note only for a major decision that needs durable rationale beyond the current behavior reference.
- Evolution docs are historical references and must not be required startup context.
- Do not use evolution docs as the source of truth for current behavior.
- Do not leave `???`, open-ended brainstorming questions, or suggested future prompts in durable docs.
- Avoid hard-coded personal paths in docs.
  Use placeholders such as `<project-root>`, `<music-root>`, `<graalvm-jdk-21>`, or environment variables.
- If a feature adds a new configuration key, document the key where existing related keys are documented.
- If a setting has both an `application.properties` default and a DB override, clearly describe that relationship.

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
- Use existing normalization helpers such as `Names`, `ReleaseDates`, and parser utilities instead of duplicating normalization logic.
- For a fresh-start schema change, edit `V1__init.sql` when the current development state allows starting from scratch.
  Add migrations only when preserving existing DBs is required.

## Collection Types And Parsing

- Keep collection type separate from folder parser/layout.
- Collection type answers what the primary browsing entity is: `ARTIST` or `TITLE`.
- Parser/layout answers how folders encode metadata, for example `FLAT_ARTIST_YEAR_ALBUM`, `NESTED_ARTIST_ALBUM`, or `TITLE_PIPELINE`.
- Do not normalize by renaming folders on disk.
  Keep raw folder/path values as evidence and normalize into DB metadata fields.
- Parsed metadata should be user-editable.
  Track whether metadata came from automatic parsing or manual override so later scans do not overwrite manual fixes.
- Title-centric collections such as soundtracks need a parser pipeline, not one regex.
  Parse the final metadata suffix first, e.g. `Title (Artist, Release Date)`, while preserving inner parentheses in titles.
- Release date is stored as canonical text.
  `releaseDate` may be a year, year-month, or full date such as `2006-03-13`; year display, chronological sorting, and filtering derive from that value.
- Album ownership is many-to-many through album/artist links.
  Do not reintroduce a single `artist_id` owner on albums.
- Title items also store `sortName`/`sort_name` for chronology/grouping.
  Auto sort names can be regenerated from parsed title metadata, but manually edited sort names must be preserved across scans.
- Ambiguous title folders should be stored with partial metadata and a parse status instead of forcing bad artist/release values.
- Future artist-centric nested layouts should be handled by adding a new parser/layout while keeping type `ARTIST`.

## Build And Test Commands

Use `codex-ui-workflow-guide.md` for verification rules and UI smoke-test workflow.

Useful dev-shell commands after sourcing `dev-shell.sh`:

```bash
kp
bapp
brun
freshrun
runjar
```

Use `freshrun` when DB state may be invalid or stale.
It stops app processes, deletes `data/`, builds, and runs the packaged jar.

For packaged frontend verification, open:

```text
http://localhost:8795/
```

Port `5173` is Vite dev server only.

UI smoke test shortcut:

1. Build and run the packaged app, usually with `brun` or:

```bash
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

2. From WSL, run the Windows browser/CDP smoke test:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w scripts/check-ui-layout.ps1)" \
  -AppUrl "http://localhost:8795/"
```

Use `-AppUrl "http://localhost:<port>/"` when testing a temporary port.
The script uses headless Chrome/Edge, checks workspace panes, internal scrolling, and core artist/title layouts, and writes screenshots to the Windows temp directory.
