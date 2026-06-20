# Music Library NG

Music Library NG is a local-first music collection app with a Quarkus backend, SQLite storage, and a Vue 3 + Vuetify frontend.

The app is designed for a single user who runs it on one PC at a time while the music root may differ between computers.

## Features

- Runs on Quarkus with Java 21.
- Uses Gradle only; Maven is not used.
- Serves a Vue 3 + Vuetify frontend from the Quarkus app.
- Stores data in SQLite with Flyway migrations.
- Default HTTP port is `8795`.
- Supports configurable scan collections added from direct music-root folders.
- Supports artist-centric collections, for example Power Metal, Melodeath, and Rock.
- Supports title-centric collections, for example Soundtracks.
- Uses a type-aware Collections workspace:
  - artist collections: collections, artists, albums
  - title collections: collections, titles
- Scans artist collections lazily by artist folders first; albums are loaded only when an artist workflow needs them.
- Parses title collections with a soundtrack-oriented folder-name pipeline.
- Tracks one album listening flag: `checked=true` means listened.
- Tracks disk presence separately from the listened flag.
- Allows artists to be assigned to collections even before any local album folder exists.
- Refreshes artist discographies from selected provider identities and adds new albums as unchecked.
- Supports one provider identity per artist, with MusicBrainz MBIDs and URL identities for Spirit of Metal and Metal Archives.
- Searches MusicBrainz artist candidates, stores the accepted artist MBID, stores provider metadata such as country/type/status, imports release groups as albums, and sends ambiguous matches to review.

## Tooling

Install these in WSL:

```bash
sudo apt update
sudo apt install -y curl git unzip sqlite3
```

Java 21 is required.
Verify:

```bash
java -version
```

Node.js is required for the Vue/Vuetify frontend.
`nvm` is recommended:

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

You do not need to install Maven or system Gradle.
The repo includes the Gradle wrapper.

### Git SSH From WSL With 1Password

If Git runs in WSL but SSH keys are managed by 1Password for Windows, configure Git in WSL to use the Windows SSH client:

```bash
git config --global core.sshCommand ssh.exe
```

This lets WSL Git delegate SSH authentication to the Windows environment where 1Password can provide the key.

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

Quarkus can build a native Windows `.exe` with GraalVM.
Build it from Windows, not WSL, because the native image build needs the Windows C++ toolchain.

Build frontend assets in WSL with the normal Gradle build first; that is the recommended path for npm/Vite work.
Then use the Windows native build described below to compile the native executable.

### Prerequisites

Install Visual Studio Build Tools with:

- Desktop development with C++
- MSVC Build Tools v143 or newer
- Windows 11 SDK

Install GraalVM for JDK 21, Windows x64 archive, and choose a local install directory.
The examples below keep that path in one environment variable:

```powershell
$env:GRAALVM_HOME = "<graalvm-jdk-21>"
```

### Developer Shell

The native build helper tries to load the Visual Studio amd64 developer environment automatically through `vswhere`.
If that does not work, open a 64-bit Visual Studio developer PowerShell before running the native build.
Either use the Visual Studio installer's developer shell shortcut, or create a shortcut that imports the Visual Studio developer shell module and selects the 64-bit toolchain:

```text
powershell.exe -noe -c "&{Import-Module '<vs-build-tools-root>\Common7\Tools\Microsoft.VisualStudio.DevShell.dll'; Enter-VsDevShell <instance-id> -DevCmdArguments '-arch=amd64 -host_arch=amd64'}"
```

Keep the shortcut's "Start in" value pointed at the Visual Studio Build Tools install directory:

```text
<vs-build-tools-root>
```

### Build

From Windows, run the root build helper:

```powershell
cd <project-root>
.\build-native-exe.bat
```

`build-native-exe.bat` is a Total Commander-friendly wrapper for `scripts/build-native-exe.ps1`.
The BAT sets the local GraalVM path before calling PowerShell, and skips frontend tasks by default because frontend assets are expected to be built in WSL.
The PowerShell script loads the Visual Studio amd64 build environment when it can find Visual Studio Build Tools, sets `JAVA_HOME` from `GRAALVM_HOME`, runs the Quarkus native build, and checks the expected executable name from `application.properties`.

If you run the PowerShell helper directly, pass `-SkipFrontend` when `frontend/dist` is already current:

```powershell
.\scripts\build-native-exe.ps1 -SkipFrontend
```

Do not build the native executable until `frontend/dist` contains the frontend changes that need to be included.

The expected native executable is:

```text
build\music-library-ng.exe
```

### External Config

For a native app distribution, prefer this layout:

```text
<app-directory>
  music-library-ng.exe
  config/application.properties
  data/
```

Quarkus automatically reads `config/application.properties` from the process working directory.
If you want `application.properties` directly next to the `.exe`, launch the executable with:

```powershell
.\music-library-ng.exe "-Dquarkus.config.locations=file:./application.properties"
```

### Console Colors

Running the executable from a Windows shortcut or by opening it from File Explorer should show colored console output normally.
When launching from Total Commander, enable Windows virtual terminal processing once for the current user:

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

Keep it unchanged for normal runs so all machines use the same shared app settings.

The default SQLite path is shared and should stay the same across machines:

```properties
music-library.db.path=data/music-library-ng.sqlite
```

Use the same app configuration on every computer.
Collections, parsers, DB path, backups, logging, and UI behavior are shared.
The only value expected to differ between computers is the physical music root, supplied with:

```bash
-Dmusic-library.music-root="<music-root>"
```

### UI Defaults

Runtime UI preferences are stored in SQLite.
First-run defaults live in `application.properties`.

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

Workspace action labels are shown when the pane reaches the configured width in CSS pixels.
Values can be changed at runtime in Settings; min/max/step remain property-only constraints.

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
music-library.ui.default-workspace-column-widths.album.collections=180
music-library.ui.default-workspace-column-widths.album.action=122
music-library.ui.default-workspace-column-widths.title.title=460
music-library.ui.default-workspace-column-widths.title.artist=220
music-library.ui.default-workspace-column-widths.title.release-date=145
music-library.ui.default-workspace-column-widths.title.action=178
music-library.ui.default-artists-screen-column-widths.name=250
music-library.ui.default-artists-screen-column-widths.country=76
music-library.ui.default-artists-screen-column-widths.type=80
music-library.ui.default-artists-screen-column-widths.status=84
music-library.ui.default-artists-screen-column-widths.albums=68
music-library.ui.default-artists-screen-column-widths.unchecked=86
music-library.ui.default-artists-screen-column-widths.local=64
music-library.ui.default-artists-screen-column-widths.provider=110
music-library.ui.default-artists-screen-column-widths.action=104
```

`music-library.ui.table-grid-column-min-width` is the shared hard minimum for table data columns.
Action columns are the exception; their minimum is based on the icon controls they must display.
Artists screen column widths use `music-library.ui.default-artists-screen-column-widths.*` for first-run defaults and `artists-screen.artists-pane.*` DB preferences after a user resizes columns.

Release date columns display the year in the table.
Fuller values, such as `2006-03-13`, are shown in a tooltip using `music-library.release.date.display.format`.

The app accepts Windows-style paths such as `<drive>:/<path-to-music-root>` and resolves them to WSL mounts when running under WSL.

### MusicBrainz Provider Defaults

MusicBrainz provider settings are first-run application defaults, not runtime UI preferences.
The configured User-Agent is sent exactly on every MusicBrainz API request.
All MusicBrainz requests in one app process are serialized and rate-limited through the same limiter.

```properties
music-library.providers.musicbrainz.base-url=https://musicbrainz.org/ws/2
music-library.providers.musicbrainz.site-url=https://musicbrainz.org
music-library.providers.musicbrainz.user-agent=music-library-ng (peter.krokavec@gmail.com)
music-library.providers.musicbrainz.request-min-interval-ms=1100
music-library.providers.musicbrainz.search-candidate-limit=5
music-library.providers.musicbrainz.release-group-page-size=100
```

### Music Root Detection

The database stores only paths relative to the runtime music root.
The root itself is machine-specific.

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

If neither expected location is valid, startup fails fast with an error that asks for the JVM property:

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

The code uses Quarkus' built-in JBoss Logging API.
Console and file handlers are configured through Quarkus properties.

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

New collections start as artist-centric.
Change the collection type with the pencil action in the Collections pane.

Artist-centric collections scan direct child folders and create artists only.
The flat album folder parser expects:

```text
artist - release date - album
```

Example:

```text
Dark Tranquillity - 2007 - Fiction
```

When a direct child folder does not match that flat shape, scans treat it as an artist folder if it has parseable nested album folders or no regular files directly inside.
The nested album folder parser expects:

```text
artist/release date - album
```

Examples:

```text
GLOBUS/2006 - Epicon
ANTTI MARTIKAINEN/2014 - Eternal Saga
```

Mixed flat and nested artist collections are supported during migration.
Empty nested artist folders are accepted by collection scans so artists can exist before local album folders are added.

Only the artist part is inserted during the collection scan.
Album data is handled later by artist/provider/album workflows.

Artist-centric album maintenance is explicit:

- `Scan local albums` parses local album folders in the selected collection and upserts albums plus local paths.
- Row-level `Scan local albums` is scoped to one artist.
- Title-bar `Scan local albums` is scoped to the selected collection.
- `Scan providers` checks online provider identities and adds remote albums as unchecked.

Scanner jobs observe disk state and update DB paths, but they do not infer manual folder renames as identity-preserving moves.
Identity-preserving folder rename and collection reorganization should be explicit preview/apply workflows that update the filesystem and the stored local path in one operation.

The Artists screen owns provider assignment.
Select an artist row to view artist info, provider metadata, and known albums.
Use the row-level provider buttons to choose MusicBrainz, Spirit of Metal, or Metal Archives.
MusicBrainz searches artist candidates and stores the accepted MBID.
Spirit of Metal and Metal Archives prompt for the concrete artist URL, which becomes the provider-specific artist identity.
When provider scans or refreshes run, exact safe matches are linked to existing albums, clearly new release groups are created as unchecked albums, and ambiguous release groups are returned for review.
Provider-created MusicBrainz albums are artist-level albums and are not assigned to a collection by default.

Clicking an artist only selects it and loads known albums.
It does not scan or modify data.

Title-centric collections store one row per direct child folder and use these parser rules in order:

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

Parsed title metadata can be edited manually.
Manual edits are marked as manual metadata and later scans update the seen/path state without overwriting the edited title, artist, release date, or sort key.

For title-centric collection scans, the collection-relative folder path is the release identity.
A new folder creates a separate album row even when parsed title or release metadata matches another folder.
A rescan of the same folder updates the same row through its local path.

When a title-centric folder has a parsed or manually entered artist, the app also creates or updates the corresponding artist, album, and local album path.
Ambiguous title-only folders stay as title rows until artist metadata is added.
Comma-separated artists in title metadata are split into separate artists and linked to the same album, so `Ad Astra (Max Richter, Lorne Balfe, 2019)` does not create one combined artist.

The configured collection itself acts as the folder/genre bucket.

## SQLite Usage

This is safe for the intended workflow:

1. Run the app on one PC.
2. Close the app.
3. Let file syncing finish if the app directory or database is synced.
4. Open the app on another PC.

Do not run the app on two machines against the same SQLite database at the same time.

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

Check provider identities for the selected collection scope:

```bash
curl -X POST http://localhost:8795/api/provider-checks/collection/melodeath
```

Search MusicBrainz candidates for one artist:

```bash
curl http://localhost:8795/api/artists/1/provider-candidates/musicbrainz
```

Accept a MusicBrainz artist identity:

```bash
curl -X PUT http://localhost:8795/api/artists/1/provider \
  -H 'Content-Type: application/json' \
  -d '{"providerId":"musicbrainz","providerArtistId":"36b891ab-5e89-4f17-bb91-f189764de5ff","providerArtistName":"Antti Martikainen","enabled":true}'
```

Refresh one artist from MusicBrainz:

```bash
curl -X POST http://localhost:8795/api/artists/1/provider/refresh
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
