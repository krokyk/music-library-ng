package org.kroky.musiclib.model;

import java.util.List;

public record RemoteReleaseGroup(
        String providerId,
        String providerReleaseGroupId,
        String title,
        String releaseDate,
        String primaryType,
        List<String> secondaryTypes,
        String providerUrl) {
}
