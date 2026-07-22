package org.kroky.musiclib.model;

public record CollectionMetadata(
        long collectionId,
        int artistCount,
        int contributorArtistCount,
        int localAlbumCount,
        int knownAlbumCount,
        int uncheckedAlbumCount,
        int checkedAlbumCount) {
}
