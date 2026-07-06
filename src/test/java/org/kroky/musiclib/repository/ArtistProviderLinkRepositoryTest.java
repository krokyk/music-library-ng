package org.kroky.musiclib.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class ArtistProviderLinkRepositoryTest {

    @TempDir
    Path tempDir;

    ArtistProviderLinkRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("providers.sqlite"));
        repository = new ArtistProviderLinkRepository();
        repository.dataSource = dataSource;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE artists (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE providers (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE artist_provider_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        artist_id INTEGER NOT NULL,
                        provider_id TEXT NOT NULL,
                        provider_artist_id TEXT,
                        provider_artist_name TEXT,
                        country TEXT,
                        disambiguation TEXT,
                        active INTEGER,
                        provider_url TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        last_success_at TEXT,
                        last_error_at TEXT,
                        last_error_message TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (artist_id, provider_id),
                        UNIQUE (provider_id, provider_artist_id)
                    )
                    """);
            statement.execute("INSERT INTO artists (id, name) VALUES (1, 'Ancient Bards')");
            statement.execute("""
                    INSERT INTO providers (id, name)
                    VALUES ('musicbrainz', 'MusicBrainz'),
                           ('spirit_of_metal', 'Spirit of Metal')
                    """);
        }
    }

    @Test
    void upsertKeepsOneLinkPerProviderWithoutReplacingOtherProviders() {
        var musicBrainz = repository.upsertForArtist(
                1,
                "musicbrainz",
                "9aa7cba4-156c-4911-a594-7abc81b41bc9",
                "Ancient Bards",
                "https://musicbrainz.org/artist/9aa7cba4-156c-4911-a594-7abc81b41bc9",
                true);
        var spiritOfMetal = repository.upsertForArtist(
                1,
                "spirit_of_metal",
                "https://www.spirit-of-metal.com/en/band/Ancient_Bards",
                "Ancient Bards",
                "https://www.spirit-of-metal.com/en/band/Ancient_Bards",
                true);

        assertEquals(2, repository.listByArtist(1).size());
        assertEquals(musicBrainz.id(), repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow().id());
        assertEquals(spiritOfMetal.id(), repository.findByArtistAndProvider(1, "spirit_of_metal").orElseThrow().id());

        repository.upsertForArtist(
                1,
                "musicbrainz",
                "9aa7cba4-156c-4911-a594-7abc81b41bc9",
                "Ancient Bards ITA",
                "https://musicbrainz.org/artist/9aa7cba4-156c-4911-a594-7abc81b41bc9",
                true);

        assertEquals(2, repository.listByArtist(1).size());
        assertEquals("Ancient Bards ITA",
                repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow().providerArtistName());
        assertTrue(repository.findByArtistAndProvider(1, "spirit_of_metal").isPresent());

        repository.deleteByArtistAndProvider(1, "musicbrainz");

        assertTrue(repository.findByArtistAndProvider(1, "musicbrainz").isEmpty());
        assertTrue(repository.findByArtistAndProvider(1, "spirit_of_metal").isPresent());
    }
}
