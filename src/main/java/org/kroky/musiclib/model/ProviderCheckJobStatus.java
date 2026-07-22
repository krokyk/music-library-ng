package org.kroky.musiclib.model;

import java.util.List;

public record ProviderCheckJobStatus(
        String status,
        String kind,
        Long requestedCollectionId,
        String requestedCollectionName,
        Long requestedArtistId,
        String requestedArtistName,
        Long activeArtistId,
        String activeArtistName,
        int itemTotal,
        int itemProcessed,
        int skippedArtistCount,
        int foundAlbumCount,
        int newAlbumCount,
        int existingAlbumCount,
        int releaseYearConflictCount,
        int titleConflictCount,
        int errorCount,
        String message,
        List<Long> artistIds,
        List<ReportArtifact> reports) {
}
