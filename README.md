# Music Library NG

Music Library NG is a local-first music collection app. It replaces the old
Swing/Derby app with a Quarkus backend, SQLite storage, and a Vue 3 + Vuetify
frontend.

The app is designed for a single user who runs it on one PC at a time while the
SQLite database lives in a Google Drive synced folder.

## Current Features

- Runs on Quarkus with Java 21.
- Uses Gradle only; Maven is not used.
- Serves a Vue 3 + Vuetify frontend from the Quarkus app.
- Stores data in SQLite with Flyway migrations.
- Default HTTP port is `8795`.
- Supports configurable scan collections.
- Supports collections based on disk folders, for example Power Metal,
  Melodeath, Rock, Soundtracks, and Musicals.
- Scans standard folders named `artist - year - album`.
- Scans soundtrack/musical folders named `title (artist, year)`.
- Allows manual artist and checked album entry even if nothing exists on disk.
- Allows manually added albums to be assigned to a collection.

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
cd /d/dev/projects/music-library-ng
java -version
./gradlew --version
node -v
npm -v
sqlite3 --version
```

You do not need to install Maven or system Gradle. The repo includes the Gradle
wrapper.

## Running In Dev Mode

Backend dev mode:

```bash
./gradlew quarkusDev
```

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
java -jar build/quarkus-app/quarkus-run.jar
```

Build and run in one command:

```bash
./gradlew build && java -jar build/quarkus-app/quarkus-run.jar
```

Build and run with the default port stated explicitly:

```bash
./gradlew build && java -Dquarkus.http.port=8795 -jar build/quarkus-app/quarkus-run.jar
```

## Configuration

Defaults live in:

```text
src/main/resources/application.properties
```

The default port is:

```properties
quarkus.http.port=${MUSIC_LIBRARY_PORT:8795}
```

Override with an environment variable:

```bash
MUSIC_LIBRARY_PORT=8888 java -jar build/quarkus-app/quarkus-run.jar
```

Or with a JVM property:

```bash
java -Dquarkus.http.port=8888 -jar build/quarkus-app/quarkus-run.jar
```

The default SQLite path is:

```properties
music-library.db.path=data/music-library.sqlite
```

For Google Drive, create a personal config file outside the repo, for example:

```text
e:/Google Drive/Apps/MusicLibrary/music-library.properties
```

Example:

```properties
music-library.db.path=e:/Google Drive/Apps/MusicLibrary/music-library.sqlite
music-library.backup.directory=e:/Google Drive/Apps/MusicLibrary/backups
music-library.music-root=G:/My Drive/Music/_vyber

music-library.collections[0].id=power-metal
music-library.collections[0].name=Power Metal
music-library.collections[0].relative-path=POWER METAL
music-library.collections[0].parser=ARTIST_YEAR_ALBUM
music-library.collections[0].enabled=true
```

Run with the external config:

```bash
java -Dquarkus.config.locations=file:/e/Google\ Drive/Apps/MusicLibrary/music-library.properties \
  -jar build/quarkus-app/quarkus-run.jar
```

The app accepts Windows-style paths such as `e:/Google Drive/...` and resolves
them to WSL mounts like `/e/...` or `/mnt/e/...` when running under WSL.

### Music Root Detection

The shared SQLite database stores only paths relative to the runtime music root.
The root itself is machine-specific.

Built-in root candidates are intentionally limited to:

```properties
music-library.root-detection.candidates[0]=/e/Google Drive/Music/_vyber
music-library.root-detection.candidates[1]=E:/Google Drive/Music/_vyber
music-library.root-detection.candidates[2]=/g/My Drive/Music/_vyber
music-library.root-detection.candidates[3]=G:/My Drive/Music/_vyber
```

A candidate is valid only when all marker playlists exist directly under it:

```properties
music-library.root-detection.markers[0]=EPIC.m3u8
music-library.root-detection.markers[1]=MELODEATH.m3u8
music-library.root-detection.markers[2]=POWER METAL.m3u8
```

If a machine needs a different path, add it manually in the machine-specific
config. Collection and album paths in the DB should remain relative.

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
  -jar build/quarkus-app/quarkus-run.jar
```

Run with very noisy parser/database flow logs:

```bash
java '-Dquarkus.log.category."org.kroky.musiclib".level=TRACE' \
  -Dquarkus.log.console.level=TRACE \
  -Dquarkus.log.file.level=TRACE \
  -jar build/quarkus-app/quarkus-run.jar
```

## Scan Collection Parsers

Standard music folders:

```text
artist - year - album
```

Example:

```text
Dark Tranquillity - 2007 - Fiction
```

Soundtracks and musicals:

```text
title (artist, year)
```

Example:

```text
The Fountain (Clint Mansell, 2006)
```

The configured collection itself acts as the folder/genre bucket. For example,
an album found under the Power Metal collection is stored with
`collectionId=power-metal`.

## SQLite On Google Drive

This is safe for the intended workflow:

1. Run the app on one PC.
2. Close the app.
3. Let Google Drive finish syncing.
4. Open the app on another PC.

Do not run the app on two machines against the same synced DB at the same time.
Future work in `PLAN.md` includes a lock file and automatic backups.

## Useful API Checks

```bash
curl http://localhost:8795/api/health
curl http://localhost:8795/api/collections
curl http://localhost:8795/api/artists
curl http://localhost:8795/api/albums
```

Create an artist:

```bash
curl -X POST http://localhost:8795/api/artists \
  -H 'Content-Type: application/json' \
  -d '{"name":"Example Artist"}'
```

Create a checked album:

```bash
curl -X POST http://localhost:8795/api/albums \
  -H 'Content-Type: application/json' \
  -d '{"artistId":1,"title":"Example Album","releaseYear":2026,"checked":true}'
```

Scan all enabled collections:

```bash
curl -X POST http://localhost:8795/api/scan
```

Scan one collection:

```bash
curl -X POST 'http://localhost:8795/api/scan?collectionId=power-metal'
```

## Quarkus Project Generation

Quarkus has a generator similar in spirit to Spring Initializr.

With the Quarkus CLI:

```bash
quarkus create app org.kroky:music-library-ng \
  --gradle \
  --java=21 \
  --extension=rest-jackson,jdbc-sqlite,flyway
```

This repo was generated from the Quarkus Gradle skeleton and then customized.

## Development Checklist

See [PLAN.md](PLAN.md). It is the working checklist for features, verification,
and future work.
