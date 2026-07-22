package org.kroky.musiclib.model;

public record MusicCollection(
        long id,
        String name,
        String relativePath,
        String resolvedPath,
        boolean exists,
        CollectionType type,
        String lastScanAt,
        String lastScanStatus,
        String lastScanMessage) {
}
