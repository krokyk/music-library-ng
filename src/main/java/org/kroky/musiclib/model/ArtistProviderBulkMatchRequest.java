package org.kroky.musiclib.model;

import java.util.List;

public record ArtistProviderBulkMatchRequest(List<Long> artistIds) {
}
