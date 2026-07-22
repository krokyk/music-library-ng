package org.kroky.musiclib.model;

import java.util.List;

public record ProviderCheckSummary(
        int processedArtistCount,
        int skippedArtistCount,
        int foundAlbumCount,
        int newAlbumCount,
        int existingAlbumCount,
        int releaseYearConflictCount,
        int titleConflictCount,
        int errorCount,
        List<String> messages,
        List<ReportArtifact> reports) {
}
