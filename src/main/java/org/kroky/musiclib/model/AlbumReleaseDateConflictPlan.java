package org.kroky.musiclib.model;

import java.util.List;

public record AlbumReleaseDateConflictPlan(
        long albumId,
        long providerLinkId,
        String albumTitle,
        String localReleaseDate,
        String providerTitle,
        String providerReleaseDate,
        String sourcePath,
        String targetPath,
        String sourceRelativePath,
        String targetRelativePath,
        int folderCount,
        int audioFileCount,
        int unsupportedFileCount,
        List<AlbumReleaseDateConflictFolderPlan> folders,
        List<AudioTagFilePlan> files,
        List<String> warnings) {
}
