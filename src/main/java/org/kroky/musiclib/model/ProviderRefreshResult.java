package org.kroky.musiclib.model;

import java.util.List;

public record ProviderRefreshResult(
        long runId,
        long artistId,
        String artistName,
        String providerId,
        int foundReleaseGroupCount,
        int linkedExistingCount,
        int createdAlbumCount,
        int reviewRequiredCount,
        int skippedCount,
        List<AlbumImportCandidate> reviewCandidates,
        List<String> messages) {
}
