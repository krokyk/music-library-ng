# Codex UI And Workflow Guide

This file owns practical UI and working-process rules for future Codex sessions
in this repository.

## Operating Style

- Start with `git status --short`. The worktree may already contain user edits.
  Do not revert or rewrite unrelated files.
- Be direct about contradictions. If a requested behavior conflicts with an
  earlier rule or creates a weak UX, call it out before coding.
- For implementation requests, iterate through the task end to end: inspect,
  patch, build, run relevant checks, inspect the result, refine if needed.
- Keep changes scoped. Prefer small, coherent patches over broad rewrites.
- Use existing store/action/repository patterns before adding new abstractions.
- Avoid boilerplate. Add helpers only when they centralize real repeated behavior.
- For risky or destructive behavior, separate similar verbs precisely:
  `Remove` means unlink from the current context; `Delete` means delete from the
  library database.
- Do not use UI validation scripts to click destructive actions unless the user
  explicitly wants test data mutated.

## Session Collaboration And Handoff

Independent Codex sessions do not share hidden chat memory. Use repository files
and commits as the shared memory.

- Keep `AGENTS.md` for stable project rules and pointers only.
- Use `codex-ui-workflow-guide.md` for stable workflow and UI rules.
- Use `evolution-*.md` files as durable design and handoff documents for major
  feature streams.
- Do not put long session transcripts into `AGENTS.md`.
- When starting a separate session, give it a narrow ownership boundary, for
  example: "work only on album grid behavior" or "work only on settings layout".
- When two sessions work in parallel, use separate branches or worktrees and
  merge through normal Git review.
- At the end of a substantial session, update the relevant `evolution-*.md` file
  with decisions, assumptions, TODOs, and known risks.
- If the work is short-lived and not worth an evolution file, create a temporary
  `handoff-<topic>.md` file and remove or fold it into an evolution file later.

Recommended handoff shape:

```text
# Handoff: <topic>

## Scope
- What this session owned.
- What it deliberately did not touch.

## Changed
- User-visible behavior changes.
- Backend/API/storage changes.
- Frontend/layout/state changes.

## Decisions
- Important choices and why they were made.

## Assumptions
- Facts the next session should verify if they matter.

## TODO
- Concrete next steps.

## Risks
- Known weak spots, unverified behavior, or integration concerns.

## Suggested Next Prompt
<prompt text for the next Codex session>
```

Before merging parallel UI work, run one integration/review session that checks
for conflicting assumptions, duplicated components, inconsistent state handling,
and missing validation.

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

Stop the packaged app afterward. If Windows cannot reach WSL through localhost,
pass the WSL IP instead:

```bash
APP_HOST="$(hostname -I | awk '{print $1}')"
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w scripts/check-ui-layout.ps1)" \
  -AppUrl "http://${APP_HOST}:8795/"
```

Treat the smoke check as failed when:

- the document height exceeds the browser viewport height
- expected collection, artist, album, or title rows are missing
- pane content has near-zero height while data exists
- pane bottoms, scrollbars, sticky headers, or row actions are visibly clipped
- screenshots show ghost columns or headers scrolling with content

For simple CSS-only changes, a frontend build may be enough, but if the visual
result is ambiguous, run the smoke check.

## App Shell And Height Rules

- The app must not create a browser vertical scrollbar. The app shell owns the
  full viewport height.
- Panes, tables, lists, dialogs, dropdowns, and history views scroll internally
  only when their own content overflows.
- Status bar placement must not cause content jumps. It is always visible and
  shows idle state when no operation is active.
- If the status bar is configured at the bottom, popups/history should open above
  it. If it is at the top, they should open below it.

## Pane Layout Rules

- Workspace screens use pane layouts, not marketing/landing layouts.
- Panes are resizable along the full vertical divider, not only at a corner.
- Pane resizing should behave like block movement:
  - resizing the collections pane moves the remaining panes as a block
  - resizing the middle pane moves the right pane as a block
  - rightmost pane absorbs remaining width
- Pane minimum widths must keep the pane title and required icon-only controls
  visible.
- Panes must not shrink so far that their title bar becomes unusable.
- Collection pane dropdowns should anchor under the triggering button. They
  should grow to available pane height, then scroll internally.
- Add-folder dropdowns should list folder names only and be only as wide as
  needed for the widest name, with the right edge aligned to the collection pane.

## Grid And Table Rules

- Workspace pane tables that need resizing or sticky headers use the custom CSS
  grid pattern, not Vuetify `v-table`.
- Use explicit pixel column widths from defaults/preferences.
- Each column boundary has one resize handle.
- Dragging a column boundary resizes the column to the left of the boundary and
  moves all columns to the right as a block.
- Column resizing must not trigger sorting.
- Double-click auto-fit on column boundaries is disabled unless it can be made
  reliable.
- All non-action columns share the configured minimum table grid column width.
- Action columns are resizable but cannot shrink below the icon-only action
  width needed by their controls.
- Action columns have no header text and row actions align left inside the action
  column in rightmost panes.
- Headers stay sticky and visible while table content scrolls.
- Headers are sortable when the column has sortable data.
- Title-centric `Title` sorting has a colored mode icon inside the title header:
  clicking the header changes direction, clicking the icon switches title-vs-sort
  sorting.
- Text that does not fit must use ellipsis. Add tooltips when the hidden text is
  important for understanding the row.

## Row Actions And Controls

- Row actions are hover/focus visible unless the workflow needs persistent
  controls.
- Inline row actions use the centralized row action button style.
- Prefer icon plus short label when pane width allows it; collapse to icon-only
  based on configurable pane-width thresholds.
- Use clear, short labels:
  - `Edit`
  - `Local`
  - `Provider`
  - `Remove`
  - `Delete`
  - `Untrack`
- Use color consistently:
  - primary blue for normal actions, scans, sorting, add, save
  - red/error for destructive delete
  - warning/yellow for missing or attention-needed local-path states
  - green/success for present-on-disk indicators
- Do not mix always-visible informational icons with hover-only action icons in a
  way that makes the info icon look like an action.
- Info icons in collection rows are always visible, muted, and right-aligned so
  they form a stable visual column.

## Dialogs, Popovers, And Forms

- Use one shared dialog/popover visual language across edit forms.
- Dialog cards and anchored edit popovers should use the same gap constants:
  small gap `10px`, large gap `20px`.
- Edit forms should not feel cramped. Prefer fewer fields and clear vertical
  spacing over dense packing.
- For pane-local edits, anchored overlays are preferred when they do not need a
  blocking centered decision.
- Confirmation dialogs are appropriate for destructive actions.
- Destructive actions with extra risk need a second warning dialog.
- Folder/path information belongs in an info tooltip/popover unless the field is
  necessary for the edit itself.
- Labels and controls in compact edit forms should align on the same row when it
  improves scanning.

## Collection And Library Semantics

- Collection screen Artists pane:
  - `Remove` removes only the artist association with the selected collection.
  - It does not delete the artist from the library database.
  - The artist should disappear from that collection pane after removal.
- Main Artists screen:
  - `Delete` is a real library database delete.
  - If the artist belongs to any collection or has local albums, require a second
    warning confirmation.
  - Deleting from the library DB never deletes folders or files on disk.
- Album/title local presence styling:
  - present on disk: normal text
  - checked but not on disk: muted normal text
  - unchecked and not on disk: muted italic text
- Do not allow unchecking an album/title that is present on disk from the simple
  checkbox control.
- `Untrack` means forget missing local-path history; it does not delete the album
  or disk content.

## Settings Rules

- Defaults belong in `src/main/resources/application.properties`.
- Runtime user preferences belong in the DB.
- Settings shown in the Settings screen should be useful at runtime. Do not add
  visible settings with no real effect.
- A DB value equal to the current default should behave as default, not custom.
- Reset-to-default should remove the DB override or use the reset endpoint.
- Settings UI should be compact, aligned, and pane-scoped when useful:
  general behavior separate from workspace pane behavior.

## Scanning And Status UI

- Same action from different entry points must route through the same store/job
  path and show the same status, spinners, polling, refresh, and history behavior.
- Collection scans are lazy and fast.
- Artist-centric collection scan populates artists only.
- Artist-centric album scan is explicit and local-album specific.
- Title-centric scans populate title albums plus contributor artists when parsing
  provides credible artist values.
- Status bar messages should be brief but specific: say what is being scanned or
  checked, not just "Scan starting".
- Scan/report history may expose detailed information, but the status bar itself
  should remain concise.
- Progress should reflect real work where practical, without slowing scans only
  to improve animation.

## Final Checks Before Responding

- Confirm the final behavior matches the newest user request.
- Run at least `npm run build --prefix frontend` for frontend changes.
- Run `./gradlew test` for backend or shared behavior changes.
- Run `./gradlew build` and `scripts/check-ui-layout.ps1` for substantial UI
  layout changes.
- Stop any app process started for validation.
- Report failed or skipped verification explicitly.
- Mention unrelated dirty files only when relevant to the user's next action.
