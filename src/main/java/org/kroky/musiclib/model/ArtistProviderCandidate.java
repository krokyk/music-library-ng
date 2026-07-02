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
        List<String> matchedLocalAlbums,
        List<RemoteReleaseGroup> releaseGroups,
        List<ArtistProviderCandidateAlbum> albums) {
}
