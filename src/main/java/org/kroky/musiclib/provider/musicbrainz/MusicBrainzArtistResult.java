package org.kroky.musiclib.provider.musicbrainz;

public record MusicBrainzArtistResult(
        String id,
        String name,
        String type,
        String country,
        String disambiguation,
        Boolean active,
        int score) {
}
