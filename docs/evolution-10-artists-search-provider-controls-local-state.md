# Evolution 10: Artists Search, Provider Controls, And Local State

## Scope

This document records Artists screen search refinements and provider/local-state workflow updates made after Evolution 09.
Future commits that continue compact Artists screen search, filtering, provider controls, collection artist scan ergonomics, or Albums pane local-state filtering should extend this document instead of adding those notes to Evolution 09.
Evolution 11 supersedes the provider matching and global Artists screen bulk-control details that were current when this document was written.
Evolution 11 also supersedes the manual artist collection membership details that were current when this document was written.

## Behavior Captured In Evolution 10

### Artists Search

- The main Artists screen search text is stored separately from the applied filter text.
  Filtering is applied after a short debounce so typing does not force the virtualized table to filter and sort on every keypress.
- The search field keeps its floating `Search artists` label but the search row is taller so the focused label is not clipped by the pane header.
- The search field has an explicit round clear button inside the right end of the field.
  Clicking the button or pressing `Escape` clears both the typed text and the applied filter immediately.
- The search field is left-aligned and uses half of the current Artists pane width.
  The field width follows pane resizing without additional stored UI state.

### Collection Artist Local State

- Artist-centric collection scans discover artists and local albums together for supported flat artist-year-album folders and nested artist/album folders.
- Artists found from local disk evidence are marked local for that collection so their rows render as active local rows immediately after the collection scan.
- Manually assigned artists remain nonlocal and dim until a collection scan or local album scan finds local disk evidence for that artist in that collection.
- Artist-only folders without parsed album subfolders still count as local artist evidence, but they may leave the albums pane empty until albums are added or discovered.
- Removing an artist from a collection is available only when no albums are associated with that artist in the selected collection.
- The Collections artists pane labels this association action `Remove` and uses `Remove from collection` as the tooltip.

### Provider Controls

- The Collections artists pane shows `Add provider` when an artist has no provider.
- Choosing MusicBrainz opened candidate search, and choosing a candidate saved the provider then immediately started that artist provider scan.
- Choosing Spirit of Metal or Metal Archives prompted for a URL, validated it before save, saved it, and immediately started that artist provider scan.
- When an artist already has a provider, the provider control is the provider chip.
- Clicking the provider chip starts a provider scan for that artist.
- The provider chip trash icon removes the provider and returns the control to `Add provider`.
- The same provider chip scan behavior applies in the global Artists screen, where the scan is unscoped and refreshes the selected artist details on that screen.
- Provider scans from the Collections artists pane add discovered albums to the selected collection only when the album has no collection memberships yet.
- Provider scans from the global Artists screen do not assign collection memberships.
- The empty albums pane keeps the existing local scan action and shows the same provider action as the Collections artists pane.
- Row actions remain visible only through the existing selected-row or hover behavior, and the actions are disabled during any scan or provider job.

### Albums Pane Filtering

- The Albums pane `Show All` filter defaults to on when no saved preference exists.
- With `Show All` on, the Albums pane shows all albums for the selected artist.
- With `Show All` off, the Albums pane shows albums that belong to the selected collection.
- With `Show All` on, albums with no collection memberships show a warning `No collection` chip in the `In` column.
- The Albums pane title bar shows a warning-colored `Add orphans` action when the selected artist has albums with no collection memberships.
  The action adds all such albums to the active collection.

### Provider URL Rules

- Spirit of Metal URLs must match `https://www.spirit-of-metal.com/en/band/<name>`.
- Spirit of Metal URLs are stored and used as provided after validation.
- Metal Archives accepts band page URLs such as `https://www.metal-archives.com/bands/Asleigh_Stake/3540329211`.
- Metal Archives URLs are normalized to the discography URL shape used by the provider fetcher.

### Scan Failure Signals

- Local artist scan failures are stored on the artist scan-state row.
- Provider scan failures are stored on the artist provider link.
- The Collections artists pane shows a red circular failure icon before the artist name only when a local or provider scan failure is present.
- The failure icon tooltip is intentionally short because the status bar and scan history carry detailed failure messages.

## Code Paths

- `frontend/src/views/ArtistsView.vue` owns the typed search text, applied search text, debounce timer, clear behavior, and Escape handling.
- `frontend/src/views/ArtistsView.vue` also owns global artist provider chip scanning and provider URL setup.
- `frontend/src/views/CollectionsView.vue` owns collection-scoped artist local state rendering, Albums pane `Show All` filtering, collection provider setup, provider chip actions, remove-from-collection labeling, and scan failure icons.
- `frontend/src/providers.ts` owns shared provider labels, icons, chip classes, and URL validation helpers.
- `frontend/src/stores/library.ts` owns provider job starts and targeted artist refresh after scan or provider job progress changes.
- `frontend/src/styles.css` owns the Artists search row height, half-width field sizing, and round clear-button styling.
- `frontend/src/styles.css` also owns collection provider chip sizing and the scan failure icon style.
- `src/main/java/org/kroky/musiclib/scan/ScanService.java` owns combined artist-centric collection discovery and explicit local album rescans.
- `src/main/java/org/kroky/musiclib/repository/ArtistRepository.java` owns local artist membership state, scoped collection album counts, and local scan failure storage.
- `src/main/java/org/kroky/musiclib/provider/ProviderUrlNormalizer.java` owns backend provider URL validation and Metal Archives normalization.
- `src/main/java/org/kroky/musiclib/resource/ArtistProviderResource.java` and `src/main/java/org/kroky/musiclib/resource/ArtistProviderLinkResource.java` apply backend URL validation before saving provider links.
- `src/main/resources/db/migration/V1__init.sql` remains the only schema migration for the fresh-start development database.

## Verification

- Run `npm run build --prefix frontend` after changes to this area.
- Run `./gradlew test` after provider URL, scan, repository, or DTO changes.
- Use the packaged UI smoke test when search row spacing, table layout, or pane resizing changes.
