package org.kroky.musiclib.model;

public final class ReleaseYears {

    private ReleaseYears() {
    }

    public static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.matches("\\d{4}")) {
            throw new IllegalArgumentException("Release year must use YYYY");
        }
        return normalize(Integer.valueOf(normalized));
    }

    public static Integer fromDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() >= 4 && normalized.substring(0, 4).matches("\\d{4}")
                ? normalize(Integer.valueOf(normalized.substring(0, 4)))
                : null;
    }

    public static Integer normalize(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1000 || value > 9999) {
            throw new IllegalArgumentException("Release year must be between 1000 and 9999");
        }
        return value;
    }
}
