package org.kroky.musiclib.model;

import java.util.List;

public record ArtistProviderBulkMatchItem(
        long artistId,
        String artistName,
        String status,
        String message,
        ArtistProviderLink providerLink,
        ArtistProviderCandidate acceptedCandidate,
        List<ArtistProviderCandidate> candidates) {
}
