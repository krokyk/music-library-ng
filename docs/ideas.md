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

## Add Multiple Provider Links Per Artist

Implement support for multiple provider identities per artist.

Current app assumes one provider identity per artist. Change this so an artist can have multiple provider links, for example MusicBrainz, Spirit of Metal, and Metal Archives at the same time.

Scope:

- Update DB/schema and repositories so `artist_provider_links` no longer enforces one provider per artist.
- Keep uniqueness for the same provider identity, e.g. no duplicate `artist + provider`.
- Add an explicit concept of enabled provider links, and consider one optional primary/default provider only if it naturally fits the current UI.
- Update artist provider UI to show/manage multiple provider chips instead of replacing the old provider when a new one is chosen.
- Update provider match/bulk-match logic so matching a new provider does not skip an artist just because another provider is already linked.
- Provider checks should scan all enabled provider links for an artist.
- If possible, run provider checks in parallel per provider, while keeping MusicBrainz throttling respected - run all providers at once, and wait for all providers to finish. Usually MusicBrainz is the slowest, Spirit of Metal fastest. Consider the value of not waiting for all providers on artist to finish
- Aggregate provider album evidence into existing album provider links/conflicts.
- Preserve per-provider album title evidence, including cleaned provider title, normalized provider title, provider release date, provider URL, and matched local album when available.
- Clean obvious provider parser artifacts before storing or displaying provider title evidence, such as Spirit of Metal spaces before terminal punctuation in titles like `A New Religion ?`.
- Preserve current release-date conflict grouping by local album + provider year, with multiple provider sources shown together.
- Do not choose or rewrite the local canonical album display title in this task.
- Do not auto-rename folders or edit tags in this task.
- Do not build metadata repair or majority-vote fixing in this task.
- Update docs for the new multiple-provider model and scan behavior.

Acceptance criteria:

- One artist can have MB + SoM + MA linked at the same time.
- Matching SoM does not remove MB.
- Bulk matching can add a missing provider link without touching existing provider links.
- Provider scan checks all enabled provider links and records album evidence from each.
- Provider album title evidence is available for later consensus checks such as local `A New Religion`, MusicBrainz `A New Religion?`, Metal Archives `A New Religion?`, and Spirit of Metal `A New Religion?`.
- Existing release-date conflict UI still works and groups provider sources correctly.
- Tests/build pass.

## Add Fuzzy Album Evidence To Provider Artist Matching

Use album evidence to rank and auto-match provider artist candidates when provider artist names differ from local artist names.

Scope:

- Use the same evidence and thresholds in manual provider candidate dialogs and bulk provider matching.
- Score candidates from artist-name or alias similarity plus album evidence.
- Sort candidate rows by final confidence descending.
- Album evidence can raise a candidate above a raw exact-name candidate when the album evidence is stronger.
- Treat provider country, status, and disambiguation as display context or weak tie-breakers only because local folders and track tags do not provide equivalent artist evidence.
- Count local/on-disk album matches as strong evidence.
- Count checked non-local DB albums as medium evidence.
- Do not let unchecked provider-created albums auto-match an artist by themselves.
- Try exact normalized album-title matching before fuzzy matching.
- Run fuzzy matching only against albums linked to the same local artist.
- Evaluate Jaro-Winkler, Levenshtein ratio, and token-set or token-sort style scoring.
- Normalize titles before scoring using existing helpers such as `Names` and release-date utilities where possible.
- Use release year as a guardrail, not just as a confidence boost.
- Allow album-match evidence only when release years are equal or differ by at most `1`.
- Treat a release-year deviation of `2` or more as evidence that something else is wrong, not as provider artist auto-match evidence.
- Show title matches with release-year deviation of `2` or more only as suspicious/manual-review evidence.
- Auto-match only when the candidate has plausible artist-name or alias similarity and strong album evidence.
- Strong album evidence can be `2+` local/on-disk album matches with exact, normalized, or high-confidence fuzzy title matches and release years equal or within `+/- 1`.
- Strong album evidence can be one local/on-disk exact or normalized album-title match with release years equal or within `+/- 1` when no competing candidate has comparable album evidence.
- Do not auto-match from one fuzzy-only album match.
- Do not auto-match from one unchecked provider-only DB album.
- Do not auto-match from an album match with no release year.
- Do not auto-match from a generic album title as the sole evidence.
- Keep a denylist of generic titles that cannot be sole auto-match evidence, such as `Greatest Hits`, `Best Of`, `Live`, `Anthology`, `Collection`, `The Collection`, `Essential`, and `The Essential`.
- Provider match dialogs should show final confidence and evidence details, not only a single opaque score.
- Candidate rows should show evidence like `Name 70 / Albums 2 local`.
- Album chips should show match type and score when useful, such as `exact`, `normalized`, or `fuzzy 94`.
- Do not rename folders, change album metadata, or resolve title mismatch conflicts from provider artist matching.
- Precompute candidate evidence during provider candidate evaluation so fuzzy scoring does not run in UI render loops.

Acceptance criteria:

- A candidate like `Athena XIX` can outrank exact-name `Athena` when it has multiple matching local albums.
- A provider candidate can auto-match when it has strong local/on-disk album evidence even if the provider artist name differs due to rename, suffix, alias, roman numeral, country suffix, or historical name change.
- Title matches with release-year deviation of `2` or more are not used as auto-match evidence.
- Provider match dialogs clearly explain why each candidate scored where it did.
- Manual provider match and bulk provider match use the same evidence and thresholds.
- The shared title normalization, scoring, and year-guardrail contract is documented as current provider matching behavior when this idea is implemented.
- Tests cover exact, normalized, fuzzy, equal-year, `+/- 1` year, greater-than-1-year mismatch, generic-title, and competing-candidate cases.
- Tests/build pass.

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
- “Use Suggested Year” should reuse the existing safe provider-year path: preview folder renames, update DB local paths, update album release date, update supported audio tags, resolve provider links.
- Add album-title mismatch detection as another repair proposal type.
- Add canonical album-title proposals when provider title evidence agrees after provider-title cleanup and title normalization.
- Example:
  `Local A New Religion`, `MusicBrainz A New Religion?`, `Metal Archives A New Religion?`, and `Spirit of Metal A New Religion?` should produce a proposal such as `Suggested title: A New Religion?, confidence: 3 of 3 providers`.
- If only one provider has title evidence or providers disagree, require explicit user choice instead of treating one provider as canonical.
- Using a suggested title should update the DB album display title and supported audio album tags after preview.
- Using a suggested title should not force an exact folder name when the provider title contains characters that are illegal in the filesystem path.
- Any folder rename for a title repair must preview the path-safe folder name separately from the canonical display title.
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

When playlist management is implemented, make provider conflict resolution update affected playlist entries whenever it renames album folders or audio files.

Scope:

- Detect every audio file path changed by `Use Provider Year` or future metadata repair actions.
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
