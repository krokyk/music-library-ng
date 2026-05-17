package org.kroky.musiclib.model;

public record ScanEvent(
        long id,
        long scanRunId,
        String level,
        String message,
        String createdAt) {
}
