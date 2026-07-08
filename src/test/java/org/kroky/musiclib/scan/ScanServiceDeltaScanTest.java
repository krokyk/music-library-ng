package org.kroky.musiclib.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;
import org.sqlite.SQLiteDataSource;

class ScanServiceDeltaScanTest {

    @TempDir
    Path tempDir;

    SQLiteDataSource dataSource;
    Path musicRoot;
    Path collectionRoot;
    ScanService scanService;
    MusicCollectionRepository collectionRepository;
    ArtistRepository artistRepository;
    AlbumRepository albumRepository;

    @BeforeEach
    void setUp() throws Exception {
        musicRoot = tempDir.resolve("music-root");
        collectionRoot = musicRoot.resolve("power-metal");
        Files.createDirectories(collectionRoot);

        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("music-library.sqlite"));
        initializeSchema(dataSource);

        FixedMusicRootService musicRootService = new FixedMusicRootService(musicRoot);

        albumRepository = new AlbumRepository();
        inject(albumRepository, "dataSource", dataSource);
        inject(albumRepository, "musicRootService", musicRootService);

        ArtistProviderLinkRepository providerLinks = new ArtistProviderLinkRepository();
        inject(providerLinks, "dataSource", dataSource);

        artistRepository = new ArtistRepository();
        inject(artistRepository, "dataSource", dataSource);
        inject(artistRepository, "albums", albumRepository);
        inject(artistRepository, "providerLinks", providerLinks);

        collectionRepository = new MusicCollectionRepository();
        inject(collectionRepository, "dataSource", dataSource);
        inject(collectionRepository, "musicRootService", musicRootService);

        scanService = new ScanService();
        scanService.collectionRepository = collectionRepository;
        scanService.artistRepository = artistRepository;
        scanService.albumRepository = albumRepository;
        scanService.scanReportWriter = new InMemoryScanReportWriter();
        scanService.parser = new FolderNameParser();
        scanService.musicRootService = musicRootService;
    }

    @Test
    void collectionScanProcessesOnlyNewLocalPathsAfterInitialScan() throws Exception {
        createAlbumFolder(collectionRoot, "Ancient Bards - 2010 - The Alliance of the Kings");
        createAlbumFolder(collectionRoot, "Athena - 1998 - A New Religion");
        var collection = collectionRepository.createFromFolder("power-metal");

        RecordingProgress firstProgress = new RecordingProgress();
        ScanSummary first = scanService.scan(collection, firstProgress);

        assertEquals(2, first.parsedCount());
        assertEquals(2, first.createdCount());
        assertEquals(0, first.updatedCount());
        assertEquals(2, firstProgress.lastItemTotal());
        assertEquals(2, scalarInt("SELECT count(*) FROM album_local_paths WHERE collection_id = 'power-metal'"));

        Path otherCollectionRoot = musicRoot.resolve("other-metal");
        Files.createDirectories(otherCollectionRoot);
        var otherCollection = collectionRepository.createFromFolder("other-metal");
        long athenaId = artistRepository.upsertByName("Athena").id();
        var existingOtherCollectionAlbum = albumRepository.upsertScanned(
                athenaId,
                "Twilight Of Days",
                "2000",
                "ATHENA - 2000 - Twilight Of Days",
                otherCollection.id());

        createAlbumFolder(collectionRoot, "Athena - 2000 - Twilight Of Days");
        createAlbumFolder(collectionRoot, "Ancient Bards - 2014 - A New Dawn Ending");

        RecordingProgress secondProgress = new RecordingProgress();
        ScanSummary second = scanService.scan(collection, secondProgress);

        assertEquals(2, second.parsedCount());
        assertEquals(1, second.createdCount());
        assertEquals(1, second.updatedCount());
        assertEquals(2, secondProgress.lastItemTotal());
        assertTrue(secondProgress.phaseMessages().contains("Comparing DB and local folders"));
        assertTrue(second.messages().get(0).contains("2 unchanged"));
        assertEquals(4, scalarInt("SELECT count(*) FROM album_local_paths WHERE collection_id = 'power-metal'"));
        assertEquals(existingOtherCollectionAlbum.id(), scalarLong("""
                SELECT album_id
                FROM album_local_paths
                WHERE collection_id = 'power-metal'
                  AND relative_path = 'Athena - 2000 - Twilight Of Days'
                """));
        assertEquals(2, scalarInt("""
                SELECT count(*)
                FROM collection_albums
                WHERE album_id = %d
                """.formatted(existingOtherCollectionAlbum.id())));

        RecordingProgress thirdProgress = new RecordingProgress();
        ScanSummary third = scanService.scan(collection, thirdProgress);

        assertEquals(0, third.parsedCount());
        assertEquals(0, third.createdCount());
        assertEquals(0, third.updatedCount());
        assertEquals(0, thirdProgress.lastItemTotal());
        assertTrue(thirdProgress.phaseMessages().contains("No new local folders to process"));
        assertTrue(third.messages().get(0).contains("4 unchanged"));
    }

    private static void createAlbumFolder(Path collectionRoot, String name) throws Exception {
        Files.createDirectories(collectionRoot.resolve(name));
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

    private int scalarInt(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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

    private static class InMemoryScanReportWriter extends ScanReportWriter {
        @Override
        public ReportArtifact write(ScanReport report) {
            return new ReportArtifact("Scan report", null, report.summaryMessage());
        }
    }

    private static class RecordingProgress implements ScanService.ProgressListener {
        private final List<Integer> itemTotals = new ArrayList<>();
        private final List<String> phaseMessages = new ArrayList<>();

        @Override
        public void collectionStarted(String collectionId, int itemTotal) {
            itemTotals.add(itemTotal);
        }

        @Override
        public void phaseStarted(String collectionId, String message) {
            phaseMessages.add(message);
        }

        int lastItemTotal() {
            return itemTotals.isEmpty() ? -1 : itemTotals.get(itemTotals.size() - 1);
        }

        List<String> phaseMessages() {
            return phaseMessages;
        }
    }
}
