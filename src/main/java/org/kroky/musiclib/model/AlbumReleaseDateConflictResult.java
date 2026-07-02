package org.kroky.musiclib.model;

import java.util.List;

public record AlbumReleaseDateConflictResult(
        Album album,
        String sourcePath,
        String targetPath,
        int folderCount,
        int duplicateAlbumsMerged,
        int tagFilesUpdated,
        List<AudioTagFilePlan> files,
        List<String> warnings) {
}
