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

    private static final long OLD_COLLECTION_ID = 1;
    private static final long SELECTED_COLLECTION_ID = 2;
    private static final long TITLE_COLLECTION_ID = 3;

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
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES(1,'Old','TEST-OLD','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES(2,'Selected','TEST-NEW','ARTIST')");
            statement.executeUpdate("INSERT INTO collections(id,name,relative_path,type) VALUES(3,'Titles','TEST-TITLES','TITLE')");
            statement.executeUpdate("INSERT INTO artists(id,name,normalized_name) VALUES(1,'Ancient Bards','ancient bards')");
            statement.executeUpdate("INSERT INTO artist_provider_links(id,artist_id,provider_id,provider_artist_name,provider_url) VALUES(1,1,'metal_archives','Ancient Bards','https://www.metal-archives.com/bands/Ancient_Bards/1')");
        }
    }

    @Test
    void assignsNewAlbumsToSelectedHomeAndKeepsIndependentYearConflict() {
        var existing = albums.create(1, "The Alliance of the Kings", 2010, true, null, OLD_COLLECTION_ID);

        var result = service.reconcile(providerLink(), "IT", true, List.of(
                release("ma-alliance", "The Alliance of the Kings", 2009),
                release("ma-soulless", "Soulless Child", 2011)), SELECTED_COLLECTION_ID);

        assertEquals(1, result.createdAlbumCount());
        assertEquals(1, result.releaseYearConflictCount());
        assertEquals(2010, albums.find(existing.id()).orElseThrow().releaseYear());
        var created = albums.list(1L, SELECTED_COLLECTION_ID, null, null, "Soulless Child").get(0);
        assertFalseChecked(created.checked());
        assertEquals(SELECTED_COLLECTION_ID, created.collection().id());
        assertEquals(2, scalarLong("SELECT count(*) FROM album_provider_links"));
        assertEquals("IT", scalarText("SELECT country FROM artist_provider_links WHERE id=1"));
    }

    @Test
    void relinksAndMergesProviderOnlyDuplicateIntoKnownAlbum() {
        var known = albums.create(1, "The Alliance of the Kings", 2010, true, null, OLD_COLLECTION_ID);
        var duplicate = albums.create(1, "The Alliance of the Kings", 2010, false, null, SELECTED_COLLECTION_ID);
        albumProviderLinks.linkAlbum(duplicate.id(), "metal_archives", "ma-alliance",
                "The Alliance of the Kings", 2010, "https://example.test/alliance");

        service.reconcile(providerLink(), "IT", true,
                List.of(release("ma-alliance", "The Alliance of the Kings", 2010)), SELECTED_COLLECTION_ID);

        assertTrue(albums.find(duplicate.id()).isEmpty());
        assertEquals(known.id(), albumProviderLinks.findAlbumId("metal_archives", "ma-alliance").orElseThrow());
        assertEquals(1, albums.list(1L, null, null, null, "The Alliance of the Kings").size());
        assertEquals(OLD_COLLECTION_ID, albums.find(known.id()).orElseThrow().collection().id());
    }

    @Test
    void keepsFuzzyDifferentYearReleaseSeparateAndReusesItAcrossProviders() throws Exception {
        var origins = albums.create(1, "Origins", 2016, true, null, OLD_COLLECTION_ID);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO artist_provider_links(id,artist_id,provider_id,provider_artist_name,provider_url) VALUES(2,1,'musicbrainz','Ancient Bards','https://musicbrainz.org/artist/1')");
        }

        service.reconcile(providerLink("musicbrainz"), "IT", true,
                List.of(release("musicbrainz", "mb-origins-ii", "Origins II", 2025)), SELECTED_COLLECTION_ID);
        service.reconcile(providerLink("metal_archives"), "IT", true,
                List.of(release("metal_archives", "ma-origins-ii", "Origins II", 2025)), SELECTED_COLLECTION_ID);

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
        albums.create(1, "Anchor", 2000, true, null, OLD_COLLECTION_ID);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_provider_release
                    BEFORE INSERT ON album_provider_links
                    WHEN NEW.provider_release_group_id = 'fail'
                    BEGIN SELECT RAISE(ABORT, 'forced reconciliation failure'); END
                    """);
        }

        assertThrows(RuntimeException.class, () -> service.reconcile(providerLink(), "IT", true, List.of(
                release("first", "First", 2001), release("fail", "Second", 2002)), SELECTED_COLLECTION_ID));

        assertEquals(1, scalarLong("SELECT count(*) FROM albums"));
        assertEquals(0, scalarLong("SELECT count(*) FROM album_provider_links"));
        assertNull(scalarText("SELECT country FROM artist_provider_links WHERE id=1"));
        assertNull(scalarText("SELECT last_success_at FROM artist_provider_links WHERE id=1"));
    }

    @Test
    void explicitTitleCollectionCannotReceiveProviderAlbums() {
        albums.create(1, "Anchor", 2000, true, null, OLD_COLLECTION_ID);

        assertThrows(IllegalArgumentException.class, () -> service.reconcile(providerLink(), "IT", true,
                List.of(release("new", "New", 2001)), TITLE_COLLECTION_ID));

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
