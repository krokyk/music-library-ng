# Evolution 07: Collection Filters, Tooltips, And Row Actions

Date: 2026-06-22
Updated: 2026-06-23

## Scope

This document captures related Collections workspace UI cleanups from 2026-06-22 and 2026-06-23.

The work focused on the Collections screen Collections, Artists, Albums, and Titles panes, shared tooltip consistency, row action behavior, Settings cleanup, and workflow guide rules that future UI work should follow.

## Related Commits

- Commit `064f9fe` refined collection filters and tooltip behavior.
- Commit `6d85cb7` refined fit-based row actions.

## Filter And Tooltip Changes

- Added an Albums pane `Show All` toggle under the pane title using the same filter-bar style as the existing Artists and Titles pane filters.
- Kept the `Show All` button text fixed while changing behavior based on toggle state.
- `Show All` defaults to on when no saved preference exists.
- When `Show All` is off, the Albums pane shows albums that belong to the selected collection.
- When `Show All` is on, the Albums pane shows all albums for the selected artist.
- Changed the album collection chip column label from `Also in` to `In` when `Show All` is on.
- When `Show All` is on, the `In` column shows all collection chips including the selected collection.
  Albums with no collection memberships show a warning `No collection` chip in this column.
- Muted albums with no collection membership so they read as library-only rows.
- Added an Artists pane `Unchecked` filter modifier for quickly finding artists with unchecked albums.
- Kept artist filtering as `(Local OR Non-local) AND Unchecked when enabled`.
- Added descriptive filter tooltips for artist, title, and album filters.
- Replaced native browser `title` tooltips under `frontend/src` with the shared Vuetify tooltip pattern.
- Added a workflow-guide rule that visible app tooltips must use shared Vuetify tooltips instead of native `title` attributes.

## Action Label Changes

- Replaced configurable pane-width action-label thresholds with automatic fit-based action labels.
- Collection pane row actions now use row-local fitting instead of the old pane-width threshold.
- Collection pane rows keep the persistent info icon at the right edge and reveal hover, focus, selected, or scanning actions to its right.
- Collection pane rows use the same 20px protected visual gap rule as artist rows.
- Collection pane action labels collapse before the collection name ellipsizes.
- Idle collection rows no longer reserve permanent width for hidden edit, scan, and delete actions.
- The collection pane minimum reserves room for icon-only row actions, the persistent info icon, the collection type icon, and a readable collection-name prefix.
- Album and title grid row labels now depend on actual rightmost action-column space.
- Album action-label fitting uses the widest action set present in the current album list.
- The `Info` icon width is reserved only when at least one current album row has local paths.
- Artist row and global Artists screen action labels now collapse based on available action area instead of stored threshold preferences.
- Artist row action-label fitting reserves `Remove` only when at least one current artist row can show that action.
- Artist rows no longer reserve permanent width for hover-only actions.
- Idle artist rows give the artist name all remaining row width after persistent status chips.
- Hovered, focused, and selected artist rows reveal actions to the right of the unchecked chip and may ellipsize the name only while those actions are visible.
- Artist rows now measure the visible row name and use a 20px minimum visual gap between the rendered name and trailing chip or actions.
- Artist row fitting first tries expanded chip plus labeled actions, then expanded chip plus icon-only actions, then compact chip plus icon-only actions, then ellipsized name plus compact chip and icon-only actions.
- Pane filter toggle labels stay visible and are not part of the artist row collapse ladder.
- The artist pane minimum reserves room for icon-only row actions, a compact unchecked-count chip, and a readable artist-name prefix.
- Restored artist pane layouts are repaired to keep the pane at the same minimum width used by drag resize when the workspace has spare width.
- Pane resize now updates measured pane widths during drag so label state changes follow the actual layout.
- Icon-only action widths remain the minimum resize floor for action columns and panes.
- Removed action-label sliders from Settings because they exposed implementation details rather than useful runtime preferences.
- Removed action-label threshold fields from the UI settings API response, frontend types, Pinia defaults, backend settings DTO, config interface, and `application.properties`.
- Updated `docs/codex-ui-workflow-guide.md` so future UI work uses automatic fit-based action labels and does not reintroduce visible threshold settings.

## Preference Keys

- `collections-screen.artists-pane.presence-filter` stores the Artists pane Local and Non-local filter state.
- `collections-screen.artists-pane.unchecked-filter` stores the Artists pane Unchecked modifier state.
- `collections-screen.albums-pane.show-all-filter` stores the Albums pane Show All toggle state.
- `collections-screen.titles-pane.presence-filter` stores the Titles pane Local and Non-local filter state.

## Removed Preference Keys

- `collections-screen.collections-pane.action-label-threshold` is obsolete.
- `collections-screen.artists-pane.action-label-threshold` is obsolete.
- `collections-screen.albums-pane.action-label-threshold` is obsolete.
- `collections-screen.titles-pane.action-label-threshold` is obsolete.
- `SettingsResource` deletes those obsolete preference rows whenever UI settings are loaded or reset.

## UI Semantics

- Artists `Local` means artists with local albums in the selected collection.
- Artists `Non-local` means artists linked to the selected collection without local albums there.
- Artists `Unchecked` means artists with at least one unchecked album.
- Titles `Local` means titles with a local folder in the selected collection.
- Titles `Non-local` means titles not currently found on disk in the selected collection.
- Albums `Show All` off means collection membership in the selected collection.
- Albums `Show All` on means artist-scoped album display across the library.
- Row action labels appear when the containing pane or action column can fit the complete labeled action set.
- Row action labels collapse to icon-only when the labeled action set no longer fits.
- Hidden hover-only row actions must not reserve permanent text width.
- Non-table pane rows should keep persistent trailing indicators at the right edge and reveal hover-only actions beside them without shifting idle text width.
- Collection and artist pane rows preserve a 20px visual gap between the rendered name and the trailing indicator or action cluster.
- Persistent row status chips may reserve row width because they are visible even when the row is idle.
- Persistent info icons may reserve row width because they are visible even when the row is idle.
- Collection pane resize must preserve enough row width for icon-only actions, the persistent info icon, the collection type icon, and a readable name prefix.
- Artist pane resize must preserve enough row width for icon-only actions, a compact unchecked-count chip, and a readable name prefix.
- Pane and column resize minimums must preserve the icon-only action set.
- Label visibility should use current layout measurements and current row action availability, not user-configurable pixel thresholds.

## Tooltip Decisions

- Use descriptive tooltip text that explains the filter consequence, not just the visible label.
- Use dynamic tooltip text for Albums `Show All` so the tooltip explains the current toggle direction.
- Avoid native `title` attributes because they produce system/browser tooltips that visually conflict with app tooltips.
- Keep `aria-label` where it is used for accessibility and not as a visible tooltip implementation.

## Files Touched

- `frontend/src/views/CollectionsView.vue` owns the collection workspace filter logic, persisted filter state, filter tooltips, action-label fitting, collection and artist row trailing controls, and resize-time pane width updates.
- `frontend/src/views/ArtistsView.vue` switched artist and known-album text overflow tooltips to Vuetify tooltips and applies fit-based action-label behavior to the global Artists table.
- `frontend/src/views/SettingsView.vue` switched settings label help to Vuetify tooltips and removed action-label threshold controls.
- `frontend/src/stores/library.ts` and `frontend/src/types.ts` removed action-label threshold settings from the frontend contract.
- `frontend/src/styles.css` adds muted no-collection album row styling and the collection and artist row trailing action layout.
- `src/main/java/org/kroky/musiclib/resource/SettingsResource.java`, `src/main/java/org/kroky/musiclib/model/UiSettings.java`, and `src/main/java/org/kroky/musiclib/config/MusicLibraryConfig.java` removed action-label threshold settings from the backend contract and defaults.
- `src/main/resources/application.properties` removed obsolete action-label threshold default properties.
- `docs/codex-ui-workflow-guide.md` documents the filter keys, album filter behavior, unchecked artist modifier, tooltip rule, and automatic action-label rule.

## Verification Already Run

- `npm run build`
- `npm run build --prefix frontend`
- `./gradlew test`
- `./gradlew build`
- `./gradlew quarkusBuild -x test`
- `git diff --check`
- `rg -n "\btitle=|:title=" frontend/src`
- `scripts/check-ui-layout.ps1` against a packaged app on temporary port `8796` using a copied SQLite database for commit `064f9fe`.
- `scripts/check-ui-layout.ps1` against the packaged app on `http://localhost:8795/` for the action-label cleanup.
- `curl http://localhost:8795/api/settings/ui` confirmed the UI settings response no longer includes action-label threshold fields.
- `sqlite3 data/music-library-ng.sqlite "select key, value from user_preferences where key like '%action-label-threshold%' order by key;"` confirmed no obsolete DB preference rows remain.

## Preserved Rules

- The `Unchecked` artist filter is intentionally a modifier, not a third presence option.
- Additional artist filters should remain separate modifiers unless they are truly mutually exclusive with Local and Non-local.
- If filter bars become crowded at smaller pane widths, shorten visible labels before changing the logical model.
- Do not reintroduce visible Settings controls for action-label pixel thresholds.
- If action buttons change, update the fit-based action width model instead of adding pane-width preferences.
- Apply the non-table row trailing-cluster rule to future pane rows like Collections and Artists before applying it to grid/table panes.
- Grid and table panes should keep using their action-column width model unless their table structure is deliberately changed.
