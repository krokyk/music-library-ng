package org.kroky.musiclib.model;

import java.util.List;

public record RemoteReleaseGroup(
        String providerId,
        String providerReleaseGroupId,
        String title,
        Integer releaseYear,
        String primaryType,
        List<String> secondaryTypes,
        String providerUrl) {
}
