package org.kroky.musiclib.db;

public final class TitleSortNames {

    private TitleSortNames() {
    }

    public static String create(String title, Integer releaseYear) {
        return create(title, releaseYear, null);
    }

    public static String create(String baseTitle, Integer releaseYear, String subtitle) {
        String titlePart = clean(baseTitle);
        String releasePart = releaseYear == null ? null : releaseYear.toString();
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
