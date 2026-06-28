package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProviderUrlNormalizerTest {

    @Test
    void acceptsStrictSpiritOfMetalBandUrl() {
        assertEquals(
                "https://www.spirit-of-metal.com/en/band/Angra",
                ProviderUrlNormalizer.normalizeSpiritOfMetal("https://www.spirit-of-metal.com/en/band/Angra"));
    }

    @Test
    void rejectsSpiritOfMetalSearchUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderUrlNormalizer.normalizeSpiritOfMetal("https://www.spirit-of-metal.com/en/search"));
    }

    @Test
    void normalizesMetalArchivesBandUrlToDiscographyUrl() {
        assertEquals(
                "https://www.metal-archives.com/band/discography/id/3540329211/tab/main",
                ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://www.metal-archives.com/bands/Asleigh_Stake/3540329211"));
    }

    @Test
    void acceptsExistingMetalArchivesDiscographyUrl() {
        assertEquals(
                "https://www.metal-archives.com/band/discography/id/3540329211/tab/main",
                ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://www.metal-archives.com/band/discography/id/3540329211/tab/main"));
    }

    @Test
    void rejectsWrongMetalArchivesDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://example.com/bands/Asleigh_Stake/3540329211"));
    }
}
