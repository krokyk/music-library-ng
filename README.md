# Music Library NG

Music Library NG is a local-first music collection app for one user on one PC at a time.
It has a Quarkus Java 21 backend, SQLite storage, and a Vue 3 + Vuetify frontend served by the backend.
The default app URL is `http://localhost:8795/`, and runtime data lives under `data/`.

## Project Map

- Backend code is under `src/main/java/org/kroky/musiclib`.
- Database schema starts at `src/main/resources/db/migration/V1__init.sql`.
- Frontend code is under `frontend/src`.
- Shared defaults and inline config comments live in `src/main/resources/application.properties`.
- Private local overrides live in ignored `config/application.properties`.
- Current application behavior is documented in `docs/current-application.md`.
- UI and workflow rules are documented in `docs/codex-ui-workflow-guide.md`.
- Unimplemented ideas are tracked in `docs/ideas.md`.

## Setup

Use WSL for regular development.
Install the basic tools:

```bash
sudo apt update
sudo apt install -y curl git unzip sqlite3
```

Java 21 is required.
Node.js is required for the Vue/Vuetify frontend.
`nvm` is the easiest Node setup:

```bash
curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install --lts
nvm use --lts
```

Verify the project toolchain:

```bash
cd <project-root>
java -version
./gradlew --version
node -v
npm -v
sqlite3 --version
```

You do not need Maven or a system Gradle install.
The repo includes the Gradle wrapper.

Create ignored local config before first run because MusicBrainz requires a private contact User-Agent:

```bash
mkdir -p config
$EDITOR config/application.properties
```

```properties
music-library.providers.musicbrainz.user-agent=music-library-ng (<email>)
```

The app can auto-detect known `_vyber` roots when the configured marker playlists exist.
When auto-detection does not fit the machine, pass the physical root with `-Dmusic-library.music-root="<music-root>"`.
Windows-style roots such as `E:/Google Drive/Music/_vyber` are resolved to WSL mounts when running under WSL.

If WSL Git should use 1Password-managed Windows SSH keys, configure Git to use the Windows SSH client:

```bash
git config --global core.sshCommand ssh.exe
```

## Running

Backend dev mode:

```bash
./gradlew quarkusDev -Dmusic-library.music-root="<music-root>"
```

Open:

```text
http://localhost:8795/
```

Frontend-only dev mode requires Quarkus to be running because Vite proxies `/api` to the backend:

```bash
cd frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173/
```

## Build And Package

Build everything:

```bash
./gradlew build
```

The build runs npm install, the Vue/Vuetify production build, Java compilation, tests, and Quarkus packaging.

Run the packaged app:

```bash
java -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

Optional helper commands live in `dev-shell.sh`.
`freshrun` stops app processes, deletes `data/`, rebuilds, and runs the packaged jar.

```bash
source dev-shell.sh
brun
freshrun
```

## Windows Native Executable

Build the native Windows `.exe` from Windows, not WSL.
Install Visual Studio Build Tools with Desktop development with C++, MSVC Build Tools v143 or newer, and the Windows 11 SDK.
Install GraalVM for JDK 21, Windows x64 archive, and point `GRAALVM_HOME` at it.

Build frontend assets in WSL first with the normal Gradle build when frontend changes need to be included.
Then run the native helper from Windows PowerShell:

```powershell
$env:GRAALVM_HOME = "<graalvm-jdk-21>"
cd <project-root>
.\build-native-exe.bat
```

The helper calls `scripts/build-native-exe.ps1`, loads the Visual Studio amd64 build environment when it can, and skips frontend tasks by default.
If you call the PowerShell helper directly after `frontend/dist` is current, use:

```powershell
.\scripts\build-native-exe.ps1 -SkipFrontend
```

The expected native executable is:

```text
build\music-library-ng.exe
```

For a native app distribution, prefer this layout:

```text
<app-directory>
  music-library-ng.exe
  config/application.properties
  data/
```

Quarkus reads `config/application.properties` from the process working directory automatically.

## Configuration

Property explanations are kept inline in `src/main/resources/application.properties` so values do not drift from the defaults.
Use ignored `config/application.properties` or JVM `-D...` arguments for private or machine-specific values.
The only value expected to differ between computers is the physical music root.
Runtime UI preferences are stored in SQLite after they are changed in Settings.
First-run UI defaults still come from `application.properties`.
The default batch provider rescan delay comes from `music-library.providers.default-batch-rescan-delay-minutes` and can be changed later in Settings.
Logs go to the console and `data/logs/music-library-ng.log`.

## Collection Model

Artist-centric collections browse collections, artists, and albums.
Title-centric collections browse collections and titles.
Artist-centric collection scans discover local artists and local albums in the same pass for supported flat and nested folder layouts.
Title-centric collection scans discover title rows, contributor artists, shared albums, and local paths in the same pass.
Local album scans remain explicit rescan actions for one artist or a whole artist-centric collection.
Provider scans from Collections add new or otherwise unassigned provider albums to the active collection and leave existing album memberships unchanged.
Artists can keep multiple provider links, such as MusicBrainz, Spirit of Metal, and Metal Archives at the same time.
Provider scans link exact, normalized, and high-confidence fuzzy provider album-title matches to existing local albums instead of adding duplicate unchecked albums.
Local album scans can attach a newly discovered local folder to a same-artist checked or provider-linked DB-only album when fuzzy title evidence is strong and the release year is compatible.
Provider scans keep local paths unchanged and record unresolved title conflicts when the linked provider title differs from the local album title.
Provider scans record unresolved release-date conflicts when a linked provider release year differs from the local album release year.
Unresolved release-date and title conflicts are shown after provider scans and remain indicated in the Artists view until resolved.
Release-date conflicts from multiple providers are grouped by local album and provider year.
Title conflicts from multiple providers are grouped by local album and provider title.
Keeping the local year preserves the local folder metadata while preventing future provider scans from adding the grouped provider albums again.
Kept-local year decisions can be reset from the Artists detail year chip so the mismatch becomes unresolved again.
Using the provider year updates the library release date metadata, resolves grouped provider links, and merges provider-only duplicates without renaming folders or writing audio tags.
Using the provider title updates the library album title metadata, resolves grouped provider links, and merges provider-only duplicates without renaming folders or writing audio tags.
Local album scans and provider scans run in background jobs so the workspace remains navigable while status updates continue.
Local and provider scans write plaintext reports under `data/reports`, and clickable status-history reports are kept only for the current window session.
Scan buttons and write actions such as add, edit, delete, and provider-link changes are disabled while a local or provider scan is running.
Batch provider scans skip provider links checked within the configured batch rescan delay, while individual artist provider scans always run.
The Artists view can bulk-match visible artists missing the selected provider against MusicBrainz, Spirit of Metal, or Metal Archives after search and collection filters are applied.
The Artists view `Add providers` action opens a multi-provider candidate dialog, starts matching missing providers immediately, preselects existing provider links, and saves only changed selections.
Provider candidate dialogs are shared between Collections and Artists, use provider chips, show confidence and fuzzy album evidence on album chips, and keep ambiguous provider search results as manual choices.
Manual provider matching and bulk provider matching use the same fuzzy artist-name and album-title evidence scoring.

Artist-centric album folder shapes:

```text
artist - release date - album
artist/release date - album
```

Title-centric folder shapes:

```text
title (artist, release date)
title (release date)
title - release date - subtitle
title - release date
title
```

MusicBrainz refresh imports supported full albums only, links exact, normalized, and high-confidence fuzzy title matches to existing albums, and creates missing albums as unchecked.

## SQLite Usage

The default SQLite connection uses WAL mode, `synchronous=NORMAL`, foreign keys, and a 30 second busy timeout so UI reads can continue during background scan writes.

This is safe for the intended workflow:

1. Run the app on one PC.
2. Close the app.
3. Let file syncing finish if the app directory or database is synced.
4. Open the app on another PC.

Do not run the app on two machines against the same SQLite database at the same time.

## Quick Checks

```bash
curl http://localhost:8795/api/health
curl http://localhost:8795/api/collections
```
