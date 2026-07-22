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
        ArtistProviderCandidate runnerUp = candidate(
                "Dark Tranquility",
                92,
                evidence("Dark Tranquillity",
                        List.of(localAlbum(1, "Fiction", "2007")),
                        "Dark Tranquility",
                        92,
                        List.of(releaseGroup("Fiction", "2007"))));

        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Dark Tranquillity",
                top,
                runnerUp));
    }

    @Test
    void rejectsNameOnlyMatchWithoutAlbumEvidence() {
        ArtistProviderCandidate candidate = candidate(
                "Dark Tranquillity",
                100,
                evidence("Dark Tranquillity", List.of(), "Dark Tranquillity", 100, List.of()));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Dark Tranquillity",
                candidate,
                null));
    }

    @Test
    void acceptsSingleLocalExactAlbumOnlyWithAdditionalEvidenceAndStrongMargin() {
        ArtistProviderCandidate top = candidate(
                "Antti Martikainen",
                100,
                evidence("Antti Martikainen",
                        List.of(
                                localAlbum(1, "Creation of the World", "2012"),
                                checkedAlbum(2, "Throne of the North", "2016")),
                        "Antti Martikainen",
                        100,
                        List.of(
                                releaseGroup("Creation of the World", "2012"),
                                releaseGroup("Throne of the North", "2016"))));
        ArtistProviderCandidate runnerUp = candidate(
                "Antti Martikainen Project",
                90,
                evidence("Antti Martikainen",
                        List.of(localAlbum(1, "Creation of the World", "2012")),
                        "Antti Martikainen Project",
                        90,
                        List.of(releaseGroup("Creation of the World", "2012"))));

        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Antti Martikainen",
                top,
                runnerUp));
    }

    @Test
    void rejectsSingleLocalExactAlbumWithoutEnoughAlbumEvidence() {
        ArtistProviderCandidate candidate = candidate(
                "Antti Martikainen",
                100,
                evidence("Antti Martikainen",
                        List.of(localAlbum(1, "Creation of the World", "2012")),
                        "Antti Martikainen",
                        100,
                        List.of(releaseGroup("Creation of the World", "2012"))));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Antti Martikainen",
                candidate,
                null));
    }

    @Test
    void rejectsSingleFuzzyOnlyLocalAlbum() {
        ArtistProviderCandidate candidate = candidate(
                "Ancient Bards",
                100,
                evidence("Ancient Bards",
                        List.of(
                                localAlbum(1, "Soulless Child", "2011"),
                                checkedAlbum(2, "A New Religion", "2014"),
                                checkedAlbum(3, "The Alliance of the Kings", "2010")),
                        "Ancient Bards",
                        100,
                        List.of(
                                releaseGroup("Soulless Childe", "2011"),
                                releaseGroup("A New Religion?", "2014"),
                                releaseGroup("The Alliance of the Kings", "2010"))));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Ancient Bards",
                candidate,
                null));
    }

    @Test
    void rejectsCandidateWhenRunnerUpIsTooClose() {
        ArtistProviderCandidate top = candidate(
                "Antti Martikainen",
                100,
                evidence("Antti Martikainen",
                        List.of(
                                localAlbum(1, "Creation of the World", "2012"),
                                localAlbum(2, "Throne of the North", "2016")),
                        "Antti Martikainen",
                        100,
                        List.of(
                                releaseGroup("Creation of the World", "2012"),
                                releaseGroup("Throne of the North", "2016"))));
        ArtistProviderCandidate runnerUp = candidate(
                "Antti Martikainen",
                98,
                evidence("Antti Martikainen",
                        List.of(
                                localAlbum(1, "Creation of the World", "2012"),
                                localAlbum(2, "Throne of the North", "2016")),
                        "Antti Martikainen",
                        98,
                        List.of(
                                releaseGroup("Creation of the World", "2012"),
                                releaseGroup("Throne of the North", "2016"))));

        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Antti Martikainen",
                top,
                runnerUp));
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

    private static org.kroky.musiclib.model.Album checkedAlbum(long id, String title, String yearText) {
        return ArtistProviderMatchServiceTest.album(id, title, yearText, true, false);
    }

    private static RemoteReleaseGroup releaseGroup(String title, String yearText) {
        return ArtistProviderMatchServiceTest.releaseGroup(title, yearText);
    }
}
