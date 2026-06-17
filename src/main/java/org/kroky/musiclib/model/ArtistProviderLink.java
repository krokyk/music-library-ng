package org.kroky.musiclib.model;

public record ArtistProviderLink(
        long id,
        long artistId,
        String artistName,
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        boolean enabled,
        String lastCheckedAt,
        String lastSuccessAt,
        String lastErrorAt,
        String lastErrorMessage,
        String createdAt,
        String updatedAt) {
}
