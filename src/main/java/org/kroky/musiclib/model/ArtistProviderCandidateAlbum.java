package org.kroky.musiclib.model;

public record ArtistProviderCandidateAlbum(
        String title,
        Integer providerReleaseYear,
        String providerUrl,
        Long localAlbumId,
        String localTitle,
        Integer localReleaseYear,
        boolean localOnDisk,
        boolean releaseYearConflict,
        String matchType,
        int titleScore,
        int evidenceStrength,
        String localEvidenceKind,
        boolean genericTitle) {
}
