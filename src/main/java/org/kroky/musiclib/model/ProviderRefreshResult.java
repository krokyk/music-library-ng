package org.kroky.musiclib.model;

import java.util.List;

public record ProviderRefreshResult(
        long artistId,
        String artistName,
        String providerId,
        int foundReleaseGroupCount,
        int existingAlbumCount,
        int createdAlbumCount,
        int releaseDateConflictCount,
        int skippedCount,
        List<String> messages,
        List<ReportArtifact> reports) {
}
