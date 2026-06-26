package org.kroky.musiclib.model;

import java.util.List;

public record ProviderCheckJobStatus(
        String id,
        String status,
        String kind,
        String requestedCollectionId,
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
        int errorCount,
        boolean cancelRequested,
        String message,
        List<Long> artistIds,
        List<Long> runIds) {
}
