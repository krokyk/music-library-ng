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
- If implemented, update `docs/ui-guide.md` because the current grid-table contract says actions align left inside the action column.

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

## Manual Artist Creation

- Add manual artist creation to the global Artists screen and artist-centric Collections screen.
- Require an artist-centric home collection before creation can begin, with the active collection preselected when launched from Collections.
- Match the new artist to a provider and fetch an eligible non-empty discography before saving anything.
- Create the artist, provider identity, albums, artist links, and initial album homes atomically, assigning every initial album to the chosen collection.
- Save nothing when provider matching fails, provider fetching fails, or the provider returns no eligible albums.
- Do not expose manual artist creation in title-centric collections because title contributors enter through title scans rather than full-discography import.
- Keep existing-artist collection placement album-driven through each album's home selector instead of adding an independent artist membership action.

## Compilation Representation

- Decide how to represent compilations where album-level contributors are not enough.
- Avoid solving this with fake composite artist names.
- Track-level ownership may be needed before this can be represented accurately.

## Duplicate Album Detection

- Add a duplicate-detection workflow for albums with slightly different names, release years, or provider identities.
- The workflow should preview merges before changing shared album identity.

## Conflict Resolution Tile Impact Preview

- Add a read-only hover or focus preview that explains exactly what clicking one provider conflict resolution tile would do.
- Show the current and target metadata value, source and target folder paths, planned audio-tag field and file count, unsupported files, and validation warnings.
- Title previews must describe title, folder-title, and `ALBUM` tag changes only.
- Release-year previews must describe release-year, folder-year, and `YEAR` tag changes only.
- Generate the preview from the same backend planning implementation used to preflight the immediate tile action so displayed effects cannot drift from applied effects.
- Hovering or focusing a tile must never remove stale paths, write database state, rename folders, or update tags.
- Clicking a tile must remain an immediate resolution action without an Apply button or confirmation step.
- Cache a successfully loaded preview only while its conflict and album state remain unchanged.

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

## Managed Playlists

This is the next major milestone after the current provider-conflict work.

Goal:

- Replace duplicate derivative collections with app-owned playlist membership while keeping one canonical physical copy of each track in its album's home collection.
- Keep a logical playlist independent of its player-specific outputs so the same membership can be published for desktop and Android players.
- Treat generated playlist files as overwriteable outputs rather than sources of truth, and do not preserve manual edits made outside the app.

Membership:

- Let a playlist include the complete contents of one or more source collections.
- Let a playlist additionally include explicit tracks from any collection without copying those files.
- Record source collections and explicit track membership in the database so generated files contain only player-compatible playlist content.
- Model each physical audio file once and retain enough stable identity to keep explicit playlist membership when an album folder or track path changes.
- Keep the initial rule set limited to source collections plus explicit tracks, without general smart-playlist expressions or exclusions.

Management:

- Add a playlist manager that can create, rename, and delete logical playlists; configure source collections; add or remove explicit tracks; choose output targets; and regenerate outputs.
- Expose playlist membership where tracks are browsed so classifying a track becomes one immediate Add-to-playlist action instead of a filesystem copy.
- Show missing or stale referenced tracks before generation and keep the last valid generated file when generation cannot complete safely.
- Write generated files through a temporary file and replace the target only after the complete output succeeds because the music root is synchronized by Google Drive.

M3U8 output:

- Generate strict extended M3U8 files directly under the music root for Poweramp and other compatible players.
- Match the existing contract exactly: one standard `#EXTM3U` header followed by alphabetically sorted relative Windows track paths, with no blank lines, per-track metadata, or custom comments.
- Allow the app to overwrite each generated M3U8 completely.

Foobar2000-native output:

- Let the same logical playlist be published into Foobar2000-native playlist state so long playlists load without reparsing M3U8 on every open.
- Do not generate `.fpl` files directly because Foobar2000 intentionally does not publish the format as an interchange specification.
- Perform native playlist creation and replacement through a supported Foobar2000 integration in which Foobar2000 reads the app-generated membership and owns its native persistence.

Migration and maintenance:

- Import the current M3U8 memberships before derivative folders are permanently removed.
- Map derivative playlist entries to canonical tracks using exact file identity where possible and require review for missing or differing copies.
- Support `EPIC` as the complete contents of `EPIC` plus `SOUNDTRACKS`, with additional explicit tracks available from other home collections.
- Support `METAL BALLADS` as explicit tracks drawn from their canonical home collections instead of a physical `METAL BALLADS` directory.
- Update every managed output when an album folder or audio path is renamed, and report the affected playlists as part of the rename result.
- Detect stale entries such as a playlist path retaining an old album year or title after its canonical folder was renamed.

Acceptance criteria:

- One logical playlist can publish both M3U8 and Foobar2000-native outputs without duplicating its membership definition.
- Regenerating `EPIC` includes all current audio files from `EPIC` and `SOUNDTRACKS` plus its explicit cross-collection tracks.
- An explicit track can belong to multiple playlists while only one physical audio file exists.
- Generated M3U8 files remain strict, relative, alphabetically sorted, and free of app-specific metadata.
- A canonical path rename updates all affected managed playlist outputs without losing explicit membership.
- Import reports entries that cannot be matched to a canonical physical track instead of silently dropping them.

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
- “Use Suggested Year” needs an audit-specific metadata-only action when the repair should not use the current conflict resolver's folder and tag behavior.
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
