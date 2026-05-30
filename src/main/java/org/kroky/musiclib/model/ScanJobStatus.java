package org.kroky.musiclib.model;

public record ScanJobStatus(
        String id,
        String status,
        String requestedCollectionId,
        String activeCollectionId,
        int artistTotal,
        int artistProcessed,
        int parsedCount,
        int createdCount,
        int skippedCount,
        boolean cancelRequested,
        String message) {
}
