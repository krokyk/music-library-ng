package org.kroky.musiclib.model;

public record UserPreference(
        String key,
        String value,
        String updatedAt) {
}
