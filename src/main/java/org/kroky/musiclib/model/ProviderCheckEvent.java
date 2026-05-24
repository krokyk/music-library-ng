package org.kroky.musiclib.model;

public record ProviderCheckEvent(
        long id,
        long runId,
        Long artistId,
        Long providerLinkId,
        String level,
        String message,
        String createdAt) {
}
