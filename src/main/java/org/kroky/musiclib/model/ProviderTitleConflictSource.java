package org.kroky.musiclib.model;

public record ProviderTitleConflictSource(
        long providerLinkId,
        String providerId,
        String providerTitle,
        Integer providerReleaseYear,
        String providerUrl) {
}
