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
        String titleResolution,
        boolean releaseDateConflict,
        boolean titleConflict,
        String createdAt,
        String updatedAt) {
}
