package org.kroky.musiclib.model;

public record Album(
        long id,
        long artistId,
        String artistName,
        String title,
        Integer releaseYear,
        AlbumStatus status,
        String relativePath,
        String resolvedPath,
        String sourceId,
        String sourceName,
        String createdAt,
        String updatedAt) {
}
