package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ProviderConflictResolutionServiceTest {

    private static final Path COLLECTION_ROOT = Path.of("src/test/resources/music-root/TEST-CONFLICT");

    @Inject AlbumProviderConflictService service;
    @Inject DataSource dataSource;

    @BeforeEach
    void seed() throws Exception {
        deleteTree(COLLECTION_ROOT);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM album_provider_links");
            statement.executeUpdate("DELETE FROM artist_provider_links");
            statement.executeUpdate("DELETE FROM albums");
            statement.executeUpdate("DELETE FROM artists");
            statement.executeUpdate("DELETE FROM collections");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('metal','Metal','TEST-CONFLICT','ARTIST')");
            statement.executeUpdate("INSERT INTO artists(id,name,normalized_name) VALUES(1,'Athena','athena')");
            statement.executeUpdate("INSERT INTO albums(id,collection_id,title,normalized_title,release_year,checked) VALUES(1,'metal','Local Title','local title',2000,1)");
            statement.executeUpdate("INSERT INTO album_artists(album_id,artist_id,position) VALUES(1,1,0)");
            statement.executeUpdate("INSERT INTO album_provider_links(id,album_id,provider_id,provider_release_group_id,provider_title,provider_release_year) VALUES(1,1,'musicbrainz','rg-1','Provider Title',2001)");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        deleteTree(COLLECTION_ROOT);
    }

    @Test
    void titleAndYearChoicesResolveEveryVariant() throws Exception {
        execute("INSERT INTO album_provider_links(id,album_id,provider_id,provider_release_group_id,provider_title,provider_release_year) VALUES(2,1,'metal_archives','rg-2','Provider Title',2001)");
        execute("INSERT INTO album_provider_links(id,album_id,provider_id,provider_release_group_id,provider_title,provider_release_year) VALUES(3,1,'spirit_of_metal','rg-3','Other Title',2002)");

        var titleResult = resolve("TITLE", "USE_PROVIDER", 1L);

        assertEquals("Provider Title", titleResult.album().title());
        assertEquals(2000, titleResult.album().releaseYear());
        assertEquals("USE_PROVIDER", scalarText("SELECT title_resolution FROM album_provider_links WHERE id=1"));
        assertEquals("USE_PROVIDER", scalarText("SELECT title_resolution FROM album_provider_links WHERE id=2"));
        assertEquals("USE_OTHER_PROVIDER", scalarText("SELECT title_resolution FROM album_provider_links WHERE id=3"));
        assertNull(scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=1"));

        var yearResult = resolve("YEAR", "USE_PROVIDER", 1L);

        assertEquals("Provider Title", yearResult.album().title());
        assertEquals(2001, yearResult.album().releaseYear());
        assertEquals("USE_PROVIDER", scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=1"));
        assertEquals("USE_PROVIDER", scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=2"));
        assertEquals("USE_OTHER_PROVIDER", scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=3"));
        assertTrue(yearResult.messages().isEmpty());
    }

    @Test
    void choosingCurrentProviderOnlyTitleResolvesEveryTitleVariant() throws Exception {
        execute("UPDATE albums SET title='Hymn of the High Seas, Vol. 2',normalized_title='hymn of the high seas vol 2' WHERE id=1");
        execute("UPDATE album_provider_links SET provider_title='Hymn of the High Seas, Vol. 2' WHERE id=1");
        execute("INSERT INTO album_provider_links(id,album_id,provider_id,provider_release_group_id,provider_title,provider_release_year) VALUES(2,1,'metal_archives','rg-2','Hymn of the High Seas (Volume 2)',2001)");

        var result = resolve("TITLE", "USE_PROVIDER", 1L);

        assertEquals("Hymn of the High Seas, Vol. 2", result.album().title());
        assertEquals("USE_PROVIDER", scalarText("SELECT title_resolution FROM album_provider_links WHERE id=1"));
        assertEquals("USE_OTHER_PROVIDER", scalarText("SELECT title_resolution FROM album_provider_links WHERE id=2"));
    }

    @Test
    void keepLocalWritesOnlyTheDecisionWithoutFolderOrTagPreflight() throws Exception {
        Path folder = COLLECTION_ROOT.resolve("not-a-valid-layout");
        Files.createDirectories(folder);
        Path audio = folder.resolve("track.mp3");
        Files.writeString(audio, "not audio");
        execute("UPDATE albums SET local_relative_path='not-a-valid-layout' WHERE id=1");

        var result = resolve("YEAR", "KEEP_LOCAL", null);

        assertEquals(2000, result.album().releaseYear());
        assertEquals("KEEP_LOCAL", scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=1"));
        assertTrue(result.messages().isEmpty());
        assertTrue(Files.isDirectory(folder));
        assertEquals("not audio", Files.readString(audio));

        var reset = resolve("YEAR", "RESET_KEEP_LOCAL", 1L);
        assertNull(scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=1"));
        assertTrue(reset.messages().isEmpty());
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void failedDatabaseWriteRestoresFolderAndRollsBackDecision() throws Exception {
        Path source = COLLECTION_ROOT.resolve("Athena - 2000 - Local Title");
        Path target = COLLECTION_ROOT.resolve("Athena - 2001 - Local Title");
        Files.createDirectories(source);
        execute("UPDATE albums SET local_relative_path='Athena - 2000 - Local Title' WHERE id=1");
        execute("INSERT INTO albums(id,collection_id,title,normalized_title,local_relative_path,release_year,checked) VALUES(2,'metal','Collision','collision','Athena - 2001 - Local Title',1999,1)");
        execute("INSERT INTO album_artists(album_id,artist_id,position) VALUES(2,1,0)");

        var result = resolve("YEAR", "USE_PROVIDER", 1L);

        assertEquals("Provider conflict was not resolved.", result.messages().get(0).summary());
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(target));
        assertEquals(2000, scalarLong("SELECT release_year FROM albums WHERE id=1"));
        assertEquals("Athena - 2000 - Local Title", scalarText("SELECT local_relative_path FROM albums WHERE id=1"));
        assertNull(scalarText("SELECT release_year_resolution FROM album_provider_links WHERE id=1"));
    }

    @Test
    void renamedFolderReturnsOldAndNewRelativePaths() throws Exception {
        Path source = COLLECTION_ROOT.resolve("Athena - 2000 - Local Title");
        Files.createDirectories(source);
        execute("UPDATE albums SET local_relative_path='Athena - 2000 - Local Title' WHERE id=1");
        execute("UPDATE album_provider_links SET provider_title='Provider — “Title”' WHERE id=1");

        var result = resolve("TITLE", "USE_PROVIDER", 1L);

        assertEquals("Provider — “Title”", result.album().title());
        assertEquals("Athena - 2000 - Provider - 'Title'", result.album().localRelativePath());
        assertTrue(Files.isDirectory(COLLECTION_ROOT.resolve("Athena - 2000 - Provider - 'Title'")));
        assertEquals(1, result.messages().size());
        assertEquals("INFO", result.messages().get(0).severity());
        assertEquals("FOLDER_RENAMED", result.messages().get(0).code());
        assertEquals("Athena - 2000 - Local Title", result.messages().get(0).details().get(0));
        assertEquals("Athena - 2000 - Provider - 'Title'", result.messages().get(0).details().get(1));
    }

    @Test
    void providerNormalizationMatchingCurrentFolderProducesQuietSuccess() throws Exception {
        Files.createDirectories(COLLECTION_ROOT.resolve("Athena - 2000 - Album"));
        execute("UPDATE albums SET local_relative_path='Athena - 2000 - Album' WHERE id=1");
        execute("UPDATE album_provider_links SET provider_title='Album?' WHERE id=1");

        var result = resolve("TITLE", "USE_PROVIDER", 1L);

        assertEquals("Album?", result.album().title());
        assertEquals("Athena - 2000 - Album", result.album().localRelativePath());
        assertTrue(result.messages().isEmpty());
    }

    @Test
    void tagFailuresAreGroupedByExactFieldAndCount() throws Exception {
        Path source = COLLECTION_ROOT.resolve("Athena - 2000 - Local Title");
        Files.createDirectories(source);
        Files.writeString(source.resolve("one.mp3"), "not audio");
        Files.writeString(source.resolve("two.flac"), "not audio");
        execute("UPDATE albums SET local_relative_path='Athena - 2000 - Local Title' WHERE id=1");

        var result = resolve("YEAR", "USE_PROVIDER", 1L);

        var warning = result.messages().stream().filter(message -> "WARNING".equals(message.severity())).findFirst().orElseThrow();
        assertEquals("2 YEAR tags failed", warning.summary());
        assertEquals(2, warning.details().size());
    }

    @Test
    void folderPreflightUsesFolderSpecificErrorSummary() throws Exception {
        Path folder = COLLECTION_ROOT.resolve("invalid-layout");
        Files.createDirectories(folder);
        execute("UPDATE albums SET local_relative_path='invalid-layout' WHERE id=1");

        var result = resolve("YEAR", "USE_PROVIDER", 1L);

        assertEquals("FOLDER_RENAME_FAILED", result.messages().get(0).code());
        assertEquals("Folder rename failed", result.messages().get(0).summary());
        assertEquals(2000, scalarLong("SELECT release_year FROM albums WHERE id=1"));
    }

    @Test
    void yearTagsKeepAnyValueBeginningWithCorrectYear() {
        assertFalse(AlbumProviderConflictService.yearTagNeedsUpdate("2020-05-01", 2020));
        assertFalse(AlbumProviderConflictService.yearTagNeedsUpdate(" 2020 remaster", 2020));
        assertTrue(AlbumProviderConflictService.yearTagNeedsUpdate("2019", 2020));
        assertTrue(AlbumProviderConflictService.yearTagNeedsUpdate("", 2020));
        assertTrue(AlbumProviderConflictService.yearTagNeedsUpdate(null, 2020));
    }

    private AlbumProviderConflictService.ResolutionResult resolve(String kind, String action, Long providerLinkId) {
        return service.resolve(new AlbumProviderConflictService.ResolutionRequest(
                kind, action, 1, 1L, providerLinkId, null, null));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String scalarText(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
