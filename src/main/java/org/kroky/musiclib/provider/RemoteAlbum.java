package org.kroky.musiclib.provider;

import java.time.LocalDate;

public record RemoteAlbum(
        String title,
        Integer releaseYear,
        LocalDate releaseDate,
        String sourceUrl) {
}
