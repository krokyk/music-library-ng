# UI Guide

This file is the visual and interaction contract for every screen in Music Library NG.
Read it before changing frontend layout, styling, controls, or behavior.
Match an existing pattern with the same meaning before adding a new one.

## Visual System

This section is the default visual contract for new and changed UI.
Match an existing control with the same meaning before introducing new styling.
Equivalent concepts and operations use the same label, component, color, geometry, and interaction across screens.
An intentional difference must follow a real workflow difference and be recorded in the relevant screen rules below.
Reuse a shared component or shared style when the same control pattern appears more than once.
Do not copy a control and allow screen-specific versions to drift.

### Theme And Surfaces

- Keep the current dark, dense desktop-workspace appearance.
- Theme colors are owned by `frontend/src/main.ts`: primary cyan `#00d5ff`, error red `#ff5f6d`, warning amber `#f8c14a`, success green `#38d996`, surface `#141414`, and background `#080808`.
- Shared layout and component styling belongs in `frontend/src/styles.css`.
  Keep one-off component CSS local only when it has no reusable visual meaning.
- Use the darkest background for the app shell, slightly lighter surfaces for panes and overlays, and `#101010` for pane headers, filter bars, and grid headers.
- Separate surfaces with restrained one-pixel translucent borders instead of shadows or decorative cards inside workspace panes.
- Use low-opacity cyan backgrounds for selection, subtle neutral backgrounds for hover, and never change layout dimensions between idle, hover, focus, and selected states.
- Shared text emphasis levels are `--app-text-muted` for secondary metadata and inactive custom rows, `--app-text-deemphasized` for accepted content intentionally pushed into the background, and `--app-text-attention-muted` for unchecked content that still needs attention.
- Reuse these variables for matching meanings instead of adding another dimmed color, while framework-disabled controls retain Vuetify's disabled treatment.
- Shared structural colors are `--app-background`, `--app-surface-pane`, `--app-surface-header`, and `--app-line-subtle`; reuse them for the corresponding shell, pane, header, and separator roles.

### Typography And Density

- The app is a dense desktop tool, not a spacious mobile or marketing interface.
- App navigation and pane titles use heavy uppercase text.
- Pane titles are the strongest text inside a workspace; optional pane metadata is smaller, mixed case, and muted.
- Grid headers use small, bold, uppercase, muted text.
- Row labels use normal casing and ellipsize rather than wrapping.
- Preserve existing control heights: pane toolbar controls and toggles are `24px`, compact row actions are `22px`, grid headers are `38px`, grid rows are `42px`, and collection navigation rows are `34px`.
- Use existing spacing before inventing a new value: `2px` between adjacent actions, `6px` between an action group and row content, `8px` inside ordinary compact groups, `10px` for small form gaps, and `20px` for separation between row text and trailing controls.

### Semantic Colors

- Color communicates meaning and must not be chosen independently per screen.
- Enabled normal actions use primary cyan, including Add, Edit, Move, Save, Scan, Provider, Sort, navigation actions, and other non-destructive mutations.
- Destructive actions use error red, including Delete and destructive confirmation controls.
- Attention-needed states and conflict actions use warning amber.
- Successful or present-on-disk states use success green only when success or presence is the meaning.
- Informational icons that do not perform an action use muted neutral grey.
- Cancel, Close, and passive read-only values may remain neutral.
- Disabled controls use Vuetify's disabled treatment and must not be made to look enabled with a semantic color override.
- Every enabled action button must declare its semantic Vuetify `color` explicitly or inherit it from a shared semantic component or class.
  Do not rely on Vuetify's default white foreground for an action.
- Provider identity chips keep their provider-specific colors and are the only routine exception to the app semantic palette.

### Buttons And Action Controls

- Use Vuetify buttons and the existing `app-toolbar-button` or `workspace-row-action` styles instead of creating a new button treatment.
- Pane-header and filter-bar actions use text buttons with a primary icon and a short label when space allows.
- Row actions use text buttons with an icon and short label when the complete action set fits.
  Collapse labels before icons when space becomes tight.
- Use icon-only buttons only when space requires them or the icon is already unambiguous in the same context.
- An action that opens a menu remains a button; the menu choices may be chips when they represent compact values or destinations.
- Toggle groups use the existing compact rounded toolbar toggle.
  Selected toggles use primary cyan fill; unselected toggles remain neutral.
- Do not use a disabled select, text field, or combo box to display a value.
  Use text or a read-only chip instead.
- Do not add confirmation for fast, reversible, non-destructive actions unless the workflow explicitly requires it.

### Chips, Badges, And Ovals

- Use a chip for one compact value, identity, destination, filter, or state; do not use chips as generic decoration.
- Neutral metadata such as a release year uses the compact muted tonal oval treatment.
- A current or read-only collection chip is dimmed and inert.
  A different collection chip is primary cyan only when clicking it navigates or applies that collection.
- Destination menus use compact primary chips because each chip performs the same normal action with a different value.
- Filter choices belong in the shared compact button-toggle treatment, not standalone ad hoc chips.
- Warning, success, and error chips use their semantic colors only when the chip itself represents that state.
- Provider chips use `ProviderChip` and its provider-specific visual identity instead of a generic chip.
- Read-only provider identity chips in Artist Info and conflict sources use `ProviderChip` external-open behavior and open the provider artist page when a URL is available.
- Clickable and read-only versions of the same chip must be distinguishable by color and pointer behavior without adding a tooltip solely to explain clickability.
- Keep related chips the same height and geometry within one row or list.
- Avoid multiple nested pills, badges, or outlines when one chip can communicate the state.

### Overlay Decorations

- Additional icons that decorate a primary value, chip, text label, or control use one shared upper-right overlay position.
- Follow the existing conflict-triangle and undo-button geometry: place the decoration over the host's upper-right edge with a `20px` square visual slot, approximately `top: -8px`, and the existing `4px` to `8px` right inset.
- Overlay decorations are absolutely positioned and must not consume layout width, change row height, shift the host component, or disturb column alignment.
- A passive state marker such as the conflict triangle and a contextual action such as Undo occupy the same overlay slot rather than appearing at different edges.
- Show only the decoration relevant to the current state.
- Passive markers remain visible; contextual actions may appear on host-row hover, focus-within, or keyboard focus when the existing interaction uses that reveal pattern.
- Reuse the `album-metadata-chip-badge` wrapper and its icon/reset geometry when decorating album metadata.
  Extend the same upper-right geometry to other host components instead of inventing another badge position.
- Preserve semantic color and tooltip rules for the decoration itself: warning markers are amber, normal actions are primary cyan, and unfamiliar or actionable icons use the shared Vuetify tooltip pattern.
  A kept-local undo icon is white because it belongs to the accepted white-outline metadata state rather than an unresolved warning.

### Icons, Tooltips, And Feedback

- Use Material Design Icons already bundled with the app.
- Pair the same action with the same icon across screens.
- Normal action icons follow the action's primary cyan color; destructive icons are red; warning icons are amber; passive information icons are muted grey.
- Tooltips explain unfamiliar icons, disabled reasons, clipped important text, or non-obvious consequences.
  Do not add tooltips that merely repeat visible labels.
- Use Vuetify tooltips, never native `title` attributes.
- Keep ordinary successful actions quiet apart from the status bar.
  Use spinners only for work slow enough that the user could reasonably wonder whether the click registered.
- Show a pane spinner when initially populating that pane's multi-row dataset without cached rows, while single-row loads and targeted refreshes keep existing pane content visible.
- Use `AppSpinner` for every indeterminate spinner and choose only `pane` (`60px`/`5px`), `inline` (`16px`/`2px`), or `control` (`22px`/`2px`).
  All shared spinners use primary cyan.
  Do not render `v-progress-circular` directly outside `AppSpinner` or introduce another spinner size or color without changing this shared contract.
- Errors, warnings, and durable information must use the established semantic colors and existing status or result-panel patterns.
- Status-history messages with expandable reports or details use a bright same-color document icon plus bold text to advertise whole-row clickability, while messages without details use no icon, normal-weight text, and the same muted color as their timestamp.

### Selection, Navigation, And Interaction

- Selected navigation and grid rows use the existing low-opacity cyan background.
- Hover uses the existing subtle background and must not compete visually with selection.
- Clicking a row selects it; clicking an enabled control inside the row selects the row first and then performs the control action.
- Current destinations are inert and dimmed when navigating to the current place would do nothing.
- Navigation chips change context without changing data; action buttons such as `Move to` change data without implicitly navigating unless that behavior is explicitly required.
- Keep controls visible on selected rows and reveal hover-only row actions without shifting columns or row height.

## App Shell And Height Rules

- The UI targets desktop browser windows only, so do not add mobile-device-specific guards or layouts.
- Desktop browser resizing must remain supported within the current viewport.
- The app must not create a browser vertical scrollbar.
  The app shell owns the full viewport height.
- Panes, tables, lists, dialogs, dropdowns, and history views scroll internally only when their own content overflows.
- Status bar placement must not cause content jumps.
  It is always visible and shows idle state when no operation is active.
  During blocking full collection scan modals, keep the bar's layout slot visible and do not render detailed progress there.
- If the status bar is configured at the bottom, popups/history should open above it.
  If it is at the top, they should open below it.
- Status history grows to its longest unwrapped entry up to a viewport-safe `70vw` width and grows vertically up to half of the available workspace on the side where it opens, with overflow scrolling inside the history window.

## Pane Layout Rules

- Runtime DB preference keys should be hierarchical and semantic:
  - screen-scoped pane/table state: `<screen>.<pane>.<thing>`
  - table column widths: `<screen>.<pane>.<column>`
  - coupled pane layouts: `<screen>.<layout-kind>.panes`
  - global UI state: `ui.<area>.<thing>`
  Do not add new flat keys such as `collections.columns.album`.
- Workspace screens use pane layouts, not marketing/landing layouts.
- Panes are resizable along the full vertical divider, not only at a corner.
- Each workspace layout designates one flexible pane, normally the rightmost pane, while user-adjusted non-flexible pane widths are persisted as CSS pixels.
- Browser-window resizing, maximizing, restoring, and snapping must preserve preferred non-flexible pane widths and apply the width difference to the flexible pane.
- Browser-driven or constraint-driven pane resizing must never overwrite preferred pane widths.
- When the viewport cannot fit every preferred width, temporarily contract panes from right to left toward their practical minimums and restore the preferred widths as space returns.
- The Collections pane uses one shared width in artist-centric and title-centric collection layouts.
  Current pane-width preference keys are `collections-screen.collections-pane.width`, `collections-screen.artists-pane.width`, and `artists-screen.artists-pane.width`.
- Pane resizing should behave like block movement:
  - resizing the collections pane moves the remaining panes as a block
  - resizing the middle pane moves the right pane as a block
  - rightmost pane absorbs remaining width
- Pane minimum widths must keep the pane title and required icon-only controls visible.
- Panes must not shrink so far that their title bar becomes unusable.
- Pane resizing must not mutate stored table column widths inside those panes.
  The rightmost/flexible table column visually absorbs pane width changes.
- Collection pane dropdowns should anchor under the triggering button.
  They should grow to available pane height, then scroll internally.
- Add-folder dropdowns should list folder names only and be only as wide as needed for the widest name, with the right edge aligned to the collection pane.

## Grid And Table Rules

- Workspace pane tables that need resizing or sticky headers use the custom CSS grid pattern, not Vuetify `v-table`.
- Workspace pane tables with unbounded or scan/provider-populated row counts must be virtualized.
- The Collections screen Artists, Albums, and Titles tables must all remain virtualized.
- Future library-management screens with full-library tables, including the main Artists screen, should use the same virtualized grid pattern before row counts become large.
- Use explicit pixel column widths from defaults/preferences only for fixed columns.
- Column width defaults come from `application.properties`; user-adjusted widths are stored as DB preferences.
  Future screens should follow the same shape, for example `artists-screen.artists-pane.name`.
  Current collection-screen column keys include `collections-screen.albums-pane.name`, `collections-screen.albums-pane.release-year`, `collections-screen.albums-pane.checked`, `collections-screen.albums-pane.home`, `collections-screen.titles-pane.title`, `collections-screen.titles-pane.artist`, and `collections-screen.titles-pane.release-year`.
  Current Artists screen column keys include `artists-screen.artists-pane.name`, `artists-screen.artists-pane.country`, `artists-screen.artists-pane.status`, `artists-screen.artists-pane.albums`, `artists-screen.artists-pane.unchecked`, `artists-screen.artists-pane.local`, and `artists-screen.artists-pane.provider`.
  Do not persist the width of a single-column inline row, a flexible spacer, or a rightmost action column.
- Non-rightmost columns keep fixed pixel widths.
  The rightmost column uses remaining available space.
- Workspace grids must use the shared column-sizing module for minimum widths, resize boundaries, browser and pane resize response, and fixed-width persistence.
- A read-only grid may place a headerless flexible spacer after its final data column so the final data column keeps a practical persisted width and resize boundary without inventing an action column.
- Each column boundary has one resize handle.
- Dragging a column boundary resizes the column immediately to the left of the boundary.
  Columns to the right move as a block; the rightmost/flexible column absorbs or gives up width.
- Column resizing must not trigger sorting.
- Double-click auto-fit on column boundaries is disabled unless it can be made reliable.
- Classify columns by behavior instead of by screen or entity.
  Text columns stay on one line and ellipsize.
  Atomic value columns preserve the complete year, number, icon, or compact immutable value.
  Control columns preserve the complete checkbox, editable status, or equivalent control.
  Chip-set columns may compact labels but may not hide complete controls.
  Action columns show the whole visible action set either fully labeled or entirely icon-only.
  Flexible spacers absorb unused width and are neither resizable nor persisted.
- A compound inline row where a name and trailing controls share one physical cell is a layout context, not another column type.
- Atomic, control, chip-set, and action column minimums must be derived from the complete currently displayed content instead of a generic table minimum.
- Numeric columns must never wrap, clip, ellipsize, or shrink below their widest displayed number.
- Standalone release years throughout the app use the shared year chip with intrinsic width, tabular numerals, centered alignment, a 16-pixel font at weight 600, and a literal visible height of 26 pixels.
- Conflicted and kept-local year and album-title chips retain the normal year or title typography, use a literal visible height of 26 pixels, and indicate state only with a single inset outline and hover fill.
  Unresolved conflicts use a bright warning outline with a faint warning hover fill, while locally resolved conflicts use a white outline with a faint white hover fill.
  Year chips retain their normal neutral fill in both states, while album-title chips remain transparent.
- Count-only chips use intrinsic width, tabular numerals, right alignment, a 14-pixel normal-weight white font, and a literal visible height of 24 pixels.
- Inline issue chips such as `3 unchecked` use the 14-pixel count numeral with a 12-pixel normal-weight label in a literal 22-pixel visible height.
- Collection chips use a 12-pixel normal-weight font and a literal visible height of 22 pixels.
- Provider chips use the shared Artists-screen provider presentation everywhere: a 12-pixel font at weight 800 and a literal visible height of 22 pixels.
- Chip borders and state outlines must be paint-only so the chip element and its visible fill retain the same specified height.
- Positive unchecked counts use the same muted attention text color as unchecked album names, with that color naturally dimming the standard tonal underlay, while zero unchecked counts and all other counts use the neutral number-chip treatment.
- Action columns cannot shrink below the icon-only width required by their current complete action set.
- Action columns have no header text and row actions align left inside the action column in rightmost panes.
- Grid-table action columns must reserve enough width for the full icon-only action set used by that table.
- Sortable grid-table columns may shrink to the practical minimum that keeps the sort arrow visible.
- Visible sort arrows should align to the right edge of the usable header area.
- Header labels must ellipsize when the visible sort arrow consumes the available header space.
- Headers stay sticky and visible while table content scrolls.
- Headers are sortable when the column has sortable data.
- Title-centric `Title` sorting has a colored mode icon inside the title header: clicking the header changes direction, clicking the icon switches title-vs-sort sorting.
- The title-sort mode tooltip must explain the resulting order in user terms: series name then release year and subtitle, or displayed-title alphabetical order.
- Text that does not fit must use ellipsis.
  A text-reveal tooltip is enabled only while that text is actually ellipsized.
  Help, action, error, and state tooltips remain available regardless of text overflow.

## Row Actions And Controls

- Do not add custom keyboard navigation, selection, save, or apply shortcuts unless the user explicitly requests them for that feature.
- Allowed custom keyboard behavior is limited to Escape closing dialogs or popovers and Escape clearing the Artists search box.
- Use visible mouse or pointer controls for row selection and apply actions.
- Do not add `tabindex`, `role="button"`, Enter handlers, or Space handlers to non-native rows, chips, or labels to make them keyboard-selectable.
- Native browser text editing and native button behavior are acceptable baseline behavior.
- Any click inside a row's visual area selects that row first, including row action controls, info controls, chips, checkboxes, and disabled action space.
  The clicked control then runs its normal action when it is enabled.
- Row actions are hover/focus visible, and selected rows keep their available actions visible.
- Inline row actions use the centralized row action button style.
- Every row action column uses the same shared button size, icon size, two-pixel action gap, eight-pixel horizontal cell padding, and left alignment across screens.
- Inline and dedicated action-column adapters must use the same adaptive action measurement.
- Adaptive row actions measure the controls that are currently rendered and switch the complete set between labeled and icon-only states without pane-specific pixel thresholds.
- Recalculate adaptive action fitting when its container width or visible action set changes.
- Hidden hover-only row actions must not reserve idle row text width.
- Persistent row status chips may reserve row width because they are visible in the idle state.
- Persistent row info icons may reserve row width because they are visible in the idle state.
- Row text may ellipsize on hover, focus, or selection when the now-visible row actions need that space.
- Collection pane minimum width must preserve icon-only row actions, the persistent info icon, the collection type icon, and a readable collection-name prefix.
- Artist pane minimum width must preserve icon-only row actions, the compact unchecked-count chip, and a readable artist-name prefix.
- Collection and artist pane rows use row-local fitting with a 20px minimum visual gap between the rendered name and the trailing indicator, chip, or actions.
- Collection row fitting collapses action labels before ellipsizing the collection name.
- Artist row fitting collapses action labels before the unchecked chip label, then ellipsizes the name while preserving that 20px gap.
- Pane filter toggle labels do not collapse as part of artist row fitting.
- Prefer icon plus short label when the containing pane or action column can fit the complete labeled action set.
  Collapse action labels automatically when the labeled action set no longer fits.
  Keep the labeled action set at its full rendered width and stable alignment until that switch, without compressing, clipping, or shifting actions inside the column.
  Do not expose pane-width action-label thresholds in Settings.
- Controls that can collapse must keep their icons visible.
  When space gets tight, remove labels first; never allow the pane, row, or action column to shrink below the width required to show all required icons.
- Main Artists screen bulk provider matching uses visible unlinked artists after search and collection filters.
  Keep the displayed count and submitted artist IDs from the same filtered list.
- Main Artists screen bulk provider controls live in the Artists pane title bar with the label `Bulk match missing providers`.
  Bulk provider controls use provider chips with normal provider display names.
  Collapse provider chip labels before removing the bulk-match label, and preserve provider icons plus counts before shrinking other title-bar content.
  A running provider chip remains clickable and reopens its hidden bulk-match progress dialog, while other bulk provider chips and write actions remain disabled.
- Main Artists screen row provider setup uses one `Add providers` action, not one action per provider.
  The action opens the fixed-size multi-provider dialog, starts matching missing providers immediately, preselects existing provider links, and saves only changed selections.
  Existing provider links are removed by deselecting their candidate row and clicking `Save`.
- Main Artists screen conflict resolution uses a warning `Conflicts` row action scoped to the selected artist.
  Do not automatically open provider conflict dialogs after provider association or provider scans.
- While a provider check is running for the selected artist, block the complete Artist Info pane beneath the standard centered `60px` pane spinner.
- Provider conflict choice tiles apply immediately without a separate Apply or confirmation control.
  The clicked tile shows a progress spinner and every conflict tile remains disabled until that request finishes.
  Hovering an expanded conflict section highlights the complete section background behind both its header and choice-tile body.
  Choice tiles use opaque surfaces so the section highlight remains visible around them without changing their resting colors.
- Every conflict choice updates the status bar and status history, folder renames use actual old and new relative paths, and a successful rename with tag failures uses one combined amber status.
- Provider conflict dialogs reserve no result-panel space during ordinary operation.
  Insert the panel above the footer only after a warning or error result exists.
  Keep the visible panel height constant, cap it at one quarter of the dialog and roughly five summary rows, and scroll additional grouped messages inside it.
  Use expandable amber warning and red error summaries, while successful actions and folder renames add no green dialog message.
  Keep result messages until the dialog closes and auto-close only when no conflicts and no messages remain.
- Main Artists screen country cells show flag icons and country names in the table row.
  Clicking a country cell opens a cell-anchored popover below the cell with a search field and country list.
  Country edits write artist overrides only and provider rescans must not overwrite those overrides.
  Manual country and status overrides must be visually distinguishable from provider-derived values without using loud link-like text color.
- Main Artists screen status cells show only the effective status in the table row.
  Clicking that status opens a cell-anchored menu with compact Active, Inactive, and clear chips.
  Status edits write artist overrides only and provider rescans must not overwrite those overrides.
- Known-album title conflict and kept-local chips must share the same height, outline, warning, and overlay-control geometry as known-album year chips in the Artists detail pane.
- Kept-local album years and titles use transparent, thin white outlined chips with normal-weight white text and white undo controls in the Artists detail known-albums list.
  Provider-resolved album titles remain plain text.
- Pane-local filters use pane-scoped keys, for example `collections-screen.artists-pane.presence-filter`, `collections-screen.artists-pane.unchecked-filter`, `collections-screen.albums-pane.show-all-filter`, and `collections-screen.titles-pane.presence-filter`.
- Pane-local scan indicators use pane-scoped keys only when the indicator remains visible outside the blocking collection scan modal, for example `collections-screen.artists-pane.scan-spinner-enabled`.
- Use the shared Vuetify tooltip pattern for UI help and hover labels.
  Do not use native `title` attributes for visible app tooltips.
- Use clear, short labels:
  - `Edit`
  - `Local`
  - `Provider`
  - `Remove`
  - `Delete`
- Use color consistently:
  - primary blue for normal actions, scans, sorting, add, save
  - red/error for destructive delete
  - warning/yellow for attention-needed states such as unchecked albums
  - green/success for present-on-disk indicators
- Do not mix always-visible informational icons with hover-only action icons in a way that makes the info icon look like an action.
- Info icons in collection rows are always visible, muted, and right-aligned so they form a stable visual column.

## Dialogs, Popovers, And Forms

- Use one shared dialog/popover visual language for surfaces, border radii, typography, controls, colors, and spacing only.
- Dialog cards and anchored edit popovers should use the same gap constants: small gap `10px`, large gap `20px`.
- Dialog content containing outlined fields must keep at least the shared `10px` small gap above the first field so a floating label never overlaps the dialog title or preceding content.
- Floating field labels must remain completely readable in empty, focused, populated, error, and disabled states.
  A dialog, grid, scroll container, title, or adjacent control must never paint over or clip any part of the label.
- Use the shared `dialog-card`, `edit-form`, and field defaults before adding dialog-specific spacing.
  Fix label clearance at the shared dialog/form seam instead of nudging one field or label.
- Dialog dimensions and overflow must be owned explicitly by each dialog type or intentional dialog family instead of broad shared selectors.
- Every real dialog, including confirmation dialogs, is centered horizontally and vertically in the browser viewport.
- Anchored menus, dropdowns, status history, and connected edit overlays keep their anchors and do not use dialog centering.
- Blocking scan-progress dialogs share a content-independent, viewport-safe `40vw` width capped at `1000px` and an exact `180px` height.
- Provider matching, provider conflict, bulk provider-match results, and plain-text reports share a fixed `55vw` width up to `1500px` and a fixed `75vh` height.
- Plain-text report lines remain unwrapped and use horizontal scrolling when they exceed the report viewport, while provider matching and conflict content contracts horizontally and scrolls vertically.
- Collection deletion uses its own `460px` responsive confirmation width.
- Anchor a pane to a control only when that is the better workflow fit, such as cell editors, menus, dropdowns, or status history near the status bar.
- Edit forms should not feel cramped.
  Prefer fewer fields and clear vertical spacing over dense packing.
- For pane-local edits, anchored overlays are preferred when they do not need a blocking centered decision.
- Confirmation dialogs are appropriate for destructive actions.
- Destructive actions with extra risk need a second warning dialog.
- Folder/path information belongs in an info tooltip/popover unless the field is necessary for the edit itself.
- Labels and controls in compact edit forms should align on the same row when it improves scanning.

## Collection Semantics

- Collection screen Artists pane:
  - Artist presence is derived from the home collections of the artist's albums.
  - Artist metadata editing belongs to the main Artists screen, not the Collections screen Artists pane.
  - The pane does not expose an artist-level `Remove` action.
  - `Unchecked` is a pane-local filter modifier that narrows the current Local/Non-local artist set to artists with unchecked albums homed in the selected collection.
- Collections screen Albums pane uses `Show All` as a pane-local toggle under the pane title.
  The default is on when no saved preference exists.
  Off shows the selected artist's albums whose home is the selected collection and hides the `Collection` column.
  On shows all albums for the selected artist and shows the `Collection` column.
  The `Collection` column renders the required home collection as a compact chip matching the album-style reference.
  A chip for the selected collection is dimmed, has no tooltip, and is not clickable.
  A chip for another collection is primary-colored, has no tooltip, and navigates to that collection, the same artist, and the same album while scrolling both rows into view.
  The action column exposes a responsive `Move to` action with a folder-move icon in either filter mode.
  `Move to` opens an anchored menu of same-type destination collection chips excluding the album's current home, and choosing one applies immediately.
  A successful move updates the visible row and loaded caches without blanking either pane, while rows that no longer satisfy the current collection filter disappear normally.
  A local album's `Move to` action is disabled with tooltip text `Album present on disk; move the folder to change its collection.`
  `Move to` is disabled when no alternative same-type collection exists.
  The pane never shows `No collection`, `Add orphans`, or album membership-removal actions.
- Collections screen Titles pane:
  - The grid contains only `Title`, `Artist`, and `Year` columns.
  - Year uses the same persisted width and right-hand resize boundary as other fixed data columns, followed by a headerless flexible spacer that absorbs remaining pane width.
  - The Titles workflow intentionally has no collection column, collection selector, or action column because title-centric collections do not expose album-home reassignment.
  - Title metadata is read-only.
  - The pane does not expose title creation, editing, collection removal, or local-path removal.
- Main Artists screen:
  - The first multi-row load shows the standard pane spinner, and returning during the same browser session renders cached artists, albums, and layout preferences without a repeated full load.
  - Clicking anywhere on a selected collection-filter chip removes that filter, while the close icon remains visible.
  - `Delete` is a real library database delete.
  - If the artist belongs to any collection or has local albums, require a second warning confirmation.
  - Deleting from the library DB never deletes folders or files on disk.
  - The right detail pane is the main edit place for artist name and sort name.
  - The right detail pane should show effective country and status, clear override actions next to overridden values, and provider evidence beside the override.
  - The provider artist type must not be shown as an artist type because providers do not agree on the meaning.
- Album/title local presence styling in the collection workspace is scoped to the selected collection, not just global `album.onDisk`.
- Present-on-disk albums/titles are always shown checked and their checkbox is disabled with tooltip text `Present on disk; can't uncheck`.
- Collection scans clear missing album local paths while preserving album home collection and checked state.

### Album Name Display States

Album names in the Collections screen use these visual states.
This is the canonical UI contract for future changes to album row styling.

![Album display states](images/albums-style.png)

| Situation | Condition | Display |
| --- | --- | --- |
| Local in selected collection | Album home matches the selected collection and `onDisk=true`. | Bright text, normal style, same size as artist names, bold `800`; checkbox is shown checked and disabled. |
| Local in another collection | Album home differs from the selected collection and `onDisk=true`. | Bright text, italic style, one CSS pixel smaller than artist names, not bold; checkbox is shown checked and disabled. |
| Checked, non-local | `onDisk=false` and `checked=true`. | Dim neutral text, normal style, one CSS pixel smaller than artist names, not bold; checked box is primary blue. |
| Unchecked, non-local | `onDisk=false` and `checked=false`. | Warm muted text, italic style, one CSS pixel smaller than artist names, not bold; checkbox is empty. |

The implementation is `albumPresenceClass` in `frontend/src/views/CollectionsView.vue`, with styles in `frontend/src/styles.css` under the `.album-presence-text--*` classes.
Local current-collection rows inherit the workspace row font size so they match Artist pane names.
Nonlocal and other-collection rows use `calc(1em - 1px)`.

## Settings Rules

- Defaults belong in `src/main/resources/application.properties`.
- Runtime user preferences belong in the DB.
- The frontend must load effective UI settings from the backend before mounting Vue and must not duplicate runtime defaults in frontend code.
- A screen with separately stored layout preferences must not render its layout until those preferences are loaded; never show default control values, pane sizes, or column widths and then redraw them.
- Global runtime preference keys use the `ui.<area>.<thing>` shape, for example `ui.status-message.visible-ms`, `ui.scan-progress.poll-interval-ms`, and `ui.status-bar.location`.
- Settings shown in the Settings screen should be useful at runtime.
  Do not add visible settings with no real effect.
- A DB value equal to the current default should behave as default, not custom.
- Reset-to-default should remove the DB override or use the reset endpoint.
- Settings UI should be compact, aligned, and pane-scoped when useful: general behavior separate from workspace pane behavior.

## Scanning And Status UI

- Same action from different entry points must route through the same store/job path and show the same status, spinners, polling, refresh, and history behavior.
- Artist-centric collection scans dynamically discover flat and nested local album folders together in the same pass.
- The collection Add dropdown shows folder names only and does not display or precompute collection type.
- Clicking a candidate keeps the Add dropdown open, removes that folder from the candidate list, and inserts a disabled provisional row in its final alphabetical position without changing the current selection.
- Provisional collection rows show a same-sized spinner in the type-icon slot so completion does not move the name, and they expose no selection, information, edit, scan, or delete interaction.
- Multiple provisional rows may be visible, while backend creation and type inference run serially in click order and continue across in-app navigation.
- Successful rows replace the spinner with the inferred type icon, while failed rows disappear and return their folder to the Add dropdown.
- Each successful row adds a short status message naming the collection and its inferred type.
- A confirmed collection deletion closes its dialog immediately, unselects the collection, disables mutations and global Artists navigation, and keeps a dimmed `Deleting <name>…` row with the same type-slot spinner until the request succeeds or fails.
- Collection deletion leaves other collection browsing and Settings navigation available, removes the row on success, and restores the ordinary row on failure.
- Successful collection deletion status names the collection and includes deleted album and newly albumless artist counts.
- The collection edit window uses the compact Artist/Title button toggle and allows correcting collection type only while the collection has zero known albums.
- Collection type is locked after the first album is added, and folder layout is never a collection-level setting.
- Title-centric scans populate title albums plus contributor artists when parsing provides credible artist values.
- Collection scans compare DB local-path and disk-folder snapshots before processing folders.
- Collection scans process only folders whose relative local path is not already known in the selected collection.
- Collection scans do not scan track files.
- Full collection scans use the shared blocking scan-progress dialog with a fixed-size progress bar and a cancel button.
- Bulk provider matching reuses the scan-progress dialog, shows `Processing <artist>` above the bar and the current `N / total artists` inside it, and adds a Close action so matching can continue while the dialog is hidden.
- Closing bulk-match progress does not cancel the job, clicking the running provider chip reopens it, and cancelling waits for the current artist to finish.
- The modal owns running collection scan progress, so the status bar may show scan start or running text but not progress counts, and collection rows do not show scan spinners or progress fills during full collection scans.
- Full collection scan progress first shows snapshot comparison text, then counts only folders that need processing.
- Nested artist collection scans pre-enumerate nested album folders before the processing progress total is known.
- Collection scan cancellation waits for the backend to finish the current item before the modal closes.
- Full collection scans refresh the selected collection context once after the job reaches a terminal state.
- Closing a completed full-scan modal immediately reveals a pane spinner in the selected Artist collection's Artists pane or selected Title collection's Titles pane until refreshed rows arrive.
- Provider scans from Collections assign genuinely new provider albums to the active artist-centric collection.
- Provider scans from the global Artists screen assign genuinely new albums to the artist's most represented collection, using alphabetical-first tie-breaking.
- Title-centric-only artists remain visible but provider matching and scan controls are disabled and bulk matching excludes them.
- Row actions stay visible through the normal selected-row or hover behavior while scan actions are disabled during any running scan or provider job.
- Status bar messages should be brief but specific: say what is being scanned or checked, not just "Scan starting".
- Scan/report history may expose detailed information, but the status bar itself should remain concise.
- Progress should reflect real work where practical, without slowing scans only to improve animation.
