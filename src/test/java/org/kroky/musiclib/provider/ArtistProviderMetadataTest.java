package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderLink;

class ArtistProviderMetadataTest {

    @Test
    void otherCountryNormalizesToInternational() {
        assertEquals("XW", CountryCodes.normalize("Other"));
    }

    @Test
    void countryUsesStrictSpecificMajority() {
        List<ArtistProviderLink> links = List.of(
                countryLink("musicbrainz", "US", true),
                countryLink("metal_archives", "US", true),
                countryLink("spirit_of_metal", "GB", true));

        assertEquals("US", ArtistProviderMetadata.countryConsensus(links));
        assertFalse(ArtistProviderMetadata.countryConflict(links));
    }

    @Test
    void countryDoesNotLetInternationalWinUnlessEveryProviderSaysInternational() {
        List<ArtistProviderLink> mixed = List.of(
                countryLink("musicbrainz", "XW", true),
                countryLink("metal_archives", "XW", true),
                countryLink("spirit_of_metal", "DE", true));
        List<ArtistProviderLink> allInternational = List.of(
                countryLink("musicbrainz", "XW", true),
                countryLink("metal_archives", "XW", true));

        assertNull(ArtistProviderMetadata.countryConsensus(mixed));
        assertTrue(ArtistProviderMetadata.countryConflict(mixed));
        assertEquals("XW", ArtistProviderMetadata.countryConsensus(allInternational));
        assertFalse(ArtistProviderMetadata.countryConflict(allInternational));
    }

    @Test
    void countryUnknownParticipatesInConflicts() {
        List<ArtistProviderLink> links = List.of(
                countryLink("musicbrainz", "US", true),
                countryLink("metal_archives", null, true));

        assertNull(ArtistProviderMetadata.countryConsensus(links));
        assertTrue(ArtistProviderMetadata.countryConflict(links));
    }

    @Test
    void statusUsesStrictConcreteMajority() {
        List<ArtistProviderLink> links = List.of(
                activeLink("musicbrainz", true, true),
                activeLink("metal_archives", true, true),
                activeLink("spirit_of_metal", null, true));

        assertEquals(Boolean.TRUE, ArtistProviderMetadata.activeConsensus(links));
        assertFalse(ArtistProviderMetadata.activeConflict(links));
    }

    @Test
    void statusUnknownParticipatesButDoesNotResolve() {
        List<ArtistProviderLink> activeUnknown = List.of(
                activeLink("musicbrainz", true, true),
                activeLink("metal_archives", null, true));
        List<ArtistProviderLink> allUnknown = List.of(
                activeLink("musicbrainz", null, true),
                activeLink("metal_archives", null, true));

        assertNull(ArtistProviderMetadata.activeConsensus(activeUnknown));
        assertTrue(ArtistProviderMetadata.activeConflict(activeUnknown));
        assertNull(ArtistProviderMetadata.activeConsensus(allUnknown));
        assertTrue(ArtistProviderMetadata.activeConflict(allUnknown));
    }

    @Test
    void disabledLinksDoNotVote() {
        List<ArtistProviderLink> links = List.of(
                countryLink("musicbrainz", "US", true),
                countryLink("metal_archives", "GB", false));

        assertEquals("US", ArtistProviderMetadata.countryConsensus(links));
        assertFalse(ArtistProviderMetadata.countryConflict(links));
    }

    private static ArtistProviderLink countryLink(String providerId, String country, boolean enabled) {
        return new ArtistProviderLink(
                0,
                1,
                "Athena",
                providerId,
                providerId + "-id",
                "Athena",
                country,
                null,
                null,
                "https://example.test/" + providerId,
                enabled,
                null,
                null,
                null,
                "",
                "");
    }

    private static ArtistProviderLink activeLink(String providerId, Boolean active, boolean enabled) {
        return new ArtistProviderLink(
                0,
                1,
                "Athena",
                providerId,
                providerId + "-id",
                "Athena",
                null,
                null,
                active,
                "https://example.test/" + providerId,
                enabled,
                null,
                null,
                null,
                "",
                "");
    }
}
