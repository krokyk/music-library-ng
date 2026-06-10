package org.kroky.musiclib.model;

import java.util.List;

public record Album(
        long id,
        List<Long> artistIds,
        List<AlbumCollection> collections,
        String artistName,
        String title,
        String releaseDate,
        String sortName,
        MetadataSource sortNameSource,
        boolean checked,
        boolean hasLocalPath,
        boolean onDisk,
        List<AlbumLocalPath> localPaths,
        String notes,
        String createdAt,
        String updatedAt) {
}
