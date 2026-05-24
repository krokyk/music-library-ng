package org.kroky.musiclib.model;

public record AlbumLocalPath(
        long id,
        long albumId,
        String collectionId,
        String collectionName,
        String relativePath,
        String resolvedPath,
        boolean onDisk,
        String firstSeenAt,
        String lastSeenAt,
        String missingSince) {
}
