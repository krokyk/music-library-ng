package org.kroky.musiclib.model;

public record ArtistProviderCandidateAlbum(
        String title,
        String providerReleaseDate,
        String providerUrl,
        Long localAlbumId,
        String localTitle,
        String localReleaseDate,
        boolean localOnDisk,
        boolean releaseDateConflict,
        String matchType,
        int titleScore,
        int evidenceStrength,
        String localEvidenceKind,
        boolean genericTitle) {
}
