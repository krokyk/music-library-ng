package org.kroky.musiclib.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseDates {

    private static final Pattern RELEASE_DATE = Pattern.compile("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$");

    private ReleaseDates() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        Matcher matcher = RELEASE_DATE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Release date must use YYYY, YYYY-MM, or YYYY-MM-DD");
        }
        String month = matcher.group(2);
        String day = matcher.group(3);
        if (month != null) {
            int monthValue = Integer.parseInt(month);
            if (monthValue < 1 || monthValue > 12) {
                throw new IllegalArgumentException("Release date month must be between 01 and 12");
            }
        }
        if (day != null) {
            try {
                LocalDate.parse(normalized);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Release date must be a valid calendar date", e);
            }
        }
        return normalized;
    }
}
