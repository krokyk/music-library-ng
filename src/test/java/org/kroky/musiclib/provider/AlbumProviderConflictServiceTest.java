package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;
import org.kroky.musiclib.scan.MusicRootService;
import org.sqlite.SQLiteDataSource;

class AlbumProviderConflictServiceTest {

    @TempDir
    Path tempDir;

    Path musicRoot;
    SQLiteDataSource dataSource;
    AlbumProviderConflictService service;
    AlbumRepository albums;
    AlbumProviderLinkRepository providerLinks;

    @BeforeEach
    void setUp() throws Exception {
        musicRoot = tempDir.resolve("music-root");
        Files.createDirectories(musicRoot.resolve("POWER METAL").resolve("ATHENA - 2000 - Twilight Of Days"));
        Files.createDirectories(musicRoot.resolve("POWER METAL").resolve("ATHENA - 1998 - A New Religion"));
        Files.createDirectories(musicRoot.resolve("NESTED METAL").resolve("Athena").resolve("1998 - Nested Album"));
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("provider-conflicts.sqlite"));
        initializeSchema(dataSource);

        FixedMusicRootService musicRootService = new FixedMusicRootService(musicRoot);
        albums = new AlbumRepository();
        inject(albums, "dataSource", dataSource);
        inject(albums, "musicRootService", musicRootService);

        providerLinks = new AlbumProviderLinkRepository();
        inject(providerLinks, "dataSource", dataSource);
        inject(providerLinks, "musicRootService", musicRootService);

        MusicCollectionRepository collections = new MusicCollectionRepository();
        inject(collections, "dataSource", dataSource);
        inject(collections, "musicRootService", musicRootService);

        service = new AlbumProviderConflictService();
        service.albums = albums;
        service.providerLinks = providerLinks;
        service.collections = collections;

        seedAlbum();
    }

    @Test
    void windowsFolderRenderingUsesConfiguredCharacterMapAndCollapsesAllWhitespace() {
        String value = "A/B\\C:D?E*F\"G\u2014H\u201dI\u201cJ|K<L>M\n\tN\u00a0 O\u0007.";

        assertEquals("A-B-C -DEF'G-H'I'J-K-L-M N O",
                AlbumProviderConflictService.windowsSafeFolderSegment(value, "Album"));
        assertEquals("Album", AlbumProviderConflictService.windowsSafeFolderSegment(" ?*... ", "Album"));
    }

    @Test
    void providerRefreshCleanupRemovesStaleArtistPathsAcrossCollections() throws Exception {
        ProviderCheckService providerChecks = new ProviderCheckService();
        providerChecks.albums = albums;
        ArtistProviderLink link = new ArtistProviderLink(
                1,
                1,
                "Athena",
                "metal_archives",
                "https://www.metal-archives.com/bands/_/1",
                "Athena",
                null,
                null,
                null,
                "https://www.metal-archives.com/bands/_/1",
                true,
                null,
                null,
                null,
                null,
                null);

        providerChecks.removeStaleLocalPaths(link);

        assertEquals(3, scalarLong("SELECT COUNT(*) FROM album_local_paths"));
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM album_local_paths WHERE album_id = 3"));
        assertEquals(0, scalarLong("SELECT COUNT(*) FROM album_local_paths WHERE album_id = 5"));
    }

    @Test
    void providerReleaseDateResolutionRenamesFolderAndUpdatesLocalPath() throws Exception {
        providerLinks.linkAlbum(
                1,
                "metal_archives",
                "ma-twilight",
                "Twilight of Days",
                "2001",
                "https://www.metal-archives.com/albums/twilight");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        var result = service.useProviderReleaseDate(1, providerLinkId);

        Path oldFolder = musicRoot.resolve("POWER METAL").resolve("ATHENA - 2000 - Twilight Of Days");
        Path newFolder = musicRoot.resolve("POWER METAL").resolve("ATHENA - 2001 - Twilight of Days");
        assertFalse(Files.exists(oldFolder));
        assertTrue(Files.isDirectory(newFolder));
        assertEquals("ATHENA - 2001 - Twilight of Days",
                scalarText("SELECT relative_path FROM album_local_paths WHERE album_id = 1"));
        assertEquals("2001", scalarText("SELECT release_date FROM albums WHERE id = 1"));
        assertEquals("Twilight of Days", scalarText("SELECT title FROM albums WHERE id = 1"));
        assertEquals("USE_PROVIDER", providerLinks.find(providerLinkId).orElseThrow().releaseDateResolution());
        assertEquals(1, result.folderCount());
    }

    @Test
    void providerTitleResolutionUsesConfiguredFolderCharacterMapButKeepsProviderTitle() throws Exception {
        String providerTitle = "A/New: Album? \"Quote\"* \u2014 \u201cLeft\u201d | <End> \\ Back\n\tSpace";
        providerLinks.linkAlbum(
                3,
                "metal_archives",
                "ma-new-religion",
                providerTitle,
                "1998",
                "https://www.metal-archives.com/albums/new-religion");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        var plan = service.planUseProviderTitle(3, providerLinkId);

        String expectedFolder = "ATHENA - 1998 - A-New - Album 'Quote' - 'Left' - -End- - Back Space";
        assertEquals(expectedFolder, plan.targetRelativePath());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("adjusted for the Windows folder name")));
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM album_local_paths WHERE album_id = 3"));

        var result = service.useProviderTitle(3, providerLinkId);

        Path oldFolder = musicRoot.resolve("POWER METAL").resolve("ATHENA - 1998 - A New Religion");
        Path newFolder = musicRoot.resolve("POWER METAL").resolve(expectedFolder);
        assertFalse(Files.exists(oldFolder));
        assertTrue(Files.isDirectory(newFolder));
        assertEquals(expectedFolder,
                scalarText("SELECT relative_path FROM album_local_paths WHERE album_id = 3"));
        assertEquals(providerLinks.find(providerLinkId).orElseThrow().providerTitle(),
                scalarText("SELECT title FROM albums WHERE id = 3"));
        assertEquals("USE_PROVIDER", providerLinks.find(providerLinkId).orElseThrow().titleResolution());
        assertEquals(1, result.folderCount());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("adjusted for the Windows folder name")));
    }

    @Test
    void providerTitleResolutionAllowsSanitizedTargetToMatchCurrentFolder() throws Exception {
        providerLinks.linkAlbum(
                3,
                "metal_archives",
                "ma-new-religion",
                "A New Religion?",
                "1998",
                "https://www.metal-archives.com/albums/new-religion");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        var plan = service.planUseProviderTitle(3, providerLinkId);

        assertEquals("ATHENA - 1998 - A New Religion", plan.targetRelativePath());
        assertFalse(plan.warnings().stream().anyMatch(warning -> warning.contains("Target folder already exists")));

        var result = service.useProviderTitle(3, providerLinkId);

        assertTrue(Files.isDirectory(musicRoot.resolve("POWER METAL").resolve("ATHENA - 1998 - A New Religion")));
        assertEquals("ATHENA - 1998 - A New Religion",
                scalarText("SELECT relative_path FROM album_local_paths WHERE album_id = 3"));
        assertEquals("A New Religion?", scalarText("SELECT title FROM albums WHERE id = 3"));
        assertEquals(1, result.folderCount());
    }

    @Test
    void providerTitleResolutionRenamesNestedAlbumFolder() throws Exception {
        providerLinks.linkAlbum(
                4,
                "metal_archives",
                "ma-nested",
                "Nested: Album?",
                "1998",
                "https://www.metal-archives.com/albums/nested");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        var result = service.useProviderTitle(4, providerLinkId);

        assertFalse(Files.exists(musicRoot.resolve("NESTED METAL").resolve("Athena").resolve("1998 - Nested Album")));
        assertTrue(Files.isDirectory(
                musicRoot.resolve("NESTED METAL").resolve("Athena").resolve("1998 - Nested - Album")));
        assertEquals("Athena/1998 - Nested - Album",
                scalarText("SELECT relative_path FROM album_local_paths WHERE album_id = 4"));
        assertEquals("Nested: Album?", scalarText("SELECT title FROM albums WHERE id = 4"));
        assertEquals(1, result.folderCount());
    }

    @Test
    void conflictContextRemovesStaleLocalPathsAndTreatsAlbumAsNonLocal() throws Exception {
        providerLinks.linkAlbum(
                5,
                "metal_archives",
                "ma-missing",
                "Missing: Album?",
                "1997",
                "https://www.metal-archives.com/albums/missing");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        var plan = service.planUseProviderTitle(5, providerLinkId);

        assertEquals(0, plan.folderCount());
        assertEquals(0, scalarLong("SELECT COUNT(*) FROM album_local_paths WHERE album_id = 5"));

        var result = service.useProviderTitle(5, providerLinkId);

        assertEquals("Missing: Album?", scalarText("SELECT title FROM albums WHERE id = 5"));
        assertEquals(1, scalarLong("SELECT checked FROM albums WHERE id = 5"));
        assertEquals(0, result.folderCount());
    }

    @Test
    void providerTitleResolutionDoesNotOverwriteExistingSanitizedTarget() throws Exception {
        Path source = musicRoot.resolve("POWER METAL").resolve("ATHENA - 1998 - A New Religion");
        Path target = musicRoot.resolve("POWER METAL").resolve("ATHENA - 1998 - A New Religion Deluxe");
        Files.createDirectories(target);
        providerLinks.linkAlbum(
                3,
                "metal_archives",
                "ma-new-religion",
                "A New Religion? Deluxe",
                "1998",
                "https://www.metal-archives.com/albums/new-religion");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'metal_archives'");

        assertThrows(IllegalStateException.class, () -> service.useProviderTitle(3, providerLinkId));

        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
        assertEquals("A New Religion", scalarText("SELECT title FROM albums WHERE id = 3"));
    }

    @Test
    void providerOnlyTitleResolutionKeepsUncheckedAlbumUnchecked() throws Exception {
        providerLinks.linkAlbum(
                2,
                "metal_archives",
                "ma-everflow",
                "Everflow Part 1: Frames of Humanity",
                "2024",
                "https://www.metal-archives.com/albums/everflow");
        providerLinks.linkAlbum(
                2,
                "musicbrainz",
                "mb-everflow",
                "Everflow, Part 1: Frames of Humanity?",
                "2024-12-06",
                "https://musicbrainz.org/release-group/everflow");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'musicbrainz'");

        var result = service.useProviderTitle(2, providerLinkId);

        assertEquals("Everflow, Part 1: Frames of Humanity?", scalarText("SELECT title FROM albums WHERE id = 2"));
        assertEquals(0, scalarLong("SELECT checked FROM albums WHERE id = 2"));
        assertEquals("USE_PROVIDER", providerLinks.find(providerLinkId).orElseThrow().titleResolution());
        assertEquals(0, result.folderCount());
    }

    @Test
    void providerOnlyReleaseDateResolutionKeepsUncheckedAlbumUnchecked() throws Exception {
        providerLinks.linkAlbum(
                2,
                "musicbrainz",
                "mb-everflow",
                "Everflow, Part 1: Frames of Humanity?",
                "2025-01-10",
                "https://musicbrainz.org/release-group/everflow");
        long providerLinkId = scalarLong("SELECT id FROM album_provider_links WHERE provider_id = 'musicbrainz'");

        var result = service.useProviderReleaseDate(2, providerLinkId);

        assertEquals("2025-01-10", scalarText("SELECT release_date FROM albums WHERE id = 2"));
        assertEquals(0, scalarLong("SELECT checked FROM albums WHERE id = 2"));
        assertEquals(0, scalarLong("SELECT COUNT(*) FROM album_local_paths WHERE album_id = 2"));
        assertEquals("USE_PROVIDER", providerLinks.find(providerLinkId).orElseThrow().releaseDateResolution());
        assertEquals(0, result.folderCount());
    }

    private void seedAlbum() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO collections (id, name, relative_path, type, parser)
                    VALUES ('power-metal', 'Power Metal', 'POWER METAL', 'ARTIST', 'FLAT_ARTIST_YEAR_ALBUM'),
                           ('nested-metal', 'Nested Metal', 'NESTED METAL', 'ARTIST', 'NESTED_ARTIST_ALBUM')
                    """);
            statement.execute("""
                    INSERT INTO artists (id, name, normalized_name)
                    VALUES (1, 'Athena', 'athena')
                    """);
            statement.execute("""
                    INSERT INTO albums (id, title, normalized_title, release_date, checked)
                    VALUES (1, 'Twilight Of Days', 'twilight of days', '2000', 1),
                           (2, 'Everflow Part 1: Frames of Humanity', 'everflow part 1 frames of humanity', '2024', 0),
                           (3, 'A New Religion', 'a new religion', '1998', 1),
                           (4, 'Nested Album', 'nested album', '1998', 1),
                           (5, 'Missing Album', 'missing album', '1997', 1)
                    """);
            statement.execute("""
                    INSERT INTO album_artists (album_id, artist_id, position)
                    VALUES (1, 1, 0),
                           (2, 1, 0),
                           (3, 1, 0),
                           (4, 1, 0),
                           (5, 1, 0)
                    """);
            statement.execute("""
                    INSERT INTO collection_albums (collection_id, album_id)
                    VALUES ('power-metal', 1),
                           ('power-metal', 3),
                           ('nested-metal', 4),
                           ('nested-metal', 5)
                    """);
            statement.execute("""
                    INSERT INTO album_local_paths (album_id, collection_id, relative_path)
                    VALUES (1, 'power-metal', 'ATHENA - 2000 - Twilight Of Days'),
                           (3, 'power-metal', 'ATHENA - 1998 - A New Religion'),
                           (3, 'nested-metal', 'Athena/1998 - Missing Copy'),
                           (4, 'nested-metal', 'Athena/1998 - Nested Album'),
                           (5, 'nested-metal', 'Athena/1997 - Missing Album')
                    """);
        }
    }

    private static void initializeSchema(DataSource dataSource) throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String scalarText(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FixedMusicRootService extends MusicRootService {
        private final Path root;

        FixedMusicRootService(Path root) {
            this.root = root;
        }

        @Override
        public Path requireRoot() {
            return root;
        }

        @Override
        public Path resolveCollection(String collectionRelativePath) {
            return root.resolve(collectionRelativePath);
        }
    }
}
