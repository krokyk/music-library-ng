package org.kroky.musiclib.model;

import java.nio.file.Path;

public record ParsedTitleItem(
        String rawFolderName,
        String title,
        String artistName,
        Integer year,
        Path relativePath,
        String collectionId,
        ParseStatus parseStatus) {
}
