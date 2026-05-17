package org.kroky.musiclib.model;

import java.nio.file.Path;

public record ParsedAlbum(
        String artistName,
        String title,
        Integer releaseYear,
        Path relativePath,
        String sourceId) {
}
