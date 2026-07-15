package org.kroky.musiclib.model;

public record ArtistCountryConflictSource(
        long providerLinkId,
        String providerId,
        String providerCountry,
        String providerUrl) {
}
