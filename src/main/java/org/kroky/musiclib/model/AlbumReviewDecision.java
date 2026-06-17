package org.kroky.musiclib.model;

public record AlbumReviewDecision(
        String providerId,
        String providerReleaseGroupId,
        String action,
        Long albumId) {
}
