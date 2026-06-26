# Evolution 05: Collection And Album UI State

Date: 2026-06-10

## Scope

This document captures the current collection/album UI state changes, artist deletion/removal behavior, scan report behavior, and project documentation relocation.

## Related Commits

- Commit `c347596` refined collection album state and scan report UI.
- Commit `f07278c` refined collection album UI behavior.

## Main Changes

- Moved planning/evolution/workflow Markdown files into `docs/`.
- Added album collection membership data to backend album responses.
- Added an optional `Collections` column in the Albums pane, shown only when an album has extra collection memberships outside the selected collection.
- Changed album row styling to distinguish:
  - album in the selected collection,
  - album in another collection,
  - checked album not on disk,
  - unchecked album not on disk.
- Removed folder status icons from album names and kept local path information behind the small info action.
- Prevented unchecking albums that are still present on disk.
- Added artist removal from the selected collection in the Collections screen for artists that no longer have local albums in that collection.
- Added real artist deletion from the main Artists screen with a second warning when the artist belongs to collections or has local albums in the DB.
- Made artist deletion show row-level busy/disabled state.
- Added manual status messages so one-off actions can use the global status bar.
- Adjusted artist-centric pane resizing so dragging the Collections/Artists divider keeps the Artists pane width constant and changes Collections/Albums as a block.
- Improved scan report modal:
  - bounded height,
  - internal scrolling,
  - denser report text,
  - copy-to-clipboard action,
  - report navigation with paging,
  - wider content wrapper via Vuetify `content-class`.
- Simplified artist-centric collection report rows to list only artist names, not the folder that first discovered them.
- Updated UI smoke check defaults to 1920x1080.

## Backend Notes

- `Album` now includes `collections`.
- `AlbumCollection` was added as a compact album membership DTO.
- `AlbumRepository` loads collection memberships for each mapped album.
- `ArtistRepository.delete` now deletes the artist and albums exclusively linked to that artist in a transaction, then removes orphan albums.
- `ArtistRepository.removeFromCollection` removes artist membership and related collection-album rows scoped to that collection.
- `AlbumResource.update` rejects unchecking an album while it has an active local path.
- Artist delete logging was lowered from warning to info.

## Frontend Notes

- `collectionAlbums` now loads all albums for the selected artist rather than only albums in the selected collection.
  Row styling and collection chips explain how each album relates to the selected collection.
- The Albums pane `Collections` column is dynamic.
  It is hidden when no visible row needs it.
- The report dialog width fix depends on `content-class="scan-report-dialog-content"` because Vuetify applies width to `.v-overlay__content`, not the root dialog class.
- If visual checks still show a narrow report dialog, inspect the actual DOM for `.scan-report-dialog-content` on `.v-overlay__content` and confirm the packaged frontend bundle was refreshed.

## Verification Already Run

- `npm run build --prefix frontend`
- `./gradlew build`
- `git diff --check`
- `scripts/check-ui-layout.ps1` was run earlier in this change set at 1920x1080 and passed for the main workspace panes.

## Current Constraint

- The UI smoke script checks workspace pane layout, but it does not explicitly open scan report dialogs.
