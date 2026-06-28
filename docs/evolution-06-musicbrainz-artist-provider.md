# Evolution 06: MusicBrainz Artist Provider

Date: 2026-06-15
Updated: 2026-06-27

## Scope

This document records the MusicBrainz artist-provider workflow.
The workflow covers MusicBrainz artist identity assignment, release-group fetching, provider album import, provider run history, and the MusicBrainz-specific parts of generic provider matching.
Evolution 11 owns the current Artists-screen bulk matching UI and the shared provider candidate API.

## Related Commits

- Commit `84c79b3` added the initial MusicBrainz artist-provider workflow.
- Commit `f800f88` and nearby UI commits refined artist provider assignment and row display.
- Commit `3b16aa7` added background provider scan jobs.
- Commit `212ba28` simplified the provider rescan delay setting.
- Commit `9ab98d2` removed the unused provider-album decision API and simplified MusicBrainz album import to supported full albums only.

## Artist Provider Identity

- An artist can have one provider identity in `artist_provider_links`.
- MusicBrainz provider identity uses `provider_artist_id` for the artist MBID.
- MusicBrainz provider URL is derived from the MBID as `https://musicbrainz.org/artist/<mbid>`.
- Provider metadata stores the remote artist name, type, country, disambiguation, and active flag when MusicBrainz supplies them.
- `providers` stores the supported provider kinds.
- `artist_provider_links.enabled=false` keeps the identity but excludes it from provider checks.
- `UNIQUE (artist_id)` enforces one provider identity per artist in the current schema.
- `UNIQUE (provider_id, provider_artist_id)` prevents assigning the same remote artist identity to multiple local artists.

## MusicBrainz Configuration

- MusicBrainz API calls use `music-library.providers.musicbrainz.base-url`.
- MusicBrainz site links use `music-library.providers.musicbrainz.site-url`.
- The request limiter uses `music-library.providers.musicbrainz.request-min-interval-ms`.
- Candidate search uses `music-library.providers.musicbrainz.search-candidate-limit`.
- Release-group paging uses `music-library.providers.musicbrainz.release-group-page-size`.
- The app requires `music-library.providers.musicbrainz.user-agent` from ignored local config.
- The committed defaults must not include a private email address.
- Every MusicBrainz request sends the configured User-Agent exactly.
- MusicBrainz requests use a process-wide limiter so concurrent UI actions do not exceed the public rate limit.

## Artist Matching

- The Artists screen can search MusicBrainz candidates for a local artist.
- Candidate search returns provider ID, MBID, provider artist name, URL, type, country, disambiguation, provider score, local match score, matched local album evidence, and preview release groups.
- The user chooses the provider identity before it is stored.
- MusicBrainz bulk artist matching is available from the Artists screen through the shared provider matching controls.
- Bulk matching auto-links only high-confidence artist identities.
- Uncertain bulk artist matches remain manual candidate-selection results and do not change the artist until accepted.
- Artists that already have a provider identity are skipped by bulk matching.

## Album Provider Identity

- `album_provider_links` stores the mapping from a provider release group to a local album.
- The table stores provider ID, provider release-group ID, provider title, provider release date, provider URL, and match source.
- Repeated provider refreshes use `album_provider_links` to avoid adding the same provider release group more than once.
- MusicBrainz stores release-group IDs, not release IDs.
- Clearing an artist provider does not delete albums or album-provider links.

## MusicBrainz Album Import

- `MusicBrainzClient.fetchReleaseGroups` fetches MusicBrainz release groups by artist MBID.
- Fetching requests `type=album`.
- The client keeps only release groups with primary type `Album` and no secondary types.
- EPs, singles, splits, compilations, live releases, demos, soundtrack secondary types, and other secondary-typed records are excluded before import.
- The import service also ignores blank titles, unsupported primary types, and records with secondary types.
- Already mapped provider release groups count as already in library.
- A provider album with an exact normalized title match to an existing local album is treated as already in library.
- Exact title matching does not require release-date equality.
- When an exact local title match has no local release date, the provider release date fills the missing value.
- A supported full album with no exact local title match is created as an unchecked album.
- Created MusicBrainz albums are linked through `album_artists`.
- Direct one-artist MusicBrainz refresh creates artist-level albums and does not assign them to a collection.
- Collection-scoped provider checks add provider-discovered albums to the selected collection only when the album has no collection memberships yet.
- Provider-discovered albums with existing collection memberships keep those memberships unchanged.
- MusicBrainz provider records that are filtered out are counted as ignored diagnostics, not surfaced as user decisions.

## Provider Check Jobs

- Provider scan buttons use the provider job endpoints.
- Provider jobs keep the workspace responsive while the job runs.
- Provider jobs show active artist, processed count, skipped count, found albums, new albums, existing albums, and errors.
- Batch provider jobs skip provider links whose last successful check is within the configured batch rescan delay.
- A failed provider check does not block an immediate retry.
- Individual artist provider checks always run even when the artist was checked recently.
- Provider check runs and events are stored in `provider_check_runs` and `provider_check_events`.
- Status-history entries with provider run IDs open a plain-text provider check report.

## Provider Report Terms

- `Artists checked` means provider links processed during the run.
- `Provider albums found` means supported provider album records read from provider responses.
- `Already in library` means provider albums already mapped or matched to a local album.
- `Added as unchecked` means new unchecked album rows created from provider data.
- `Provider records ignored` is a diagnostics count for filtered or malformed provider records.
- `Errors` means provider links that failed during the run.

## HTTP API

- `GET /api/artists/{artistId}/provider` returns the selected provider identity for an artist.
- `PUT /api/artists/{artistId}/provider` stores or replaces the selected provider identity.
- `DELETE /api/artists/{artistId}/provider` clears the selected provider identity.
- `GET /api/artists/{artistId}/provider-candidates/musicbrainz` is superseded by `GET /api/artists/{artistId}/provider-candidates/{providerId}` for shared provider candidate search.
- `POST /api/provider-matches/{providerId}/artists` performs shared bulk artist matching for the requested provider.
- `POST /api/artists/{artistId}/provider/refresh` refreshes one artist's MusicBrainz provider albums.
- `POST /api/provider-checks/jobs/artist/{artistId}` starts a provider job for one artist.
- `POST /api/provider-checks/jobs/collection/{collectionId}` starts a provider job for one collection.
- `POST /api/provider-checks/jobs/all` starts a provider job for all enabled provider links.
- `GET /api/provider-checks/jobs/current` returns current provider job status.
- `POST /api/provider-checks/jobs/current/cancel` requests cancellation.
- `GET /api/provider-checks/runs?limit=25` returns recent provider runs.
- `GET /api/provider-checks/runs/{id}/events` returns provider run events.

## Code Paths

- `MusicBrainzClient` owns MusicBrainz HTTP calls, rate limiting, pagination, and release-group filtering.
- `ArtistProviderMatchService` searches candidates for one artist.
- `ArtistProviderBulkMatchService` performs shared provider bulk artist matching.
- `ArtistProviderRefreshService` imports MusicBrainz albums for a provider identity.
- `ProviderCheckService` coordinates provider checks across MusicBrainz and HTML providers.
- `ProviderCheckJobService` owns background provider job state and status messages.
- `AlbumProviderLinkRepository` stores provider release-group mappings.
- `ArtistProviderResource` owns one-artist provider identity and refresh endpoints.
- `ProviderCheckResource` owns provider job and run-history endpoints.

## Current Constraints

- Provider refresh imports album-level release groups only.
- Provider refresh has no track, release, cover-art, or MusicBrainz edit submission flow.
- Provider refresh does not delete local albums or local paths because a provider omits a release group.
- Provider refresh does not infer local disk presence from provider data.

## Verification

- MusicBrainz regular-album filtering is covered by `MusicBrainzClientTest`.
- Provider HTML parsing is covered by `MetalArchivesProviderTest` and `SpiritOfMetalProviderTest`.
- The provider refresh and report code was built with `npm --prefix frontend run build` and `./gradlew build` in commit `9ab98d2`.
