# Evolution 01: Title-Centric Collections

## Goal

Support both artist-centric collections and title-centric collections without forcing soundtracks into the `artist -> album` model.

Artist-centric collections keep the existing flow:

```text
Collections -> Artists -> Albums
```

Title-centric collections use a different flow:

```text
Collections -> Titles
```

When a title-centric row has parsed or manually entered artist metadata, scans and title metadata saves also populate the shared artist, album, and local-path tables.
Ambiguous title-only rows remain title-only until artist metadata is available.

The initial title-centric use case is `SOUNDTRACKS`.

## Related Commits

- Commit `9f45437` added title-centric collection support.
- Commit `7273ab9` later unified title-centric collections with the album-based library model and superseded the separate durable title-table direction described in this early document.

## Data Model

- Add collection classification:
  - `collections.type`: `ARTIST` or `TITLE`
  - `collections.parser`: folder parser/layout, separate from type
- Keep type and parser separate:
  - type answers what the user browses
  - parser answers how folder names encode metadata
- Add title-centric storage:
  - `collection_title_items`
  - `collection_id`
  - `raw_folder_name`
  - `relative_path`
  - `title`
  - `artist_name`
  - `release_date`: raw release value, for example `2023`, `2023-05`, or `2006-03-13`
  - `sort_name`
  - `sort_name_source`: `AUTO` or `MANUAL`
  - `parse_status`: `EXACT`, `PARTIAL`, `TITLE_ONLY`, or `MANUAL`
  - seen/timestamp fields
- Add `album_artists` so one album/title can be linked to more than one artist.
- Keep raw folder/path values as evidence.
- Normalize metadata into DB fields.
- Do not rename folders on disk as part of normalization.

## Parsing

Add a title parser pipeline for soundtrack/title collections.

Rules, in order:

1. Final `(artist, release date)` suffix, preserving inner title parentheses.
   - `Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)`
   - `Ad Astra (Max Richter, Lorne Balfe, 2019)`
2. Final `(release date)` suffix.
   - `Conan the Barbarian (2011)`
3. `Title - YYYY` or `Title - YYYY-MM-DD`.
   - `World of Warcraft - 2004`
4. `Title - YYYY - Subtitle` or `Title - YYYY-MM-DD - Subtitle`.
   - `World of Warcraft - 2007 - The Burning Crusade`
5. Fallback title-only.
   - `Clash of the Titans`
   - `Wojciech Kilar - The Best`

Year display, chronological sorting, and filtering derive from `release_date`.
Manual edits preserve the entered release text.

Auto `sort_name` should be generated from structured pieces, for example:

```text
World of Warcraft | 2007 | The Burning Crusade
```

Manual `sort_name` edits must be protected across future scans.

Manual edits must be protected by `parse_status = MANUAL`: scanner updates path/seen state only and does not overwrite title metadata.

Comma-separated title artists are kept as display text in the title row, but split into separate artists when populating shared artist/album/local-path data.

## Collections Pane

- New collections default to type `ARTIST`.
- Pencil opens an anchored edit popover next to the collection row.
- The popover shows:
  - folder/path info
  - focused collection-name text field with selected text
  - collection type toggle: `Artist` / `Title`
- Changing type updates the collection metadata.
- Do not silently convert existing scan data.
  Re-scan populates data for the selected type.

## Artist-Centric Layout

For `ARTIST` collections:

```text
Collections | Artists | Albums
```

- Keep current artist list.
- Load albums lazily only after artist selection.
- Future nested artist folders should be added as a new parser/layout while keeping type `ARTIST`.

## Title-Centric Layout

For `TITLE` collections:

```text
Collections | Titles
```

The title table must include at least:

```text
Title | Artist | Release date
```

Also include parse status and actions when useful.

Title item editing should allow fixing:

- title
- artist
- release date
- sort as

Saving manual edits sets:

```text
parse_status = MANUAL
```

## Resizable Columns

- Table headers should be resizable by dragging.
- Double-clicking a header resize handle should autosize to the longest visible text in the column.
- Table headers should be clickable for sorting.
- Title-centric `Title` sorting has two modes:
  - display title
  - `sort_name`
- The title sort-mode toggle lives inside the `Title` header as a distinct colored clickable icon.
- Release date columns display only the year in the table; full release dates are shown in hover tooltips.
- Artist-centric albums default to release-date ascending sort.
- Changed column widths are stored in DB preferences.
- Default column widths are stored in `application.properties`.
- Scope widths by view/table:
  - title collection table
  - artist collection table
  - album table
- Use min widths to keep columns usable.
- Use ellipsis/wrapping rules to prevent overlap.

## API

- Extend collection responses with `type` and parser/layout.
- Allow updating collection name, type, and parser/layout.
- Add title item APIs:
  - list items by collection
  - update normalized metadata

## Original Implementation Order

1. Update schema, models, repositories, resources.
2. Add title parser pipeline.
3. Make scan type-aware.
4. Add collection edit popover with type toggle.
5. Add title-centric view.
6. Add title item editing.
7. Add reusable persisted column sizing.
8. Build and test.
