package org.kroky.musiclib.provider;

import java.util.List;

public record ProviderArtistDetails(
        String country,
        Boolean active,
        List<RemoteAlbum> albums) {
}
