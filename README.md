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
- Visual and interaction rules are documented in `docs/ui-guide.md`.
- Frontend workflow and verification rules are documented in `docs/ui-workflow-guide.md`.
- Unimplemented ideas are tracked in `docs/ideas.md`.

## Setup

Regular development is supported from either WSL or native Windows PowerShell.
Java 21 and Node.js are required, and the repository includes the Gradle wrapper so Maven and a system Gradle install are unnecessary.

### WSL

Install the basic tools:

```bash
sudo apt update
sudo apt install -y curl git unzip sqlite3
```

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

### Windows PowerShell

Install Git for Windows, JDK 21, and Node.js.
Set `JAVA_HOME` to the JDK 21 directory and add its `bin` directory to the current terminal when `java` is not already on `Path`:

```powershell
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Verify the native toolchain:

```powershell
Set-Location <project-root>
java -version
.\gradlew.bat --version
node --version
npm.cmd --version
```

The Gradle frontend tasks keep Windows dependencies under `frontend/node_modules_win` and WSL dependencies under `frontend/node_modules_wsl` when inactive.
They swap the current platform directory into the standard `frontend/node_modules` name before npm or Vite runs, so switching platforms reuses each platform's existing installation.
For an installation created before platform markers were introduced, rename `frontend/node_modules` once to `frontend/node_modules_win` or `frontend/node_modules_wsl` according to where it was installed, then run `frontendInstall`.
Do not run Windows and WSL frontend builds or Vite processes at the same time because they share the active `node_modules` path.

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

Native Windows PowerShell:

```powershell
.\gradlew.bat quarkusDev "-Dmusic-library.music-root=<music-root>"
```

Open:

```text
http://localhost:8795/
```

Frontend-only dev mode requires Quarkus to be running because Vite proxies `/api` to the backend:

```bash
./gradlew frontendInstall
cd frontend
npm run dev
```

Use `.\gradlew.bat frontendInstall` before `npm.cmd run dev --prefix frontend` in native Windows PowerShell.

Open:

```text
http://localhost:5173/
```

## Build And Package

Build everything:

```bash
./gradlew build
```

Native Windows PowerShell:

```powershell
.\gradlew.bat build
```

The build runs npm install, the Vue/Vuetify production build, Java compilation, tests, and Quarkus packaging.

Run the packaged app:

```bash
java -Dmusic-library.music-root="<music-root>" \
  -jar build/quarkus-app/quarkus-run.jar
```

Native Windows PowerShell:

```powershell
& "$env:JAVA_HOME\bin\java.exe" "-Dmusic-library.music-root=<music-root>" -jar build\quarkus-app\quarkus-run.jar
```

Optional helper commands live in `dev-shell.sh` for WSL and `dev-shell.ps1` for native Windows PowerShell.
`freshrun` stops app processes, deletes `data/`, rebuilds, and runs the packaged jar.
The Windows helper checks the lock owner's PID and process start time, stops that Windows process even when the app uses a different HTTP port, and removes its lock after confirming the process exited.
It aborts when stopping fails or the lock belongs to WSL, an unknown owner, or a reused PID, and `freshrun` refuses to delete `data/` while any lock directory remains inside it.
Stop WSL apps from WSL and remove a leftover WSL lock manually only after confirming that the app has exited.

```bash
source dev-shell.sh
brun
freshrun
```

Native Windows PowerShell:

```powershell
. .\dev-shell.ps1
brun
freshrun
```

## Windows Native Executable

Build the native Windows `.exe` from Windows, not WSL.
Install Visual Studio Build Tools with Desktop development with C++, MSVC Build Tools v143 or newer, and the Windows 11 SDK.
Install GraalVM for JDK 21, Windows x64 archive, and point `GRAALVM_HOME` at it.

Build frontend assets with the normal Gradle build on Windows or WSL first when frontend changes need to be included.
Run the batch launcher from Windows PowerShell, Command Prompt, Explorer, or Total Commander:

```bat
cd <project-root>
.\build-native-exe.bat
```

The batch launcher runs `scripts/build-native-exe.ps1`, loads the Visual Studio amd64 build environment when it can, and skips frontend tasks by default.
Edit its `GRAALVM_HOME` value when the local GraalVM installation moves.

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
Runtime UI preferences are stored in SQLite after they are changed in the interface.
First-run UI defaults still come from `application.properties`.
Pane-width defaults use `music-library.ui.default-pane-widths.collections`, `music-library.ui.default-pane-widths.collection-artists`, and `music-library.ui.default-pane-widths.artists-screen`; divider changes persist scalar CSS-pixel overrides in the database.
The default batch provider rescan delay comes from `music-library.providers.default-batch-rescan-delay-minutes` and can be changed later in Settings.
Logs go to the console and `data/logs/music-library-ng.log`.

## Collection Model

Music Library NG organizes your music into artist-centric collections, which browse collections, artists, and albums, and title-centric collections such as soundtracks, which browse collections and titles.
It scans your folders to discover albums, matches them against online providers such as MusicBrainz, Spirit of Metal, and Metal Archives to fill in and verify metadata, and lets you resolve title and release-year conflicts.
Bulk provider matching runs in the background with per-artist progress and can be hidden, reopened from the running provider chip, or cancelled after the current artist finishes.
Scans never delete your files, and never rename a folder or write audio tags except when you explicitly choose a provider title or year.

Name album folders using one of these shapes so scans can parse them.

Artist-centric album folder shapes:

```text
artist - year - album
artist/year - album
```

Title-centric folder shapes:

```text
title (artist, year)
title (year)
title - year - subtitle
title - year
title
```

See `docs/current-application.md` for the full collection, scanning, and provider behavior.

## SQLite Usage

The default SQLite connection uses WAL mode, `synchronous=NORMAL`, foreign keys, and a 30 second busy timeout so UI reads can continue during background scan writes.
Startup atomically creates a `<database-path>.lock` directory, and a second Windows or WSL app process using the same database fails fast.
Normal shutdown removes the directory.
After a hard crash, remove a stale lock directory manually only after confirming that no app process is running.

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
