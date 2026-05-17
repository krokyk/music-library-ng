package org.kroky.musiclib.model;

public record RootCandidate(
        String configuredPath,
        String resolvedPath,
        boolean exists,
        boolean markersFound) {
}
