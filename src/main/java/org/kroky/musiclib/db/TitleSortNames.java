package org.kroky.musiclib.db;

public final class TitleSortNames {

    private TitleSortNames() {
    }

    public static String create(String title, String releaseDate) {
        return create(title, releaseDate, null);
    }

    public static String create(String baseTitle, String releaseDate, String subtitle) {
        String titlePart = clean(baseTitle);
        String releasePart = clean(releaseDate);
        String subtitlePart = clean(subtitle);
        if (releasePart == null && subtitlePart == null) {
            return titlePart;
        }
        if (subtitlePart == null) {
            return titlePart + " | " + releasePart;
        }
        return titlePart + " | " + releasePart + " | " + subtitlePart;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
