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
- Keep backups under `data/backups` unless the user explicitly chooses another location.

## Cover Art

- Add cover-art support, probably through Cover Art Archive for MusicBrainz-linked albums.
- Cover art should be cached locally and should not slow normal table browsing.

## Provider Chip Visibility In Collection Artists

- Keep an artist provider chip visible in the Collections screen Artists pane whenever the artist has a provider identity.
- Do not make the provider identity visible only on row hover because provider assignment is persistent row state, not just an action.
- Preserve the existing hover and selected-row behavior for actions that operate on the provider chip.

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

## Compilation Representation

- Decide how to represent compilations where album-level contributors are not enough.
- Avoid solving this with fake composite artist names.
- Track-level ownership may be needed before this can be represented accurately.

## Duplicate Album Detection

- Add a duplicate-detection workflow for albums with slightly different names, release dates, or provider identities.
- The workflow should preview merges before changing shared album identity.

## Folder Rename Preview And Confirmation

- Add a UI preview and explicit confirmation before conflict resolution renames folders or updates audio tags.
- Show source and target folders, planned tag changes, unsupported files, and warnings from the existing conflict plan.
- Reuse the existing Windows-safe folder rendering, rollback, and stored local-path update behavior.

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

## Add Provider Metadata Repair / Audit Workflow

Implement a separate metadata repair/audit workflow that uses provider evidence to propose fixes. This must be separate from provider match, bulk-match, and normal provider scan behavior.

Goal:

- Detect likely local metadata issues by comparing local albums against evidence from multiple providers.
- Present repair proposals to the user.
- Never auto-rename folders or edit tags without explicit user confirmation and preview.

Scope:

- Add a Provider Audit / Metadata Repair entry point, probably from Artists or a provider-conflict area.
- The audit should compare local albums with provider album evidence for one artist first; batch/all-artists can come later if the design supports it cleanly.
- Add release-year repair proposals based on provider agreement.
- Example:
  `Local 2000, MusicBrainz 2001, Metal Archives 2001, Spirit of Metal 2000` should produce a proposal such as `Suggested year: 2001, confidence: 2 of 3 providers`.
- Majority agreement is only evidence, not an automatic mutation.
- “Use Suggested Year” should reuse the metadata-only provider conflict resolution path when the repair is only a library metadata decision.
- Add album-title mismatch detection as another repair proposal type.
- Add canonical album-title proposals when provider title evidence agrees after provider-title cleanup and title normalization.
- Example:
  `Local A New Religion`, `MusicBrainz A New Religion?`, `Metal Archives A New Religion?`, and `Spirit of Metal A New Religion?` should produce a proposal such as `Suggested title: A New Religion?, confidence: 3 of 3 providers`.
- If only one provider has title evidence or providers disagree, require explicit user choice instead of treating one provider as canonical.
- Using a suggested title should update the DB album display title when the repair is only a library metadata decision.
- Using a suggested title should not force an exact folder name when the provider title contains characters that are illegal in the filesystem path.
- Any optional folder rename for a title repair must live in the separate folder rename workflow and preview the path-safe folder name separately from the canonical display title.
- Reuse the shared provider-matching title normalization, scoring, and year-guardrail contract instead of defining separate title-matching thresholds here.
- If provider artist matching is implemented first, this repair idea should reference the documented current behavior rather than the removed idea.
- Treat repair findings as explicit proposals even when the same evidence would be strong enough for provider artist auto-match.
- Title mismatch proposal actions can be:
  `Keep Local Title`, `Use Provider Title`, `Link As Same Album`, `Ignore`.
- Do not run title comparison during UI render.
- Update docs for the repair workflow and its safety boundaries.

Acceptance criteria:

- Audit can show year disagreement with provider source counts.
- Audit can show likely title mismatches like:
  `Origine: The Black Crystal Sword Saga Part 2`
  versus
  `Origine (The Black Crystal Sword Saga, Pt. 2)`.
- Repair proposals are explicit and reviewable.
- No filesystem rename or tag edit occurs without a preview and user confirmation.
- Normal provider scans still only collect evidence and conflicts; they do not apply repairs.
- Tests/build pass.

## Update Playlists After Audio Path Renames

When playlist management is implemented, make the future folder rename workflow update affected playlist entries whenever it renames album folders or audio files.

Scope:

- Detect every audio file path changed by the future folder rename workflow or future metadata repair actions that explicitly mutate files.
- Build an old-path to new-path mapping for renamed folders/files.
- Update all managed playlists that reference any renamed audio file.
- Preserve playlist order, comments, blank lines, and relative/absolute path style where possible.
- Preview playlist changes together with folder rename and tag-update plans before applying.
- Apply playlist updates only after the user confirms the filesystem mutation.
- Roll back or clearly report partial failure if folder rename succeeds but playlist rewrite fails.
- Document that unmanaged external playlists are not modified unless explicitly imported/managed by the app.

Acceptance criteria:

- Renaming an album folder updates playlist entries pointing to tracks inside that folder.
- Preview shows how many playlist entries will change and which playlists are affected.
- No playlist file is rewritten when the user cancels the conflict resolution.
- Existing conflict resolution tests cover path mapping and playlist update behavior once playlist support exists.
