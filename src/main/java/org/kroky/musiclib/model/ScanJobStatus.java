package org.kroky.musiclib.model;

import java.util.List;

public record ScanJobStatus(
        String id,
        String status,
        String kind,
        String requestedCollectionId,
        String requestedCollectionName,
        Long requestedArtistId,
        String activeCollectionId,
        String activeCollectionName,
        int itemTotal,
        int itemProcessed,
        int parsedCount,
        int createdCount,
        int skippedCount,
        boolean cancelRequested,
        String message,
        List<Long> runIds) {
}
