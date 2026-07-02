package org.kroky.musiclib.model;

public record ProviderReleaseDateConflictSource(
        long providerLinkId,
        String providerId,
        String providerTitle,
        String providerReleaseDate,
        String providerUrl) {
}
