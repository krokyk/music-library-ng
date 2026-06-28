# Evolution 09: Provider Check Reporting And Fetching

Historical note: This evolution document is archival context, not current source of truth.
Use `docs/current-application.md` and the current code when behavior conflicts.

Date: 2026-06-26

## Scope

This document captures provider-check workflow changes.
The work covers provider scan status behavior, provider run details, provider error visibility, regular-album filtering, and more resilient provider fetching.

## Related Commits

- Commit `77f97c4` preserved the Collections screen Artists pane during local and provider scans.
  It patches affected artist rows after scan jobs and supports collection-scoped artist loading for those refreshes.
- Commit `212ba28` simplified the provider rescan delay slider.
  It removed per-tick labels and kept the selected delay visible in the current-value readout.
- Commit `9ab98d2` refined provider reporting, provider fetching, MusicBrainz album filtering, and the provider album import API surface.

## Status And Report Workflow

- Provider scan status history entries are clickable when they have provider run IDs.
- Clicking a provider status entry opens a plain-text provider check report matching the scan-report style.
- Provider reports are generated from stored provider runs and provider events.
- Provider reports avoid colored custom styling and use the same plain text presentation as scan reports.
- Provider reports keep the run ID in diagnostics so a user can correlate app UI with Quarkus log lines.
- Provider startup status no longer shows `providers (0/0)` while the job has not discovered item totals.
- Provider run details can be copied to the clipboard from the report dialog.
- Provider report generation is implemented in `frontend/src/App.vue`.

## Provider Report Language

- The main provider summary now uses user-facing outcome terms instead of implementation terms.
- `Provider albums found` means the number of regular albums read from provider responses.
- `Already in library` means provider albums that already have a local album row or were matched to one during the run.
- `Added as unchecked` means new unchecked album rows created from provider data.
- `Errors` means provider artists that failed during the run.
- `Provider records ignored` is a diagnostics count for provider records excluded because they are not supported full albums or lack required data.
- Older stored provider events are parsed into the current report labels for compatibility.
- Raw stored run messages are no longer shown in the main report summary because they can contain obsolete implementation wording.

## Error Visibility

- Provider failures now include the provider URL, HTTP status, and root-cause class/message where available.
- Provider failures are logged with stack traces and run, artist, and provider-link identifiers.
- The UI report surfaces provider error details in the clickable message detail instead of only showing a failed status line.
- Job-level provider failures use the same root-cause description helper as per-provider failures.
- Failed provider checks no longer count as a successful recent check for batch cooldown skipping.
- Batch cooldown skipping now uses `last_success_at` and allows immediate retry when the latest provider state is an error.
- Cooldown skip messages say `successfully checked within` to avoid implying a failed check blocks retry.

## MusicBrainz Import Rules

- MusicBrainz release-group fetching now requests `type=album` instead of `album|ep`.
- MusicBrainz imports defensively keep only release groups with primary type `Album` and no secondary types.
- EPs, singles, splits, compilations, live releases, demos, and other secondary typed records are excluded before import.
- MusicBrainz full albums are either matched by exact normalized title or added as unchecked albums.
- MusicBrainz provider records that are not supported full albums are ignored as diagnostics.
- MusicBrainz provider messages now say `albums` instead of exposing `release groups` in new user-facing run events.
- The unused provider-album decision endpoint and DTOs were removed.
- MusicBrainz regular-album filtering is covered by unit tests.

## HTML Provider Fetching

- Metal Archives no longer fetches the full band page with `Jsoup.connect`.
- Metal Archives extracts the band ID and fetches the main discography endpoint with Java `HttpClient`.
- Metal Archives parses the returned HTML string with Jsoup and imports only `Full-length` rows.
- Metal Archives retries transient HTTP statuses `429`, `500`, `502`, `503`, and `504`.
- Metal Archives sends a simple app User-Agent and uses the original band URL as the referer for the discography endpoint.
- Spirit of Metal now also fetches with Java `HttpClient` and parses the returned HTML string with Jsoup.
- Spirit of Metal retries the same transient HTTP statuses as Metal Archives.
- Spirit of Metal parses album rows from the page discography section and keeps the provider album URL as source evidence.
- HTML provider parsing behavior is covered by unit tests.

## Dev Tooling

- The Windows UI smoke-test script now has top-level constants for browser path candidates, the CDP profile name, and browser startup timeout.
- The script reuses the CDP profile constant in start and stop logic.

## Verification

- `npm run build`
- `./gradlew test --tests org.kroky.musiclib.provider.html.MetalArchivesProviderTest --tests org.kroky.musiclib.provider.html.SpiritOfMetalProviderTest`
- `./gradlew test --tests org.kroky.musiclib.provider.musicbrainz.MusicBrainzClientTest`
- `./gradlew build`
- `git diff --check`
- Packaged-app provider check for Asleigh Stake through Metal Archives on temporary port `8796`.
- Packaged-app provider check for Angra through Spirit of Metal on temporary port `8796`.
- Packaged UI smoke test against a temporary packaged app on `http://localhost:8796/`.
