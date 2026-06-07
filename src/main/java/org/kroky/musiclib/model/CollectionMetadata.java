package org.kroky.musiclib.model;

public record CollectionMetadata(
        String collectionId,
        int artistCount,
        int contributorArtistCount,
        int localAlbumCount,
        int knownAlbumCount,
        int uncheckedAlbumCount,
        int checkedAlbumCount) {
}
