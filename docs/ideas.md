# Ideas

This file tracks unimplemented ideas only.
When an idea is implemented, remove it from this file or replace it with the smaller remaining idea.
When an idea is rejected, remove it from this file.
Current behavior belongs in `docs/current-application.md`.

## App Lock File

- Add an app-level lock file so the app can warn when another app instance or another machine appears to be using the same SQLite database.
- The warning should protect the intended one-PC-at-a-time workflow without pretending SQLite is a multi-user sync system.

## Backup And Restore

- Add startup and shutdown backup handling for the SQLite database.
- Add backup and restore metadata so the app can show what backup was produced and which runtime database it belongs to.
- Keep backups under the configured backup directory unless the user explicitly chooses another location.

## Cover Art

- Add cover-art support, probably through Cover Art Archive for MusicBrainz-linked albums.
- Cover art should be cached locally and should not slow normal table browsing.

## Provider Chip Visibility In Collection Artists

- Keep an artist provider chip visible in the Collections screen Artists pane whenever the artist has a provider identity.
- Do not make the provider identity visible only on row hover because provider assignment is persistent row state, not just an action.
- Preserve the existing hover and selected-row behavior for actions that operate on the provider chip.

## Collection Scan Consolidation And Performance

- Revisit the local scan buttons in the Collections screen Artists pane because the bulk local scan overlaps with collection scan behavior.
- Evaluate whether per-artist local scans still provide enough value to justify their buttons.
- If the per-artist scan is not useful, remove local scan buttons from the Artists pane and keep collection scan as the main local discovery action.
- Improve collection scan performance for the common case where the user only added new folders on disk.
- Consider loading existing collection artists and local paths into memory, scanning folder names quickly, and comparing the two sets before doing heavier database writes.
- Preserve the current rule that scans update database evidence and never rename or delete files on disk.

## Provider Action Detail Reports

- Add structured plaintext detail for clickable status-history entries that summarize provider checks, provider matching, provider review, or provider decision application.
- A compact status message should keep the outcome summary, for example `Applied MusicBrainz decisions: linked 3, created 2, skipped 7`.
- The report popup should stay plaintext for consistency with scan reports and for easy copy, diagnostics, and log correlation.
- The clickable detail should list exactly what happened in grouped sections such as linked albums, new unchecked albums, skipped artists, ignored provider records, skipped already-existing local albums, and errors.
- Linked rows should include the provider album, the local album, and relevant collection membership when available.
- Created rows should show the new unchecked album titles.
- Skipped rows should explain why no database change was made.

## Grid Table Action Alignment

- Revisit grid-table row action alignment so action controls align to the right edge of their action area.
- This would make grid tables feel closer to the Collections and Artists pane rows where trailing controls are visually right-aligned.
- If implemented, update `docs/codex-ui-workflow-guide.md` because the current grid-table contract says actions align left inside the action column.

## Artists Screen Search Scope

- Expand main Artists screen search beyond artist name when it produces useful results.
- Candidate searchable columns include provider name, provider artist name, provider country, active status, local scan failure text, and provider error text.
- Evaluate whether searching numeric count columns is useful or whether it creates surprising matches.
- Keep the search behavior predictable enough that the visible bulk-match scope remains easy to understand.

## Inactive Provider Refresh Controls

- Skip provider scans by default for artists whose provider metadata says the artist is inactive, split, disbanded, or otherwise no longer active.
- This skip should apply to batch-style provider scans where checking inactive artists usually wastes time.
- Keep an explicit override for the rare case where an inactive artist should be refreshed anyway.
- Consider a right-aligned `Refresh inactive` control in the Artists screen search and filter area.
- Individual artist refresh should remain available as the deliberate override when the user is looking at one specific artist.

## Album Collection Membership Assignment

- Add collection membership editing to album edit flows so provider-discovered albums can be assigned to collections by user choice.
- Provider-discovered albums should not automatically gain collection membership unless the provider job is collection-scoped and the album is otherwise unassigned.
- Consider prompting when the user marks a provider-discovered album checked from a collection context.
- The prompt could offer to also add the album to the active collection.
- Avoid prompting when the album already belongs to the active collection or when the checkbox change is not collection-contextual.

## Collection Type Choice

- Revisit collection creation so the user either chooses collection type explicitly or gets a useful inferred default.
- Type inference could inspect folder names for title-pipeline patterns versus artist-album patterns.
- Because collection creation is infrequent, an explicit type choice may be clearer and safer than clever inference.
- The workflow should avoid silently creating the wrong type when folder names are ambiguous.

## Collection Chip Navigation

- Make collection chips in the Collections screen Albums pane clickable.
- Clicking a chip should navigate to that collection and select the relevant artist and album when those entities exist in the target collection.
- The navigation should preserve the user expectation that a collection chip represents real membership, not just decorative metadata.
- If the target collection is title-centric, navigate to the title row for the album instead of trying to select an artist pane row.

## Screenshots

- Add maintained screenshots for the README or documentation.
- Screenshots should be regenerated from the packaged app so they match the production UI.

## Release Packaging

- Document the preferred release packaging layout and update process beyond the current native executable build notes.
- The packaging docs should cover config location, data location, backups, and upgrade expectations.

## Compilation Representation

- Decide how to represent compilations where album-level contributors are not enough.
- Avoid solving this with fake composite artist names.
- Track-level ownership may be needed before this can be represented accurately.

## Duplicate Album Detection

- Add a duplicate-detection workflow for albums with slightly different names, release dates, or provider identities.
- The workflow should preview merges before changing shared album identity.

## Folder Rename Workflow

- Add an identity-preserving folder rename workflow with preview and stored local-path updates.
- This workflow must not silently rename folders as part of scanning.

## Artist Collection Organizer

- Add an organizer that can preview and apply moving flat artist album folders into nested artist folders.
- The organizer should separate preview, validation, filesystem changes, and database local-path updates.

## JSON Export And Import

- Add JSON export and import for extra recovery safety.
- The export should include enough identity and relationship data to rebuild the library without provider or scan history loss unless intentionally scoped down.

## Browser Or PWA Access

- Add an Android-friendly browser or PWA access pattern if remote browsing becomes useful.
- This should not change the one-user local-first storage model.

## Track And Audio Metadata Audit

- Add an explicit deep-audit workflow for track files and audio tags if album-level discovery stops being enough.
- This should be separate from normal collection scans because it is slower and introduces track identity, duplicate-file, and tag parsing rules.
