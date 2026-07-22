# Current Application Reference

This document is the source of truth for current Music Library NG behavior.
Use `docs/evolution-*.md` only as historical context when the current implementation needs archaeology.

## Product Boundary

- Music Library NG is a local-first music collection app for one user running on one PC at a time.
- The backend is Quarkus on Java 21 with SQLite storage initialized by Flyway migrations under `src/main/resources/db/migration`.
- The frontend is Vue 3, Pinia, Vuetify, and Vite, and the production frontend is served by the Quarkus backend.
- The default app URL is `http://localhost:8795/`.
- Runtime data lives under `data/`.
- The app tracks artists, albums, required album home collections, provider identities, local folder evidence, and listened state.
- Normal scans read disk state without renaming folders, while explicit provider conflict actions may rename one album folder and update supported audio tags.

## Runtime And Configuration

- Shared defaults live in `src/main/resources/application.properties`.
- Private or machine-specific overrides live in ignored `config/application.properties` or JVM `-D...` properties.
- The SQLite path defaults to `data/music-library-ng.sqlite`.
- SQLite connections use WAL mode, `synchronous=NORMAL`, foreign keys, and a 30 second busy timeout so UI reads can continue during background scan writes.
- Logs default to console output plus `data/logs/music-library-ng.log`.
- Generated reports default to `data/reports`.
- The music root is startup configuration, not a runtime UI preference.
- `music-library.music-root` can supply the physical music root when auto-detection does not fit the machine.
- Auto-detection checks configured root candidates in order and accepts a candidate only when all configured marker playlists exist directly under that root.
- Windows-style music-root candidates can be resolved to WSL mount paths.
- Startup validates the music root before Flyway migration.
- Startup must fail fast when neither an explicit music root nor auto-detection resolves a valid root.
- Runtime UI preferences are stored in SQLite `user_preferences`.
- First-run UI defaults still come from `application.properties`.
- A runtime setting equal to its current default should behave as default rather than as a meaningful custom override.

## Data Model

- `artists` stores artist identity, display name, optional sort name, optional country override, and optional active-status override.
- `albums` stores durable album or title identity, one required home collection, optional local relative path, nullable release year, sort name, checked state, and notes.
- `album_artists` links albums to known artists and preserves contributor order; title-centric albums may have no link when their folder contains no contributor metadata.
- `collections` stores user-visible collection homes with generated integer IDs, a collection `type`, and no persisted folder parser.
- `providers` stores supported provider kinds.
- `artist_provider_links` stores provider identities per artist plus provider country, active-status, disambiguation evidence, enabled state, and provider-specific scan status.
- `album_provider_links` stores provider release-group mappings to albums and optional release-year and title conflict resolution.
- `user_preferences` stores runtime preference overrides.

## Album State

- `albums.checked=true` means the user has listened to or accepted the album or title.
- `albums.checked=false` means the album or title is known but not listened to or accepted yet.
- Local disk presence is separate from checked state.
- Local presence comes from the album's non-null local relative path when its resolved folder still exists.
- Local scans mark locally present albums checked because local folders are treated as already accepted library items.
- Provider-created albums are unchecked by default.
- Present-on-disk albums and titles are shown checked in the UI and cannot be unchecked while local path evidence remains active.

## Collection Types And Parsing

- Collection `type` answers which entity the user browses first.
- `ARTIST` collections use the `Collections | Artists | Albums` layout.
- `TITLE` collections use the `Collections | Titles` layout.
- The collection Add dropdown lists available folder names without probing or displaying their type.
- Selecting a folder immediately adds a disabled provisional row in alphabetical order while the backend creates the collection and infers its type once from directory names only.
- Multiple selected folders appear immediately and are created serially in click order without changing the current collection selection.
- A successful create replaces the provisional row's same-sized spinner with the inferred type icon, while a failed create removes the row, restores the folder candidate, reports the error, and continues the queue.
- A successful create adds `Added collection "<name>" (<type>).` to the status bar and session history.
- Type inference counts flat and nested artist album folders as artist evidence and other direct folders as title evidence.
- Artist evidence must use either `artist - year - album` or `artist/year - album`, and everything else at the direct collection level is title evidence.
- An empty folder or tied evidence defaults to `ARTIST`, and the inferred type remains a convenience rather than an authoritative classification.
- Collection type can be changed from the collection edit window while the collection contains no albums and is locked after the first album is added.
- `ARTIST` scans detect flat and nested layouts per folder and can process both layouts in one collection.
- `TITLE` scans use the title parser pipeline.
- Flat artist folders use `artist - year - album`.
- Nested artist folders use `artist/year - album`.
- Title folders support `title (artist, year)`, `title (year)`, `title - year - subtitle`, `title - year`, and title-only fallback.
- Release years are stored as nullable integers from `1000` through `9999` and all folder parsing, display, sorting, filtering, and provider comparison use year precision only.
- Collection scans keep folder and path values as source evidence and do not normalize folders by renaming them on disk.

## Collection Semantics

- Every album has exactly one required home collection and can never be deliberately left collectionless.
- A non-local album can be reassigned only to another collection of the same type.
- A local album cannot be manually reassigned because its physical collection folder is stronger evidence than a UI choice.
- One album can have at most one local folder across the complete music root.
- A newly discovered local folder can rehome a matching non-local album, while finding a second folder for an already-local album is reported as duplicate storage and does not replace the canonical path.
- When a local folder disappears, the scan clears the album's local path but preserves its home collection, metadata, checked state, and provider evidence.
- Artist presence in collections is derived entirely from the home collections of the artist's albums.
- Artists with no remaining albums are removed rather than persisted as albumless collection members.
- A soundtrack contributor can be linked to one title without implying that the full artist discography belongs to that collection.
- Deleting a collection deletes its albums, provider relationships that cascade from those albums, and artists left with no albums, but never deletes files or folders on disk.

## Local Scans

- User-facing local scan actions run through scan job flows.
- Scan jobs provide status, progress, cancellation, current-session report artifacts, and post-scan refresh behavior.
- Collection scans enumerate direct child directories under the selected collection root.
- Collection scans build per-run DB album-path and disk-folder snapshots before processing folders.
- Collection scans process only folders whose relative local path is not already known in the selected collection.
- Existing local paths that are still present on disk are treated as unchanged and are not reparsed or upserted.
- Title collection scans parse direct child folders into album rows and contributor artist links when artist metadata exists.
- Title collection scans create titles directly in the scanned home collection and store their local relative paths.
- Artist collection scans dynamically discover flat and nested artist folders together, assign the scanned collection as home, and store local path evidence on each album.
- Collection scans clear album local paths that were not seen in the latest collection disk snapshot.
- Collection scans preserve album rows, checked state, and home collection when a local path disappears.
- Collection scans can attach a newly found local folder to an existing same-artist checked or provider-linked DB-only album when exact, normalized, or high-confidence fuzzy title evidence matches and the release year is compatible.
- Collection scan fuzzy merges preserve the existing album identity, checked state, display title, and provider links while updating its home collection from physical evidence and adding the local path.
- Collection scans merge same-artist provider-only duplicate albums into the scanned local album when strong title evidence and compatible release years identify the same album.
- Collection scans report a duplicate-storage error and keep the existing binding when a matched album's previous and newly found folders both exist.
- Collection scans do not scan tracks.
- Full collection scans show a blocking modal with a progress bar while the job runs.
- Full collection scans first report snapshot comparison, then count only folders that need processing.
- Nested artist collection scans pre-enumerate nested album folders before the processing progress total is known.
- Cancelling a full collection scan requests cancellation and keeps the modal open until the backend finishes the current item and returns a terminal job status.
- When the full collection scan modal closes, the selected Artist collection's Artists pane or selected Title collection's Titles pane immediately shows its pane spinner until the refreshed rows arrive.
- Full collection scans refresh the selected collection context once after the modal closes, preserving the selected artist when possible.
- Scan reports are generated as plain text and stored under the configured report directory.
- Scan report files use a timestamp, collection id, and report kind in their filename, with a numeric suffix only when needed to avoid overwriting an existing file.
- Scan reports render structured summary counts and do not duplicate the completion status message.
- Artist collection scan summaries distinguish artists found from albums parsed.
- Artist collection scans do not live-refresh processed artists into the Collections artist pane while the scan is still running.

## Providers

- Supported provider IDs are `musicbrainz`, `spirit_of_metal`, and `metal_archives`.
- Each artist can have multiple provider identities, with at most one link per provider.
- MusicBrainz identities use MBIDs and derive site URLs from those MBIDs.
- Spirit of Metal and Metal Archives identities use provider URLs validated and normalized by provider-specific code.
- Metal Archives artist provider links are stored as band-page URLs under `/bands/<band-name>/<id>` or `/bands/_/<id>`.
- Metal Archives provider checks derive `/band/discography/id/<id>/tab/main` internally for scraping and UI external links append `#band_tab_discography`.
- Provider metadata stores country as an ISO alpha-2 code, active status as nullable boolean evidence, and disambiguation as provider evidence when available.
- The app supports `XW` as an `International` pseudo-country for manual artist country overrides.
- Provider country values `International`, `Multinational`, and `Other` normalize to `XW`.
- Artist country and active-status overrides live on the artist row and are never overwritten by provider rescans.
- Effective artist country and status prefer the artist override, then provider consensus, then unknown.
- Provider consensus uses enabled provider links only.
- Provider country consensus uses exact normalized country values only and has no scoring or fuzzy matching.
- A specific country wins only with strict provider majority.
- `XW` International wins automatically only when every enabled provider with country evidence resolves to `XW`.
- Provider status consensus uses only `Active`, `Inactive`, and `Unknown`.
- Provider status values such as split, split-up, disbanded, inactive, on hold, and changed name normalize to `Inactive`.
- `Unknown` participates in provider metadata conflicts but is not a selectable status resolution.
- Artist provider country and status conflicts remain unresolved when no strict consensus exists and the artist has no manual override.
- `PUT /api/artists/{artistId}/provider` upserts the link for the requested provider without replacing other provider links.
- `DELETE /api/artists/{artistId}/providers/{providerId}` removes one provider link.
- Provider candidate search is shared across supported providers through `GET /api/artists/{artistId}/provider-candidates/{providerId}`.
- Manual provider candidate dialogs and bulk provider matching use the same backend candidate-evidence evaluator.
- Provider candidate confidence combines provider search score, artist-name similarity, album-title evidence, and a small capped release-year bonus.
- Provider candidate rows sort by final confidence descending, then album evidence score descending, then provider search score descending.
- Album-title evidence matching tries exact title equality, normalized title equality, then fuzzy scoring on normalized titles.
- Fuzzy title matching treats differing ordered digit sequences as distinct album evidence when both titles contain numbers, while wording variants with the same numbers such as `Vol. 1` and `Volume 1` remain eligible to match.
- Fuzzy title scoring uses the best Jaro-Winkler, Levenshtein ratio, and token-set or token-sort result.
- Exact title matches score `100`, normalized title matches score `96`, fuzzy title matches score their computed fuzzy score, and fuzzy scores below `84` are treated as no title match.
- Fuzzy title scores from `84` through `91` are shown as manual-review evidence but do not contribute auto-match album evidence.
- Fuzzy title scores of `92` or higher can contribute album evidence.
- Local on-disk album-title evidence is strong evidence, checked non-local album-title evidence is medium evidence, and unchecked non-local provider-created album evidence is display-only context.
- Generic album titles contribute half weight and cannot be the only auto-match evidence.
- Generic album titles are `Greatest Hits`, `Best Of`, `Live`, `Anthology`, `Collection`, `The Collection`, `Essential`, and `The Essential`.
- Release-year compatibility adds only a capped bonus for album evidence that already matched by title.
- Release-year compatibility never creates an album-title evidence match by itself.
- Provider candidates expose `finalScore`, `nameScore`, `albumEvidenceScore`, `yearBonus`, `evidenceSummary`, and per-album evidence with local title, provider title, match type, title score, evidence strength, and local evidence kind.
- Bulk provider auto-matching requires provider search score at least `80`, artist-name score at least `65`, album evidence score at least `64`, final score at least `82`, and a final-score margin of at least `8` over the runner-up.
- Bulk provider auto-matching requires local non-generic album-title evidence and never auto-matches from checked non-local, unchecked non-local, provider-only, generic-only, or one fuzzy-only album match.
- Provider candidate dialogs show candidate albums as chips with overflow counts.
- The Collections screen provider candidate dialog saves the clicked provider candidate immediately for the selected provider.
- The Artists screen `Add providers` row action opens a fixed-size multi-provider dialog titled `Match Provider for <artist>`.
- Artists provider tabs are ordered by provider display name, show a spinner while that provider is being matched, keep loaded results cached while switching tabs, expose `Refresh all`, and keep the active tab selected after refresh.
- Artists provider tabs show a top-right green badge when a candidate is selected.
- Existing provider links appear as preselected candidate rows in their provider tabs.
- Deselecting an existing provider row and clicking `Save` removes that provider link.
- Selecting a different provider candidate and clicking `Save` adds or updates that provider link.
- Clicking a provider candidate row applies that candidate, while the row's `Open` button opens the provider artist page as a separate action with a tooltip.
- Provider candidate rows show one coherent clickable hover highlight, and the `Open` button has its own hover treatment.
- Provider candidate rows show compact evidence summaries such as `Confidence 91 / Name 70 / Albums 2 local`.
- Provider candidate album chips show provider album titles without match-type suffixes.
- Provider candidate album chips use a green check badge only when title and year both fully match local evidence, and use an amber question badge when matched local evidence has a title or year conflict.
- Provider candidate album chips are dimmed for provider-only albums and keep checked or unchecked non-local evidence visually distinct when there is no title or year conflict.
- Provider candidate album chips sort stronger evidence first, prioritizing exact title plus matching year, then partial title or year matches, then weaker or unmatched evidence.
- Provider candidate album chips have bounded widths and expose concise title and year match summaries through multiline tooltips.
- Bulk provider matching is shared across supported providers through `POST /api/provider-matches/{providerId}/artists`.
- Bulk provider matching receives exact artist IDs from the frontend visible scope.
- Bulk provider matching skips artists that already have an identity for the requested provider.
- Bulk provider matching auto-links only high-confidence matches and leaves ambiguous matches for manual selection.
- Provider checks run through provider job flows for user-facing scan buttons.
- One-artist provider checks scan all enabled provider links for that artist.
- Batch provider jobs skip provider links whose last successful check is inside the configured batch rescan delay.
- Failed provider checks do not block immediate retry.
- Individual artist provider checks always run even when the artist was checked recently.
- Provider checks do not infer local disk presence.
- Provider checks do not delete local albums or clear local path evidence because a provider omits a release.
- Provider checks generate plain text report files under the configured report directory.
- Provider report files use a timestamp and short report subject in their filename, with a numeric suffix only when needed to avoid overwriting an existing file.
- Provider adapters fetch and filter eligible remote releases before local library writes begin.
- Each eligible discography is reconciled atomically for one artist and one provider, including provider metadata, album changes, provider links, collection assignment, duplicate merges, and the successful-check timestamp.
- A failed artist-provider reconciliation rolls back that provider's complete local write set and records the failure separately so other providers and artists remain committed.
- Provider checks use the shared provider candidate album-title evaluator to link exact, normalized, and high-confidence fuzzy provider releases to existing local albums.
- Exact and normalized provider titles may link while reporting a release-year conflict, but a fuzzy provider title auto-links only when its known release year is compatible with the local year.
- Album evidence prefers exact or normalized titles and compatible years before local-presence strength so another provider reuses an existing provider-only album instead of matching a similarly named local album.
- Provider checks can link a provider release to an existing provider-created unchecked album from another provider when strong title evidence and compatible release years identify the same album.
- Provider checks prefer a strong local album match over an existing provider-only link and merge same-artist provider-only duplicates into that local album.
- Provider checks keep the local album path unchanged when provider and local album titles differ.
- Provider checks record unresolved title conflicts when a provider release links to an existing local album whose title differs from the provider title.
- Provider checks record unresolved release-year conflicts when a provider release links to an existing local album whose release year differs from the provider year.
- Unresolved provider release-year and title conflicts remain visible after a provider check through artist row warning indicators and the artist-scoped conflict resolver.
- Unresolved provider artist country and status conflicts remain visible through the same artist row warning indicators and artist-scoped conflict resolver.

## Status History And Reports

- The status bar and status history are current-window session state only.
- Status history is not persisted in SQLite and is lost after reload or app exit.
- Completed scan and provider-check status entries can carry one or more report artifacts returned by the completed job.
- Report dialog navigation such as `1/3` is the current report index inside the in-memory status history.
- Reports omit internal identifiers.
- Durable audit data comes from the generated plain text files under `data/reports`, not from database job tables.
- The artist-scoped provider conflict resolver groups artist metadata conflicts by field and album metadata conflicts by local album and conflict type.
- The provider conflict resolver displays the artist's linked providers above the album groups.
- Provider chips inside the provider conflict resolver include an external-open icon and open the linked provider page when clicked.
- The provider conflict resolver uses a constant-height dialog with internal scrolling.
- The provider conflict resolver shows conflict sections as an accordion with the first section open and only one section open at a time.
- The provider conflict resolver title shows an artist name plus a conflict-count chip that counts local album and conflict-type sections, not provider variants.
- Collapsed conflict-section headers show only the album path or title plus a variant-count chip such as `3 Title variants` or `1 Year variant`.
- Expanded artist country conflict sections show a manual country selector tile and one provider tile for each distinct provider country value.
- Expanded artist status conflict sections show manual Active or Inactive choices only for concrete status values that no provider supplied, plus one provider tile for each distinct provider status value.
- Expanded album conflict sections show one local tile only when the album has an on-disk local folder and one provider tile for each distinct provider value.
- Provider-only album conflict sections show provider-sourced tiles for current and differing provider values instead of a local tile.
- Provider variant tiles group multiple provider sources when they propose the same provider value for the same conflict type.
- Conflict choice tiles center the chosen value and show smaller source information in the lower right.
- Clicking a conflict tile resolves the complete field conflict immediately without an Apply button or confirmation step, accepting the chosen provider group and dismissing the other provider variants.
- While one tile is resolving, the clicked tile shows a spinner and every conflict tile in the dialog is disabled.
- Every conflict action is recorded in application logs with artist, album, conflict kind, action, source, old and resulting values, and old and new relative folder paths where applicable.
- Audio-tag failures are logged individually as warnings after the successful database and folder transaction.
- Country conflict tiles show a flag and country name for concrete country values.
- Status conflict tiles show Active and Inactive chips, with Active green and Inactive red.
- Choosing an artist metadata conflict value writes an artist override.
- Clearing an artist metadata override returns the artist to provider consensus and may re-open the conflict.
- Keeping the local year marks all grouped provider releases as resolved against the local album so future provider checks do not add the provider album again.
- Kept-local year decisions remain visible in the Artists detail known-album list and can be reset from the year chip to make the mismatch unresolved again.
- Using a provider year updates only the album release year, composes the folder name from the unchanged title plus chosen year, accepts the matching provider group, dismisses the other year variants, and merges provider-only duplicates.
- Using the provider year preserves the album checked state.
- Supported audio `YEAR` tags are left unchanged when their current value begins with the chosen four-digit year, preserving values such as `2020-05-01`; missing or mismatching values are overwritten with exactly `YYYY`.
- Keeping the local title marks every differing provider title for that album as resolved against the local album.
- Keeping the local title clears title resolution on provider links that already match the local title.
- Kept-local year and title decisions remain visible as transparent, thin white outlined chips with normal-weight white text and white undo controls in the Artists detail known-albums list.
- Using a provider title updates only the album title, composes the folder name from the chosen title plus unchanged year, accepts the matching provider group, dismisses the other title variants, and merges provider-only duplicates.
- Using a provider title preserves the album checked state.
- Opening the provider conflict dialog clears missing local paths for that artist once before conflicts are loaded.
- A conflict action treats an album with no remaining local folder as provider-only, so it updates provider and album metadata without renaming a folder or editing audio tags.
- Each album conflict action opens one database transaction, applies database changes first, renames the album's one local folder when needed, commits only after the rename succeeds, and restores the original folder plus rolls back database changes when any transactional step fails.
- Audio-tag updates run after the database and folder transaction commits, so tag failures retain the resolved metadata and folder while producing warning results.
- Conflict-driven folder renames preserve provider metadata in the database and audio tags while rendering Windows-safe folder names with the configured character substitutions, collapsed whitespace, and no trailing spaces or periods.
- Folder rendering replaces `/`, `\`, `|`, `<`, `>`, and em dash with `-`, replaces `:` with ` -`, removes `?` and `*`, and replaces straight or curly double quotes with `'`.
- Conflict-driven folder renames fail instead of overwriting another folder when the rendered target already exists.
- Every conflict action updates the status bar and status history with its latest outcome, while successful folder renames use the actual old and new relative paths.
- A successful folder rename with tag failures produces one combined amber status while retaining expandable tag-failure details in the dialog.
- Only warning and error results appear in the expandable grouped panel above the dialog footer, while successful actions and folder renames add no green dialog message.
- The result panel has a constant bounded height, shows at most roughly one quarter of the dialog or five summary rows without growing, and scrolls internally for additional messages.
- Resolved sections disappear immediately, and the dialog closes automatically only when no conflicts and no result messages remain.
- Provider conflict choice tiles show compact source provider chips with provider icons and external-open icons.
- Compact source provider chips open the linked provider artist page and use tooltips such as `Open artist page in Metal Archives`.
- Collection-scoped provider checks assign genuinely new provider albums to the selected artist-centric collection and keep existing album homes unchanged.
- Provider checks from the global Artists screen assign genuinely new albums to the artist's most represented collection, breaking count ties by the same alphabetical order used in the UI.
- Artists represented only by title-centric collections do not participate in provider matching, bulk matching, or provider scans.
- MusicBrainz imports supported full albums only.
- MusicBrainz ignores EPs, singles, splits, compilations, live releases, demos, soundtrack secondary types, and malformed provider records as diagnostics.
- Exact, normalized, and high-confidence fuzzy matches from any supported provider may fill a missing library release year.
- The first successfully reconciled provider fills a missing release year, while a later provider with a different year creates an unresolved conflict instead of replacing it.
- Automatic missing-release-year enrichment updates only database metadata and never renames folders or writes audio tags.
- HTML providers import supported album rows from their discography pages and are covered by parser tests for the response shapes the app depends on.
- HTML provider artist details refresh provider country and active-status evidence from the artist page when the provider exposes those values.

## Frontend Screens

- `Collections` is the primary work surface.
- `Artists` is the global artist management and provider matching view.
- `Settings` exposes effective runtime configuration and UI preferences.
- The app shell owns the full viewport height and should not create a browser vertical scrollbar.
- Workspace panes, tables, dialogs, dropdowns, and history views scroll internally when their own content overflows.
- The status bar always keeps its configured top or bottom layout slot.
- During blocking full collection scan modals, the status bar remains visible and may show scan start or running text, but detailed progress belongs only in the modal.
- The status bar shows idle state when no operation is active.

## Collections Screen

- The Collections screen selects one collection at a time.
- No collection is selected on initial load.
- Selecting a collection loads the relevant middle or right pane and clears dependent selection from the previous collection.
- Artist collections show Collections, Artists, and Albums panes.
- Title collections show Collections and Titles panes, and the Titles grid contains only read-only `Title`, `Artist`, and `Year` data columns.
- The Titles grid has no collection or action column because title-centric collections do not expose album-home reassignment, and a headerless flexible spacer follows the resizable Year column so Year does not absorb unused pane width.
- Pane sizes are persisted as percentages.
- Artist and title pane filters are pane-local preferences.
- Artist local/non-local presence and the unchecked badge and filter stay scoped to the selected collection, while Albums `Show All` includes the artist's whole discography.
- The Albums pane `Show All` toggle defaults to on when no saved preference exists.
- With `Show All` off, the Albums pane shows the selected artist's albums whose home is the selected collection and hides the `Collection` column.
- With `Show All` on, the Albums pane shows all albums for the selected artist and the `Collection` column shows each album's home as a compact chip.
- A current-collection chip is dimmed and inert, while another collection's chip navigates to that collection, the same artist, and the same album.
- The Albums pane action column provides `Move to` for immediate non-local album reassignment through same-type destination chips.
- `Move to` remains visible but disabled for local albums and when no alternative same-type collection exists.
- Moving an album updates loaded album and artist caches without clearing either pane, removes rows that no longer match the current filter, and clears the selected artist only when the moved album was that artist's last home in the current collection.
- Albums and titles are never shown with a `No collection` state.
- The Collections screen does not expose real album database deletion.
- The Collections screen does not expose artist-level or album-level collection removal because collection presence is derived from required album homes.
- Collection album and title metadata is read-only; scans, provider scans, checked state, and non-local album rehoming remain available.
- The Collections screen does not expose artist metadata editing.
- Collection deletion confirms how many albums and newly albumless artists will be deleted from the database and never deletes the collection folder.
- After Delete is confirmed, the dialog closes immediately, the collection is unselected, its disabled dimmed row says `Deleting <name>…` beside a type-slot spinner until completion, mutations and global Artists navigation are disabled, and browsing another collection or Settings remains available.
- Successful collection deletion status identifies the deleted collection and reports its album and newly albumless artist counts.
- Deleting an artist from the global Artists screen is a real library database delete and never deletes files on disk.

## Artists Screen

- The first Artists screen visit shows a pane spinner while its multi-row library data loads.
- Artists, albums, and Artists-screen layout preferences remain cached for the browser session, so returning from another page renders from memory while mutations update affected entries and a browser reload starts a fresh load.
- The Artists screen search text is debounced before it becomes the applied filter.
- The Artists screen supports derived collection-presence filtering.
- Multiple selected collection filters use OR semantics.
- Clicking anywhere on a selected collection-filter chip removes that filter, while the visible close icon continues to communicate the action.
- Search text is AND-ed with the derived collection-presence filter.
- The Artists screen bulk provider scope is the currently visible artist rows missing the specific provider after search and collection filters.
- The displayed bulk count for each provider and submitted artist IDs must come from the same filtered list.
- Provider setup and provider matching controls use provider chips with normal provider display names.
- Saving or clearing provider identities updates affected rows in place where practical so pane scroll position is preserved.
- The Artists table shows artist name, country, status, album counts, local counts, provider identities, and row actions.
- The Artists table does not show provider artist type because providers use incompatible meanings for that field.
- Artists represented only by title-centric collections remain visible, but provider setup and scan controls are disabled and those artists are excluded from bulk provider matching.
- Country cells use bundled SVG flag assets and country names.
- Clicking a country cell opens a cell-anchored popover with a search field and country list.
- Country popover selections write only the artist country override.
- Manual country and status overrides are visually distinguished from provider-derived values so rescan-stable values are visible in the table.
- Status cells show only the effective status, and clicking the status opens a cell-anchored menu with Active, Inactive, and clear controls.
- Status menu edits write only the artist active-status override.
- The Artists detail pane is the main artist metadata edit surface for name and sort name.
- The Artists detail pane shows effective country and status plus provider evidence when an override differs from the provider value.
- Provider chips in Artist Info open that provider's artist page in a new browser tab.
- A provider check for the selected artist blocks Artist Info beneath the standard centered pane spinner until that artist check finishes.
- The Artists top tab and artist rows show warning indicators while provider artist metadata, release-year, or title conflicts remain unresolved.
- Known-album year and title chips show warning indicators while provider release-year or title conflicts remain unresolved.
- Artist rows expose a warning `Conflicts` row action when the artist has unresolved provider artist metadata, title, or release-year conflicts.
- Clicking the `Conflicts` action or an unresolved known-album year or title chip opens the provider conflict dialog for that artist only.
- Known-album year chips show an outlined kept-local state with an undo action when a provider year mismatch was resolved by keeping the local year.
- Known-album title chips use the same warning, kept-local, and overlay-control geometry as known-album year chips.
- Known-album titles show an outlined kept-local state with an undo action when a provider title mismatch was resolved by keeping the local title.
- The Artists detail pane shows known albums with the same local, checked non-local, and unchecked non-local color treatment used by collection album rows.
- An album checked from Collections keeps that updated state when the Artists screen performs its first session load concurrently with the change.

## UI Rules

- Use `docs/ui-guide.md` as the detailed visual and interaction contract.
- Use `docs/codex-ui-workflow-guide.md` as the frontend workflow and verification contract.
- Row actions select the row first and then run the clicked control when enabled.
- Row actions are hover or focus visible, and selected rows keep available actions visible.
- Inline row actions use the centralized row action button style.
- Visible app tooltips use the shared Vuetify tooltip pattern rather than native `title` attributes.
- `Remove` means unlink from the current context.
- `Delete` means delete from the library database.
- Present-on-disk rows use local presence styling scoped to the selected collection.
- Album display states in the Collections screen are defined in the workflow guide.

## API Surface

- Health uses `GET /api/health`.
- Collections use `/api/collections`.
- Collection candidates use `GET /api/collections/candidates`.
- Creating a collection posts only its direct-root `relativePath`, and the backend infers and persists its initial type during creation.
- Collection metadata uses `GET /api/collections/{id}/metadata`.
- Collection artists use `GET /api/collections/{id}/artists`.
- Collection titles use `GET /api/collections/{id}/titles`.
- Collection deletion impact uses `GET /api/collections/{id}/delete-preview`.
- Deleting a collection uses `DELETE /api/collections/{id}` and returns album and artist deletion counts.
- Artists use `GET`, `PUT`, and `DELETE` routes under `/api/artists`; standalone partial artist creation is not exposed.
- Albums use `GET` and `PUT` routes under `/api/albums`; standalone partial album creation is not exposed.
- Non-local album rehoming uses `PUT /api/albums/{albumId}/collection` with one required target collection ID.
- One-artist provider identities use `PUT /api/artists/{artistId}/provider` and `DELETE /api/artists/{artistId}/providers/{providerId}`.
- Provider candidate search uses `GET /api/artists/{artistId}/provider-candidates/{providerId}`.
- Provider bulk matching uses `POST /api/provider-matches/{providerId}/artists`.
- Provider bulk matching requires an explicit `artistIds` array and never falls back to all artists.
- Scan jobs use `/api/scan`.
- Provider check jobs use `/api/provider-checks`.
- Unresolved provider artist country conflicts use `GET /api/provider-conflicts/artist-countries`.
- Unresolved provider artist status conflicts use `GET /api/provider-conflicts/artist-statuses`.
- Unresolved provider release-year conflicts use `GET /api/provider-conflicts/release-years`.
- Unresolved provider title conflicts use `GET /api/provider-conflicts/titles`.
- Opening one artist's conflict dialog and clearing stale local paths uses `POST /api/provider-conflicts/artists/{artistId}/open`.
- Immediate title, year, country, and status conflict actions use `POST /api/provider-conflicts/resolve`.
- Settings use `/api/settings/music-root` and `/api/settings/ui`.
- Raw preference access uses `/api/preferences/{key}`.
- SPA fallback must stay separate from `/api`, `/q`, and asset-like routes.

## Current Constraints

- SQLite is intended for one running app instance against one database at a time.
- The app currently has no app-level lock file.
- The app currently has no automatic startup or shutdown backup flow.
- Track-level collection membership is not implemented.
- Track file scanning and audio tag parsing are not part of normal collection scans.
- Cover art is not implemented.
- Provider refresh has no track, release, cover-art, or MusicBrainz edit submission flow.
- HTML provider search pages are not stable public APIs, so parser tests are required when their parsing changes.
- Title provider scans are not a default title-collection workflow.
- Multi-user sync conflict handling is not implemented.
