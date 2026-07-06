package org.kroky.musiclib.model;

import java.util.List;

public record ArtistProviderCandidate(
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        String country,
        String disambiguation,
        Boolean active,
        int providerScore,
        int matchScore,
        int finalScore,
        int nameScore,
        int albumEvidenceScore,
        int yearBonus,
        String evidenceSummary,
        List<String> matchedLocalAlbums,
        List<ArtistProviderCandidateAlbum> albumEvidence,
        List<RemoteReleaseGroup> releaseGroups,
        List<ArtistProviderCandidateAlbum> albums) {
}
