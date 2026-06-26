package org.kroky.musiclib.model;

import java.util.List;

public record ProviderRefreshResult(
        long runId,
        long artistId,
        String artistName,
        String providerId,
        int foundReleaseGroupCount,
        int existingAlbumCount,
        int createdAlbumCount,
        int skippedCount,
        List<String> messages) {
}
