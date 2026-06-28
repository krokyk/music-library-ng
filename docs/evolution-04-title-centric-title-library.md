# Evolution 04: Collection Albums And Title-Centric Collections

## Goal

Clarify the model for title-centric collections, such as soundtracks, while also fixing album membership edge cases in artist-centric collections.

The main decision:

```text
Albums are the durable library entity.
Collections decide how albums are discovered, displayed, and grouped.
```

## Related Commits

- Commit `7273ab9` unified title collections with the album-based library model.

There should not be a separate durable `titles` table for now.
A soundtrack, movie score, or game score is treated as a normal album/title-like record in the existing album model.

## Key Distinctions

Keep these concepts separate:

```text
artist identity
album identity
artist contributed to album
artist is followed in collection
album belongs to collection
album has a local path in collection
```

These are different relationships and should not be collapsed into one.

Current artist-centric browsing often implies:

```text
collection -> followed artists -> albums
```

That is not enough.
Album membership must also be explicit:

```text
collection -> albums
```

## Why Artist Membership Is Not Enough

### Soundtrack Contributor Case

A soundtrack composer can create works in very different styles.
Liking one soundtrack does not imply following the composer's whole discography.

Example:

```text
artist: Max Richter
album/title: Ad Astra
collection: Soundtracks
```

This means:

```text
Max Richter contributed to Ad Astra.
Ad Astra belongs to Soundtracks.
```

It does not mean:

```text
Follow Max Richter in Soundtracks.
```

### Multi-Genre Artist Case

One artist can have albums that belong to different collections.

Example:

```text
Artist X -> Power Metal album
Artist X -> Melodeath album
```

The same artist can be relevant to multiple collections, but each album still needs its own collection membership.

### Multi-Collection Album Case

One album can belong to multiple collections.

Example:

```text
Album A -> Metal
Album A -> Metal Ballads
Album A -> Rock
```

Collections must not own albums exclusively.

Track-level membership is deferred.
If only some tracks from an album belong to a collection, that is a future track model problem and should not be solved in this evolution.

## Current Model

Add direct album membership:

```text
collection_albums
- collection_id
- album_id
- created_at
- updated_at
```

Rules:

- primary key or unique constraint on `(collection_id, album_id)`
- delete collection membership without deleting the album
- deleting a collection cascades only membership/local-path evidence, not shared albums or artists
- deleting an album cascades membership and local-path evidence for that album

Do not add a `source` column initially.
It is ambiguous once the same album is first added manually, later found locally, and later refreshed by a provider.
If source/origin becomes useful in the UI, add a proper event/history concept later instead of overloading collection membership.

Keep existing shared album identity:

```text
albums
- id
- title
- normalized_title
- release_date
- sort_name
- normalized_sort_name
- sort_name_source
- checked
- notes
- created_at
- updated_at
```

Keep contributor links:

```text
album_artists
- album_id
- artist_id
- position
```

Keep local folder evidence:

```text
album_local_paths
- album_id
- collection_id
- relative_path
- first_seen_at
- last_seen_at
```

Current `album_local_paths` already has the needed local evidence:

- `album_id`
- `collection_id`
- `relative_path`
- `first_seen_at`
- `last_seen_at`

Fields such as `title`, `normalized_title`, `artist_name`, `release_date`, `sort_name`, and `sort_name_source` should move to `albums` and `album_artists`, not stay on a collection-local title table.

Do not carry over `raw_folder_name`, `parse_status`, or `metadata_source`:

- `relative_path` is already the source evidence for direct child folder scans.
- missing artist or release date is visible directly from empty table fields and can be queried without a separate parse-status column.
- manual edit protection should live on the editable album fields if it becomes necessary, not on the local-path row.

## Collection Type Meaning

`collections.type` should drive layout and parsing, not durable entity type.

Artist-centric collection:

```text
type = ARTIST
primary browsing entity = followed artists
collection scan = discover artists and local albums
local album scan = rescan albums/local paths
provider scan = available for followed artists
layout = Collections | Artists | Albums
```

Title-centric collection:

```text
type = TITLE
primary browsing entity = albums shown as titles
collection scan = discover albums directly from title folders
provider scan = not available by default
layout = Collections | Titles
```

In both cases the durable item is still `albums`.

## Checked State

Keep `checked` on `albums`.

Do not add separate checked state to local paths or collection membership, because those rows are not the library item.
A title/soundtrack can remain checked even when the local folder is removed or when it belongs to multiple collections.

The checked meaning should remain consistent:

```text
checked = user has listened to or accepted this album/title entity
```

## Title-Centric Manual Add

Title-centric collections should have a `+ Add Title` action, similar to `+ Add Artist` in artist-centric collections.

Manual add should:

- create or reuse an `albums` row
- link contributor artists through `album_artists` when supplied
- add a `collection_albums` row
- not require a local path

This supports checked soundtracks that are no longer on disk or were never local in the first place.

Deleting a local title path should remove that `album_local_paths` row, but it should not remove the `collection_albums` membership.
Removing an album/title from a collection should be a separate explicit action.

## Title-Centric Local Scan

Scanning a title-centric collection should:

- parse direct child folders
- create or reuse album rows
- link contributor artists when parsed
- add `collection_albums`
- add or refresh `album_local_paths`

Parsed artists are contributors only.
They should not automatically become followed artists in `artist_collections`.

## Artist-Centric Local Scan

Artist-centric collection scan discovers local artists and local albums:

```text
collection folders -> followed artists + albums + local paths
```

Explicit local album scans should still support rescanning one artist or a whole collection:

- parse album folders
- create or reuse album rows
- link album artists
- add `collection_albums`
- add or refresh `album_local_paths`

Artist selection must not scan or mutate data.

Provider scans create or update artist-level album knowledge only.
Provider-discovered albums should not be added to `collection_albums` unless a local or title scan later finds collection evidence.
Row-level provider scans from the Collections artists pane use the selected collection only to choose the artist/provider context.
Row-level provider scans from the global Artists screen are unscoped and refresh the selected artist details on that screen.

## Display Rules

Artist-centric selected collection:

```text
Collections | Artists | Albums
```

The Albums pane should show albums that belong to the selected collection and selected artist.
This should eventually use `collection_albums` plus `album_artists`, not only `artist_collections`.

Title-centric selected collection:

```text
Collections | Titles
```

The Titles pane is a title-oriented album grid.
It should show albums belonging to the selected collection.

Columns remain approximately:

```text
Title | Artist | Release date | Status/Checked | Actions
```

No separate top-level app section named `Titles` is needed for now.

## Collection Info Tooltip

Add an always-visible disabled-style info icon in each collection row.

Positioning:

- aligned to the right side of the collection row
- vertically aligned across rows
- separate from hover actions
- visually subdued so it reads as metadata, not a command

The tooltip should be lazy-loaded and cached in frontend memory:

- first hover loads metadata from the backend
- while loading, show a spinner and loading text
- once loaded, reuse cached data for that page session
- invalidate only when count-affecting relationships change

Do not materialize these counts in the database yet.
The counts are derived from indexed DB tables and should remain source-of-truth reads until there is a measured performance reason to store them.

## Artist-Centric Tooltip

Display:

```text
Path: <path>
Artists: 92
Local albums: 143
Known albums: 270
Unchecked albums: 12
```

Meanings:

- `Artists`: distinct followed artists assigned to the collection.
- `Local albums`: distinct collection albums with active local paths.
- `Known albums`: distinct albums assigned to the collection.
- `Unchecked albums`: assigned collection albums where `checked = false`.

## Title-Centric Tooltip

Display:

```text
Path: <path>
Artists: 48
Local titles: 123
Checked titles: 344
```

Meanings:

- `Artists`: distinct contributor artists linked to collection albums.
- `Local titles`: distinct collection albums with active local paths.
- `Checked titles`: checked albums assigned to the collection, including albums without a local path.

Unchecked title counts are intentionally deferred.

## Cache Invalidation

Invalidate collection metadata after actions that can change counts:

- collection scan finishes
- local album scan finishes
- provider scan finishes for a collection or artist
- manual artist membership changes
- manual album/title add or delete
- title metadata edit that changes contributors or local path state
- album checked toggle for albums assigned to collections
- collection delete

Do not invalidate count metadata for display-only changes:

- collection name change
- UI settings changes
- pane or column resizing
- sorting or selection changes

Because the tooltip shows checked/unchecked counts, album checked toggles must invalidate metadata for any collections containing that album once `collection_albums` exists.

## What Happens To collection_title_items

`collection_title_items` should be deleted in the target model.

It should not remain as a durable title/library entity, and it should not remain as a parallel scan-evidence table once title-centric flows are album-based.

Target ownership:

```text
albums = durable title/library identity
collection_albums = collection membership
album_local_paths = local folder evidence
```

No extra parse-status table or parse-status column is needed for the current workflow.
The local folder evidence is `album_local_paths.relative_path`; the canonical title metadata lives on `albums` and `album_artists`.

Rows with incomplete parsed metadata can be found by querying the actual missing values, for example missing contributor artists or missing release dates.
A separate parse-status column is not needed for the current workflow.

## Deferred

Do not implement these now:

- track-level collection membership
- title provider scans
- composer/performer/role distinctions
- separate global Titles top-level section
- generic shared `library_items` abstraction

These can be revisited only when the workflows require them.

## Original Implementation Order

1. Add `collection_albums` to the fresh V1 schema.
2. Add repository support for assigning/removing albums from collections.
3. Update album queries to filter by `collection_albums` where appropriate.
4. Update title-centric scans to create albums plus collection membership.
5. Update artist-centric local album scans to create collection membership.
6. Keep provider scans from changing existing collection memberships, while allowing collection-scoped provider scans to assign albums that have no memberships yet.
7. Replace title-centric display data with collection albums.
8. Add `+ Add Title` for title-centric collections.
9. Add collection info tooltip using derived DB counts and frontend cache.
10. Delete `collection_title_items` after title-centric scans, edits, deletes, and display all use album-based storage.
