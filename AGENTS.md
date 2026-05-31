# AGENTS.md

Project-specific instructions for AI agents and other contributors working in this repository.

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

- `src/main/java/org/kroky/musiclib/resource`: HTTP resources.
- `src/main/java/org/kroky/musiclib/repository`: SQLite access.
- `src/main/java/org/kroky/musiclib/scan`: filesystem scan logic.
- `src/main/java/org/kroky/musiclib/provider`: online provider checks.
- `src/main/resources/db/migration/V1__init.sql`: current fresh DB schema.
- `frontend/src/views`: top-level Vue screens.
- `frontend/src/stores/library.ts`: main Pinia store.
- `frontend/src/styles.css`: shared app styling.
- `dev-shell.sh`: local helper functions.

## Working Style

- Be direct and factual. Surface contradictions, risks, or simpler alternatives.
- Prefer small, coherent changes over broad rewrites.
- Match existing patterns before introducing new abstractions.
- Keep boilerplate low. Do not add layers, DTOs, helpers, or framework code unless they remove real duplication or clarify behavior.
- Do not hide important behavior in clever code. Prefer boring, readable code.
- Use precise names. Avoid vague names such as `data`, `handler`, `manager`, or `helper` when a domain name is available.
- Keep comments sparse. Add comments only for non-obvious decisions, edge cases, or constraints.
- Do not revert unrelated changes in the worktree.

## Documentation Expectations

Document behavior that a maintainer or user needs to know.

- Update `README.md` when setup, build, runtime behavior, native packaging, config, or user-visible workflows change.
- Update this file when repository conventions or agent workflow expectations change.
- Keep docs practical. Prefer commands, file locations, and rules over broad explanation.
- Avoid hard-coded personal paths in docs. Use placeholders such as `<project-root>`, `<music-root>`, `<graalvm-jdk-21>`, or environment variables.
- If a feature adds a new configuration key, document the key where existing related keys are documented.
- If a setting has both an `application.properties` default and a DB override, clearly describe that relationship.

## Backend Rules

- Use Quarkus REST resources for API endpoints under `/api/...`.
- Keep frontend SPA fallback separate from API routes. Do not route `/api`, `/q`, or asset-like paths to `index.html`.
- Use repositories for database access. Avoid SQL in resources.
- Use structured SQL and prepared statements. Do not build SQL by concatenating user-controlled values.
- SQLite lock risk matters. Keep transactions and write windows small.
- Log normal user actions as `INFO` or lower. Use `WARN` only for conditions that need attention and are not normal user-controlled outcomes.
- For a fresh-start schema change, edit `V1__init.sql` when the current development state allows starting from scratch. Add migrations only when preserving existing DBs is required.

## Frontend Rules

- The app itself must not create a browser vertical scrollbar. Pages should fit the browser height; panes and dialogs scroll internally when needed.
- Use pane-style layouts for app screens. Avoid landing-page or marketing layouts.
- Keep operational UI compact and scannable.
- Prefer aligned, tabular controls for settings and dense operational screens.
- Use icons for compact actions and text buttons for clear commands.
- Use Vuetify components consistently, but do not accept default Vuetify layout behavior when it breaks alignment or density.
- For dropdowns/popovers tied to pane controls, anchor them near the triggering control instead of centering a modal unless the task needs a blocking decision.
- Status/history UI should preserve layout stability. Avoid content appearing/disappearing in a way that shifts the main workspace.
- If text can grow, constrain it with ellipsis, wrapping, or internal scrolling so it does not overlap adjacent controls.
- Workspace pane data grids should use the custom CSS grid pattern, not Vuetify `v-table`, when columns need resizing or sticky headers. Use explicit pixel column widths, one resize handle per column boundary, a sticky grid header, and a single scroll container owned by the grid. Avoid table/colgroup layout for these panes because native table layout can resize adjacent columns unpredictably and create ghost columns.
- If the same action can be invoked from multiple places, route all entry points through the same store/action/job path. The visible behavior must be identical: same status bar messages, same polling, same busy indicators, same cancellation behavior when available, and same refresh-after-completion behavior.

## Settings And Preferences

- Defaults belong in `application.properties`.
- Runtime user preferences belong in the DB.
- A DB preference equal to the current default should be treated as default, not custom.
- Reset controls should remove DB overrides or call the reset endpoint, returning the effective value to the property default.
- Do not expose every property in the Settings screen. Expose only settings that are useful to change at runtime.
- Avoid settings that cause jarring immediate layout changes unless there is a strong reason.

## Scanning Behavior

- Collection filesystem scan should be lazy and fast.
- Initial collection scan populates artists only. Do not populate albums during collection scan.
- Artists discovered from folders should be created in readable title case, not all caps.
- Albums should be loaded or scanned only when the user explicitly drills into artist/album workflows or provider checks require it.
- Progress should reflect actual work where practical, but do not make scans materially slower just to improve animation.
- Any collection scan trigger, including Settings and collection row actions, must use the scan job flow so status history, status bar progress, row spinners/progress, polling, and post-scan refresh are consistent.

## Collection Types And Parsing

- Keep collection type separate from folder parser/layout.
- Collection type answers what the primary browsing entity is: `ARTIST` or `TITLE`.
- Parser/layout answers how folders encode metadata, for example `FLAT_ARTIST_YEAR_ALBUM`, `NESTED_ARTIST_ALBUM`, or `TITLE_PIPELINE`.
- Do not normalize by renaming folders on disk. Keep raw folder/path values as evidence and normalize into DB metadata fields.
- Parsed metadata should be user-editable. Track whether metadata came from automatic parsing or manual override so later scans do not overwrite manual fixes.
- Title-centric collections such as soundtracks need a parser pipeline, not one regex. Parse the final metadata suffix first, e.g. `Title (Artist, Year)`, while preserving inner parentheses in titles.
- Ambiguous title folders should be stored with partial metadata and a parse status instead of forcing bad artist/year values.
- Future artist-centric nested layouts should be handled by adding a new parser/layout while keeping type `ARTIST`.

## Build And Test Commands

Primary verification:

```bash
npm run build --prefix frontend
./gradlew test
```

Full package build:

```bash
./gradlew build
```

Useful dev-shell commands after sourcing `dev-shell.sh`:

```bash
kp
bapp
brun
freshrun
runjar
```

Use `freshrun` when DB state may be invalid or stale. It stops app processes, deletes `data/`, builds, and runs the packaged jar.

For packaged frontend verification, open:

```text
http://localhost:8795/
```

Port `5173` is Vite dev server only.

## Review Checklist

Before finishing a change:

- Does the behavior match the latest user request, not an older intermediate request?
- Does the app still build?
- Are browser scrollbars avoided at the app level?
- Are panes/dialogs/dropdowns scrollable when content overflows?
- Are runtime preferences stored in the right place?
- Are docs updated if setup, config, or workflow changed?
- Is the commit-message summary accurate for the whole diff?
