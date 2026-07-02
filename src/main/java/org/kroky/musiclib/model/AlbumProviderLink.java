package org.kroky.musiclib.model;

public record AlbumProviderLink(
        long id,
        long albumId,
        String providerId,
        String providerReleaseGroupId,
        String providerTitle,
        String providerReleaseDate,
        String providerUrl,
        String releaseDateResolution,
        boolean releaseDateConflict,
        String createdAt,
        String updatedAt) {
}
