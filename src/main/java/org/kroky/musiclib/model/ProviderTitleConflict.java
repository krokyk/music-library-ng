package org.kroky.musiclib.model;

import java.util.List;

public record ProviderTitleConflict(
        long albumId,
        long providerLinkId,
        long artistId,
        String artistName,
        String albumTitle,
        Integer localReleaseYear,
        String providerTitle,
        Integer providerReleaseYear,
        String providerId,
        String providerUrl,
        String localRelativePath,
        List<ProviderTitleConflictSource> sources) {
}
