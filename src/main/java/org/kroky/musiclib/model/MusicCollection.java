package org.kroky.musiclib.model;

public record MusicCollection(
        String id,
        String name,
        String relativePath,
        String resolvedPath,
        boolean exists,
        ParserType parser,
        String lastScanAt,
        String lastScanStatus,
        String lastScanMessage) {
}
