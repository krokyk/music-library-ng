package org.kroky.musiclib.model;

public record MusicSource(
        String id,
        String name,
        String relativePath,
        String resolvedPath,
        boolean exists,
        ParserType parser,
        boolean enabled,
        String lastScanAt,
        String lastScanStatus,
        String lastScanMessage) {
}
