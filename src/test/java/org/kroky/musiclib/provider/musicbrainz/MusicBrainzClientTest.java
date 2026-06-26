package org.kroky.musiclib.provider.musicbrainz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.RemoteReleaseGroup;

class MusicBrainzClientTest {

    @Test
    void acceptsOnlyPrimaryAlbumsWithoutSecondaryTypes() {
        assertTrue(MusicBrainzClient.isRegularAlbum(releaseGroup("Album", List.of())));

        assertFalse(MusicBrainzClient.isRegularAlbum(releaseGroup("EP", List.of())));
        assertFalse(MusicBrainzClient.isRegularAlbum(releaseGroup("Album", List.of("Compilation"))));
        assertFalse(MusicBrainzClient.isRegularAlbum(releaseGroup("Album", List.of("Live"))));
        assertFalse(MusicBrainzClient.isRegularAlbum(releaseGroup(null, List.of())));
    }

    private static RemoteReleaseGroup releaseGroup(String primaryType, List<String> secondaryTypes) {
        return new RemoteReleaseGroup(
                MusicBrainzClient.PROVIDER_ID,
                "release-group-id",
                "Title",
                "2024",
                primaryType,
                secondaryTypes,
                "https://musicbrainz.org/release-group/release-group-id");
    }
}
