# Music Library NG Plan

This file is the project checklist. Keep it current as work progresses so the
codebase can be inspected and resumed without rediscovering intent.

## Product Direction

- [x] Choose architecture: Quarkus backend, SQLite database, Vue 3/Vuetify frontend.
- [x] Keep the app local-first and runnable from multiple PCs through a Google Drive synced SQLite file.
- [x] Make the HTTP port configurable with default `8795`.
- [x] Support manual artist/album entry even when nothing exists on disk.
- [x] Support configurable disk sources, including initially enabled and ignored sources.
- [x] Package the Vue/Vuetify frontend into the Quarkus app for single-command use.
- [ ] Add a Derby import tool for the legacy `music-library` database.

## Configuration

- [x] Add `application.properties` defaults.
- [x] Add config file support through Quarkus config locations.
- [x] Define source entries with `id`, `name`, `relative-path`, `parser`, and `enabled`.
- [x] Add music root detection using only approved `_vyber` candidates and marker playlists.
- [x] Store only paths relative to the runtime music root in SQLite.
- [x] Add default sources for the current `_vyber` folders.
- [x] Mark `CLASSICAL` and `CZECH & SLOVAK` as disabled for the initial release.
- [ ] Add a UI settings page for editing sources.
- [ ] Add app-level lock file and warning if another machine appears to be using the DB.
- [ ] Add startup/shutdown backup handling.

## Database

- [x] Add SQLite/Flyway schema.
- [x] Use numeric IDs instead of artist names as primary keys.
- [x] Add artists, albums, music sources, scan runs, and scan events.
- [x] Store relative disk path and status separately.
- [x] Add indexes for search and uniqueness.
- [ ] Add schema migrations for provider IDs: MusicBrainz, Discogs, TheAudioDB.
- [ ] Add backup/restore metadata tables.

## Backend

- [x] Scaffold Quarkus project with Gradle wrapper.
- [x] Add health endpoint.
- [x] Add artist CRUD endpoints.
- [x] Add album CRUD endpoints.
- [x] Add scan sources endpoint.
- [x] Add scan endpoint for configured sources.
- [x] Parse standard folders: `artist - year - album`.
- [x] Parse soundtrack/musical folders: `title (artist, year)`.
- [x] Upsert scanned albums as checked/owned.
- [x] Allow manual creation of checked albums.
- [ ] Add asynchronous scan jobs and progress streaming.
- [ ] Add MusicBrainz provider abstraction.
- [ ] Add Cover Art Archive support.
- [ ] Add optional legacy Metal Archives / Spirit of Metal providers.
- [ ] Add directory picker helper per OS, if needed.
- [x] Add tests around edge-case folder parsing.

## Frontend

- [x] Add a temporary browser UI served by Quarkus for early verification.
- [x] Add `frontend/` Vue 3/Vuetify workspace files.
- [x] Install Node tooling and run the Vue build.
- [ ] Build `LibraryView.vue` with editable Vuetify tables.
- [ ] Add inline checkbox/status editing for albums.
- [ ] Add inline artist URL/name editing where safe.
- [x] Add manual artist and album controls.
- [x] Add scan source view.
- [ ] Add settings page.
- [ ] Add job progress UI.
- [x] Wire frontend build output into `src/main/resources/META-INF/resources`.

## Documentation

- [x] Document local prerequisites and install options.
- [x] Document run commands.
- [x] Document SQLite-on-Google-Drive usage.
- [x] Document source configuration.
- [ ] Add screenshots after the real Vue/Vuetify UI is running.
- [ ] Document legacy Derby import.
- [ ] Document release packaging.

## Verification

- [x] `./gradlew test`
- [x] `./gradlew build`
- [x] Start app on port `8795`.
- [x] Verify `/api/health`.
- [x] Verify manual artist/album creation.
- [x] Verify scan parser with sample directories.
- [x] Verify SQLite file creation in configured path.
- [x] Verify Vue frontend build after Node is installed.

## Open Questions / Future Enhancements

- [ ] Decide whether `OWNED`, `CHECKED`, or both should exist as album states.
- [ ] Decide how to represent compilations with multiple artists.
- [ ] Add duplicate-detection workflow for slightly different album names.
- [ ] Add "ignore album" and "wishlist" flows.
- [ ] Add export/import to JSON for extra safety.
- [ ] Add Android-friendly access pattern, likely through browser/PWA first.
