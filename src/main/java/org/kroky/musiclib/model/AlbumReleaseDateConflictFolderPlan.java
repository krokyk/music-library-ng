package org.kroky.musiclib.model;

import java.util.List;

public record AlbumReleaseDateConflictFolderPlan(
        long localPathId,
        String collectionId,
        String collectionName,
        String sourcePath,
        String targetPath,
        String sourceRelativePath,
        String targetRelativePath,
        int audioFileCount,
        int unsupportedFileCount,
        List<AudioTagFilePlan> files) {
}
