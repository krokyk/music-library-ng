# Evolution 01: Title-Centric Collections

## Goal

Support both artist-centric collections and title-centric collections without forcing
soundtracks into the `artist -> album` model.

Artist-centric collections keep the existing flow:

```text
Collections -> Artists -> Albums
```

Title-centric collections use a different flow:

```text
Collections -> Titles
```

The initial title-centric use case is `SOUNDTRACKS`.

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
  - `year`
  - `metadata_source`: `AUTO` or `MANUAL`
  - `parse_status`: `EXACT`, `PARTIAL`, `TITLE_ONLY`, or `MANUAL`
  - seen/timestamp fields
- Keep raw folder/path values as evidence.
- Normalize metadata into DB fields.
- Do not rename folders on disk as part of normalization.

## Parsing

Add a title parser pipeline for soundtrack/title collections.

Rules, in order:

1. Final `(artist, year)` suffix, preserving inner title parentheses.
   - `Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)`
2. Final `(year)` suffix.
   - `Conan the Barbarian (2011)`
3. `Title - YYYY`.
   - `World of Warcraft - 2004`
4. `Title - YYYY - Subtitle`.
   - `World of Warcraft - 2007 - The Burning Crusade`
5. Fallback title-only.
   - `Clash of the Titans`
   - `Wojciech Kilar - The Best`

Manual edits must be protected:

- `metadata_source = AUTO`: scanner may update parsed metadata.
- `metadata_source = MANUAL`: scanner updates path/seen state only.

## Collections Pane

- New collections default to type `ARTIST`.
- Pencil opens an anchored edit popover next to the collection row.
- The popover shows:
  - folder/path info
  - focused collection-name text field with selected text
  - collection type toggle: `Artist` / `Title`
- Changing type updates the collection metadata.
- Do not silently convert existing scan data. Re-scan populates data for the selected type.

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
Title | Artist | Year
```

Also include parse status and actions when useful.

Title item editing should allow fixing:

- title
- artist
- year

Saving manual edits sets:

```text
metadata_source = MANUAL
parse_status = MANUAL
```

## Resizable Columns

- Table headers should be resizable by dragging.
- Double-clicking a header resize handle should autosize to the longest visible text in the column.
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

## Implementation Order

1. Update schema, models, repositories, resources.
2. Add title parser pipeline.
3. Make scan type-aware.
4. Add collection edit popover with type toggle.
5. Add title-centric view.
6. Add title item editing.
7. Add reusable persisted column sizing.
8. Build and test.
