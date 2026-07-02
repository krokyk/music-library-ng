package org.kroky.musiclib.model;

public record ProviderCheckRun(
        long id,
        Long artistId,
        Long providerLinkId,
        String startedAt,
        String finishedAt,
        String status,
        int processedArtistCount,
        int foundAlbumCount,
        int newAlbumCount,
        int existingAlbumCount,
        int releaseDateConflictCount,
        int errorCount,
        String message) {
}
