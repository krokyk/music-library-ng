package org.kroky.musiclib.model;

import java.util.List;

public record ScanJobStatus(
        String status,
        String kind,
        String requestedCollectionId,
        String requestedCollectionName,
        Long requestedArtistId,
        String requestedArtistName,
        String activeCollectionId,
        String activeCollectionName,
        Long activeArtistId,
        String activeArtistName,
        List<Long> processedArtistIds,
        int itemTotal,
        int itemProcessed,
        int artistCount,
        int parsedCount,
        int createdCount,
        int skippedCount,
        boolean cancelRequested,
        String message,
        List<ReportArtifact> reports) {
}
