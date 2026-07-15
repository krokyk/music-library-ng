package org.kroky.musiclib.provider;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CountryCodes {

    private static final Map<String, String> COUNTRY_CODES = countryCodes();

    private CountryCodes() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = key(value);
        if (normalized.length() == 2 && normalized.chars().allMatch(Character::isLetter)) {
            String code = normalized.toUpperCase(Locale.ROOT);
            return COUNTRY_CODES.containsValue(code) ? code : null;
        }
        return COUNTRY_CODES.get(normalized);
    }

    private static Map<String, String> countryCodes() {
        Map<String, String> codes = new HashMap<>();
        for (String code : Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2)) {
            Locale locale = Locale.of("", code);
            codes.put(key(locale.getDisplayCountry(Locale.ENGLISH)), code);
        }
        alias(codes, "United States of America", "US");
        alias(codes, "USA", "US");
        alias(codes, "UK", "GB");
        alias(codes, "England", "GB");
        alias(codes, "Scotland", "GB");
        alias(codes, "Wales", "GB");
        alias(codes, "Northern Ireland", "GB");
        alias(codes, "Russia", "RU");
        alias(codes, "Russian Federation", "RU");
        alias(codes, "Czech Republic", "CZ");
        alias(codes, "South Korea", "KR");
        alias(codes, "Korea South", "KR");
        alias(codes, "North Korea", "KP");
        alias(codes, "Korea North", "KP");
        alias(codes, "Iran", "IR");
        alias(codes, "Vietnam", "VN");
        alias(codes, "Kosovo", "XK");
        alias(codes, "International", "XW");
        alias(codes, "Multinational", "XW");
        alias(codes, "Other", "XW");
        return Map.copyOf(codes);
    }

    private static void alias(Map<String, String> codes, String name, String code) {
        codes.put(key(name), code);
    }

    private static String key(String value) {
        return value == null ? "" : value.trim()
                .replace('&', ' ')
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("[^\\p{Alnum}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
