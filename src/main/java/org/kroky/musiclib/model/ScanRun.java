package org.kroky.musiclib.model;

public record ScanRun(
        long id,
        String collectionId,
        String collectionName,
        String startedAt,
        String finishedAt,
        String status,
        int parsedCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        String message) {
}
