package org.kroky.musiclib.model;

import java.util.List;

public record AlbumImportCandidate(
        RemoteReleaseGroup releaseGroup,
        String decision,
        Long matchedAlbumId,
        String matchedAlbumTitle,
        String reason,
        List<AlbumMatchOption> options) {
}
