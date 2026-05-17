package org.kroky.musiclib.model;

import java.util.List;

public record MusicRootInfo(
        String selectedRoot,
        boolean detected,
        boolean valid,
        List<String> markers,
        List<RootCandidate> candidates) {
}
