# Current Application Reference

This document is the source of truth for current Music Library NG behavior.
Use `docs/evolution-*.md` only as historical context when the current implementation needs archaeology.

## Product Boundary

- Music Library NG is a local-first music collection app for one user running on one PC at a time.
- The backend is Quarkus on Java 21 with SQLite storage initialized from `src/main/resources/db/migration/V1__init.sql`.
- The frontend is Vue 3, Pinia, Vuetify, and Vite, and the production frontend is served by the Quarkus backend.
- The default app URL is `http://localhost:8795/`.
- Runtime data lives under `data/`.
- The app tracks artists, albums, collection membership, provider identities, local folder evidence, and listened state.
- The app does not manage audio files on disk unless a future feature explicitly says it is a filesystem operation.

## Runtime And Configuration

- Shared defaults live in `src/main/resources/application.properties`.
- Private or machine-specific overrides live in ignored `config/application.properties` or JVM `-D...` properties.
- The SQLite path defaults to `data/music-library-ng.sqlite`.
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
- `albums` stores durable album or title identity, release date, sort name, checked state, and notes.
- `album_artists` links albums to one or more artists and preserves contributor order.
- `collections` stores user-visible collections with a collection `type` and parser layout.
- `collection_albums` stores explicit album membership in collections.
- `album_local_paths` stores local folder evidence for an album within a collection.
- `artist_collections` stores collection-scoped artist scan state and local scan failures.
- `providers` stores supported provider kinds.
- `artist_provider_links` stores one provider identity per artist plus provider country, active-status, and disambiguation evidence.
- `album_provider_links` stores provider release-group mappings to local albums.
- `scan_runs` and `scan_events` store local scan history.
- `provider_check_runs` and `provider_check_events` store provider check history.
- `user_preferences` stores runtime preference overrides.

## Album State

- `albums.checked=true` means the user has listened to or accepted the album or title.
- `albums.checked=false` means the album or title is known but not listened to or accepted yet.
- Local disk presence is separate from checked state.
- Local presence comes from active `album_local_paths` rows whose resolved paths still exist.
- Local scans mark locally present albums checked because local folders are treated as already accepted library items.
- Provider-created albums are unchecked by default.
- Manual album creation defaults to checked when the caller omits the value.
- Present-on-disk albums and titles are shown checked in the UI and cannot be unchecked while local path evidence remains active.

## Collection Types And Parsers

- Collection `type` answers which entity the user browses first.
- Collection `parser` answers how folders encode metadata.
- `ARTIST` collections use the `Collections | Artists | Albums` layout.
- `TITLE` collections use the `Collections | Titles` layout.
- Supported parser layouts are `FLAT_ARTIST_YEAR_ALBUM`, `NESTED_ARTIST_ALBUM`, and `TITLE_PIPELINE`.
- Flat artist folders use `artist - release date - album`.
- Nested artist folders use `artist/release date - album`.
- Title folders support `title (artist, release date)`, `title (release date)`, `title - release date - subtitle`, `title - release date`, and title-only fallback.
- Release dates are stored as canonical text and may be `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`.
- Year display, chronological sorting, and release-date tooltips derive from the stored release date.
- Folder and path values are source evidence and must not be normalized by renaming folders on disk.

## Collection Semantics

- Album collection membership is explicit through `collection_albums`.
- Artist presence in a collection is derived from collection albums plus local scan-state rows.
- Artist membership alone is not enough to decide which albums belong to a collection.
- A soundtrack contributor can be linked to one title without implying that the full artist discography belongs to that collection.
- One album can belong to multiple collections.
- Deleting collection membership must not delete the shared album or artist unless a specific delete workflow says so.
- Deleting a collection removes collection membership, local path evidence, and artist scan-state rows for that collection.
- Database deletes must never delete files or folders on disk unless the request explicitly says it is a filesystem delete.

## Local Scans

- User-facing local scan actions run through scan job flows.
- Scan jobs provide status, progress, cancellation, history, reports, and post-scan refresh behavior.
- Collection scans enumerate direct child directories under the selected collection root.
- Title collection scans parse direct child folders into album rows and contributor artist links when artist metadata exists.
- Title collection scans assign scanned albums to the collection and upsert local path evidence.
- Artist collection scans discover artists, albums, collection membership, local path evidence, and local artist scan state.
- Explicit local album scans are available for one artist or for an entire artist collection.
- A one-artist local album scan removes stale local path rows only for that artist in the selected collection.
- A collection-wide local album scan removes stale local path rows for the whole selected collection.
- Local scans remove local path evidence that was not seen in the latest relevant scan.
- Local scans preserve album rows, checked state, and collection membership when a local path disappears.
- Local scans do not scan tracks.
- Scan reports are generated as plain text and stored under the configured report directory.

## Providers

- Supported provider IDs are `musicbrainz`, `spirit_of_metal`, and `metal_archives`.
- Each artist can have at most one provider identity.
- MusicBrainz identities use MBIDs and derive site URLs from those MBIDs.
- Spirit of Metal and Metal Archives identities use provider URLs validated and normalized by provider-specific code.
- Provider metadata stores country as an ISO alpha-2 code, active status as nullable boolean evidence, and disambiguation as provider evidence when available.
- The app supports `XW` as an `International` pseudo-country for manual artist country overrides.
- Artist country and active-status overrides live on the artist row and are never overwritten by provider rescans.
- Effective artist country and status prefer the artist override, then the current provider evidence, then unknown.
- Provider candidate search is shared across supported providers through `GET /api/artists/{artistId}/provider-candidates/{providerId}`.
- Bulk provider matching is shared across supported providers through `POST /api/provider-matches/{providerId}/artists`.
- Bulk provider matching receives exact artist IDs from the frontend visible scope.
- Bulk provider matching skips artists that already have a provider identity.
- Bulk provider matching auto-links only high-confidence matches and leaves ambiguous matches for manual selection.
- Provider checks run through provider job flows for user-facing scan buttons.
- Batch provider jobs skip provider links whose last successful check is inside the configured batch rescan delay.
- Failed provider checks do not block immediate retry.
- Individual artist provider checks always run even when the artist was checked recently.
- Provider checks do not infer local disk presence.
- Provider checks do not delete local albums or local path evidence because a provider omits a release.
- Collection-scoped provider checks assign newly created or otherwise unassigned provider albums to the selected collection.
- Provider checks from the global Artists screen refresh artist-level provider data without assigning collection membership.
- MusicBrainz imports supported full albums only.
- MusicBrainz ignores EPs, singles, splits, compilations, live releases, demos, soundtrack secondary types, and malformed provider records as diagnostics.
- MusicBrainz exact normalized title matches count as already in library and may fill a missing local release date.
- HTML providers import supported album rows from their discography pages and are covered by parser tests for the response shapes the app depends on.
- HTML provider artist details refresh provider country and active-status evidence from the artist page when the provider exposes those values.

## Frontend Screens

- `Collections` is the primary work surface.
- `Artists` is the global artist management and provider matching view.
- `Library` is the global album table view.
- `Settings` exposes effective runtime configuration and UI preferences.
- The app shell owns the full viewport height and should not create a browser vertical scrollbar.
- Workspace panes, tables, dialogs, dropdowns, and history views scroll internally when their own content overflows.
- The status bar is always visible and shows idle state when no operation is active.

## Collections Screen

- The Collections screen selects one collection at a time.
- No collection is selected on initial load.
- Selecting a collection loads the relevant middle or right pane and clears dependent selection from the previous collection.
- Artist collections show Collections, Artists, and Albums panes.
- Title collections show Collections and Titles panes.
- Pane sizes are persisted as percentages.
- Artist and title pane filters are pane-local preferences.
- The Albums pane `Show All` toggle defaults to on when no saved preference exists.
- With `Show All` off, the Albums pane shows albums that belong to the selected collection and selected artist.
- With `Show All` on, the Albums pane shows all albums for the selected artist.
- With `Show All` on, albums with no collection membership show a warning `No collection` chip.
- Removing an artist from a collection removes that artist's collection album links and scan-state row for the selected collection.
- Removing an artist from a collection does not delete the artist from the library database.
- The Collections screen does not expose artist metadata editing.
- Deleting an artist from the global Artists screen is a real library database delete and never deletes files on disk.

## Artists Screen

- The Artists screen search text is debounced before it becomes the applied filter.
- The Artists screen supports collection membership filtering.
- Multiple selected collection filters use OR semantics.
- Search text is AND-ed with the collection membership filter.
- The Artists screen bulk provider scope is the currently visible unlinked artist rows after search and collection filters.
- The displayed bulk count and submitted artist IDs must come from the same filtered list.
- Provider setup and provider matching controls use provider chips.
- Saving or clearing provider identities updates affected rows in place where practical so pane scroll position is preserved.
- The Artists table shows artist name, country, status, album counts, local counts, provider identity, and row actions.
- The Artists table does not show provider artist type because providers use incompatible meanings for that field.
- Country cells use bundled SVG flag assets and country names.
- Clicking a country cell opens a cell-anchored popover with a search field and country list.
- Country popover selections write only the artist country override.
- Manual country and status overrides are visually distinguished from provider-derived values so rescan-stable values are visible in the table.
- Status cells show only the effective status, and clicking the status opens a cell-anchored menu with Active, Split-up, and clear controls.
- Status menu edits write only the artist active-status override.
- The Artists detail pane is the main artist metadata edit surface for name and sort name.
- The Artists detail pane shows effective country and status plus provider evidence when an override differs from the provider value.

## UI Rules

- Use `docs/codex-ui-workflow-guide.md` as the detailed UI and verification contract.
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
- Collection metadata uses `GET /api/collections/{id}/metadata`.
- Collection artists use `GET /api/collections/{id}/artists`.
- Collection titles use `GET`, `POST`, `PUT`, and `DELETE` routes under `/api/collections/{id}/titles`.
- Collection album membership additions use `POST /api/collections/{id}/albums`.
- Artists use `GET`, `POST`, `PUT`, and `DELETE` routes under `/api/artists`.
- Artist removal from a collection uses `DELETE /api/artists/{id}/collections/{collectionId}`.
- Albums use `GET`, `POST`, `PUT`, and `DELETE` routes under `/api/albums`.
- One-artist provider identity uses `GET`, `PUT`, and `DELETE /api/artists/{artistId}/provider`.
- Generic artist provider links also exist under `/api/artists/{artistId}/provider-links`.
- Provider candidate search uses `GET /api/artists/{artistId}/provider-candidates/{providerId}`.
- Provider bulk matching uses `POST /api/provider-matches/{providerId}/artists`.
- Scan jobs and scan history use `/api/scan`.
- Provider check jobs and provider history use `/api/provider-checks`.
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
