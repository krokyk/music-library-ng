package org.kroky.musiclib.provider;

import java.util.Locale;

public final class ProviderStatuses {

    private ProviderStatuses() {
    }

    public static Boolean active(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
        if ("active".equals(normalized)) {
            return true;
        }
        if (normalized.contains("split")
                || normalized.contains("disband")
                || normalized.contains("inactive")
                || normalized.contains("on hold")
                || normalized.contains("changed name")) {
            return false;
        }
        return null;
    }
}
