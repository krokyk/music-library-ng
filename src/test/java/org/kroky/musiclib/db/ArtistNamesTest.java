package org.kroky.musiclib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ArtistNamesTest {

    @Test
    void splitsCommaSeparatedArtistNames() {
        assertEquals(
                List.of("Max Richter", "Lorne Balfe"),
                ArtistNames.splitList("Max Richter, Lorne Balfe"));
    }

    @Test
    void convertsAllCapsNamesAndRemovesDuplicates() {
        assertEquals(
                List.of("Max Richter", "Lorne Balfe"),
                ArtistNames.splitList("MAX RICHTER, LORNE BALFE, Max Richter"));
    }
}
