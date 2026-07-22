package org.kroky.musiclib.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.provider.ArtistProviderBulkMatchService;
import org.kroky.musiclib.scan.ScanService;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class LibraryDomainRepositoryTest {

    private static final Path MUSIC_ROOT = Path.of("src/test/resources/music-root");
    private static final List<String> TEST_FOLDERS = List.of("TEST-OLD", "TEST-NEW", "TEST-TITLES", "TEST-DELETE", "TEST-NESTED", "TEST-INFER", "TEST-INFER-TIE", "TEST-INFER-TITLE", "TEST-INFER-EMPTY");

    @Inject DataSource dataSource;
    @Inject AlbumRepository albums;
    @Inject ArtistRepository artists;
    @Inject ArtistProviderLinkRepository providerLinks;
    @Inject MusicCollectionRepository collections;
    @Inject ArtistProviderBulkMatchService bulkMatch;
    @Inject ScanService scans;

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS fail_album_insert");
            statement.executeUpdate("DELETE FROM album_provider_links");
            statement.executeUpdate("DELETE FROM artist_provider_links");
            statement.executeUpdate("DELETE FROM albums");
            statement.executeUpdate("DELETE FROM artists");
            statement.executeUpdate("DELETE FROM collections");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('old','Old','TEST-OLD','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('new','New','TEST-NEW','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('titles','Titles','TEST-TITLES','TITLE')");
            statement.executeUpdate("INSERT INTO artists(id,name,normalized_name) VALUES(1,'Athena','athena')");
        }
        cleanupFolders();
    }

    @AfterEach
    void cleanupFolders() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS fail_album_insert");
        }
        for (String name : TEST_FOLDERS) deleteTree(MUSIC_ROOT.resolve(name));
    }

    @Test
    void freshSchemaUsesOneRequiredAlbumHomeAndIntegerYear() throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA table_info(albums)")) {
            while (result.next()) columns.add(result.getString("name"));
        }

        assertTrue(columns.containsAll(Set.of("collection_id", "local_relative_path", "release_year")));
        assertFalse(columns.contains("release_date"));
        assertEquals(0, scalarLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('collection_albums','album_local_paths','artist_collections')"));
        assertEquals(0, scalarLong("SELECT count(*) FROM pragma_table_info('collections') WHERE name='parser'"));
        assertThrows(Exception.class, () -> execute("INSERT INTO albums(collection_id,title,normalized_title,release_year) VALUES('old','Bad','bad',999)"));
    }

    @Test
    void infersCollectionTypeFromContentAndLocksItOnlyAfterAnAlbumExists() throws Exception {
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER/Flat Artist - 2000 - Album"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER/Nested Artist/2001 - Album"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER/Film (Composer, 2002)"));

        var candidate = collections.listFolderCandidates().stream()
                .filter(item -> item.relativePath().equals("TEST-INFER"))
                .findFirst().orElseThrow();
        assertEquals("TEST-INFER", candidate.folderName());
        var inferred = collections.createFromFolder("TEST-INFER");
        assertEquals(CollectionType.ARTIST, inferred.type());
        assertEquals(CollectionType.TITLE, collections.update(inferred.id(), inferred.name(), CollectionType.TITLE).orElseThrow().type());

        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-TIE/Flat Artist - 2000 - Album"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-TIE/Film (Composer, 2002)"));
        assertEquals(CollectionType.ARTIST, collections.createFromFolder("TEST-INFER-TIE").type());

        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-TITLE/Flat Artist - 2000 - Album"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-TITLE/Film One (Composer, 2002)"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-TITLE/Film Two (Composer, 2003)"));
        assertEquals(CollectionType.TITLE, collections.createFromFolder("TEST-INFER-TITLE").type());

        Files.createDirectories(MUSIC_ROOT.resolve("TEST-INFER-EMPTY"));
        assertEquals(CollectionType.ARTIST, collections.createFromFolder("TEST-INFER-EMPTY").type());

        execute("INSERT INTO albums(collection_id,title,normalized_title,checked) VALUES('" + inferred.id() + "','Film','film',1)");
        assertThrows(IllegalArgumentException.class,
                () -> collections.update(inferred.id(), inferred.name(), CollectionType.ARTIST));
    }

    @Test
    void artistScanProcessesFlatAndNestedFoldersTogether() throws Exception {
        execute("INSERT INTO collections(id,name,relative_path,type) VALUES('nested','Nested','TEST-NESTED','ARTIST')");
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-NESTED/Flat Artist - 2000 - Flat Album"));
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-NESTED/Nested Artist/2001 - Nested Album"));

        var summary = scans.scan("nested", ScanService.ProgressListener.NONE);

        assertEquals("DONE", summary.status());
        assertEquals(2, summary.parsedCount());
        assertEquals(Set.of("Flat Artist - 2000 - Flat Album", "Nested Artist/2001 - Nested Album"),
                albums.list(null, "nested", null, null, null).stream()
                        .map(album -> album.localRelativePath()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void stalePhysicalEvidenceRehomesButSimultaneousDuplicateStorageFails() throws Exception {
        var album = albums.create(1, "Heroes", 2000, false, null, "old");
        albums.updateLocalRelativePath(album.id(), "Athena - 2000 - Heroes");
        Path newFolder = MUSIC_ROOT.resolve("TEST-NEW/Athena - 2000 - Heroes");
        Files.createDirectories(newFolder);

        var rebound = albums.upsertScanned(1, "Heroes", 2000, "Athena - 2000 - Heroes", "new");

        assertEquals(album.id(), rebound.id());
        assertEquals("new", albums.find(album.id()).orElseThrow().collection().id());
        assertThrows(IllegalStateException.class,
                () -> albums.upsertScanned(1, "Heroes", 2000, "Athena - 2000 - Heroes Copy", "new"));

        deleteTree(newFolder);
        assertEquals(1, albums.removeStaleLocalPathsForArtist(1));
        assertEquals("new", albums.find(album.id()).orElseThrow().collection().id());
        assertEquals(null, albums.find(album.id()).orElseThrow().localRelativePath());
    }

    @Test
    void onlyNonLocalAlbumsCanMoveBetweenCollectionsOfTheSameType() {
        var album = albums.create(1, "Heroes", 2000, false, null, "old");
        albums.create(1, "Second", 2001, false, null, "old");

        assertEquals(List.of("old"), artists.find(1).orElseThrow().collectionIds());

        assertEquals("new", albums.reassignCollection(album.id(), "new").collection().id());
        assertEquals("new", albums.majorArtistCollection(1));
        assertThrows(IllegalArgumentException.class, () -> albums.reassignCollection(album.id(), "titles"));

        albums.upsertScanned(1, "Heroes", 2000, "Athena - 2000 - Heroes", "new");
        assertThrows(IllegalArgumentException.class, () -> albums.reassignCollection(album.id(), "old"));
    }

    @Test
    void collectionDeleteCascadesDatabaseRowsButLeavesDiskUntouched() throws Exception {
        execute("INSERT INTO collections(id,name,relative_path,type) VALUES('delete','Delete','TEST-DELETE','ARTIST')");
        execute("INSERT INTO artists(id,name,normalized_name) VALUES(2,'Shared','shared')");
        Path physicalFolder = MUSIC_ROOT.resolve("TEST-DELETE/Athena - 2000 - Gone");
        Files.createDirectories(physicalFolder);
        var exclusive = albums.upsertScanned(1, "Gone", 2000, "Athena - 2000 - Gone", "delete");
        var sharedDeleted = albums.create(2, "Shared Gone", 2001, true, null, "delete");
        albums.create(2, "Shared Kept", 2002, true, null, "new");

        var preview = collections.deletePreview("delete");
        var result = collections.delete("delete");

        assertEquals(2, preview.albumCount());
        assertEquals(1, preview.artistCount());
        assertEquals(2, result.albumsDeleted());
        assertEquals(1, result.artistsDeleted());
        assertTrue(albums.find(exclusive.id()).isEmpty());
        assertTrue(albums.find(sharedDeleted.id()).isEmpty());
        assertTrue(artists.find(1).isEmpty());
        assertTrue(artists.find(2).isPresent());
        assertTrue(Files.isDirectory(physicalFolder));
    }

    @Test
    void titleOnlyArtistsRejectProviderLinksAndAreExcludedFromBulkMatch() {
        albums.create(1, "Soundtrack", 2000, true, null, "titles");

        assertThrows(IllegalArgumentException.class,
                () -> providerLinks.upsertForArtist(1, "musicbrainz", "mbid", "Athena", null, true));
        var result = bulkMatch.matchProviderArtists("musicbrainz", List.of(1L));
        assertEquals(0, result.requestedArtistCount());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void emptyNestedArtistFolderDoesNotCreateAlbumlessArtist() throws Exception {
        execute("DELETE FROM artists");
        execute("INSERT INTO collections(id,name,relative_path,type) VALUES('nested','Nested','TEST-NESTED','ARTIST')");
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-NESTED/Empty Artist"));

        var summary = scans.scan("nested", ScanService.ProgressListener.NONE);

        assertEquals("DONE", summary.status());
        assertEquals(0, artists.list(null).size());
        assertEquals(1, summary.skippedCount());
    }

    @Test
    void failedAlbumInsertDoesNotLeaveNewScannedArtist() throws Exception {
        execute("DELETE FROM artists");
        execute("INSERT INTO collections(id,name,relative_path,type) VALUES('nested','Nested','TEST-NESTED','ARTIST')");
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-NESTED/New Artist/2000 - Album"));
        execute("""
                CREATE TRIGGER fail_album_insert BEFORE INSERT ON albums
                BEGIN SELECT RAISE(ABORT, 'forced album failure'); END
                """);

        assertThrows(IllegalStateException.class, () -> scans.scan("nested", ScanService.ProgressListener.NONE));

        assertTrue(artists.list(null).isEmpty());
    }

    @Test
    void titleFolderWithoutContributorCreatesArtistlessAlbum() throws Exception {
        execute("DELETE FROM artists");
        Files.createDirectories(MUSIC_ROOT.resolve("TEST-TITLES/Conan the Barbarian (2011)"));

        var summary = scans.scan("titles", ScanService.ProgressListener.NONE);

        assertEquals("DONE", summary.status());
        var album = albums.list(null, "titles", null, null, null).get(0);
        assertTrue(album.artistIds().isEmpty());
        assertEquals(2011, album.releaseYear());
        assertTrue(artists.list(null).isEmpty());
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
