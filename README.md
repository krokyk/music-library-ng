# Music Library NG

Music Library NG is a local-first music collection app with a Quarkus backend,
SQLite storage, and a Vue 3 + Vuetify frontend.

The app is designed for a single user who runs it on one PC at a time while the
music root may differ between computers.

## Features

- Runs on Quarkus with Java 21.
- Uses Gradle only; Maven is not used.
- Serves a Vue 3 + Vuetify frontend from the Quarkus app.
- Stores data in SQLite with Flyway migrations.
- Default HTTP port is `8795`.
- Supports configurable scan collections added from direct music-root folders.
- Supports artist-centric collections, for example Power Metal, Melodeath, and
  Rock.
- Supports title-centric collections, for example Soundtracks.
- Uses a type-aware Collections workspace:
  - artist collections: collections, artists, albums
  - title collections: collections, titles
- Scans artist collections lazily by artist folders first; albums are loaded
  only when an artist workflow needs them.
- Parses title collections with a soundtrack-oriented folder-name pipeline.
- Tracks one album listening flag: `checked=true` means listened.
- Tracks disk presence separately from the listened flag.
- Allows artists to be assigned to collections even before any local album folder exists.
- Refreshes artist discographies from provider links and adds new albums as unchecked.

## Tooling

Install these in WSL:

```bash
sudo apt update
sudo apt install -y curl git unzip sqlite3
```

Java 21 is required. Verify:

```bash
java -version
```

Node.js is required for the Vue/Vuetify frontend. `nvm` is recommended:

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

You do not need to install Maven or system Gradle. The repo includes the Gradle
wrapper.

### Git SSH From WSL With 1Password

If Git runs in WSL but SSH keys are managed by 1Password for Windows, configure
Git in WSL to use the Windows SSH client:

```bash
git config --global core.sshCommand ssh.exe
```

This lets WSL Git delegate SSH authentication to the Windows environment where
1Password can provide the key.

## Running In Dev Mode

Backend dev mode:

```bash
./gradlew quarkusDev -Dmusic-library.music-root="<music-root>"
```

The JVM property is optional only when auto-detection finds a valid music root.

Open:

```text
http://localhost:8795
```

Frontend-only dev mode, with Vite proxying `/api` to Quarkus:

```bash
cd frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

## Building

Build everything:

```bash
./gradlew build
```

The Gradle build runs:

- npm install
- Vue/Vuetify production build
- frontend copy into Quarkus resources
- Java compilation
- tests
- Quarkus packaging

Run the packaged app:

```bash
java -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

Build and run in one command:

```bash
./gradlew build && java -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

## Windows Native Executable

Quarkus can build a native Windows `.exe` with GraalVM. Build it from Windows,
not WSL, because the native image build needs the Windows C++ toolchain.

Build frontend assets in WSL with the normal Gradle build first; that is the
recommended path for npm/Vite work. Then use the Windows native build described
below to compile the native executable.

### Prerequisites

Install Visual Studio Build Tools with:

- Desktop development with C++
- MSVC Build Tools v143 or newer
- Windows 11 SDK

Install GraalVM for JDK 21, Windows x64 archive, and choose a local install
directory. The examples below keep that path in one environment variable:

```powershell
$env:GRAALVM_HOME = "<graalvm-jdk-21>"
```

### Developer Shell

Open a 64-bit Visual Studio developer PowerShell before running the native
build. Either use the Visual Studio installer's developer shell shortcut, or
create a shortcut that imports the Visual Studio developer shell module and
selects the 64-bit toolchain:

```text
powershell.exe -noe -c "&{Import-Module '<vs-build-tools-root>\Common7\Tools\Microsoft.VisualStudio.DevShell.dll'; Enter-VsDevShell <instance-id> -DevCmdArguments '-arch=amd64 -host_arch=amd64'}"
```

Keep the shortcut's "Start in" value pointed at the Visual Studio Build Tools
install directory:

```text
<vs-build-tools-root>
```

### Build

From the 64-bit developer PowerShell:

```powershell
$env:GRAALVM_HOME = "<graalvm-jdk-21>"
$env:JAVA_HOME = $env:GRAALVM_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

java -version
cd <project-root>
.\gradlew build "-Dquarkus.native.enabled=true" "-Dquarkus.package.jar.enabled=false"
```

If the frontend assets were already built in WSL and `frontend/dist` is current,
the native build can skip the npm steps:

```powershell
.\gradlew build "-Dquarkus.native.enabled=true" "-Dquarkus.package.jar.enabled=false" -x frontendInstall -x frontendBuild
```

Do not skip those tasks when frontend changes need to be included in the native
executable.

### External Config

For a native app distribution, prefer this layout:

```text
<app-directory>
  music-library-ng-runner.exe
  config/application.properties
  data/
```

Quarkus automatically reads `config/application.properties` from the process
working directory. If you want `application.properties` directly next to the
`.exe`, launch the executable with:

```powershell
.\music-library-ng-runner.exe "-Dquarkus.config.locations=file:./application.properties"
```

### Console Colors

Running the executable from a Windows shortcut or by opening it from File
Explorer should show colored console output normally. When launching from Total
Commander, enable Windows virtual terminal processing once for the current user:

```powershell
Set-ItemProperty -Path "HKCU:\Console" -Name "VirtualTerminalLevel" -Type DWord -Value 1
```

## Configuration

Shared defaults live in:

```text
src/main/resources/application.properties
```

The default port is:

```properties
quarkus.http.port=8795
```

Keep it unchanged for normal runs so all machines use the same shared app
settings.

The default SQLite path is shared and should stay the same across machines:

```properties
music-library.db.path=data/music-library-ng.sqlite
```

Use the same app configuration on every computer. Collections, parsers, DB path,
backups, logging, and UI behavior are shared. The only value expected to differ
between computers is the physical music root, supplied with:

```bash
-Dmusic-library.music-root="<music-root>"
```

### UI Defaults

Runtime UI preferences are stored in SQLite. First-run defaults live in
`application.properties`.

Status/progress defaults:

```properties
music-library.ui.default-status-complete-visible-ms=10000
music-library.ui.default-scan-poll-interval-ms=200
music-library.ui.default-collection-scan-spinner-enabled=true
music-library.ui.default-artist-scan-spinner-enabled=true
music-library.ui.default-collection-scan-progress-enabled=true
music-library.ui.default-status-history-date-format=yyyy-MM-dd HH:mm:ss.SSS
music-library.release.date.display.format=yyyy-MM-dd
music-library.ui.default-status-bar-location=top
```

Workspace action labels are shown when the pane reaches the configured width in
CSS pixels. Values can be changed at runtime in Settings; min/max/step remain
property-only constraints.

```properties
music-library.ui.default-action-label-thresholds.collections=400
music-library.ui.default-action-label-thresholds.artists=500
music-library.ui.default-action-label-thresholds.albums=400
music-library.ui.default-action-label-thresholds.titles=600
music-library.ui.action-label-threshold-min.collections=300
music-library.ui.action-label-threshold-min.artists=400
music-library.ui.action-label-threshold-min.albums=300
music-library.ui.action-label-threshold-min.titles=500
music-library.ui.action-label-threshold-max=2000
music-library.ui.action-label-threshold-step=50
```

Workspace column width defaults:

```properties
music-library.ui.table-grid-column-min-width=40
music-library.ui.default-workspace-column-widths.artist.name=280
music-library.ui.default-workspace-column-widths.album.name=360
music-library.ui.default-workspace-column-widths.album.release-date=145
music-library.ui.default-workspace-column-widths.album.checked=120
music-library.ui.default-workspace-column-widths.album.action=122
music-library.ui.default-workspace-column-widths.title.title=460
music-library.ui.default-workspace-column-widths.title.artist=220
music-library.ui.default-workspace-column-widths.title.release-date=145
music-library.ui.default-workspace-column-widths.title.status=120
music-library.ui.default-workspace-column-widths.title.action=178
```

`music-library.ui.table-grid-column-min-width` is the shared hard minimum for
table data columns. Action columns are the exception; their minimum is based on
the icon controls they must display.

Release date columns display the year in the table. Fuller values, such as
`2006-03-13`, are shown in a tooltip using
`music-library.release.date.display.format`.

The app accepts Windows-style paths such as `<drive>:/<path-to-music-root>` and
resolves them to WSL mounts when running under WSL.

### Music Root Detection

The database stores only paths relative to the runtime music root. The root
itself is machine-specific.

Built-in root candidates are intentionally limited to:

```properties
music-library.root-detection.candidates[0]=<primary-music-root>
music-library.root-detection.candidates[1]=<secondary-music-root>
```

A candidate is valid only when all marker playlists exist directly under it:

```properties
music-library.root-detection.markers[0]=EPIC.m3u8
music-library.root-detection.markers[1]=MELODEATH.m3u8
music-library.root-detection.markers[2]=POWER METAL.m3u8
```

If neither expected location is valid, startup fails fast with an error that
asks for the JVM property:

```bash
java -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

## Logging

Application logs go to both places by default:

```text
console where you run java -jar
data/logs/music-library-ng.log
```

Logging is configured in:

```text
src/main/resources/application.properties
```

The important properties are:

```properties
quarkus.log.min-level=TRACE
quarkus.log.level=INFO
quarkus.log.category."org.kroky.musiclib".level=INFO
quarkus.log.console.enabled=true
quarkus.log.console.level=INFO
quarkus.log.file.enabled=true
quarkus.log.file.level=INFO
quarkus.log.file.path=data/logs/music-library-ng.log
```

The code uses Quarkus' built-in JBoss Logging API. Console and file handlers are
configured through Quarkus properties.

Run with more detailed app logs:

```bash
java '-Dquarkus.log.category."org.kroky.musiclib".level=DEBUG' \
  -Dquarkus.log.console.level=DEBUG \
  -Dquarkus.log.file.level=DEBUG \
  -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

Run with very noisy parser/database flow logs:

```bash
java '-Dquarkus.log.category."org.kroky.musiclib".level=TRACE' \
  -Dquarkus.log.console.level=TRACE \
  -Dquarkus.log.file.level=TRACE \
  -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

## Collection Types And Parsers

New collections start as artist-centric. Change the collection type with the
pencil action in the Collections pane.

Artist-centric collections scan direct child folders and create artists only.
The current flat parser expects:

```text
artist - release date - album
```

Example:

```text
Dark Tranquillity - 2007 - Fiction
```

Only the artist part is inserted during the collection scan. Album data is
handled later by artist/provider/album workflows.

Artist-centric album maintenance is explicit:

- `Scan local albums` parses local album folders in the selected collection and
  upserts albums plus local paths.
- Row-level `Scan local albums` is scoped to one artist.
- Title-bar `Scan local albums` is scoped to the selected collection.
- `Scan providers` checks online provider links and adds remote albums as
  unchecked.

Clicking an artist only selects it and loads known albums. It does not scan or
modify data.

Title-centric collections store one row per direct child folder and use these
parser rules in order:

```text
title (artist, release date)
title (release date)
title - release date - subtitle
title - release date
title
```

Examples:

```text
The Fountain (Clint Mansell, 2006)
Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)
Conan the Barbarian (2011)
World of Warcraft - 2007 - The Burning Crusade
Clash of the Titans
```

Parsed title metadata can be edited manually. Manual edits are marked as manual
metadata and later scans update the seen/path state without overwriting the
edited title, artist, release date, or sort key.

When a title-centric folder has a parsed or manually entered artist, the app
also creates or updates the corresponding artist, album, and local album path.
Ambiguous title-only folders stay as title rows until artist metadata is added.
Comma-separated artists in title metadata are split into separate artists and
linked to the same album, so `Ad Astra (Max Richter, Lorne Balfe, 2019)` does
not create one combined artist.

The configured collection itself acts as the folder/genre bucket.

## SQLite Usage

This is safe for the intended workflow:

1. Run the app on one PC.
2. Close the app.
3. Let file syncing finish if the app directory or database is synced.
4. Open the app on another PC.

Do not run the app on two machines against the same SQLite database at the same
time.

## Useful API Checks

```bash
curl http://localhost:8795/api/health
curl http://localhost:8795/api/collections
curl http://localhost:8795/api/artists
curl 'http://localhost:8795/api/artists?collectionId=melodeath'
curl http://localhost:8795/api/albums
curl 'http://localhost:8795/api/albums?collectionId=melodeath&artistId=1'
```

Start local album scans:

```bash
curl -X POST 'http://localhost:8795/api/scan/jobs/local-albums?collectionId=melodeath'
curl -X POST 'http://localhost:8795/api/scan/jobs/local-albums?collectionId=melodeath&artistId=1'
```

Check provider links for the selected collection scope:

```bash
curl -X POST http://localhost:8795/api/provider-checks/collection/melodeath
```

Remove a title-centric local path while keeping the linked library album:

```bash
curl -X DELETE http://localhost:8795/api/collections/soundtracks/titles/1
```

Create an artist and assign it to a collection:

```bash
curl -X POST http://localhost:8795/api/artists \
  -H 'Content-Type: application/json' \
  -d '{"name":"Example Artist","collectionIds":["melodeath"]}'
```

Create a checked album:

```bash
curl -X POST http://localhost:8795/api/albums \
  -H 'Content-Type: application/json' \
  -d '{"artistId":1,"title":"Example Album","releaseDate":"2026","checked":true}'
```

Start a scan job for all collections:

```bash
curl -X POST http://localhost:8795/api/scan/jobs
```

Start a scan job for one collection:

```bash
curl -X POST 'http://localhost:8795/api/scan/jobs?collectionId=power-metal'
```

Poll or cancel the current scan job:

```bash
curl http://localhost:8795/api/scan/jobs/current
curl -X POST http://localhost:8795/api/scan/jobs/current/cancel
```
