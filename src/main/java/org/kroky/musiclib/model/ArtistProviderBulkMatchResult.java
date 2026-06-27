package org.kroky.musiclib.model;

import java.util.List;

public record ArtistProviderBulkMatchResult(
        int requestedArtistCount,
        int processedArtistCount,
        int matchedCount,
        int manualCount,
        int noMatchCount,
        int skippedCount,
        int errorCount,
        List<ArtistProviderBulkMatchItem> items,
        List<String> messages) {
}
