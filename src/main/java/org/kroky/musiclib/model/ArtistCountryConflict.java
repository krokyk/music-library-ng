package org.kroky.musiclib.model;

import java.util.List;

public record ArtistCountryConflict(
        long artistId,
        String artistName,
        List<ArtistCountryConflictSource> sources) {
}
