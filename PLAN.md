# Music Library NG Plan

This file is the project checklist. Keep it current as work progresses so the
codebase can be inspected and resumed without rediscovering intent.

## Product Direction

- [x] Choose architecture: Quarkus backend, SQLite database, Vue 3/Vuetify frontend.
- [x] Keep the app local-first and runnable from multiple PCs through a Google Drive synced SQLite file.
- [x] Make the HTTP port configurable with default `8795`.
- [x] Support manual artist/album entry even when nothing exists on disk.
- [x] Support configurable disk collections, including initially enabled and ignored collections.
- [x] Package the Vue/Vuetify frontend into the Quarkus app for single-command use.
- [ ] Add a Derby import tool for the legacy `music-library` database.

## Configuration

- [x] Add `application.properties` defaults.
- [x] Add config file support through Quarkus config locations.
- [x] Define collection entries with `id`, `name`, `relative-path`, `parser`, and `enabled`.
- [x] Add music root detection using only approved `_vyber` candidates and marker playlists.
- [x] Store only paths relative to the runtime music root in SQLite.
- [x] Add default collections for the current `_vyber` folders.
- [x] Mark `CLASSICAL` and `CZECH & SLOVAK` as disabled for the initial release.
- [ ] Add a UI settings page for editing collections.
- [ ] Add app-level lock file and warning if another machine appears to be using the DB.
- [ ] Add startup/shutdown backup handling.

## Database

- [x] Add SQLite/Flyway schema.
- [x] Use numeric IDs instead of artist names as primary keys.
- [x] Add artists, albums, collections, scan runs, and scan events.
- [x] Store listened state as `checked` and local disk presence as separate path rows.
- [x] Add indexes for search and uniqueness.
- [x] Add provider link tables and provider check history.
- [ ] Add backup/restore metadata tables.

## Backend

- [x] Scaffold Quarkus project with Gradle wrapper.
- [x] Add health endpoint.
- [x] Add artist CRUD endpoints.
- [x] Add album CRUD endpoints.
- [x] Add collections endpoint.
- [x] Add scan endpoint for configured collections.
- [x] Parse standard folders: `artist - year - album`.
- [x] Parse soundtrack/musical folders: `title (artist, year)`.
- [x] Upsert scanned albums as checked/owned.
- [x] Allow manual creation of checked albums.
- [ ] Add asynchronous scan jobs and progress streaming.
- [x] Add provider abstraction.
- [ ] Add Cover Art Archive support.
- [x] Add optional legacy Metal Archives / Spirit of Metal providers.
- [ ] Add directory picker helper per OS, if needed.
- [x] Add tests around edge-case folder parsing.

## Frontend

- [x] Add a temporary browser UI served by Quarkus for early verification.
- [x] Add `frontend/` Vue 3/Vuetify workspace files.
- [x] Install Node tooling and run the Vue build.
- [x] Build `LibraryView.vue` with editable Vuetify tables.
- [x] Add inline listened checkbox editing for albums.
- [x] Add artist/provider-link management view.
- [x] Add manual artist and album controls.
- [x] Add scan collection view.
- [ ] Add settings page.
- [ ] Add job progress UI.
- [x] Wire frontend build output into `src/main/resources/META-INF/resources`.

## Documentation

- [x] Document local prerequisites and install options.
- [x] Document run commands.
- [x] Document SQLite-on-Google-Drive usage.
- [x] Document collection configuration.
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

- [x] Use one boolean album state: `checked` means listened.
- [ ] Decide how to represent compilations with multiple artists.
- [ ] Add duplicate-detection workflow for slightly different album names.
- [ ] Add export/import to JSON for extra safety.
- [ ] Add Android-friendly access pattern, likely through browser/PWA first.
