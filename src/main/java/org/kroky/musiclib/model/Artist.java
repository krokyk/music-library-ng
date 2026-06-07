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
        String createdAt,
        String updatedAt) {
}
