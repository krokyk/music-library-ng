# Music Library NG Implementation Plan

This plan describes the product model, architecture, and implementation rules for Music Library NG.

Core behavior:

- `Album.checked` means the album was listened to.
- An artist can exist before any local tracks exist.
- Provider checks add new albums as unchecked.
- Local scan detects albums that exist on disk.

## 1. Core Product Model

The app tracks artists and albums in a personal listening collection.

It must answer:

- Which artists am I tracking?
- Which albums have I listened to?
- Which albums have I not listened to yet?
- Which albums exist locally on disk?
- Which tracked artists have unchecked albums?
- Which provider-linked artists have new albums?
- Which local folders were scanned and matched to albums?

The app has exactly one album listening state:

```text
checked = true  -> I listened to the album
checked = false -> I have not listened to the album yet
```

Disk presence is separate:

```text
onDisk = true  -> the app currently sees a local album folder/path
onDisk = false -> no current local path is known or the known path is missing
```

Important combinations:

```text
checked=true,  onDisk=true  -> listened and locally present
checked=true,  onDisk=false -> listened, no kept local tracks
checked=false, onDisk=false -> not listened yet
checked=false, onDisk=true  -> local files exist, not marked listened - invalid, when scanning, this should be immediately fixed to checked=true,  onDisk=true  -> listened and locally present
```

There are no selected/rejected/unlistened album statuses.

The collection workspace display matrix for these states is documented in `docs/codex-ui-workflow-guide.md` under "Album Name Display States".

## 2. Navigation And Main Screen UX

Use top-level sections:

```text
Collections | Artists | Library | Settings
```

`Collections` is the default everyday working screen.
It should follow this three-pane layout:

```text
+---------------------------------------------------------------+
| Top app bar / actions / status                                |
+--------------+-------------------+----------------------------+
| Collections  | Artists table     | Albums table               |
|              |                   |                            |
| Collection 1 | Name              | Name     Year    Checked   |
| Collection 2 | Missing indicator |                            |
| ...          |                   |                            |
| Collection N |                   |                            |
+--------------+-------------------+----------------------------+
```

Collections pane:

- Left sidebar with selectable collection list.
- Only one collection can be selected at a time.
- Page load fetches only collections; no collection is selected.
- With no collection selected, artists and albums panes are empty.
- Selecting a collection loads artists for that collection and clears the albums pane.
- Switching collection clears the selected artist and clears the albums pane before loading artists for the new collection.
- Include an icon/button for configuration/status near the collection title.
- Configuration itself remains file-based in Quarkus properties.
  The app keeps one shared config and varies only `music-library.music-root` per machine.
- The collections pane should stay narrow and stable; it is navigation, not content.

Artists pane:

- Middle pane is a basic artists table for the selected collection.
- Show artist name.
- Show a compact indication when the artist has missing/unchecked albums.
- Missing/unchecked indication is based on provider data and updates only after a provider check.
- Selecting an artist refreshes the albums table to show that artist's albums.
- Artists shown here are artists assigned to the selected collection, including manually added artists with no local tracks.
- Artist rows have hover actions:
  - pencil icon opens artist detail modal;
  - refresh icon checks/refreshes discography for that artist using configured provider links.
- Artist detail modal contains editable artist details only:
  - name;
  - sort name;
  - notes;
  - collection membership;
  - provider links/URLs.
- Artist detail modal does not show album summary/list.
  Album review stays in the albums pane.

Albums pane:

- Right pane is a basic albums table for the selected collection and selected artist.
- Columns:
  - `Name`
  - `Year`
  - `Checked`
- `Checked` is a checkbox and means listened.
- Checking/unchecking the checkbox updates the album immediately.
- Album row hover action:
  - trash icon;
  - opens confirmation dialog before deleting the album.
- Keep this table dense, fast, and easy to scan.
- Disk presence may be shown compactly, but it should not compete with the core `Checked` workflow.

Selection behavior:

- Selecting a collection filters artists to artists assigned to that collection.
- Selecting a collection with no artist selected leaves the albums pane empty.
- Selecting an artist loads albums for that artist in the selected collection context.
- Provider-discovered albums without local paths should still appear for the artist so they can be checked after listening.

Other top-level sections:

- `Artists`: global artist management view for all artists, richer provider visibility, and bulk provider operations.
- `Library`: global album search/table across all collections and artists.
- `Settings`: config-file-backed settings view; see configuration section.

## 3. Database

Use one base Flyway migration:

```text
src/main/resources/db/migration/V1__init.sql
```

The canonical schema is in `V1__init.sql`.

Use SQLite foreign keys:

```sql
PRAGMA foreign_keys = ON;
```

### 3.1 Schema

```sql
create table artists (
  id integer primary key autoincrement,
  name text not null,
  normalized_name text not null,
  sort_name text,
  notes text,
  created_at text not null default current_timestamp,
  updated_at text not null default current_timestamp,
  unique(normalized_name)
);

create table albums (
  id integer primary key autoincrement,
  artist_id integer not null,
  title text not null,
  normalized_title text not null,
  release_year integer,
  release_date text,
  checked integer not null default 0,
  notes text,
  created_at text not null default current_timestamp,
  updated_at text not null default current_timestamp,
  foreign key (artist_id) references artists(id) on delete cascade,
  check (checked in (0, 1))
);

create index idx_albums_artist_id on albums(artist_id);
create index idx_albums_checked on albums(checked);
create index idx_albums_year on albums(release_year);

create unique index ux_albums_artist_title_year
  on albums(artist_id, normalized_title, coalesce(release_year, -1));

create table collections (
  id text primary key,
  name text not null,
  relative_path text not null,
  parser text not null,
  enabled integer not null default 1,
  last_scan_at text,
  last_scan_status text,
  last_scan_message text,
  created_at text not null default current_timestamp,
  updated_at text not null default current_timestamp,
  check (enabled in (0, 1))
);

create table artist_collections (
  artist_id integer not null,
  collection_id text not null,
  created_at text not null default current_timestamp,
  primary key (artist_id, collection_id),
  foreign key (artist_id) references artists(id) on delete cascade,
  foreign key (collection_id) references collections(id) on delete cascade
);

create index idx_artist_collections_collection
  on artist_collections(collection_id);

create table album_local_paths (
  id integer primary key autoincrement,
  album_id integer not null,
  collection_id text not null,
  relative_path text not null,
  first_seen_at text not null default current_timestamp,
  last_seen_at text not null default current_timestamp,
  missing_since text,
  foreign key (album_id) references albums(id) on delete cascade,
  foreign key (collection_id) references collections(id) on delete restrict,
  unique(collection_id, relative_path)
);

create index idx_album_local_paths_album on album_local_paths(album_id);
create index idx_album_local_paths_collection on album_local_paths(collection_id);
create index idx_album_local_paths_missing on album_local_paths(missing_since);

create table artist_provider_links (
  id integer primary key autoincrement,
  artist_id integer not null,
  provider_id text not null,
  provider_url text not null,
  enabled integer not null default 1,
  last_checked_at text,
  last_success_at text,
  last_error_at text,
  last_error_message text,
  created_at text not null default current_timestamp,
  updated_at text not null default current_timestamp,
  foreign key (artist_id) references artists(id) on delete cascade,
  check (enabled in (0, 1)),
  unique(provider_id, provider_url)
);

create index idx_artist_provider_links_artist on artist_provider_links(artist_id);
create index idx_artist_provider_links_enabled on artist_provider_links(enabled);

create table scan_runs (
  id integer primary key autoincrement,
  collection_id text,
  started_at text not null default current_timestamp,
  finished_at text,
  status text not null,
  parsed_count integer not null default 0,
  created_count integer not null default 0,
  updated_count integer not null default 0,
  missing_count integer not null default 0,
  skipped_count integer not null default 0,
  message text,
  foreign key (collection_id) references collections(id) on delete set null,
  check (status in ('RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

create table scan_events (
  id integer primary key autoincrement,
  scan_run_id integer not null,
  level text not null,
  message text not null,
  created_at text not null default current_timestamp,
  foreign key (scan_run_id) references scan_runs(id) on delete cascade,
  check (level in ('INFO', 'WARN', 'ERROR', 'SKIPPED'))
);

create table provider_check_runs (
  id integer primary key autoincrement,
  artist_id integer,
  provider_link_id integer,
  started_at text not null default current_timestamp,
  finished_at text,
  status text not null,
  processed_artist_count integer not null default 0,
  found_album_count integer not null default 0,
  new_album_count integer not null default 0,
  existing_album_count integer not null default 0,
  error_count integer not null default 0,
  message text,
  foreign key (artist_id) references artists(id) on delete set null,
  foreign key (provider_link_id) references artist_provider_links(id) on delete set null,
  check (status in ('RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

create table provider_check_events (
  id integer primary key autoincrement,
  run_id integer not null,
  artist_id integer,
  provider_link_id integer,
  level text not null,
  message text not null,
  created_at text not null default current_timestamp,
  foreign key (run_id) references provider_check_runs(id) on delete cascade,
  foreign key (artist_id) references artists(id) on delete set null,
  foreign key (provider_link_id) references artist_provider_links(id) on delete set null,
  check (level in ('INFO', 'WARN', 'ERROR'))
);
```

## 4. Backend Domain DTOs

Use album terminology in DTOs and APIs.

### Artist

```java
public record Artist(
    long id,
    String name,
    String sortName,
    String notes,
    List<String> collectionIds,
    int albumCount,
    int checkedAlbumCount,
    int uncheckedAlbumCount,
    int localAlbumCount,
    int providerLinkCount,
    String createdAt,
    String updatedAt
) {}
```

### Album

```java
public record Album(
    long id,
    long artistId,
    String artistName,
    String title,
    Integer releaseYear,
    String releaseDate,
    boolean checked,
    boolean hasLocalPath,
    boolean onDisk,
    List<AlbumLocalPath> localPaths,
    String notes,
    String createdAt,
    String updatedAt
) {}
```

### AlbumLocalPath

```java
public record AlbumLocalPath(
    long id,
    long albumId,
    String collectionId,
    String collectionName,
    String relativePath,
    String resolvedPath,
    boolean onDisk,
    String firstSeenAt,
    String lastSeenAt,
    String missingSince
) {}
```

### ArtistProviderLink

```java
public record ArtistProviderLink(
    long id,
    long artistId,
    String providerId,
    String providerUrl,
    boolean enabled,
    String lastCheckedAt,
    String lastSuccessAt,
    String lastErrorAt,
    String lastErrorMessage
) {}
```

## 5. Backend Services And Repositories

Suggested package layout:

```text
org.kroky.musiclib.artist
org.kroky.musiclib.album
org.kroky.musiclib.collection
org.kroky.musiclib.scan
org.kroky.musiclib.provider
org.kroky.musiclib.settings
org.kroky.musiclib.db
```

Keep repository code simple JDBC unless moving to a different persistence style is clearly worth it.

Core repositories:

- `ArtistRepository`
- `AlbumRepository`
- `AlbumLocalPathRepository`
- `CollectionRepository`
- `ScanRunRepository`
- `ArtistProviderLinkRepository`
- `ProviderCheckRunRepository`

Core services:

- `MusicRootService`
- `StartupConfigValidator`
- `FolderNameParser`
- `ScanService`
- `ProviderRegistry`
- `ProviderCheckService`

## 5.1 Configuration And Startup Validation

Use plain Quarkus `.properties` for shared configuration.

Reason:

- Quarkus already supports property files, and the per-machine music root can be supplied as a JVM system property.
- It avoids adding a custom JSON/YAML configuration layer.
- The same values can be consumed by Quarkus and app services.
- One shared config keeps collections, parsers, DB location, backups, logging, and UI behavior identical across computers.
- Only the physical Google Drive music root should vary per computer, and it can usually be auto-detected.

Shared config file:

```properties
music-library.db.path=data/music-library-ng.sqlite
music-library.backup.directory=data/backups

music-library.collections[0].id=melodeath
music-library.collections[0].name=Melodeath
music-library.collections[0].relative-path=MELODEATH
music-library.collections[0].parser=ARTIST_YEAR_ALBUM
music-library.collections[0].enabled=true
```

Run with the shared config file and a per-machine root override:

```bash
java -Dquarkus.config.locations=file:/path/to/music-library.properties \
  -Dmusic-library.music-root="E:/Google Drive/Music/_vyber" \
  -jar build/quarkus-app/quarkus-run.jar
```

The JVM property is optional when autodetection succeeds.

On another computer, keep the same config file and change only:

```bash
java -Dquarkus.config.locations=file:/path/to/music-library.properties \
  -Dmusic-library.music-root="G:/My Drive/Music/_vyber" \
  -jar build/quarkus-app/quarkus-run.jar
```

Startup validation rules:

- If `music-library.music-root` is supplied as a JVM system property:
  - it must not be blank;
  - it must resolve to an existing path;
  - it must be a directory;
  - it must not be empty;
  - it must contain the expected marker playlists directly in the root folder.
- If `music-library.music-root` is not supplied, auto-detect the root in this exact order:
  1. `E:/Google Drive/Music/_vyber`
  2. `G:/My Drive/Music/_vyber`
- A candidate is valid only if it exists, is a directory, is not empty, and contains the marker playlists directly under that root.
- Marker playlists:
  - `EPIC.m3u8`
  - `MELODEATH.m3u8`
  - `POWER METAL.m3u8`
- The path resolver may translate these Windows-style paths to local mount paths where needed, but the logical detection order remains E first, then G.
- The app must fail fast during startup if explicit validation and autodetection both fail.
- The error must clearly identify checked paths and explain how to supply the JVM property.

Example failure:

```text
Music root was not configured and autodetection failed.
Checked:
  E:/Google Drive/Music/_vyber
  G:/My Drive/Music/_vyber
Both expected locations are invalid or missing marker playlists.
Start the app with a valid per-machine JVM property:
  -Dmusic-library.music-root="E:/Google Drive/Music/_vyber"
```

Do not silently continue without a valid music root.

## 6. API

### Artists

```text
GET    /api/artists?collectionId=&search=
GET    /api/artists/{id}
POST   /api/artists
PUT    /api/artists/{id}
DELETE /api/artists/{id}
```

Create/update body:

```json
{
  "name": "Amaranthe",
  "sortName": null,
  "notes": null,
  "collectionIds": ["melodeath"]
}
```

Artist collection membership:

```text
GET /api/collections/{collectionId}/artists
PUT /api/artists/{id}/collections
```

Membership update body:

```json
{
  "collectionIds": ["melodeath", "metal"]
}
```

Artist membership rules:

- Manual artists can be assigned to one or more collections even before they have local paths.
- Local scan should assign discovered artists to the scanned collection.
- Provider-discovered albums do not change collection membership by themselves.
- The Collections screen artists pane uses this membership table, not only local paths.

### Albums

```text
GET    /api/albums?collectionId=&artistId=&checked=&hasLocalPath=&search=
GET    /api/albums/{id}
POST   /api/albums
PUT    /api/albums/{id}
DELETE /api/albums/{id}
```

Create/update body:

```json
{
  "artistId": 1,
  "title": "Manifest",
  "releaseYear": 2020,
  "releaseDate": null,
  "checked": true,
  "notes": null
}
```

Manual album creation defaults `checked` to `true` if omitted, because manual entry usually means "I listened to this."

Provider-discovered album creation sets `checked = false`.

### Collections

```text
GET /api/collections
```

Collections are user-managed from direct folders under the configured music root.
First-run defaults come from application properties, while user-created collections and runtime layout/preferences are stored in SQLite.

### Settings

```text
GET /api/settings/music-root
GET /api/settings/ui
PUT /api/settings/ui
DELETE /api/settings/ui
```

Settings use Quarkus properties as first-run defaults and SQLite preferences for runtime overrides.
The Settings view exposes only settings that are useful to change while the app is running.
The provider batch rescan delay uses `music-library.providers.default-batch-rescan-delay-minutes` as its first-run default and stores user changes in SQLite.

### Scan

```text
POST /api/scan/jobs
POST /api/scan/jobs?collectionId=melodeath
POST /api/scan/jobs/local-albums?collectionId=melodeath
POST /api/scan/jobs/local-albums?collectionId=melodeath&artistId=1
GET  /api/scan/jobs/current
POST /api/scan/jobs/current/cancel
GET  /api/scan/runs?limit=25
GET  /api/scan/runs/{id}
GET  /api/scan/runs/{id}/events
```

User-facing scan triggers must use the scan job flow so status history, busy indicators, progress polling, cancellation, and post-scan refresh stay consistent.

### Provider Links

```text
GET    /api/artists/{artistId}/provider-links
POST   /api/artists/{artistId}/provider-links
PUT    /api/artists/{artistId}/provider-links/{linkId}
DELETE /api/artists/{artistId}/provider-links/{linkId}
```

Provider link body:

```json
{
  "providerId": "spirit_of_metal",
  "providerUrl": "https://www.spirit-of-metal.com/...",
  "enabled": true
}
```

### Provider Checks

```text
POST /api/provider-checks/artist/{artistId}
POST /api/provider-checks/provider-link/{linkId}
POST /api/provider-checks/collection/{collectionId}
POST /api/provider-checks/all
POST /api/provider-checks/jobs/artist/{artistId}
POST /api/provider-checks/jobs/collection/{collectionId}
POST /api/provider-checks/jobs/all
GET  /api/provider-checks/jobs/current
POST /api/provider-checks/jobs/current/cancel
GET  /api/provider-checks/runs?limit=25
GET  /api/provider-checks/runs/{id}/events
```

User-facing provider scan triggers must use the provider check job flow so the status bar, row spinners, disabled scan buttons, and post-check refresh stay consistent.
Batch provider check jobs skip provider links whose `last_checked_at` is inside the configured batch rescan delay.
Individual artist provider check jobs always run even when the artist was checked recently.

## 7. Local Scan Behavior

Keep parser support:

```text
artist - year - album
title (artist, year)
```

For each parsed local folder:

1. Resolve collection root.
2. Parse artist/title/year.
3. Upsert artist by normalized name.
4. Find album by artist + normalized title + year.
5. If album does not exist, create it with `checked = true`.
6. If album exists and local files are found, set `checked = true`.
7. Upsert `album_local_paths` by collection + relative path.
8. Set `last_seen_at = now` and clear `missing_since`.
9. Ensure the artist is assigned to the scanned collection in `artist_collections`.

At the start or end of a collection scan:

- Mark known paths for that collection missing when they were not seen in this scan.
- Do not delete albums automatically.
- Do not delete local path rows automatically.

The scan should be idempotent: rescanning the same folders should not create duplicate albums or paths.

## 8. Provider Check Behavior

Provider checks fetch discography data from configured provider links and normalize it into remote album records.

Provider abstraction:

```java
public interface DiscographyProvider {
    String providerId();
    boolean supports(String providerUrl);
    List<RemoteAlbum> fetchAlbums(String providerUrl) throws ProviderException;
}
```

```java
public record RemoteAlbum(
    String title,
    Integer releaseYear,
    LocalDate releaseDate,
    String sourceUrl
) {}
```

Supported providers:

- `musicbrainz`
- `spirit_of_metal`
- `metal_archives`

For each remote album:

1. Match existing album by artist + normalized title + year.
2. If found, leave `checked` unchanged.
3. If not found, create album with `checked = false`.
4. Record provider check events.

Provider checks should not infer local disk presence.
Provider checks run as background jobs from the frontend and update only the active artist row spinner.
MusicBrainz calls still go through the process-wide MusicBrainz request limiter.
Non-MusicBrainz providers may start the next artist as soon as the previous provider response is handled.

## 9. Frontend

Use Vue 3 + Vuetify.

Main views:

```text
CollectionsView.vue
ArtistsView.vue
LibraryView.vue
SettingsView.vue
```

### Collections View

This is the default route and primary work surface.

Layout:

- left collections pane;
- middle artists table;
- right albums table.

Behavior:

- page load fetches collections only;
- no collection selected on load;
- selecting a collection fetches artists for that collection;
- switching collection clears selected artist and albums;
- selecting an artist fetches albums for that artist;
- artist row hover shows pencil and refresh actions;
- album row hover shows delete action;
- delete action requires confirmation.

### Library View

Features:

- Search albums by artist/title/year.
- Filter by checked state.
- Filter by local path presence.
- Inline listened checkbox.
- Inline year editing if simple.
- Local presence indicator from `onDisk`.
- Show collection/path details without overwhelming the table.
- Add album form.

Suggested table columns:

```text
Artist
Album
Year
Listened
Local
Collection/Path
```

Use a checkbox for `Listened`.

For `Local`, choose a compact UX such as:

- disk/check icon for on disk;
- muted dash for not local;
- tooltip with resolved path when available.

### Artists View

Features:

- Search artists.
- Select an artist to manage provider links.
- Show album counts.
- Show unchecked album count.
- Show local album count.
- Manage provider links.
- Check releases for one artist.
- Bulk provider operations.

Suggested columns:

```text
Artist
Albums
Unchecked
Local
Provider Links
Actions
```

Artist detail modal:

- opens from pencil hover action in the Collections artists table;
- contains editable artist details only;
- includes provider link editing;
- includes collection membership editing;
- does not include album summary/list.

### Settings View

Features:

- Shows configured music root from the JVM override.
- Shows root validation state.
- Shows configured collections.
- Settings are backed by the shared Quarkus properties file; `music-library.music-root` is per-machine runtime input.
- Settings are read-only; per-machine values are supplied when the app starts.

### Provider Check UI

Features:

- Check one artist.
- Check all enabled provider links.
- Show recent provider check runs.
- Show how many new unchecked albums were added.

## 10. Implementation Checklist

1. Maintain `V1__init.sql` as the base schema, including `artist_collections`.
2. Keep album listening state as the single `checked` flag.
3. Validate `music-library.music-root` before database migration.
4. Keep domain records and repositories aligned with the database schema.
5. Provide collection-scoped artist membership APIs.
6. Provide artist APIs with collection membership and provider links.
7. Provide album APIs with `checked`, `hasLocalPath`, and `onDisk`.
8. Keep local scan idempotent through `album_local_paths` and `artist_collections`.
9. Keep Collections view as the three-pane default screen.
10. Keep artist detail modal focused on artist details, provider links, and collection membership only.
11. Keep album delete behind confirmation.
12. Keep Library view focused on global album search and listened/local state.
13. Keep Artists view focused on global artist management and provider checks.
14. Keep provider check API and run history available.
15. Add focused backend tests for schema, scan, provider, and API behavior.
16. Run full app smoke tests from an empty DB.

## 11. Tests

Backend tests:

- Schema creates successfully from empty DB.
- Artist can be created without albums.
- Album can be created checked with no local path.
- Album checked flag can be toggled.
- Local scan creates checked albums and local paths.
- Local scan marks locally present albums checked.
- Local rescan does not duplicate albums/paths.
- Missing local folders mark path `missing_since`.
- Album DTO reports `hasLocalPath` and `onDisk`.
- Artist DTO reports album counts.
- Provider check creates unchecked albums.
- Provider check does not duplicate existing albums.

Frontend smoke checks:

- Empty DB loads.
- Add artist.
- Add listened album with no local files.
- Toggle listened checkbox.
- Scan local collection.
- See local indicator for scanned albums.
- Add provider link.
- Check releases and see new unchecked albums.

## 12. Non-Goals

Do not implement:

- Three album status model.
- Wishlist/ignored/listen-later.
- Automatic provider checks on startup.
- Playlist writing.
- Full audio metadata parsing.
- Native packaging.
- Multi-user sync conflict handling.

## 13. Acceptance Criteria

The app is usable when:

- A fresh DB is created from `V1__init.sql`.
- Artists can exist without albums.
- Albums have one editable listened/checked flag.
- Manual listened albums can exist without local files.
- Local scan creates/updates local album paths.
- Local scan marks locally present albums as checked.
- The UI clearly distinguishes listened state from local disk presence.
- Provider links can be configured per artist.
- Provider checks add new albums as unchecked.
- Existing albums are not duplicated by scan or provider check.
