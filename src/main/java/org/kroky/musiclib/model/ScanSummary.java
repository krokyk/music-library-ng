package org.kroky.musiclib.model;

import java.util.List;

public record ScanSummary(
        long runId,
        String sourceId,
        String status,
        int parsedCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        List<String> messages) {
}
