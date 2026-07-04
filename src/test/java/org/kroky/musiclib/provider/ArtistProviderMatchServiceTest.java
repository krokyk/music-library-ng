package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.MetadataSource;
import org.kroky.musiclib.model.RemoteReleaseGroup;

class ArtistProviderMatchServiceTest {

    @Test
    void titleMatchScoresWhenNormalizedTitleMatches() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Winterheart's Guild", "1999")));
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Winterheart's Guild", "2000")));
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Winterheart's Guild", "2001")));
    }

    @Test
    void titleMatchStillScoresWhenKnownYearsDifferByMoreThanOneYear() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Winterheart's Guild", "2002")));
    }

    @Test
    void titleMatchScoresWhenEitherYearIsMissing() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", null),
                releaseGroup("Winterheart's Guild", "2002")));
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Winterheart's Guild", null)));
    }

    @Test
    void titleMatchUsesNormalizedNames() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album("Écailles de lune", "2000"),
                releaseGroup("Ecailles de Lune", "2001")));
    }

    @Test
    void titleMatchDoesNotScoreWhenNormalizedNamesDiffer() {
        assertFalse(ArtistProviderMatchService.titleMatchesForScoring(
                album("Winterheart's Guild", "2000"),
                releaseGroup("Silence", "2000")));
    }

    @Test
    void releaseYearsAreScoreCompatibleWithinOneYear() {
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible("2000", "1999"));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible("2000", "2000"));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible("2000", "2001"));
    }

    @Test
    void releaseYearsAreNotScoreCompatibleOutsideOneYear() {
        assertFalse(ArtistProviderMatchService.releaseYearsScoreCompatible("2000", "2002"));
    }

    @Test
    void releaseYearsAreScoreCompatibleWhenEitherYearIsMissing() {
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(null, "2002"));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible("2000", null));
    }

    private static Album album(String title, String releaseDate) {
        return new Album(
                1,
                List.of(1L),
                List.of(),
                "Sonata Arctica",
                title,
                releaseDate,
                null,
                MetadataSource.AUTO,
                true,
                true,
                true,
                List.of(),
                List.of(),
                null,
                "2026-01-01T00:00:00",
                "2026-01-01T00:00:00");
    }

    private static RemoteReleaseGroup releaseGroup(String title, String releaseDate) {
        return new RemoteReleaseGroup(
                "musicbrainz",
                title,
                title,
                releaseDate,
                "Album",
                List.of(),
                "https://musicbrainz.org/release-group/" + title);
    }
}
