package org.kroky.musiclib.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kroky.musiclib.scan.MusicRootService;
import org.sqlite.SQLiteDataSource;

class AlbumProviderLinkRepositoryTest {

    @TempDir
    Path tempDir;

    AlbumProviderLinkRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("album-providers.sqlite"));
        repository = new AlbumProviderLinkRepository();
        repository.dataSource = dataSource;
        repository.musicRootService = new FixedMusicRootService(tempDir);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE artists (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE albums (
                        id INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        release_date TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE album_artists (
                        album_id INTEGER NOT NULL,
                        artist_id INTEGER NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (album_id, artist_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE collections (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        relative_path TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE album_local_paths (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        album_id INTEGER NOT NULL,
                        collection_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE album_provider_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        album_id INTEGER NOT NULL,
                        provider_id TEXT NOT NULL,
                        provider_release_group_id TEXT NOT NULL,
                        provider_title TEXT NOT NULL,
                        provider_release_date TEXT,
                        provider_url TEXT,
                        release_date_resolution TEXT,
                        title_resolution TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (provider_id, provider_release_group_id),
                        UNIQUE (album_id, provider_id, provider_release_group_id)
                    )
                    """);
            statement.execute("INSERT INTO artists (id, name) VALUES (1, 'Ancient Bards')");
            statement.execute("""
                    INSERT INTO collections (id, name, relative_path)
                    VALUES ('power-metal', 'Power Metal', 'Power Metal')
                    """);
            statement.execute("""
                    INSERT INTO albums (id, title, release_date)
                    VALUES (1, 'Origine (The Black Crystal Sword Saga, Pt. 2)', '2019'),
                           (2, 'A New Religion?', '2014'),
                           (3, 'The Alliance of the Kings', '2010'),
                           (4, 'A New Religion', '1998')
                    """);
            statement.execute("""
                    INSERT INTO album_artists (album_id, artist_id, position)
                    VALUES (1, 1, 0),
                           (2, 1, 0),
                           (3, 1, 0),
                           (4, 1, 0)
                    """);
            statement.execute("""
                    INSERT INTO album_local_paths (album_id, collection_id, relative_path)
                    VALUES (1, 'power-metal', 'Power Metal/Ancient Bards/2019 - Origine (The Black Crystal Sword Saga, Pt. 2)'),
                           (2, 'power-metal', 'Power Metal/Ancient Bards/2014 - A New Religion ?'),
                           (3, 'power-metal', 'Power Metal/Ancient Bards/2010 - The Alliance of the Kings'),
                           (4, 'power-metal', 'Power Metal/Athena/1998 - A New Religion')
                    """);
        }
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

    @Test
    void titleConflictsGroupEquivalentProviderTitlesAndResolveTogether() {
        repository.linkAlbum(
                1,
                "musicbrainz",
                "mb-origine",
                "Origine: The Black Crystal Sword Saga, Pt. 2",
                "2019-01-25",
                "https://musicbrainz.org/release-group/mb-origine");
        repository.linkAlbum(
                1,
                "spirit_of_metal",
                "som-origine",
                "Origine: The Black Crystal Sword Saga, Pt. 2 ",
                "2019",
                "https://www.spirit-of-metal.com/origine");
        repository.linkAlbum(
                2,
                "musicbrainz",
                "mb-religion",
                "A New Religion ?",
                "2014",
                "https://musicbrainz.org/release-group/mb-religion");

        var conflicts = repository.listTitleConflicts();

        assertEquals(1, conflicts.size());
        assertEquals(1, conflicts.get(0).albumId());
        assertEquals("Origine: The Black Crystal Sword Saga, Pt. 2", conflicts.get(0).providerTitle());
        assertEquals(2, conflicts.get(0).sources().size());

        assertEquals(2, repository.resolveMatchingTitleConflicts(
                1,
                "Origine: The Black Crystal Sword Saga, Pt. 2",
                "KEEP_LOCAL"));
        assertEquals(0, repository.listTitleConflicts().size());
        assertEquals("KEEP_LOCAL", repository.find(conflicts.get(0).sources().get(0).providerLinkId()).orElseThrow()
                .titleResolution());
        assertEquals("KEEP_LOCAL", repository.find(conflicts.get(0).sources().get(1).providerLinkId()).orElseThrow()
                .titleResolution());

        assertEquals(2, repository.resetMatchingKeepLocalTitleConflicts(
                1,
                "Origine: The Black Crystal Sword Saga, Pt. 2"));
        assertEquals(1, repository.listTitleConflicts().size());
    }

    @Test
    void providerTitleChoiceResolvesOtherProviderTitlesForAlbum() {
        String providerTitle = "The Alliance of the Kings - The Black Crystal Sword Saga Pt.1";
        repository.linkAlbum(
                3,
                "metal_archives",
                "ma-alliance",
                providerTitle,
                "2010",
                "https://www.metal-archives.com/albums/alliance");
        repository.linkAlbum(
                3,
                "spirit_of_metal",
                "som-alliance",
                providerTitle,
                "2010",
                "https://www.spirit-of-metal.com/alliance");
        repository.linkAlbum(
                3,
                "musicbrainz",
                "mb-alliance",
                "The Alliance of the Kings",
                "2010-02-19",
                "https://musicbrainz.org/release-group/mb-alliance");

        var conflicts = repository.listTitleConflicts();

        assertEquals(1, conflicts.size());
        assertEquals(2, conflicts.get(0).sources().size());

        assertEquals(3, repository.resolveAlbumTitleUsingProvider(3, providerTitle));
        assertEquals(0, repository.listTitleConflicts().size());
        assertEquals("USE_PROVIDER", repository.find(conflicts.get(0).sources().get(0).providerLinkId()).orElseThrow()
                .titleResolution());
        assertEquals("USE_PROVIDER", repository.find(conflicts.get(0).sources().get(1).providerLinkId()).orElseThrow()
                .titleResolution());
        assertEquals("USE_OTHER_PROVIDER", repository.find(3).orElseThrow().titleResolution());
    }

    @Test
    void providerTitleChoiceUsesOtherProviderForNormalizedButDifferentTitleText() {
        repository.linkAlbum(
                1,
                "metal_archives",
                "ma-origine",
                "Origine - The Black Crystal Sword Saga Part 2",
                "2019",
                "https://www.metal-archives.com/origine");
        repository.linkAlbum(
                1,
                "musicbrainz",
                "mb-origine",
                "Origine (The Black Crystal Sword Saga Part 2)",
                "2019-01-23",
                "https://musicbrainz.org/release-group/mb-origine");
        repository.linkAlbum(
                1,
                "spirit_of_metal",
                "som-origine",
                "Origine: The Black Crystal Sword Saga Part 2",
                "2019",
                "https://www.spirit-of-metal.com/origine");

        assertEquals(3, repository.resolveAlbumTitleUsingProvider(
                1,
                "Origine - The Black Crystal Sword Saga Part 2"));

        assertEquals("USE_PROVIDER", repository.find(1).orElseThrow().titleResolution());
        assertEquals("USE_OTHER_PROVIDER", repository.find(2).orElseThrow().titleResolution());
        assertEquals("USE_OTHER_PROVIDER", repository.find(3).orElseThrow().titleResolution());
    }

    @Test
    void localTitleChoiceKeepsDifferentProviderTitlesAndClearsMatchingTitles() {
        repository.linkAlbum(
                3,
                "metal_archives",
                "ma-alliance",
                "The Alliance of the Kings - The Black Crystal Sword Saga Pt.1",
                "2010",
                "https://www.metal-archives.com/albums/alliance");
        repository.linkAlbum(
                3,
                "musicbrainz",
                "mb-alliance",
                "The Alliance of the Kings",
                "2010-02-19",
                "https://musicbrainz.org/release-group/mb-alliance");

        assertEquals(2, repository.resolveAlbumTitleUsingLocal(3, "The Alliance of the Kings"));
        assertEquals(0, repository.listTitleConflicts().size());
        assertEquals("KEEP_LOCAL", repository.find(1).orElseThrow().titleResolution());
        assertNull(repository.find(2).orElseThrow().titleResolution());
    }

    @Test
    void localTitleChoiceKeepsPunctuationOnlyProviderTitleConflicts() {
        repository.linkAlbum(
                4,
                "musicbrainz",
                "mb-religion",
                "A New Religion?",
                "1998-06-18",
                "https://musicbrainz.org/release-group/mb-religion");

        assertEquals(1, repository.resolveAlbumTitleUsingLocal(4, "A New Religion"));

        assertEquals("KEEP_LOCAL", repository.find(1).orElseThrow().titleResolution());
    }
}
