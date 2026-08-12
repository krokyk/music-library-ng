package org.kroky.musiclib.model;

public record ArtistProviderBulkMatchJobStatus(
        String status,
        String kind,
        String providerId,
        String providerName,
        Long activeArtistId,
        String activeArtistName,
        int itemTotal,
        int itemProcessed,
        int matchedCount,
        int manualCount,
        int noMatchCount,
        int skippedCount,
        int errorCount,
        boolean cancelRequested,
        String message,
        ArtistProviderBulkMatchResult result) {
}
