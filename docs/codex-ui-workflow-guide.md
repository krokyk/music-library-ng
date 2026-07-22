# Codex Frontend Workflow Guide

This file owns the working process and verification rules for frontend changes.
Read `docs/ui-guide.md` for the app's visual and interaction contract.

## Operating Style

- Start with `git status --short`.
  The worktree may already contain user edits.
  Do not revert or rewrite unrelated files.
- Be direct about contradictions.
  If a requested behavior conflicts with an earlier rule or creates a weak UX, call it out before coding.
- For implementation requests, iterate through the task end to end: inspect, patch, build, run relevant checks, inspect the result, refine if needed.
- Finish implementation changes with `./gradlew build` unless a concrete blocker prevents it.
  The user expects the app to be build-ready without needing to run the build separately.
- Keep changes scoped.
  Prefer small, coherent patches over broad rewrites.
- Use existing store/action/repository patterns before adding new abstractions.
- Avoid boilerplate.
  Add helpers only when they centralize real repeated behavior.
- For risky or destructive behavior, separate similar verbs precisely: `Remove` means unlink from the current context; `Delete` means delete from the library database.
- Do not use UI validation scripts to click destructive actions unless the user explicitly wants test data mutated.

## Session Collaboration And Handoff

Independent Codex sessions do not share hidden chat memory.
Use the current source-of-truth docs and the codebase as shared memory.

- Keep `AGENTS.md` for stable project rules and pointers only.
- Use `current-application.md` as the authoritative reference for current behavior, model, API semantics, provider behavior, scan behavior, settings behavior, and current constraints.
- Use `ui-guide.md` for stable visual and interaction rules.
- Use `codex-ui-workflow-guide.md` for stable frontend workflow and verification rules.
- Use `ideas.md` for unimplemented ideas only.
- Remove or shrink ideas from `ideas.md` when implementing or rejecting them.
- Treat `evolution-*.md` files as preserved historical archaeology only.
- Consult evolution documents only when the current docs and code do not explain why an older decision exists.
- Do not put long session transcripts into `AGENTS.md`.
- Do not put chat chronology, session labels, suggested prompts, abandoned ideas, speculative alternatives, or commit archaeology into current docs.
- Create a design note only for a major decision that needs durable rationale beyond the current behavior reference.
- After a major decision is implemented, update `current-application.md`.
- Before committing documentation, search the relevant code paths for behavior that could make the doc stale.
- When starting a separate session, give it a narrow ownership boundary, for example: "work only on album grid behavior" or "work only on settings layout".
- When two sessions work in parallel, use separate branches or worktrees and merge through normal Git review.
- At the end of a substantial session, update `current-application.md`, `ideas.md`, `README.md`, `AGENTS.md`, or this guide as appropriate for the behavior that actually changed.
- Temporary handoff files are allowed only for explicit parallel work and should be removed or folded into the source-of-truth docs before the work is finished.

Before merging parallel UI work, run one integration/review session that checks for conflicting assumptions, duplicated components, inconsistent state handling, and missing validation.

## UI Verification Workflow

Use `scripts/check-ui-layout.ps1` for any non-trivial UI change involving:

- workspace panes, pane resizing, or pane proportions
- scroll behavior or browser-level page height
- sticky headers or custom grid layout
- column resizing
- status bar location/history overlays
- row action visibility, dense table controls, or hover-only controls
- dialogs, dropdowns, or anchored popovers that can clip or overlap content

Recommended final UI check:

```bash
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

Then, in another shell:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w scripts/check-ui-layout.ps1)" \
  -AppUrl "http://localhost:8795/"
```

Stop the packaged app afterward.
If Windows cannot reach WSL through localhost, pass the WSL IP instead:

```bash
APP_HOST="$(hostname -I | awk '{print $1}')"
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w scripts/check-ui-layout.ps1)" \
  -AppUrl "http://${APP_HOST}:8795/"
```

When running Windows browser checks from WSL:

- Prefer `scripts/check-ui-layout.ps1` for layout smoke tests because it owns Chrome or Edge startup, CDP wiring, viewport setup, screenshots, and cleanup.
- Pass script paths through `wslpath -w` so PowerShell receives Windows paths.
- Keep the app process in WSL and let Windows Chrome open `http://localhost:<port>/`.
- If Windows cannot reach the WSL app through `localhost`, pass the WSL IP in `-AppUrl`.
- For focused DOM checks that the shared script does not cover, create a temporary PowerShell script outside the repo and delete it after the run.
- Put custom CDP logic inside the PowerShell script so browser startup, `Invoke-RestMethod http://127.0.0.1:<cdp-port>/json/...`, and WebSocket evaluation all run on the Windows side.
- Do not assume WSL `curl`, Node, or other Linux tools can reach a Windows Chrome CDP listener on `127.0.0.1`.
- Use the default CDP port `9223` unless there is a concrete reason to use another port.
- If an alternate CDP port fails while `9223` works, use `9223` instead of spending time debugging Windows browser port binding.
- If Chrome or Edge does not expose CDP, clean only the temporary CDP browser processes and profile directories that match the test port or profile name.
- Avoid inline PowerShell one-liners when commands contain `$env:...`, `${env:...}`, script blocks, or nested quotes because Bash expansion often corrupts them.
- A temporary `.ps1` file is the reliable option for non-trivial Windows browser automation from WSL.

Treat the smoke check as failed when:

- the document height exceeds the browser viewport height
- expected collection, artist, album, or title rows are missing
- pane content has near-zero height while data exists
- pane bottoms, scrollbars, sticky headers, or row actions are visibly clipped
- screenshots show ghost columns or headers scrolling with content

For simple CSS-only changes, a frontend build may be enough, but if the visual result is ambiguous, run the smoke check.

## Final Checks Before Responding

- Confirm the final behavior matches the newest user request.
- Compare every new or changed control against `docs/ui-guide.md`, including semantic color, component type, geometry, icon, tooltip, and responsive label behavior.
- Inspect the states affected by the change, including idle, hover, selected, enabled, disabled, open, or decorated states where applicable.
- For outlined form fields, verify empty, focused, populated, error, and disabled states and reject any clipped or overpainted floating label.
- Compare analogous grids and controls across screens and confirm that every visual or interaction difference is intentional and documented in `docs/ui-guide.md`.
- Run at least `npm run build --prefix frontend` for frontend changes.
- Run `./gradlew test` for backend or shared behavior changes.
- Run `./gradlew build` and `scripts/check-ui-layout.ps1` for substantial UI layout changes.
- Stop any app process started for validation.
- Report failed or skipped verification explicitly.
- Mention unrelated dirty files only when relevant to the user's next action.
