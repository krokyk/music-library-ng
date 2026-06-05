package org.kroky.musiclib.db;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ArtistNames {

    private ArtistNames() {
    }

    public static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String part : value.split("\\s*,\\s*")) {
            String name = Names.chicagoStyle(part.trim().replaceAll("\\s+", " "));
            String normalized = Names.normalize(name);
            if (!normalized.isBlank() && seen.add(normalized)) {
                names.add(name);
            }
        }
        return names;
    }
}
