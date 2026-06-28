# Evolution 08: Row Selection And Grid Table Behavior

Historical note: This evolution document is archival context, not current source of truth.
Use `docs/current-application.md` and the current code when behavior conflicts.

Date: 2026-06-24

## Scope

This document captures the row-selection and regular grid-table behavior contract from the Collections and Artists screens.
The row-selection contract applies to collection sidebar rows, the Collections screen Artists list, the Collections screen Albums table, the Collections screen Titles table, and the main Artists screen table.
Library and Settings tables are out of scope for this pass because they use separate prototype, form, or status layouts.

## Related Commits

- Commit `3fc2f64` finalized grid table row selection.
- Commit `9b3e3b4` kept selected row actions visible.

## Row Selection

- Rows use single selection unless a future table explicitly introduces multi-select.
- Clicking anywhere in a row's visual area selects that row first.
- Row action controls, info controls, chips, checkboxes, and disabled action space are part of the row's visual area.
- After the row is selected, the clicked control runs its normal action when it is enabled.
- Clicking the already selected row keeps it selected.
- Clicking a different row in the same row set clears the previous selection for that row set.
- Selecting a parent row clears child row selections when the child content is redrawn for the new parent.
- Selected rows keep their available row actions visible.
- Hover and focus still reveal row actions for unselected rows.

## Regular Grid Tables

- The regular grid tables covered by this pass are Collections Albums, Collections Titles, and the main Artists table.
- Grid tables with an action column reserve a real action column.
- Action columns cannot shrink below the full icon-only action set used by that table.
- Sortable grid-table columns may shrink to the practical minimum that keeps the sort arrow visible.
- Header text does not reserve empty space for an inactive sort arrow.
- Visible sort arrows align to the right edge of the usable header area.
- When a sort arrow is visible and the header text no longer fits, the header text ellipsizes.
- If only the sort arrow is visible after user resizing, that is acceptable.
- The Albums checked column cannot shrink below the checkbox control width.

## Release Date Display

- Album and title release-date sorting uses the stored `releaseDate` value.
- Album and title release-date cells display only the derived year.
- Displayed years use the shared neutral chip style and should not look clickable.
- A year-only value such as `2006` displays `2006` without an extra tooltip.
- A year-month value such as `2006-03` displays `2006` and shows `2006-03` in the tooltip.
- A full date such as `2006-03-13` displays `2006` and shows the configured formatted full date in the tooltip.
- Empty release dates render an empty cell with no chip.
