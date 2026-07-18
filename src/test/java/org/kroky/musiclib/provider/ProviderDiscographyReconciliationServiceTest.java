package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ProviderDiscographyReconciliationServiceTest {

    @Inject
    ProviderDiscographyReconciliationService service;

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    AlbumRepository albums;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS fail_provider_release");
            for (String table : List.of(
                    "album_provider_links",
                    "album_local_paths",
                    "collection_albums",
                    "album_artists",
                    "albums",
                    "artist_collections",
                    "artist_provider_links",
                    "collections",
                    "artists")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            statement.executeUpdate("""
                    INSERT INTO artists (id, name, normalized_name)
                    VALUES (1, 'Ancient Bards', 'ancient bards')
                    """);
            statement.executeUpdate("""
                    INSERT INTO artist_provider_links (
                        id, artist_id, provider_id, provider_artist_name, provider_url
                    )
                    VALUES (
                        1, 1, 'metal_archives', 'Ancient Bards',
                        'https://www.metal-archives.com/bands/Ancient_Bards/1'
                    )
                    """);
        }
    }

    @Test
    void reconcilesWholeDiscographyAndFillsMissingReleaseDate() {
        var existing = albums.create(1, "The Alliance of the Kings", null, true, null);

        var result = service.reconcile(
                providerLink(),
                "IT",
                true,
                List.of(
                        release("ma-alliance", "The Alliance of the Kings", "2010"),
                        release("ma-soulless", "Soulless Child", "2011")),
                null);

        assertEquals(1, result.existingAlbumCount());
        assertEquals(1, result.createdAlbumCount());
        assertEquals("2010", albums.find(existing.id()).orElseThrow().releaseDate());
        assertEquals(0, scalarLong("SELECT checked FROM albums WHERE title = 'Soulless Child'"));
        assertEquals(2, scalarLong("SELECT COUNT(*) FROM album_provider_links"));
        assertEquals("IT", scalarText("SELECT country FROM artist_provider_links WHERE id = 1"));
        assertEquals(1, scalarLong("SELECT active FROM artist_provider_links WHERE id = 1"));
        assertNotNull(scalarText("SELECT last_success_at FROM artist_provider_links WHERE id = 1"));
    }

    @Test
    void keepsTheFirstProviderDateAndReportsALaterProviderConflict() throws Exception {
        var existing = albums.create(1, "The Alliance of the Kings", null, true, null);
        service.reconcile(
                providerLink(),
                "IT",
                true,
                List.of(release("ma-alliance", "The Alliance of the Kings", "2010")),
                null);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO artist_provider_links (
                        id, artist_id, provider_id, provider_artist_id, provider_artist_name, provider_url
                    )
                    VALUES (
                        2, 1, 'musicbrainz', 'artist-mbid', 'Ancient Bards',
                        'https://musicbrainz.org/artist/artist-mbid'
                    )
                    """);
        }

        var result = service.reconcile(
                providerLinks.findByArtistAndProvider(1, "musicbrainz").orElseThrow(),
                "IT",
                true,
                List.of(new RemoteReleaseGroup(
                        "musicbrainz",
                        "mb-alliance",
                        "The Alliance of the Kings",
                        "2009",
                        "Album",
                        List.of(),
                        "https://musicbrainz.org/release-group/mb-alliance")),
                null);

        assertEquals(1, result.releaseDateConflictCount());
        assertEquals("2010", albums.find(existing.id()).orElseThrow().releaseDate());
        assertEquals(2, scalarLong("SELECT COUNT(*) FROM album_provider_links"));
    }

    @Test
    void rollsBackTheArtistProviderPairWhenOneReleaseFails() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_provider_release
                    BEFORE INSERT ON album_provider_links
                    WHEN NEW.provider_release_group_id = 'fail'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced reconciliation failure');
                    END
                    """);
        }

        assertThrows(IllegalStateException.class, () -> service.reconcile(
                providerLink(),
                "IT",
                true,
                List.of(
                        release("ma-alliance", "The Alliance of the Kings", "2010"),
                        release("fail", "Soulless Child", "2011")),
                null));

        assertEquals(0, scalarLong("SELECT COUNT(*) FROM albums"));
        assertEquals(0, scalarLong("SELECT COUNT(*) FROM album_provider_links"));
        assertNull(scalarText("SELECT country FROM artist_provider_links WHERE id = 1"));
        assertNull(scalarText("SELECT last_success_at FROM artist_provider_links WHERE id = 1"));
    }

    @Test
    void refreshesLinkedEvidenceAndUsesPostMergeCollectionState() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO collections (id, name, relative_path, type, parser)
                    VALUES
                        ('other', 'Other', 'OTHER', 'ARTIST', 'FLAT_ARTIST_YEAR_ALBUM'),
                        ('selected', 'Selected', 'SELECTED', 'ARTIST', 'FLAT_ARTIST_YEAR_ALBUM')
                    """);
        }
        var local = albums.create(1, "The Alliance of the Kings", "2010", true, null);
        var providerOnly = albums.create(1, "The Alliance of the Kings", "2010", false, null, "other");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO album_provider_links (
                        album_id, provider_id, provider_release_group_id,
                        provider_title, provider_release_date, provider_url
                    )
                    VALUES (
                        %d, 'metal_archives', 'ma-alliance',
                        'The Alliance of the Kings', '2009', 'https://old.example/alliance'
                    )
                    """.formatted(providerOnly.id()));
        }

        service.reconcile(
                providerLink(),
                "IT",
                true,
                List.of(release("ma-alliance", "The Alliance of the Kings", "2010")),
                "selected");

        assertEquals(1, scalarLong("SELECT COUNT(*) FROM albums"));
        assertEquals(local.id(), scalarLong("SELECT album_id FROM album_provider_links WHERE provider_release_group_id = 'ma-alliance'"));
        assertEquals("2010", scalarText("SELECT provider_release_date FROM album_provider_links WHERE provider_release_group_id = 'ma-alliance'"));
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM collection_albums WHERE collection_id = 'other'"));
        assertEquals(0, scalarLong("SELECT COUNT(*) FROM collection_albums WHERE collection_id = 'selected'"));
    }

    private ArtistProviderLink providerLink() {
        return providerLinks.findByArtistAndProvider(1, "metal_archives").orElseThrow();
    }

    private static RemoteReleaseGroup release(String id, String title, String releaseDate) {
        return new RemoteReleaseGroup(
                "metal_archives",
                id,
                title,
                releaseDate,
                "Album",
                List.of(),
                "https://www.metal-archives.com/albums/Ancient_Bards/" + id);
    }

    private long scalarLong(String sql) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String scalarText(String sql) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
