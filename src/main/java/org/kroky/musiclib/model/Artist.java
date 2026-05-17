package org.kroky.musiclib.model;

public record Artist(
        long id,
        String name,
        String sortName,
        String notes,
        String createdAt,
        String updatedAt) {
}
