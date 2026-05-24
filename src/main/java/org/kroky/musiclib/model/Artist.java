package org.kroky.musiclib.model;

public record Artist(
        long id,
        String name,
        String sortName,
        String notes,
        int albumCount,
        int checkedAlbumCount,
        int uncheckedAlbumCount,
        int localAlbumCount,
        int providerLinkCount,
        String createdAt,
        String updatedAt) {
}
