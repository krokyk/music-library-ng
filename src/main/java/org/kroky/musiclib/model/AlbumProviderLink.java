package org.kroky.musiclib.model;

public record AlbumProviderLink(
        long id,
        long albumId,
        String providerId,
        String providerReleaseGroupId,
        String providerTitle,
        Integer providerReleaseYear,
        String providerUrl,
        String releaseYearResolution,
        String titleResolution,
        boolean releaseYearConflict,
        boolean titleConflict,
        String createdAt,
        String updatedAt) {
}
