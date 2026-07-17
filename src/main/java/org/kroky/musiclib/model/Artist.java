package org.kroky.musiclib.model;

import java.util.List;

public record Artist(
        long id,
        String name,
        String sortName,
        String countryOverride,
        Boolean activeOverride,
        List<String> collectionIds,
        int albumCount,
        int uncheckedAlbumCount,
        int localAlbumCount,
        String providerCountry,
        Boolean providerActive,
        List<ArtistProviderLink> providerLinks,
        String localScanErrorMessage) {
}
