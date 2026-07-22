package org.kroky.musiclib.model;

import java.util.List;

public record Album(
        long id,
        List<Long> artistIds,
        AlbumCollection collection,
        String artistName,
        String title,
        Integer releaseYear,
        String sortName,
        MetadataSource sortNameSource,
        boolean checked,
        String localRelativePath,
        String resolvedPath,
        boolean onDisk,
        List<AlbumProviderLink> providerLinks,
        String notes,
        String createdAt,
        String updatedAt) {
}
