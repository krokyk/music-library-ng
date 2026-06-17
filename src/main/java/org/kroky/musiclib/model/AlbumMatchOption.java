package org.kroky.musiclib.model;

public record AlbumMatchOption(
        long albumId,
        String title,
        String releaseDate,
        int matchScore,
        String reason) {
}
