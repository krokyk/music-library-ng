package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistRepository;

class ArtistProviderBulkMatchServiceTest {

    @Test
    void preservesArtistNameAndRootCauseWhenProviderLinkingFails() {
        try (var fixture = new BulkFixture()) {
            fixture.links.failure = new IllegalStateException("Unable to save provider link",
                    new SQLException("Database unavailable"));

            var result = fixture.service.matchProviderArtists("musicbrainz", List.of(311L));
            var item = result.items().get(0);

            assertEquals(1, result.errorCount());
            assertEquals(311L, item.artistId());
            assertEquals("Rhapsody", item.artistName());
            assertEquals(ArtistProviderBulkMatchService.STATUS_ERROR, item.status());
            assertTrue(item.message().contains("Unable to save provider link"));
            assertTrue(item.message().contains("SQLException: Database unavailable"));
        }
    }

    @Test
    void requiresExplicitReviewWhenProviderIdentityBelongsToAnotherArtist() {
        try (var fixture = new BulkFixture()) {
            fixture.links.owner = Optional.of(new ArtistProviderLink(
                    1L, 312L, "Rhapsody of Fire", "musicbrainz",
                    fixture.matches.candidates.get(0).providerArtistId(), "Rhapsody of Fire", "IT", null, true,
                    fixture.matches.candidates.get(0).providerUrl(), true, null, null, null, null, null));

            var result = fixture.service.matchProviderArtists("musicbrainz", List.of(311L));
            var item = result.items().get(0);

            assertEquals(1, result.manualCount());
            assertEquals(0, result.errorCount());
            assertEquals("Rhapsody", item.artistName());
            assertEquals(ArtistProviderBulkMatchService.STATUS_NEEDS_MANUAL, item.status());
            assertTrue(item.message().contains("already linked to Rhapsody of Fire"));
            assertEquals(312L, item.providerLink().artistId());
            assertNull(item.acceptedCandidate());
            assertEquals(fixture.matches.candidates.get(0).providerArtistId(), item.candidates().get(0).providerArtistId());
            assertEquals(0, fixture.links.upsertCalls);

            fixture.matches.candidates = List.of(candidate("Rhapsody", 100,
                    evidence("Rhapsody", List.of(), "Rhapsody", 100, List.of())));
            var manualResult = fixture.service.matchProviderArtists("musicbrainz", List.of(311L));
            assertEquals(312L, manualResult.items().get(0).providerLink().artistId());
            assertEquals(0, fixture.links.upsertCalls);
        }
    }

    @Test
    void processesSharedIdentityOnceUsingTheFirstRequestedCreditedName() {
        try (var fixture = new BulkFixture()) {
            fixture.sharedIdentity = true;
            fixture.matches.candidates = List.of();

            var result = fixture.service.matchProviderArtists("musicbrainz", List.of(312L, 311L, 312L));

            assertEquals(1, result.requestedArtistCount());
            assertEquals(1, result.processedArtistCount());
            assertEquals(1, fixture.matches.searchCalls);
            assertEquals(312L, result.items().get(0).artistId());
            assertEquals("Rhapsody of Fire", result.items().get(0).artistName());
        }
    }

    @Test
    void acceptsStrongMatchWithMultipleLocalAlbums() {
        ArtistProviderCandidate top = candidate(
                "Dark Tranquillity",
                100,
                evidence("Dark Tranquillity",
                        List.of(
                                localAlbum(1, "Fiction", "2007"),
                                localAlbum(2, "Character", "2005")),
                        "Dark Tranquillity",
                        100,
                        List.of(
                                releaseGroup("Fiction", "2007"),
                                releaseGroup("Character", "2005"))));
        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(top));
    }

    @Test
    void rejectsNameOnlyMatchWithoutAlbumEvidence() {
        ArtistProviderCandidate candidate = candidate(
                "Dark Tranquillity",
                100,
                evidence("Dark Tranquillity", List.of(), "Dark Tranquillity", 100, List.of()));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void acceptsSingleLocalExactAlbum() {
        ArtistProviderCandidate candidate = candidate(
                "Antti Martikainen",
                100,
                evidence("Antti Martikainen",
                        List.of(localAlbum(1, "Creation of the World", "2012")),
                        "Antti Martikainen",
                        100,
                        List.of(releaseGroup("Creation of the World", "2012"))));

        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void acceptsSingleHighConfidenceFuzzyLocalAlbum() {
        ArtistProviderCandidate candidate = candidate(
                "Ancient Bards",
                100,
                evidence("Ancient Bards",
                        List.of(localAlbum(1, "Soulless Child", "2011")),
                        "Ancient Bards",
                        100,
                        List.of(releaseGroup("Soulless Childe", "2011"))));

        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void rejectsLocalAlbumWithIncompatibleYear() {
        ArtistProviderCandidate candidate = candidate(
                "Ancient Bards",
                100,
                evidence("Ancient Bards",
                        List.of(localAlbum(1, "Soulless Child", "2011")),
                        "Ancient Bards",
                        100,
                        List.of(releaseGroup("Soulless Childe", "2014"))));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void rejectsWeakArtistNameMatch() {
        ArtistProviderCandidate candidate = candidate(
                "Completely Different",
                100,
                evidence("Antti Martikainen",
                        List.of(localAlbum(1, "Creation of the World", "2012")),
                        "Completely Different",
                        100,
                        List.of(releaseGroup("Creation of the World", "2012"))));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    static ArtistProviderCandidate candidate(String name, int providerScore,
            ProviderCandidateEvidenceEvaluator.CandidateEvidence evidence) {
        return new ArtistProviderCandidate(
                "musicbrainz",
                "36b891ab-5e89-4f17-bb91-f189764de5ff",
                name,
                "https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff",
                null,
                null,
                null,
                providerScore,
                evidence.finalScore(),
                evidence.nameScore(),
                evidence.albumEvidenceScore(),
                evidence.yearBonus(),
                evidence.evidenceSummary(),
                evidence.albumEvidence());
    }

    private static ProviderCandidateEvidenceEvaluator.CandidateEvidence evidence(
            String localArtistName,
            List<org.kroky.musiclib.model.Album> albums,
            String providerArtistName,
            int providerScore,
            List<RemoteReleaseGroup> releaseGroups) {
        return ProviderCandidateEvidenceEvaluator.evaluate(
                localArtistName,
                albums,
                providerArtistName,
                providerScore,
                List.of(),
                releaseGroups);
    }

    private static org.kroky.musiclib.model.Album localAlbum(long id, String title, String yearText) {
        return ArtistProviderMatchServiceTest.album(id, title, yearText, true, true);
    }

    private static RemoteReleaseGroup releaseGroup(String title, String yearText) {
        return ArtistProviderMatchServiceTest.releaseGroup(title, yearText);
    }

    private static class BulkFixture implements AutoCloseable {
        final ArtistProviderBulkMatchService service = new ArtistProviderBulkMatchService();
        final StubLinks links = new StubLinks();
        final StubMatches matches = new StubMatches();
        boolean sharedIdentity;

        BulkFixture() {
            service.artists = new ArtistRepository() {
                @Override
                public Optional<Artist> find(long id) {
                    String name = id == 311L ? "Rhapsody" : "Rhapsody of Fire";
                    long canonicalId = canonicalArtistId(id);
                    return Optional.of(new Artist(id, name, null,
                            null, null, List.of(1L), 1, 0, 1, null, null, List.of(),
                            canonicalId, canonicalId == 311L ? "Rhapsody" : "Rhapsody of Fire",
                            sharedIdentity ? List.of(311L, 312L) : List.of(id),
                            sharedIdentity ? List.of("Rhapsody", "Rhapsody of Fire") : List.of(name)));
                }

                @Override
                public long canonicalArtistId(long id) {
                    return sharedIdentity && id == 312L ? 311L : id;
                }
            };
            service.albums = new AlbumRepository() {
                @Override
                public Long majorArtistCollection(long artistId) {
                    return 1L;
                }
            };
            service.providerLinks = links;
            service.matches = matches;
        }

        @Override
        public void close() {
            matches.shutdown();
        }
    }

    private static class StubLinks extends ArtistProviderLinkRepository {
        Optional<ArtistProviderLink> owner = Optional.empty();
        RuntimeException failure;
        int upsertCalls;

        @Override
        public Optional<ArtistProviderLink> findByArtistAndProvider(long artistId, String providerId) {
            return Optional.empty();
        }

        @Override
        public Optional<ArtistProviderLink> findByProviderIdentity(String providerId, String providerArtistId) {
            return owner;
        }

        @Override
        public ArtistProviderLink upsertForArtist(long artistId, String providerId, String providerArtistId,
                String providerArtistName, String providerUrl, String country,
                String disambiguation, Boolean active, boolean enabled) {
            upsertCalls++;
            if (failure != null) {
                throw failure;
            }
            throw new AssertionError("This test must not create a provider link.");
        }
    }

    private static class StubMatches extends ArtistProviderMatchService {
        List<ArtistProviderCandidate> candidates = List.of(candidate("Rhapsody", 100,
                evidence("Rhapsody", List.of(localAlbum(1, "Legendary Tales", "1997")),
                        "Rhapsody", 100, List.of(releaseGroup("Legendary Tales", "1997")))));
        int searchCalls;

        @Override
        public List<ArtistProviderCandidate> searchBulkCandidates(long artistId, String providerId) {
            searchCalls++;
            return candidates;
        }

        @Override
        public ArtistProviderCandidate enrichSelectedCandidate(ArtistProviderCandidate candidate) {
            return candidate;
        }
    }
}
