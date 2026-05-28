package org.kroky.musiclib.db;

import java.text.Normalizer;
import java.util.Locale;

public final class Names {

    private Names() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    public static String slug(String value) {
        return normalize(value).replace(' ', '-');
    }

    public static String chicagoStyle(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim().replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ");
        if (!isAllCaps(cleaned)) {
            return cleaned;
        }
        String[] words = cleaned.toLowerCase(Locale.ROOT).split(" ");
        for (int i = 0; i < words.length; i++) {
            if (i > 0 && i < words.length - 1 && isLowercaseChicagoWord(words[i])) {
                continue;
            }
            words[i] = capitalizeCompound(words[i]);
        }
        return String.join(" ", words);
    }

    private static boolean isLowercaseChicagoWord(String value) {
        return switch (value) {
            case "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "nor", "of", "on", "or",
                    "the", "to", "with" -> true;
            default -> false;
        };
    }

    private static boolean isAllCaps(String value) {
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter;
    }

    private static String capitalizeCompound(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else {
                result.append(c);
                capitalizeNext = c == '-' || c == '/';
            }
        }
        return result.toString();
    }
}
