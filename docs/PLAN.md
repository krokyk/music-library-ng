# Music Library NG Roadmap

This roadmap tracks product capabilities and useful next steps.

## Core Capabilities

- Local-first Quarkus backend, SQLite database, and Vue 3/Vuetify frontend.
- Single packaged app served on port `8795`.
- Shared app configuration across computers.
- Per-machine music root supplied by `music-library.music-root` or auto-detected from approved `_vyber` locations.
- Configured disk collections with parser, enabled state, and relative path.
- Artist tracking independent of local disk presence.
- Album listening state stored as one boolean flag: `checked`.
- Local disk presence stored through album path rows.
- Collection membership for artists, including manually added artists without local albums.
- Local scan for `artist - year - album` folders.
- Local scan for `title (artist, year)` soundtrack/musical folders.
- Provider links and provider check history.
- Provider checks that add newly discovered albums as unchecked.
- Three-pane Collections workspace.
- Global Library and Artists views.
- Read-only Settings view for effective runtime configuration.

## Next Improvements

- Add app-level lock file and warning if another machine appears to be using the database.
- Add startup/shutdown backup handling.
- Add backup/restore metadata tables.
- Add asynchronous scan jobs and progress streaming.
- Add Cover Art Archive support.
- Add directory picker helper per OS, if useful.
- Add job progress UI.
- Add screenshots.
- Document release packaging.
- Decide how to represent compilations with multiple artists.
- Add duplicate-detection workflow for slightly different album names.
- Add export/import to JSON for extra safety.
- Add Android-friendly browser/PWA access pattern.

## Verification

- `./gradlew test`
- `./gradlew build`
- Start the packaged app on port `8795`.
- Verify `/api/health`.
- Verify startup fails fast without a valid music root.
- Verify a fresh SQLite database is created from `V1__init.sql`.
- Verify manual artist creation with collection membership.
- Verify manual album creation and checked toggling.
- Verify scan parser behavior with sample directories.
- Verify provider link creation and provider check run history.
- Verify the Vue frontend build.
