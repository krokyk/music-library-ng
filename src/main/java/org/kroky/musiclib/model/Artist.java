package org.kroky.musiclib.model;

import java.util.List;

public record Artist(
        long id,
        String name,
        String sortName,
        String notes,
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
        String providerArtistType,
        String providerArtistCountry,
        String providerArtistDisambiguation,
        Boolean providerArtistActive,
        String createdAt,
        String updatedAt) {
}
