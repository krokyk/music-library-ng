# Evolution 06: MusicBrainz Artist Provider

Date: 2026-06-15

## Scope

This milestone adds the first real API-backed artist provider.

The target workflow is:

- select one artist,
- find the matching MusicBrainz artist,
- store the MusicBrainz artist MBID as the artist's provider identity,
- refresh the provider discography,
- match returned MusicBrainz release groups against existing local albums,
- create missing albums as unchecked,
- ask the user only when the album match cannot be determined safely.

The first implementation should prove the full loop with one artist:

```text
Antti Martikainen
MusicBrainz artist MBID: 36b891ab-5e89-4f17-bb91-f189764de5ff
```

Bulk artist matching is not part of this milestone.
The app should support one artist at a time first.

## Product Decisions

- An artist has zero or one discography provider.
- MusicBrainz is the first provider.
- The provider identity for MusicBrainz is the artist MBID.
- Provider checks use MusicBrainz release groups, not raw releases.
- Release groups are imported as albums.
- Imported provider albums are created as unchecked.
- Existing albums are not duplicated when a clear match exists.
- Ambiguous remote release groups are shown for user review instead of being guessed.
- Manual local album edits must not be overwritten by provider data.
- MusicBrainz API usage must set the configured User-Agent exactly and stay at
  or below one request per second across all MusicBrainz requests.

## Non-Goals

- Do not build a polished bulk MusicBrainz matching screen.
- Do not download or mirror the full MusicBrainz PostgreSQL database for this milestone.
- Do not support multiple providers per artist yet.
- Do not add Last.fm integration.
- Do not import tracks.
- Do not import cover art.
- Do not submit data back to MusicBrainz.
- Do not solve provider conflicts across multiple sources.
- Do not auto-delete local albums that disappear from a provider response.

## Post-MVP Bulk Matching

Bulk MusicBrainz matching is now available from Settings as a focused follow-up to the one-artist workflow.
The bulk matcher reuses the same MusicBrainz candidate search and scores candidates with MusicBrainz score, artist-name similarity, local album title matches, and local title-plus-year matches.
The app auto-links only high-confidence candidates with local album evidence and enough separation from the runner-up candidate.
Candidates without enough evidence are returned as `NEEDS_REVIEW` so the existing one-artist candidate chooser can make the final decision.
Artists that already have a provider identity are skipped instead of being overwritten.
No-match decisions are not persisted yet, so a later bulk run may try those artists again.

## External API Notes

Official MusicBrainz API facts verified on 2026-06-15:

- API root: `https://musicbrainz.org/ws/2/`
- JSON is supported with `fmt=json`.
- Search is required when only an artist name is known.
- Browse or lookup requires an MBID.
- Public clients must use a meaningful User-Agent.
- This app must use the configured `music-library.providers.musicbrainz.user-agent` exactly.
- The committed defaults must not include a personal email address.
- Private contact values belong in ignored external config.
- Startup must fail when the MusicBrainz User-Agent is missing or does not match `music-library-ng (<email>)`.
- Public clients must not exceed one request per second.
- Browse/search results are paginated; use `limit` and `offset`.

Relevant documentation:

- `https://musicbrainz.org/doc/MusicBrainz_API`
- `https://musicbrainz.org/doc/MusicBrainz_API/Search`
- `https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting`
- `https://musicbrainz.org/doc/Release_Group/Type`

## Current App State

The app has a provider concept:

- `providers` stores supported provider kinds such as MusicBrainz, Spirit of Metal, and Metal Archives.
- `artist_provider_links` stores the selected provider identity for an artist.
- `DiscographyProvider` fetches `RemoteAlbum` rows from a provider URL.
- `ProviderCheckService` loops enabled provider identities and creates missing albums.
- Existing HTML providers parse pages from Metal Archives and Spirit of Metal.
- The Artists screen assigns one provider identity per artist from row-level provider buttons.

This milestone changes the semantics:

- the UI should call this "Provider", singular,
- one artist can have at most one provider identity,
- provider checks should use provider-specific artist identity,
- MusicBrainz should not be represented as "a pasted URL" internally.

Keep the table name `artist_provider_links` for now to avoid broad churn.
The table holds one row per artist and can later return to 1:n by removing the unique index on `artist_id`.

## Database Model

Because the project is still treated as fresh-start development, update `src/main/resources/db/migration/V1__init.sql` directly.

### Artist Provider Identity

Replace the current provider URL-only shape with a provider identity shape:

```sql
CREATE TABLE providers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url_required INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (url_required IN (0, 1)),
    CHECK (enabled IN (0, 1))
);

CREATE TABLE artist_provider_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER NOT NULL,
    provider_id TEXT NOT NULL,
    provider_artist_id TEXT,
    provider_artist_name TEXT,
    provider_artist_type TEXT,
    provider_artist_country TEXT,
    provider_artist_disambiguation TEXT,
    provider_artist_active INTEGER,
    provider_url TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    last_checked_at TEXT,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE,
    FOREIGN KEY (provider_id) REFERENCES providers(id),
    CHECK (enabled IN (0, 1)),
    CHECK (provider_artist_active IN (0, 1)),
    UNIQUE (artist_id),
    UNIQUE (provider_id, provider_artist_id)
);

CREATE INDEX idx_artist_provider_links_enabled ON artist_provider_links(enabled);
```

Rules:

- `provider_id` references `providers.id`.
- `provider_id='musicbrainz'` requires `provider_artist_id`.
- For MusicBrainz, `provider_artist_id` is the artist MBID.
- For MusicBrainz, `provider_url` is derived as `https://musicbrainz.org/artist/<mbid>`.
- For Spirit of Metal and Metal Archives, `provider_artist_id` is the concrete artist URL.
- For URL providers, `provider_url` stores the same concrete artist URL used for fetching.
- `provider_artist_name` stores the MusicBrainz display name at match time.
- `provider_artist_type`, `provider_artist_country`, `provider_artist_disambiguation`, and `provider_artist_active` store provider metadata when available.
- `enabled=false` means the provider identity exists but checks skip it.
- `UNIQUE (artist_id)` enforces one provider per artist.
- `UNIQUE (provider_id, provider_artist_id)` prevents two local artists from being linked to the same remote artist by accident.

### Album Provider Identity

Add a small table to remember which provider release group maps to which local
album:

```sql
CREATE TABLE album_provider_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    album_id INTEGER NOT NULL,
    provider_id TEXT NOT NULL,
    provider_release_group_id TEXT NOT NULL,
    provider_title TEXT NOT NULL,
    provider_release_date TEXT,
    provider_url TEXT,
    match_source TEXT NOT NULL DEFAULT 'AUTO',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    CHECK (match_source IN ('AUTO', 'MANUAL')),
    UNIQUE (provider_id, provider_release_group_id),
    UNIQUE (album_id, provider_id, provider_release_group_id)
);

CREATE INDEX idx_album_provider_links_album ON album_provider_links(album_id);
```

Why this is needed:

- repeated provider refreshes should not repeatedly ask about the same remote
  release group,
- accepted manual matches should be remembered,
- remote release group identity is more stable than title/date matching,
- future provider-specific source display can be added without changing album
  rows.

Do not store MusicBrainz release IDs for this milestone.
Use release group IDs.

Open point:

- ??? There is no "ignore this remote release group forever" table in the MVP.
  If review noise becomes a problem, add `provider_release_ignores` later.

## Configuration

Add first-run defaults to `application.properties`.

```properties
music-library.providers.musicbrainz.base-url=https://musicbrainz.org/ws/2
music-library.providers.musicbrainz.site-url=https://musicbrainz.org
music-library.providers.musicbrainz.request-min-interval-ms=1100
music-library.providers.musicbrainz.search-candidate-limit=5
music-library.providers.musicbrainz.release-group-page-size=100
music-library.providers.default-batch-rescan-delay-minutes=60
```

Put the required private User-Agent in ignored local config such as `./config/application.properties`.

```properties
music-library.providers.musicbrainz.user-agent=music-library-ng (<email>)
```

Notes:

- `request-min-interval-ms` should be slightly above 1000 to avoid accidental
  bursts.
- The configured User-Agent must be sent on every MusicBrainz request.
- Do not rely on JDK/Quarkus/default HTTP client user agents.
- Do not make concurrent MusicBrainz requests. A single process-wide limiter
  should serialize them.
- Retry handling must also respect the same limiter.
- MusicBrainz client properties are not runtime UI preferences.
  They belong in `application.properties`, not `user_preferences`.
- The batch provider rescan delay is different from MusicBrainz API throttling.
- The batch provider rescan delay uses `application.properties` as its default and stores user changes in `user_preferences`.
- Setting the batch provider rescan delay to `0` disables the recent-check skip.

## Backend Model Types

Add provider-specific DTOs without leaking raw MusicBrainz JSON to the frontend.

Suggested records:

```java
public record ArtistProviderCandidate(
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        String type,
        String country,
        String disambiguation,
        int providerScore,
        int matchScore,
        List<String> matchedLocalAlbums,
        List<RemoteReleaseGroup> releaseGroups) {
}

public record RemoteReleaseGroup(
        String providerId,
        String providerReleaseGroupId,
        String title,
        String releaseDate,
        String primaryType,
        List<String> secondaryTypes,
        String providerUrl) {
}

public record AlbumImportCandidate(
        RemoteReleaseGroup releaseGroup,
        String decision,
        Long matchedAlbumId,
        String matchedAlbumTitle,
        String reason,
        List<AlbumMatchOption> options) {
}

public record AlbumMatchOption(
        long albumId,
        String title,
        String releaseDate,
        int matchScore,
        String reason) {
}
```

Decision values:

```text
AUTO_MATCH_EXISTING
AUTO_CREATE
REVIEW
SKIP_ALREADY_LINKED
SKIP_UNSUPPORTED
```

## Backend Services

### MusicBrainz Client

Add a client class, for example:

```text
src/main/java/org/kroky/musiclib/provider/musicbrainz/MusicBrainzClient.java
```

Responsibilities:

- build MusicBrainz URLs safely,
- send the configured `User-Agent` header on every request,
- enforce throttling across all MusicBrainz requests in the process,
- serialize MusicBrainz requests so two UI actions cannot accidentally exceed
  the public API rate limit,
- parse JSON into local DTOs,
- handle pagination,
- throw `ProviderException` with useful messages.

Use JDK `HttpClient` and Jackson `ObjectMapper`.
Avoid adding a new dependency unless the implementation becomes awkward.

Minimum methods:

```java
List<MusicBrainzArtistResult> searchArtists(String artistName, int limit);

List<RemoteReleaseGroup> fetchReleaseGroups(String artistMbid);
```

Search endpoint:

```text
GET /ws/2/artist?query=artist:"Antti Martikainen"&fmt=json&limit=5
```

Release group endpoint:

```text
GET /ws/2/release-group?artist=<mbid>&type=album|ep&fmt=json&limit=100&offset=0
```

Release group fetch rules:

- fetch all pages until returned count is exhausted,
- wait through the same MusicBrainz limiter before every page request,
- normalize `first-release-date` with `ReleaseDates.normalize`,
- if MusicBrainz omits date, keep `releaseDate=null`,
- ignore time values if MusicBrainz ever returns them,
- include primary type `Album` and `EP`,
- keep secondary types for display/review.

HTTP/rate-limit rules:

- Do not commit the private User-Agent value.
- Require the private User-Agent value from external config.
- Use the configured value exactly; do not append extra framework tokens.
- There must be one process-wide limiter for all MusicBrainz calls.
- The limiter must apply to artist searches, release-group browse pages,
  validation lookups, retries, and any future MusicBrainz endpoint.
- If MusicBrainz returns `503`, `429`, or another transient rate/server error,
  do not retry immediately. Wait through the limiter and use bounded retries.
- Surface persistent rate-limit failures as provider errors in the UI and
  provider check history.

Open point:

- ??? MusicBrainz has both primary and secondary release-group types.
  MVP should include `Album` and `EP` primary types even when secondary type is
  `Compilation` or `Soundtrack`, because Antti Martikainen has relevant local
  albums that MusicBrainz classifies this way.
  Later we may add UI filters for compilations/live/remix releases.

### Artist Provider Match Service

Add a service such as:

```text
src/main/java/org/kroky/musiclib/provider/ArtistProviderMatchService.java
```

Responsibilities:

- load the local artist,
- load that artist's local albums,
- search MusicBrainz artist candidates,
- fetch each candidate's release groups,
- score each candidate,
- return candidates sorted by match score, then provider score.

Candidate scoring should use:

- MusicBrainz search score,
- normalized artist name equality,
- normalized artist name similarity,
- count of local album title matches,
- count of local album title plus release-year matches,
- candidate type/country/disambiguation only as display evidence.

Do not auto-link in this service.
The user must accept the provider identity in the MVP.

Local album filtering for scoring:

- ignore obvious UI/test albums,
- ignore albums with release dates far in the future,
- include checked and unchecked albums,
- include local and nonlocal albums.

For the current DB, Antti Martikainen has test rows:

```text
UI Test Checked Nonlocal   2098
UI Test Unchecked Nonlocal 2099
```

These should not reduce candidate confidence.

Open point:

- ??? Decide the exact future-date cutoff. A pragmatic default is:
  ignore local albums whose release year is greater than current year + 1.

### Artist Provider Repository

Update `ArtistProviderLinkRepository`:

- return at most one provider by artist,
- upsert provider identity by artist,
- clear provider by artist,
- preserve `markSuccess` and `markError`,
- include `provider_artist_id` and `provider_artist_name` in all DTOs.

Suggested methods:

```java
Optional<ArtistProviderLink> findByArtist(long artistId);

ArtistProviderLink upsertForArtist(
        long artistId,
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        boolean enabled);

void deleteByArtist(long artistId);
```

Keep `find(long id)` for check-run compatibility.

### Album Provider Link Repository

Add:

```text
src/main/java/org/kroky/musiclib/repository/AlbumProviderLinkRepository.java
```

Suggested methods:

```java
Optional<Long> findAlbumId(String providerId, String providerReleaseGroupId);

void linkAlbum(
        long albumId,
        String providerId,
        String providerReleaseGroupId,
        String providerTitle,
        String providerReleaseDate,
        String providerUrl,
        String matchSource);
```

### Provider Check Service

Update `ProviderCheckService` to support provider artist IDs.

For `provider_id='musicbrainz'`:

- require `provider_artist_id`,
- fetch release groups by MBID,
- produce album import candidates,
- auto-apply safe decisions,
- return review candidates when needed.

The existing `ProviderCheckSummary` is not enough for review.
Either add a new response type for single-artist refresh, or extend the summary
with a review list.

Preferred MVP shape:

```java
public record ProviderRefreshResult(
        long runId,
        long artistId,
        String artistName,
        String providerId,
        int foundReleaseGroupCount,
        int linkedExistingCount,
        int createdAlbumCount,
        int reviewRequiredCount,
        int skippedCount,
        List<AlbumImportCandidate> reviewCandidates,
        List<String> messages) {
}
```

Open point:

- ??? The existing collection/all provider check endpoints assume a simple summary.
  For this milestone, wire the richer review response only for one artist.
  Collection/all provider checks can remain disabled or simple until album
  review handling is designed for multi-artist runs.

## Album Matching Rules

Remote release groups should be classified before modifying albums.

### Already Linked

If `album_provider_links` has `(provider_id, provider_release_group_id)`, skip
the release group.

Decision:

```text
SKIP_ALREADY_LINKED
```

### Exact Existing Album Match

Auto-link to an existing album when:

- normalized remote title equals normalized local title, and
- one of these is true:
  - release dates are exactly equal,
  - both release years are equal,
  - local release date is null,
  - remote release date is null.

Decision:

```text
AUTO_MATCH_EXISTING
```

Action:

- insert `album_provider_links`,
- do not overwrite local title,
- do not overwrite local release date unless local release date is null.

### Safe Auto-Create

Auto-create an unchecked album when:

- no existing album has an exact normalized title match,
- no existing album has a strong fuzzy title match,
- remote title is nonblank.

Decision:

```text
AUTO_CREATE
```

Action:

- create album for the artist,
- set `checked=false`,
- set title from MusicBrainz release group title,
- set release date from normalized `first-release-date`,
- insert `album_provider_links` with `match_source='AUTO'`.

### Review Required

Require user review when:

- remote title is very similar to an existing album but not exact,
- exact title matches multiple local albums,
- exact title matches but release years conflict,
- remote release group has a secondary type that may be unwanted,
- local title has qualifiers like `(Remastered)`,
- MusicBrainz title looks like a volume/collection split of one local title,
- the candidate would create a likely duplicate.

Decision:

```text
REVIEW
```

Review options:

- link to one existing local album,
- create as a new unchecked album,
- skip this time,
- open MusicBrainz page.

MVP does not need a permanent ignore option.

### Examples From Antti Martikainen

Likely automatic matches:

```text
Eternal Saga                 -> Eternal Saga                 2014 / 2014-02-18
Enter Infinity               -> Enter Infinity               2015 / 2015-09-28
Throne Of The North          -> Throne of the North          2015 / 2015-02-27
Adventuria, Vol. 1           -> Adventuria, Vol. 1           2016 / 2016-12-20
Synthesia                    -> Synthesia                    2016 / 2016-06-11
The Sound of Courage         -> The Sound of Courage         2017 / 2017-04-01
The Heart of Avalon          -> The Heart of Avalon          2019 / 2019-08-29
Sonic Savior                 -> Sonic Savior                 2020 / 2020-12-28
Carmina Gloria               -> Carmina Gloria               2021 / 2021-07-21
```

Likely review cases:

```text
Northern Steel (Remastered)  <-> Northern Steel              2020 / 2017-12-06
Hymn of the High Seas        <-> Hymn of the High Seas       2020 / 2018-11-01
Origins                      <-> Origins                     2016 / 2016-07-24, secondary Compilation
```

Likely auto-created remote albums not currently local:

```text
Creation of the World
Another Saga
The Last Chronicle
Set Sail for the Golden Age
Hymn of the High Seas, Vol. 1
Hymn of the High Seas, Vol. 2
Novum
Wanderlust
Crimson Saga
Zero Frontier
Neon Horizon
Alatyr
Origins II
```

This list is illustrative.
The implementation should use live MusicBrainz API responses.

## HTTP API

### Get Current Artist Provider

```text
GET /api/artists/{artistId}/provider
```

Returns `404` or `null` if no provider is assigned.

Response:

```json
{
  "id": 1,
  "artistId": 95,
  "artistName": "Antti Martikainen",
  "providerId": "musicbrainz",
  "providerArtistId": "36b891ab-5e89-4f17-bb91-f189764de5ff",
  "providerArtistName": "Antti Martikainen",
  "providerUrl": "https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff",
  "enabled": true
}
```

### Search MusicBrainz Candidates

```text
GET /api/artists/{artistId}/provider-candidates/musicbrainz
```

Returns ranked candidates.

For Antti Martikainen, expected top candidate:

```json
{
  "providerId": "musicbrainz",
  "providerArtistId": "36b891ab-5e89-4f17-bb91-f189764de5ff",
  "providerArtistName": "Antti Martikainen",
  "providerUrl": "https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff",
  "type": "Person",
  "country": "FI",
  "disambiguation": "composer from Finland",
  "providerScore": 100,
  "matchScore": 100,
  "matchedLocalAlbums": [
    "Eternal Saga",
    "Enter Infinity",
    "Synthesia"
  ]
}
```

### Accept Artist Provider

```text
PUT /api/artists/{artistId}/provider
```

Request:

```json
{
  "providerId": "musicbrainz",
  "providerArtistId": "36b891ab-5e89-4f17-bb91-f189764de5ff",
  "providerArtistName": "Antti Martikainen",
  "providerUrl": "https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff",
  "enabled": true
}
```

Behavior:

- validates provider ID,
- validates MusicBrainz MBID format,
- upserts the provider row for the artist,
- replaces any existing provider for that artist.

### Clear Artist Provider

```text
DELETE /api/artists/{artistId}/provider
```

Behavior:

- removes the artist provider identity,
- does not remove albums,
- does not remove album provider links in the MVP unless the album itself is
  deleted.

Open point:

- ??? Decide later whether clearing an artist provider should also clear
  `album_provider_links` for that provider.

### Refresh One Artist Provider

Use a dedicated endpoint for the richer review response:

```text
POST /api/artists/{artistId}/provider/refresh
```

Behavior:

- requires an enabled provider,
- fetches remote release groups,
- auto-links exact matches,
- auto-creates safe missing albums,
- returns review candidates.

Do not require a collection ID for the MVP.
Provider-discovered albums are artist-level known albums, not local collection
scan results.

### Provider Check Jobs

```text
POST /api/provider-checks/jobs/artist/{artistId}
POST /api/provider-checks/jobs/collection/{collectionId}
POST /api/provider-checks/jobs/all
GET  /api/provider-checks/jobs/current
POST /api/provider-checks/jobs/current/cancel
```

Provider scan buttons in the UI use the job endpoints so the workspace remains responsive during long MusicBrainz refreshes.
Only the active artist row shows a scan spinner during batch provider scans.
The status bar shows the active artist, processed count, total count, and skipped count while a provider job is running.
Batch provider jobs skip enabled provider links whose `last_checked_at` is inside the configured batch rescan delay.
Individual artist provider jobs always run even when the artist was checked recently.
All provider scan buttons and write actions are disabled while a provider job is running.
MusicBrainz release-group requests still go through the same process-wide limiter and do not run concurrently.

Open point:

- ??? Existing provider-check endpoints support optional collection assignment.
  Decide later whether a provider-created album should be assigned to the
  currently selected collection. The cleaner default is no collection assignment
  unless the user explicitly runs from a collection context.

### Apply Album Review Decisions

```text
POST /api/artists/{artistId}/provider/album-decisions
```

Request:

```json
{
  "decisions": [
    {
      "providerId": "musicbrainz",
      "providerReleaseGroupId": "example-rgid",
      "action": "LINK_EXISTING",
      "albumId": 123
    },
    {
      "providerId": "musicbrainz",
      "providerReleaseGroupId": "example-rgid-2",
      "action": "CREATE"
    },
    {
      "providerId": "musicbrainz",
      "providerReleaseGroupId": "example-rgid-3",
      "action": "SKIP"
    }
  ]
}
```

Actions:

```text
LINK_EXISTING
CREATE
SKIP
```

Rules:

- `LINK_EXISTING` requires `albumId`.
- `CREATE` creates unchecked album and links it.
- `SKIP` does not persist anything in the MVP.
- Invalid release group IDs must be rejected unless they came from the current refresh response or can be re-fetched safely.

Open point:

- ??? Persisting review candidates between browser refreshes requires either a transient server-side cache or a durable import-run table.
  MVP can avoid this by requiring the UI to submit decisions from the current response.

## Frontend UX

The Artists screen assigns provider identities from the artist row.
Clicking the row selects the artist and updates the right-side Artist Info panel.
The row shows provider metadata columns for country, type, and active status when the selected provider supplies them.
The row shows MusicBrainz, Spirit of Metal, and Metal Archives buttons when no provider is assigned.
When a provider is assigned, the Provider column shows an icon and label chip with a trash action to clear the provider.
When a provider is assigned, the selected provider button is removed from that row and the remaining provider buttons replace the provider if used.
The row delete button keeps the existing artist deletion behavior.
The previous generic `Provider`, `Match MB`, and row-level refresh buttons are not part of this screen.
Provider refresh/check actions remain in the collection/provider scan workflow.

The right-side panel is read-only artist information.
It shows the selected artist name, sort name, country, type, active status, collections, album counts, selected provider identity, provider URL, provider error, and known albums.
It does not edit provider links.

### Match Dialog

Triggered by the MusicBrainz row button.

Layout:

- compact dialog or right-side pane,
- candidate rows/cards,
- no marketing or explanatory layout,
- show evidence rather than prose.

Candidate display:

```text
Name
MBID
Type
Country
Disambiguation
Matched local albums
Preview release groups
Use
Open
```

For Antti Martikainen the top candidate should be obvious:

```text
Antti Martikainen
Person, FI
composer from Finland
Matched albums: many
```

### Refresh Result Dialog

Triggered by provider scan or the provider refresh endpoint.

After the refresh:

- show a status summary,
- if no review is needed, close or show a short success message,
- if review is needed, show the album review dialog.

Summary:

```text
Found 31 release groups
Linked 12 existing albums
Created 13 unchecked albums
3 need review
```

Review row:

```text
Remote title | Remote date | Type | Suggested local album | Reason | Action
```

Actions per row:

- select existing album,
- create new,
- skip,
- open MusicBrainz.

The UI should keep the main app layout stable.
Dialogs/panes should scroll internally.
Do not add a browser-level vertical scrollbar.

## Antti Martikainen MVP Walkthrough

1. Start from a fresh DB schema with no provider for Antti Martikainen.
2. Open `Artists`.
3. Select or locate `Antti Martikainen`.
4. Click the `MusicBrainz` row button.
5. Backend searches MusicBrainz for artist name.
6. Backend returns candidate:

```text
Antti Martikainen
MBID 36b891ab-5e89-4f17-bb91-f189764de5ff
Person, FI, composer from Finland
```

7. User clicks `Use`.
8. App stores:

```text
artist_id = 95
provider_id = musicbrainz
provider_artist_id = 36b891ab-5e89-4f17-bb91-f189764de5ff
provider_artist_name = Antti Martikainen
provider_artist_type = Person
provider_artist_country = FI
provider_url = https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff
enabled = true
```

9. User runs the provider scan from the collection/provider workflow.
10. Backend fetches MusicBrainz release groups for that MBID.
11. Backend auto-links clear existing albums.
12. Backend auto-creates clear missing albums as unchecked.
13. Backend returns review cases for ambiguous items such as:

```text
Northern Steel (Remastered) <-> Northern Steel
Hymn of the High Seas local year mismatch / volume split risk
Compilation-classified release groups
```

14. User chooses link/create/skip for review cases.
15. Artist album list refreshes.
16. New provider albums appear as unchecked.
17. Status bar and recent checks reflect the provider refresh.

## Edge Cases

### Artist Matching

- MusicBrainz returns no candidates.
- MusicBrainz returns many candidates with the same name.
- Correct MusicBrainz artist uses a different name than the local artist.
- Local artist name has punctuation spacing differences.
- Local artist name is a folder shorthand.
- Local artist is not in MusicBrainz.
- Candidate has no release groups.
- MusicBrainz search succeeds but release-group fetch fails.
- User accepts wrong candidate and needs to change provider.

### Album Matching

- Remote release group has no date.
- Local album has no date.
- Local date is year-only and remote date is full date.
- Exact title match but different year.
- Same remote title appears more than once.
- Same local title appears more than once.
- Local title includes qualifiers like `(Remastered)`, `(Deluxe)`, or `(OST)`.
- Remote title is split into volumes but local title is not.
- Remote release group is a compilation/soundtrack/live/remix.
- Remote release group looks like a single but appears under EP.
- Remote release group title is blank or malformed.
- Album already exists for another artist in a multi-artist relationship.
- Album belongs to multiple local artists.
- MusicBrainz has duplicate or bad metadata.

### Provider Refresh

- Network timeout.
- HTTP 503 or rate limiting.
- Invalid JSON.
- MusicBrainz changes result ordering.
- MusicBrainz changes release-group data between refreshes.
- User closes browser during review.
- User refreshes twice before applying review decisions.
- Existing album was deleted after refresh candidates were produced.
- Provider was cleared after refresh candidates were produced.

### Data Integrity

- Never create duplicate album rows for the same artist and same provider
  release group.
- Never overwrite manual local title metadata with provider title.
- Never overwrite manual local release date with provider date.
- Never mark imported provider albums as checked.
- Never uncheck albums that are on disk.
- Never delete local paths because a provider omits a release.

## Implementation Order

1. Update `V1__init.sql`.
2. Update Java records:
   - `ArtistProviderLink`
   - add MusicBrainz candidate/release-group DTOs
   - add album import DTOs
3. Update repositories:
   - `ArtistProviderLinkRepository`
   - add `AlbumProviderLinkRepository`
4. Add MusicBrainz config to `MusicLibraryConfig`.
5. Add `MusicBrainzClient`.
6. Add artist provider match service.
7. Add album matching/import service.
8. Add artist provider REST resource endpoints.
9. Update `ProviderCheckService` or add a single-artist provider refresh service that reuses provider check run logging.
10. Update frontend types and Pinia store.
11. Update Artists screen:
    - row-level provider assignment buttons,
    - MusicBrainz candidate dialog,
    - read-only Artist Info panel,
    - album review dialog in provider scan workflow.
12. Test Antti Martikainen end-to-end.
13. Only after the one-artist loop works, consider a one-off script for initial high-confidence matching of the rest of the library.

## Verification Plan

Backend:

```bash
./gradlew test
```

Frontend:

```bash
npm run build --prefix frontend
```

Full package:

```bash
./gradlew build
```

Manual functional check:

1. Start with fresh DB.
2. Add or scan artists so `Antti Martikainen` exists.
3. Match MusicBrainz provider.
4. Confirm DB has one `artist_provider_links` row for the artist.
5. Run provider scan or the provider refresh endpoint.
6. Confirm safe existing albums are linked.
7. Confirm safe missing albums are created unchecked.
8. Confirm ambiguous albums are shown for review.
9. Apply at least one manual link decision.
10. Confirm `album_provider_links.match_source='MANUAL'`.
11. Run provider scan or the provider refresh endpoint again and confirm already linked release groups are skipped.

UI layout check:

- Run `scripts/check-ui-layout.ps1` if the Artists screen layout, dialogs, pane scroll behavior, or provider review UI affects page fit.
- Treat a browser-level vertical scrollbar as a failure.

## Implementation Notes

- The MVP was implemented against the current many-to-many album model using `album_artists`.
- The old `/api/artists/{artistId}/provider-links` endpoints remain for compatibility, but the schema now enforces one provider row per artist.
- MusicBrainz provider identity uses `provider_artist_id` for the artist MBID and `album_provider_links` for release-group mappings.
- Existing HTML providers can still use URL-oriented calls, with the URL acting as the legacy provider identity when no stable provider artist ID is supplied.
- The Artists screen owns provider assignment, selected-artist provider info, and known album display.
- Provider refresh/check workflows remain collection/provider scan actions.
- Provider-created MusicBrainz albums are currently artist-level albums and are not assigned to a collection by default.
- Review decisions are applied by re-fetching the artist's current MusicBrainz release groups, not by persisting a transient import-run cache.
- Permanent ignore decisions remain future work.

## Open Points

- ??? Should provider-created albums be assigned to the currently selected
  collection, or remain artist-level albums only?
- ??? Should secondary type `Compilation` be imported by default?
- ??? Should secondary type `Soundtrack` be imported by default for composer-like
  artists?
- ??? Should live/remix/demo release groups be hidden by default?
- ??? Should the app store permanent "ignore this remote release group" decisions?
- ??? Should clearing an artist provider clear album provider links for that artist?
- ??? Should `ProviderCheckService` keep collection/all checks enabled before
  multi-artist review handling exists?
- ??? Should MusicBrainz artist matching support searching by local album title when
  artist-name search is ambiguous or wrong?
- ??? Should a later initialization script auto-accept high-confidence matches for
  the remaining artists?

## Suggested Next Prompt

```text
Read AGENTS.md and docs/evolution-06-musicbrainz-artist-provider.md.
Implement the schema/model/repository prerequisites for the MusicBrainz provider MVP.
Keep the artist/provider relationship 0-or-1, add provider_artist_id/provider_artist_name, and add album_provider_links for MusicBrainz release group identity.
Use V1__init.sql as the canonical fresh-start schema.
Do not build the frontend matching UI yet.
Run ./gradlew test when done.
```
