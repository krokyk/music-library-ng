package org.kroky.musiclib.model;

public record ArtistProviderCandidateAlbum(
        String title,
        String providerReleaseDate,
        String providerUrl,
        Long localAlbumId,
        String localReleaseDate,
        boolean localOnDisk,
        boolean releaseDateConflict) {
}
