package org.kroky.musiclib.model;

import java.util.List;

public record ProviderCheckSummary(
        long runId,
        int processedArtistCount,
        int skippedArtistCount,
        int foundAlbumCount,
        int newAlbumCount,
        int existingAlbumCount,
        int errorCount,
        List<String> messages) {
}
