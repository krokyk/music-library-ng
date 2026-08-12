package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.RemoteReleaseGroup;

class ArtistProviderBulkMatchServiceTest {

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
}
