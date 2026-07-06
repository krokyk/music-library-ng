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
    void normalizesMetalArchivesBandUrlToBandUrl() {
        assertEquals(
                "https://www.metal-archives.com/bands/Asleigh_Stake/3540329211",
                ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://www.metal-archives.com/bands/Asleigh_Stake/3540329211"));
    }

    @Test
    void normalizesExistingMetalArchivesDiscographyUrlToBandUrl() {
        assertEquals(
                "https://www.metal-archives.com/bands/_/3540329211",
                ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://www.metal-archives.com/band/discography/id/3540329211/tab/main"));
    }

    @Test
    void normalizesExistingMetalArchivesDiscographyUrlWithArtistNameToBandUrl() {
        assertEquals(
                "https://www.metal-archives.com/bands/Andy_Gillion/3540460064",
                ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://www.metal-archives.com/band/discography/id/3540460064/tab/main",
                        "Andy Gillion"));
    }

    @Test
    void buildsMetalArchivesDiscographyUrlFromBandUrl() {
        assertEquals(
                "https://www.metal-archives.com/band/discography/id/3540329211/tab/main",
                ProviderUrlNormalizer.metalArchivesDiscographyUrl(
                        "https://www.metal-archives.com/bands/Asleigh_Stake/3540329211"));
    }

    @Test
    void rejectsWrongMetalArchivesDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderUrlNormalizer.normalizeMetalArchives(
                        "https://example.com/bands/Asleigh_Stake/3540329211"));
    }
}
