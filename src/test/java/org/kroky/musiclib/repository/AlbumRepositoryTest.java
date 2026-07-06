package org.kroky.musiclib.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kroky.musiclib.db.Names;
import org.sqlite.SQLiteDataSource;

class AlbumRepositoryTest {

    @TempDir
    Path tempDir;

    AlbumRepository repository;
    SQLiteDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("albums.sqlite"));
        repository = new AlbumRepository();
        repository.dataSource = dataSource;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE artists (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        normalized_name TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE albums (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        normalized_title TEXT NOT NULL,
                        release_date TEXT,
                        sort_name TEXT,
                        normalized_sort_name TEXT,
                        sort_name_source TEXT NOT NULL DEFAULT 'AUTO',
                        checked INTEGER NOT NULL DEFAULT 0,
                        notes TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                    CREATE TABLE collection_albums (
                        collection_id TEXT NOT NULL,
                        album_id INTEGER NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (collection_id, album_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE album_local_paths (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        album_id INTEGER NOT NULL,
                        collection_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (collection_id, relative_path)
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
            statement.execute("INSERT INTO artists (id, name, normalized_name) VALUES (1, 'Ancient Bards', 'ancient bards')");
        }
    }

    @Test
    void scannedLocalFolderFuzzyMatchesCheckedProviderAlbum() throws Exception {
        long albumId;
        try (var connection = dataSource.getConnection();
                var insert = connection.prepareStatement("""
                        INSERT INTO albums (
                            title, normalized_title, release_date,
                            sort_name, normalized_sort_name, checked
                        )
                        VALUES (?, ?, '2010', ?, ?, 1)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            String providerTitle = "The Alliance of the Kings - The Black Crystal Sword Saga Pt. 1";
            insert.setString(1, providerTitle);
            insert.setString(2, Names.normalize(providerTitle));
            insert.setString(3, providerTitle);
            insert.setString(4, Names.normalize(providerTitle));
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                keys.next();
                albumId = keys.getLong(1);
            }
        }
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO album_artists (album_id, artist_id, position) VALUES (" + albumId + ", 1, 0)");
            statement.execute("""
                    INSERT INTO album_provider_links (
                        album_id, provider_id, provider_release_group_id,
                        provider_title, provider_release_date
                    )
                    VALUES (
                        %d, 'musicbrainz', 'mb-alliance',
                        'The Alliance of the Kings - The Black Crystal Sword Saga Pt. 1', '2010'
                    )
                    """.formatted(albumId));
        }

        var result = repository.upsertScanned(
                1,
                "The Alliance of the Kings",
                "2010",
                "Ancient Bards - 2010 - The Alliance of the Kings",
                "power-metal");

        assertEquals(albumId, result.id());
        assertFalse(result.created());
        assertEquals(1, scalarInt("SELECT count(*) FROM albums"));
        assertEquals(albumId, scalarLong("SELECT album_id FROM album_local_paths WHERE collection_id = 'power-metal'"));
    }

    private int scalarInt(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
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
}
