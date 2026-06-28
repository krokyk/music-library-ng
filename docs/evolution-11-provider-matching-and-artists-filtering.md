# Evolution 11: Provider Matching And Artists Filtering

## Scope

This document records the provider matching and main Artists screen filtering changes after Evolution 10.
The work covers shared provider candidate search, bulk provider matching for visible unlinked artists, collection membership filtering in the Artists pane, provider-chip setup controls, and removal of the old Settings bulk MusicBrainz entry point.

## Provider Candidate Search

- MusicBrainz, Spirit of Metal, and Metal Archives all use the same artist candidate DTO.
- Candidate rows include provider ID, remote artist ID, remote artist name, provider URL, provider metadata, provider score, local match score, matched local albums, and preview remote albums.
- MusicBrainz candidate search still uses the MusicBrainz web service and release-group evidence.
- Spirit of Metal candidate search fetches `find.php`, parses only the bands result section, and ignores album and label result sections for artist identity matching.
- Spirit of Metal row order is only search evidence, not proof of correctness, because broad searches can put an alias or neighboring name before the exact artist.
- Metal Archives candidate search uses the `search/ajax-band-search` JSON endpoint and parses the returned band table rows.
- Metal Archives candidate URLs may be band-page URLs, while saved provider URLs are still normalized to the main discography URL shape.
- HTML-provider candidate album evidence is fetched through the existing provider discography fetchers and mapped into preview album rows for scoring.

## Bulk Matching

- Bulk provider matching now lives in the global Artists pane instead of Settings.
- The bulk scope is the currently visible Artists pane rows after search and collection filters, with artists that already have a provider removed.
- The frontend sends the exact visible unlinked artist IDs to the bulk API so the displayed count and backend work scope cannot drift.
- The bulk label is `Bulk match 84 unlinked artists:` in the expanded state.
- Provider chips sit immediately to the right of the label.
- Shrinking removes provider chip labels first, then collapses the label to `(84)`, then lets the search text field give up space.
- The minimum Artists pane width is constrained by grid column minimums plus the minimum operational bulk-match controls.
- Bulk matching auto-links only high-confidence candidates with local album evidence and sufficient score separation from the runner-up.
- Ambiguous candidates remain manual rows in the bulk result dialog.
- Artists with existing providers are skipped defensively by the backend even though the frontend removes them from the submitted scope.

## Collection Filtering

- The main Artists pane has collection membership filtering under the search bar.
- `All` means no collection restriction.
- Selecting `All` clears all selected collections.
- Selecting one or more collections deselects `All`.
- Multiple selected collections are OR-ed.
- Search text is AND-ed with the collection membership filter.
- The selected collection filter is stored in the `artists-screen.collection-filter.ids` preference.
- A small plus control opens an anchored checkbox menu for all collections.
- Selected collections are shown as chips in the filter row, with overflow summarized as a count chip.
- The Collections page artist dialog edits artist metadata only and does not expose manual collection membership.
- The Collections page does not offer collection-scoped creation of bare artist rows because collection membership is derived from collection content.
- The public artist create/update API no longer accepts collection IDs.
- Collection-scoped artist lists, collection artist counts, and collection provider scans derive artist presence from album collection links plus local scan-state rows.
- Removing an artist from the selected collection removes the artist's collection album links and local scan-state row so the row disappears without resetting the pane scroll.
- Editing a collection name patches the collection row without reselecting the collection or refreshing pane content.
- Collection scan completion refreshes scanned collection rows in place and preserves the selected artist and pane scroll position when the artist still exists.

## Provider Chips

- Provider choices in setup and bulk matching use real provider chips, not generic blue provider buttons.
- Provider chips use the same provider icon and provider-specific class family as the Artists pane provider column.
- The normal maximum provider-chip visual is icon plus label.
- Icon-only provider chips are the compact form used when horizontal space is tight.
- Clicking a provider setup chip immediately searches candidates for that provider.
- Candidate `Use` actions remain regular command buttons because they are not provider identity controls.
- Saving or clearing an artist provider updates the affected artist, provider link cache, and selected artist albums in place instead of reloading collection content, so pane scroll positions are preserved.
- Bulk provider matching also refreshes processed artist rows in place and does not clear collection content.

## API

- `GET /api/artists/{artistId}/provider-candidates/{providerId}` returns candidates for the requested provider.
- `POST /api/provider-matches/{providerId}/artists` bulk-matches the supplied artist IDs for the requested provider.
- `ArtistProviderMatchService` owns shared candidate scoring and remote album evidence collection.
- `ArtistProviderBulkMatchService` owns shared bulk matching and conservative auto-linking.
- `SpiritOfMetalProvider` owns Spirit of Metal band search parsing and album discography fetching.
- `MetalArchivesProvider` owns Metal Archives AJAX band search parsing and album discography fetching.

## Frontend Code Paths

- `frontend/src/views/ArtistsView.vue` owns Artists search, collection membership filtering, bulk provider chips, provider candidate dialog, and bulk result dialog.
- `frontend/src/views/CollectionsView.vue` owns collection-scoped add-provider setup and now uses provider chips plus shared candidate search.
- `frontend/src/stores/library.ts` owns generic provider candidate search and generic bulk provider match actions.
- `frontend/src/providers.ts` remains the shared provider label, icon, class, and URL-validation registry.
- `frontend/src/styles.css` owns provider-chip action styling, Artists bulk-control shrink behavior, and the collection filter row/menu styling.

## Current Constraints

- HTML provider search pages are not stable public APIs, so parser tests cover the response shapes the app depends on.
- Spirit of Metal and Metal Archives matching remains intentionally conservative for automatic linking.
- Manual URL fallback remains available for URL-based providers in the Collections add-provider dialog.
- The global Artists screen provider setup path is candidate-first and does not expose a separate URL prompt.

## Verification

- `npm run build --prefix frontend`
- `./gradlew test --tests org.kroky.musiclib.provider.html.MetalArchivesProviderTest --tests org.kroky.musiclib.provider.html.SpiritOfMetalProviderTest --tests org.kroky.musiclib.provider.ArtistProviderBulkMatchServiceTest`
- `./gradlew test`
- `./gradlew build`
- `scripts/check-ui-layout.ps1` passed against the packaged app on `http://localhost:8795/` with a temporary fixture SQLite DB containing `Melodeath` and `Soundtracks`.
- The packaged app could not be smoke-tested against the existing `data/music-library-ng.sqlite` because that local DB has a Flyway checksum mismatch for migration version `1`.
- Live Spirit of Metal and Metal Archives searches for `angra` and `Mors Principium Est` were checked against the current response shapes.
