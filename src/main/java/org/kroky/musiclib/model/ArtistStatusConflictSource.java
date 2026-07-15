package org.kroky.musiclib.model;

public record ArtistStatusConflictSource(
        long providerLinkId,
        String providerId,
        Boolean providerActive,
        String providerUrl) {
}
