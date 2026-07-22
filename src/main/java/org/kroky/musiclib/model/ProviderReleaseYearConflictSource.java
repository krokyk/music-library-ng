package org.kroky.musiclib.model;

public record ProviderReleaseYearConflictSource(
        long providerLinkId,
        String providerId,
        String providerTitle,
        Integer providerReleaseYear,
        String providerUrl) {
}
