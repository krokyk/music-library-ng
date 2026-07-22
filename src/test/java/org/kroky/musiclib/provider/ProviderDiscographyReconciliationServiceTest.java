package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ProviderDiscographyReconciliationServiceTest {

    @Inject ProviderDiscographyReconciliationService service;
    @Inject ArtistProviderLinkRepository providerLinks;
    @Inject AlbumProviderLinkRepository albumProviderLinks;
    @Inject AlbumRepository albums;
    @Inject DataSource dataSource;

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS fail_provider_release");
            statement.executeUpdate("DELETE FROM album_provider_links");
            statement.executeUpdate("DELETE FROM artist_provider_links");
            statement.executeUpdate("DELETE FROM albums");
            statement.executeUpdate("DELETE FROM artists");
            statement.executeUpdate("DELETE FROM collections");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('old','Old','TEST-OLD','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('selected','Selected','TEST-NEW','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES('titles','Titles','TEST-TITLES','TITLE')");
            statement.executeUpdate("INSERT INTO artists(id,name,normalized_name) VALUES(1,'Ancient Bards','ancient bards')");
            statement.executeUpdate("INSERT INTO artist_provider_links(id,artist_id,provider_id,provider_artist_name,provider_url) VALUES(1,1,'metal_archives','Ancient Bards','https://www.metal-archives.com/bands/Ancient_Bards/1')");
        }
    }

    @Test
    void assignsNewAlbumsToSelectedHomeAndKeepsIndependentYearConflict() {
        var existing = albums.create(1, "The Alliance of the Kings", 2010, true, null, "old");

        var result = service.reconcile(providerLink(), "IT", true, List.of(
                release("ma-alliance", "The Alliance of the Kings", 2009),
                release("ma-soulless", "Soulless Child", 2011)), "selected");

        assertEquals(1, result.createdAlbumCount());
        assertEquals(1, result.releaseYearConflictCount());
        assertEquals(2010, albums.find(existing.id()).orElseThrow().releaseYear());
        var created = albums.list(1L, "selected", null, null, "Soulless Child").get(0);
        assertFalseChecked(created.checked());
        assertEquals("selected", created.collection().id());
        assertEquals(2, scalarLong("SELECT count(*) FROM album_provider_links"));
        assertEquals("IT", scalarText("SELECT country FROM artist_provider_links WHERE id=1"));
    }

    @Test
    void relinksAndMergesProviderOnlyDuplicateIntoKnownAlbum() {
        var known = albums.create(1, "The Alliance of the Kings", 2010, true, null, "old");
        var duplicate = albums.create(1, "The Alliance of the Kings", 2010, false, null, "selected");
        albumProviderLinks.linkAlbum(duplicate.id(), "metal_archives", "ma-alliance",
                "The Alliance of the Kings", 2010, "https://example.test/alliance");

        service.reconcile(providerLink(), "IT", true,
                List.of(release("ma-alliance", "The Alliance of the Kings", 2010)), "selected");

        assertTrue(albums.find(duplicate.id()).isEmpty());
        assertEquals(known.id(), albumProviderLinks.findAlbumId("metal_archives", "ma-alliance").orElseThrow());
        assertEquals(1, albums.list(1L, null, null, null, "The Alliance of the Kings").size());
        assertEquals("old", albums.find(known.id()).orElseThrow().collection().id());
    }

    @Test
    void keepsFuzzyDifferentYearReleaseSeparateAndReusesItAcrossProviders() throws Exception {
        var origins = albums.create(1, "Origins", 2016, true, null, "old");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO artist_provider_links(id,artist_id,provider_id,provider_artist_name,provider_url) VALUES(2,1,'musicbrainz','Ancient Bards','https://musicbrainz.org/artist/1')");
        }

        service.reconcile(providerLink("musicbrainz"), "IT", true,
                List.of(release("musicbrainz", "mb-origins-ii", "Origins II", 2025)), "selected");
        service.reconcile(providerLink("metal_archives"), "IT", true,
                List.of(release("metal_archives", "ma-origins-ii", "Origins II", 2025)), "selected");

        var artistAlbums = albums.list(1L, null, null, null, null);
        assertEquals(2, artistAlbums.size());
        assertEquals(origins.id(), albums.list(1L, null, null, null, "Origins").stream()
                .filter(album -> album.title().equals("Origins"))
                .findFirst().orElseThrow().id());
        var originsTwo = artistAlbums.stream()
                .filter(album -> album.title().equals("Origins II"))
                .findFirst().orElseThrow();
        assertEquals(2025, originsTwo.releaseYear());
        assertEquals(2, originsTwo.providerLinks().size());
    }

    @Test
    void wholeDiscographyFailureRollsBackAlbumsLinksAndProviderMetadata() throws Exception {
        albums.create(1, "Anchor", 2000, true, null, "old");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_provider_release
                    BEFORE INSERT ON album_provider_links
                    WHEN NEW.provider_release_group_id = 'fail'
                    BEGIN SELECT RAISE(ABORT, 'forced reconciliation failure'); END
                    """);
        }

        assertThrows(RuntimeException.class, () -> service.reconcile(providerLink(), "IT", true, List.of(
                release("first", "First", 2001), release("fail", "Second", 2002)), "selected"));

        assertEquals(1, scalarLong("SELECT count(*) FROM albums"));
        assertEquals(0, scalarLong("SELECT count(*) FROM album_provider_links"));
        assertNull(scalarText("SELECT country FROM artist_provider_links WHERE id=1"));
        assertNull(scalarText("SELECT last_success_at FROM artist_provider_links WHERE id=1"));
    }

    @Test
    void explicitTitleCollectionCannotReceiveProviderAlbums() {
        albums.create(1, "Anchor", 2000, true, null, "old");

        assertThrows(IllegalArgumentException.class, () -> service.reconcile(providerLink(), "IT", true,
                List.of(release("new", "New", 2001)), "titles"));

        assertEquals(1, scalarLong("SELECT count(*) FROM albums"));
        assertEquals(0, scalarLong("SELECT count(*) FROM album_provider_links"));
    }

    private ArtistProviderLink providerLink() {
        return providerLink("metal_archives");
    }

    private ArtistProviderLink providerLink(String providerId) {
        return providerLinks.findByArtistAndProvider(1, providerId).orElseThrow();
    }

    private static RemoteReleaseGroup release(String id, String title, Integer releaseYear) {
        return release("metal_archives", id, title, releaseYear);
    }

    private static RemoteReleaseGroup release(String providerId, String id, String title, Integer releaseYear) {
        return new RemoteReleaseGroup(providerId, id, title, releaseYear, "Album", List.of(),
                "https://www.metal-archives.com/albums/Ancient_Bards/" + id);
    }

    private long scalarLong(String sql) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String scalarText(String sql) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertFalseChecked(boolean checked) { assertEquals(false, checked); }
}
