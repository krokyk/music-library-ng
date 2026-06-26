# Evolution 02: Release Dates And Multi-Artist Albums

## Goal

Make release metadata consistent across the app and allow one album/title to be linked to multiple artists without creating fake composite artist names.

This completes the follow-up work from title-centric collections where soundtracks can be parsed as titles, but still populate shared artist, album, and local-path data.

## Related Commits

- Commit `cab3964` added release-date normalization and multi-artist title albums.

## Release Date Model

- `release_date` is the canonical release field.
- Values may be partial or complete:
  - `YYYY`
  - `YYYY-MM`
  - `YYYY-MM-DD`
- Stored numeric year fields are removed.
- Release-date precision fields are not stored separately.
- Year display is derived from `release_date`.
- Full release dates are shown in tooltips when available.
- The display format is configured by `music-library.release.date.display.format`.

## Title Sort Model

Title-centric rows store a generated or manually edited `sort_name`.

Default sort names are generated from structured title pieces:

```text
World of Warcraft | 2007 | The Burning Crusade
```

The title table supports sorting by either:

- visible title
- `sort_name`

The title header includes a distinct colored icon to switch between these modes.

Manual `sort_name` edits are preserved across future scans.

## Manual Metadata Protection

`metadata_source` was removed as a separate column.

Manual title edits are protected by:

```text
parse_status = MANUAL
```

When a title item is manual, scans update path/seen state only and do not overwrite the edited title, artist, release date, or sort key.

## Multi-Artist Albums

Albums no longer have a single direct `artist_id` owner.

Album ownership is now stored in:

```text
album_artists
```

This allows one album to be linked to multiple artists while still keeping one album row and one local-path row.

The join table stores `position` so artist display order can follow parsed input.

Example:

```text
Ad Astra (Max Richter, Lorne Balfe, 2019)
```

creates:

- one title row with artist text `Max Richter, Lorne Balfe`
- one album row for `Ad Astra`
- two artist rows, `Max Richter` and `Lorne Balfe`
- two album links pointing to the same album

Filtering albums by either linked artist returns the same album.

## Scanning Behavior

Title-centric scans now:

- parse title metadata
- upsert title items
- split comma-separated artists into individual artists
- assign each artist to the collection
- create or update the shared album and local path when artist metadata exists

Artist-centric collection scans remain lazy:

- discover artists only
- do not populate albums during the collection scan

Scan job progress fields were renamed from artist-specific names to generic item names because the progress unit is a scanned directory/item:

```text
itemTotal
itemProcessed
```

## Provider Checks

Provider album ingestion now uses canonical `release_date` instead of numeric year fields.
Provider-created albums are linked through the same album/artist relationship as local scans.

## UI Updates

- Title-centric and album release columns display year only.
- Full release dates are shown in hover tooltips.
- Title metadata editing uses `Release date`.
- Status/progress text uses generic item counts.
- Frontend album DTOs expose `artistIds` instead of a single `artistId`.

## Schema Summary

Fresh V1 schema changes include:

- remove `albums.artist_id`
- remove stored release year fields
- remove release-date precision fields
- remove title metadata-source field
- add `album_artists(album_id, artist_id, position)`
- keep `collection_title_items.release_date`
- keep `collection_title_items.sort_name`
