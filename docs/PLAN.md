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
- Local scan for mixed artist collections with `artist/year - album` nested folders.
- Local scan for `title (artist, year)` soundtrack/musical folders.
- Provider identities and provider check history.
- Provider checks that add newly discovered albums as unchecked.
- Background provider check jobs with status-bar progress and active-row scan indicators.
- Configurable batch provider rescan delay that skips recently checked provider links.
- One provider identity per artist with a provider lookup row and provider-specific artist identity.
- MusicBrainz provider identity uses MBID-backed artist matching and stores provider metadata such as country, type, and active status.
- MusicBrainz release-group refresh with exact auto-linking, safe unchecked album creation, and review candidates for ambiguous matches.
- Bulk MusicBrainz artist matching with conservative auto-linking and review results for uncertain candidates.
- Three-pane Collections workspace.
- Global Library and Artists views.
- Settings view for effective runtime configuration and UI preferences.

## Next Improvements

- Add app-level lock file and warning if another machine appears to be using the database.
- Add startup/shutdown backup handling.
- Add backup/restore metadata tables.
- Add Cover Art Archive support.
- Add directory picker helper per OS, if useful.
- Add richer provider check detail UI for skipped and error events.
- Add screenshots.
- Document release packaging.
- Decide how to represent compilations with multiple artists.
- Add permanent ignore decisions for unwanted provider release groups if review noise becomes a problem.
- Add duplicate-detection workflow for slightly different album names.
- Add identity-preserving folder rename workflow with preview and stored local-path update.
- Add artist-collection organizer with preview/apply support for moving flat album folders into nested artist folders.
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
- Verify provider identity assignment and provider check run history.
- Verify the Vue frontend build.
