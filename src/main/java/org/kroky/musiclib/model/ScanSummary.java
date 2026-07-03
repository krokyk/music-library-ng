package org.kroky.musiclib.model;

import java.util.List;

public record ScanSummary(
        String collectionId,
        String status,
        int artistCount,
        int parsedCount,
        int createdCount,
        int updatedCount,
        int missingCount,
        int skippedCount,
        List<String> messages,
        List<ReportArtifact> reports) {
}
