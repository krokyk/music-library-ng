package org.kroky.musiclib.model;

import java.util.List;

public record Album(
        long id,
        long artistId,
        String artistName,
        String title,
        Integer releaseYear,
        String releaseDate,
        boolean checked,
        boolean hasLocalPath,
        boolean onDisk,
        List<AlbumLocalPath> localPaths,
        String notes,
        String createdAt,
        String updatedAt) {
}
