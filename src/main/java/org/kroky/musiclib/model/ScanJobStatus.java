package org.kroky.musiclib.model;

public record ScanJobStatus(
        String id,
        String status,
        String kind,
        String requestedCollectionId,
        Long requestedArtistId,
        String activeCollectionId,
        int itemTotal,
        int itemProcessed,
        int parsedCount,
        int createdCount,
        int skippedCount,
        boolean cancelRequested,
        String message) {
}
