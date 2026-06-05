package org.kroky.musiclib.model;

public record CollectionTitleItem(
        long id,
        String collectionId,
        String rawFolderName,
        String relativePath,
        String title,
        String artistName,
        String releaseDate,
        String sortName,
        MetadataSource sortNameSource,
        ParseStatus parseStatus,
        String firstSeenAt,
        String lastSeenAt,
        String missingSince,
        String createdAt,
        String updatedAt) {
}
