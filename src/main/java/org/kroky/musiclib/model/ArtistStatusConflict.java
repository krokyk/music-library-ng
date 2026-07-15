package org.kroky.musiclib.model;

import java.util.List;

public record ArtistStatusConflict(
        long artistId,
        String artistName,
        List<ArtistStatusConflictSource> sources) {
}
