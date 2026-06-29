package org.kroky.musiclib.provider;

import java.util.List;

public interface DiscographyProvider {
    String providerId();

    boolean supports(String providerUrl);

    List<RemoteAlbum> fetchAlbums(String providerUrl) throws ProviderException;

    default ProviderArtistDetails fetchArtistDetails(String providerUrl) throws ProviderException {
        return new ProviderArtistDetails(null, null, fetchAlbums(providerUrl));
    }
}
