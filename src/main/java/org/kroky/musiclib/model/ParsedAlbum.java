package org.kroky.musiclib.model;

import java.nio.file.Path;

public record ParsedAlbum(
        String artistName,
        String title,
        String releaseDate,
        Path relativePath,
        String collectionId) {
}
