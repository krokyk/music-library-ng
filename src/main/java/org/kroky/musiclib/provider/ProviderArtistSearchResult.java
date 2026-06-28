package org.kroky.musiclib.provider;

public record ProviderArtistSearchResult(
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        String type,
        String country,
        String disambiguation,
        Boolean active,
        int providerScore) {
}
