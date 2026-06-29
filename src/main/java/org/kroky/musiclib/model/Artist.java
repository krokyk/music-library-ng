package org.kroky.musiclib.model;

import java.util.List;

public record Artist(
        long id,
        String name,
        String sortName,
        String countryOverride,
        Boolean activeOverride,
        List<String> collectionIds,
        List<String> localCollectionIds,
        int albumCount,
        int checkedAlbumCount,
        int uncheckedAlbumCount,
        int localAlbumCount,
        int providerLinkCount,
        String providerId,
        String providerArtistId,
        String providerArtistName,
        String providerUrl,
        String providerCountry,
        String providerDisambiguation,
        Boolean providerActive,
        String providerLastErrorMessage,
        int collectionAlbumCount,
        String localScanErrorMessage,
        String createdAt,
        String updatedAt) {
}
