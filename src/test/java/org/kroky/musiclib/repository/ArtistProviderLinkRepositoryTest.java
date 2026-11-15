package org.kroky.musiclib.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class ArtistProviderLinkRepositoryTest {

    @TempDir
    Path tempDir;

    ArtistProviderLinkRepository repository;
    SQLiteDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("providers.sqlite"));
        repository = new ArtistProviderLinkRepository();
        repository.dataSource = dataSource;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE artists (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        canonical_artist_id INTEGER,
                        country_override TEXT,
                        active_override INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE providers (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE collections (id INTEGER PRIMARY KEY, type TEXT NOT NULL)");
            statement.execute("CREATE TABLE albums (id INTEGER PRIMARY KEY, collection_id INTEGER NOT NULL)");
            statement.execute("CREATE TABLE album_artists (album_id INTEGER NOT NULL, artist_id INTEGER NOT NULL)");
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
            statement.execute("INSERT INTO collections (id, type) VALUES (1, 'ARTIST')");
            statement.execute("INSERT INTO albums (id, collection_id) VALUES (1, 1)");
            statement.execute("INSERT INTO album_artists (album_id, artist_id) VALUES (1, 1)");
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

    @Test
    void sharesLinksAndCollectionScopeWithoutChangingTheirCreditedOwners() throws Exception {
        execute("INSERT INTO artists(id,name,canonical_artist_id) VALUES(2,'Early Bards',1),(3,'Other Credit',1)");
        var musicBrainz = repository.upsertForArtist(1, "musicbrainz", "shared-mbid", "Ancient Bards", null, true);
        var spiritOfMetal = repository.upsertForArtist(2, "spirit_of_metal", "som-id", "Early Bards", null, true);

        assertEquals(List.of(musicBrainz, spiritOfMetal), repository.listByArtist(3));
        assertEquals(1L, repository.findByArtistAndProvider(2, "musicbrainz").orElseThrow().artistId());
        assertEquals(musicBrainz, repository.findByProviderIdentity("musicbrainz", " shared-mbid ").orElseThrow());
        assertTrue(repository.findByProviderIdentity("musicbrainz", null).isEmpty());
        var byRequestedCredits = repository.listByArtistIds(List.of(2L, 3L, 999L));
        assertEquals(Set.of(2L, 3L), byRequestedCredits.keySet());
        assertEquals(List.of(musicBrainz, spiritOfMetal), byRequestedCredits.get(2L));
        assertEquals(byRequestedCredits.get(2L), byRequestedCredits.get(3L));

        var reused = repository.upsertForArtist(3, "musicbrainz", "shared-mbid", "Replacement name", null, true);
        assertEquals(musicBrainz, reused);
        assertEquals("Ancient Bards", repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow().providerArtistName());

        execute("INSERT INTO collections(id,type) VALUES(2,'ARTIST')");
        execute("INSERT INTO albums(id,collection_id) VALUES(2,2)");
        execute("INSERT INTO album_artists(album_id,artist_id) VALUES(2,2),(2,3)");
        assertEquals(List.of(musicBrainz, spiritOfMetal), repository.listEnabledByCollection(2));

        repository.deleteByArtistAndProvider(3, "musicbrainz");
        assertTrue(repository.findByArtistAndProvider(3, "musicbrainz").isPresent());
        repository.deleteByArtistAndProvider(1, "musicbrainz");
        assertTrue(repository.findByArtistAndProvider(3, "musicbrainz").isEmpty());
        assertEquals(List.of(spiritOfMetal), repository.listByArtist(1));
    }

    @Test
    void inheritedProviderLookupPrefersTheRequestedCreditsOwnLink() throws Exception {
        execute("INSERT INTO artists(id,name,canonical_artist_id) VALUES(2,'Early Bards',1)");
        var first = repository.upsertForArtist(1, "musicbrainz", "first-mbid", "Ancient Bards", null, true);
        var second = repository.upsertForArtist(2, "musicbrainz", "second-mbid", "Early Bards", null, true);

        assertEquals(first, repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow());
        assertEquals(second, repository.findByArtistAndProvider(2, "musicbrainz").orElseThrow());
        assertEquals(2, repository.listByArtist(1).size());
    }

    @Test
    void selectingSiblingProviderPageRemovesOnlyTheRequestersPreviousPage() throws Exception {
        execute("INSERT INTO artists(id,name,canonical_artist_id) VALUES(2,'Early Bards',1)");
        execute("INSERT INTO albums(id,collection_id) VALUES(2,1)");
        execute("INSERT INTO album_artists(album_id,artist_id) VALUES(2,2)");
        execute("""
                CREATE TABLE album_provider_links (
                    id INTEGER PRIMARY KEY,
                    album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
                    provider_id TEXT NOT NULL,
                    provider_release_group_id TEXT NOT NULL
                )
                """);
        execute("""
                INSERT INTO album_provider_links(id,album_id,provider_id,provider_release_group_id)
                VALUES(1,1,'musicbrainz','release-one'),(2,2,'musicbrainz','release-two')
                """);
        var sibling = repository.upsertForArtist(1, "musicbrainz", "sibling-mbid", "Ancient Bards",
                "https://musicbrainz.org/artist/sibling-mbid", "IT", "Original provider metadata", true, true);
        repository.upsertForArtist(2, "musicbrainz", "old-mbid", "Early Bards", null, true);
        var otherProvider = repository.upsertForArtist(2, "spirit_of_metal", "som-id", "Early Bards", null, true);

        var selected = repository.upsertForArtist(2, "musicbrainz", "sibling-mbid", "Ignored replacement",
                "https://example.com/ignored", "US", null, false, false);

        assertEquals(sibling, selected);
        assertEquals(sibling, repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow());
        assertEquals(sibling, repository.findByArtistAndProvider(2, "musicbrainz").orElseThrow());
        assertTrue(repository.findByProviderIdentity("musicbrainz", "old-mbid").isEmpty());
        assertEquals(otherProvider, repository.findByArtistAndProvider(2, "spirit_of_metal").orElseThrow());
        assertEquals(2, repository.listByArtist(2).size());
        assertEquals(2, scalarLong("SELECT count(*) FROM albums"));
        assertEquals(2, scalarLong("SELECT count(*) FROM album_artists"));
        assertEquals(2, scalarLong("SELECT count(*) FROM album_provider_links"));
        assertEquals(1, scalarLong("SELECT canonical_artist_id FROM artists WHERE id=2"));
    }

    @Test
    void rejectsSharingARemoteIdentityBeforeTheArtistNamesAreExplicitlyLinked() throws Exception {
        execute("INSERT INTO artists(id,name) VALUES(2,'Different Artist')");
        execute("INSERT INTO albums(id,collection_id) VALUES(2,1)");
        execute("INSERT INTO album_artists(album_id,artist_id) VALUES(2,2)");
        var original = repository.upsertForArtist(1, "musicbrainz", "owned-mbid", "Ancient Bards", null, true);

        var failure = assertThrows(IllegalArgumentException.class,
                () -> repository.upsertForArtist(2, "musicbrainz", "owned-mbid", "Different Artist", null, true));

        assertTrue(failure.getMessage().contains("already linked to Ancient Bards"));
        assertEquals(original, repository.findByArtistAndProvider(1, "musicbrainz").orElseThrow());
        assertTrue(repository.listByArtist(2).isEmpty());
        assertThrows(Exception.class, () -> execute("""
                INSERT INTO artist_provider_links(artist_id,provider_id,provider_artist_id)
                VALUES(2,'musicbrainz','owned-mbid')
                """));
    }

    @Test
    void metadataConflictsUseCanonicalNamesAndOverridesWhilePreservingSourceLinks() throws Exception {
        execute("""
                INSERT INTO artists(id,name,canonical_artist_id,country_override,active_override)
                VALUES(2,'Early Bards',1,'FR',0)
                """);
        var first = repository.upsertForArtist(1, "musicbrainz", "mbid", "Ancient Bards", null, "IT", null, true, true);
        var second = repository.upsertForArtist(2, "spirit_of_metal", "som-id", "Early Bards", null, "US", null, false, true);

        var countries = repository.listCountryConflicts();
        var statuses = repository.listStatusConflicts();
        assertEquals(1, countries.size());
        assertEquals(1L, countries.get(0).artistId());
        assertEquals("Ancient Bards", countries.get(0).artistName());
        assertEquals(List.of(first.id(), second.id()), countries.get(0).sources().stream().map(source -> source.providerLinkId()).toList());
        assertEquals(1, statuses.size());
        assertEquals(1L, statuses.get(0).artistId());
        assertEquals("Ancient Bards", statuses.get(0).artistName());
        assertEquals(List.of(first.id(), second.id()), statuses.get(0).sources().stream().map(source -> source.providerLinkId()).toList());

        execute("UPDATE artists SET country_override='DE',active_override=1 WHERE id=1");
        assertTrue(repository.listCountryConflicts().isEmpty());
        assertTrue(repository.listStatusConflicts().isEmpty());
    }

    private void execute(String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
