# Evolution 03: Artist-Centric Album Scanning

## Goal

Add explicit local and remote album refresh workflows for artist-centric collections without making normal navigation mutate data.

The key boundary:

```text
clicking an artist selects only
scanning is always an explicit action
```

## Current Boundary

Artist-centric collection scan remains fast and shallow:

```text
collection folders -> artists
```

It should not populate albums and should not scan tracks.

Albums are discovered through explicit album-level workflows:

- local album scan
- provider scan

## Artists Pane Actions

For an artist-centric selected collection, the Artists pane title bar should use:

```text
ARTISTS                         [scan local] [scan providers] [sort] [add artist]
```

All title-bar actions are scoped to the currently selected collection.

Row hover actions should include:

```text
artist row: [scan local] [scan providers] [edit/link actions]
```

Recommended icon meanings:

- local scan: disk/folder refresh
- provider scan: web/cloud refresh
- sort: existing artist sort control
- add artist: existing add-person control

## Local Album Scan

Local scan parses album folders for artist-centric collections.

For the current flat layout, parse:

```text
ARTIST - release date - ALBUM
```

It should upsert:

- artist
- album
- album_artists
- album_local_paths

It should not scan track files.

The row action scans one artist in the selected collection.

The title-bar action is collection-scoped on the backend.
It scans direct child folders for the selected collection and upserts matching artists/albums/local paths.
The frontend must not decide the complete artist set from its current filtered or loaded rows.

Local scan API:

```text
POST /api/scan/jobs/local-albums?collectionId=<collection-id>
POST /api/scan/jobs/local-albums?collectionId=<collection-id>&artistId=<artist-id>
```

When `artistId` is supplied, only folders whose parsed artist matches the selected artist are processed.
Missing local paths are marked missing only for that artist.

When `artistId` is omitted, all matching folders in the selected collection are processed.
Missing local paths are marked missing for the whole collection.

## Provider Scan

Provider scan checks enabled provider links for artists.

The row action checks one artist.

The title-bar action checks all artists in the selected collection with enabled provider links.

Provider-created albums are unchecked by default and are linked to the artist through `album_artists`.

Provider collection API:

```text
POST /api/provider-checks/collection/<collection-id>
```

Artists without enabled provider links are skipped and reported in the provider summary.

## Empty Albums Pane

When an artist is selected and no known albums are loaded, the Albums pane should offer explicit selected-artist actions:

```text
No albums loaded for this artist. [Scan local albums] [Scan providers]
```

This keeps the action available without making artist selection perform a scan.

## Track-Level Scanning

Track scanning is deferred.

It should be a separate deep-audit feature, not part of normal collection, artist, or album discovery.

Reasons:

- it is expensive
- it introduces track identity and duplicate-file questions
- it requires file tag parsing rules
- it changes the app from album collection tracking toward file auditing

Future track scanning should be explicit and scoped, likely per album or per collection maintenance run.

## Job And Status Behavior

All entry points for the same action must route through the same service/job path.

Local scan from row, empty state, or title bar must produce the same:

- status bar messages
- status history entries
- busy indicators
- polling behavior
- cancellation behavior when available
- refresh-after-completion behavior

Provider scan should follow the same consistency rule.

## Implementation Order

1. Add local album scan service methods for one artist and all selected collection artists.
2. Add API endpoints or scan job types for local album scan.
3. Route row, empty-state, and title-bar local scan actions through the same store action.
4. Add selected-collection provider bulk scan.
5. Add Artists pane title-bar buttons in the agreed order.
6. Ensure status bar/history/busy indicators match existing scan behavior.
7. Add parser and repository tests for local album upsert/link behavior.
