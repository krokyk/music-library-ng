package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderCandidate;

class ArtistProviderBulkMatchServiceTest {

    @Test
    void acceptsStrongMatchWithMultipleLocalAlbums() {
        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Dark Tranquillity",
                candidate("Dark Tranquillity", 100, 86, List.of("Fiction", "Character")),
                candidate("Dark Tranquility", 92, 70, List.of("Fiction"))));
    }

    @Test
    void rejectsNameOnlyMatchWithoutAlbumEvidence() {
        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Dark Tranquillity",
                candidate("Dark Tranquillity", 100, 100, List.of()),
                null));
    }

    @Test
    void acceptsSingleAlbumOnlyWhenNameAndMarginAreStrong() {
        assertTrue(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Antti Martikainen",
                candidate("Antti Martikainen", 100, 78, List.of("Creation of the World")),
                candidate("Antti Martikainen Project", 90, 60, List.of("Creation of the World"))));
    }

    @Test
    void rejectsCandidateWhenRunnerUpIsTooClose() {
        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceMusicBrainzMatch(
                "Antti Martikainen",
                candidate("Antti Martikainen", 100, 86, List.of("Creation of the World", "Throne of the North")),
                candidate("Antti Martikainen", 98, 82, List.of("Creation of the World"))));
    }

    private static ArtistProviderCandidate candidate(String name, int providerScore, int matchScore,
            List<String> matchedLocalAlbums) {
        return new ArtistProviderCandidate(
                "musicbrainz",
                "36b891ab-5e89-4f17-bb91-f189764de5ff",
                name,
                "https://musicbrainz.org/artist/36b891ab-5e89-4f17-bb91-f189764de5ff",
                null,
                null,
                null,
                null,
                providerScore,
                matchScore,
                matchedLocalAlbums,
                List.of());
    }
}
