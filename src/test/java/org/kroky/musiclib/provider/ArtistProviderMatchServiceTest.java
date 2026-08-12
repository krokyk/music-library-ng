package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumCollection;
import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.ArtistProviderCandidateAlbum;
import org.kroky.musiclib.model.MetadataSource;
import org.kroky.musiclib.model.RemoteReleaseGroup;

class ArtistProviderMatchServiceTest {

    @Test
    void titleMatchScoresExactNormalizedAndFuzzyTitles() {
        assertEquals(new ProviderCandidateEvidenceEvaluator.TitleMatch("exact", 100),
                ProviderCandidateEvidenceEvaluator.titleMatch("The Alliance of the Kings", "The Alliance of the Kings"));

        assertEquals(new ProviderCandidateEvidenceEvaluator.TitleMatch("normalized", 96),
                ProviderCandidateEvidenceEvaluator.titleMatch("A New Religion", "A New Religion?"));

        assertEquals(new ProviderCandidateEvidenceEvaluator.TitleMatch("normalized", 96),
                ProviderCandidateEvidenceEvaluator.titleMatch("A New Religion", "A New Religion ?"));

        var fuzzy = ProviderCandidateEvidenceEvaluator.titleMatch("Soulless Child", "Soulless Childe");
        assertEquals("fuzzy", fuzzy.type());
        assertTrue(fuzzy.score() >= 92);
    }

    @Test
    void titleMatchStillScoresWhenKnownYearsDifferByMoreThanOneYear() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album(1, "Winterheart's Guild", "2000", true, true),
                releaseGroup("Winterheart's Guild", "2002")));
    }

    @Test
    void titleMatchScoresWhenEitherYearIsMissing() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album(1, "Winterheart's Guild", null, true, true),
                releaseGroup("Winterheart's Guild", "2002")));
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album(1, "Winterheart's Guild", "2000", true, true),
                releaseGroup("Winterheart's Guild", null)));
    }

    @Test
    void titleMatchUsesNormalizedNames() {
        assertTrue(ArtistProviderMatchService.titleMatchesForScoring(
                album(1, "Écailles de lune", "2000", true, true),
                releaseGroup("Ecailles de Lune", "2001")));
    }

    @Test
    void titleMatchDoesNotScoreWhenNormalizedNamesDiffer() {
        assertFalse(ArtistProviderMatchService.titleMatchesForScoring(
                album(1, "Winterheart's Guild", "2000", true, true),
                releaseGroup("Silence", "2000")));
    }

    @Test
    void artistNameScoreUsesNormalizedEqualityFuzzyScoringAndAliases() {
        assertEquals(100, ProviderCandidateEvidenceEvaluator.artistNameScore("Ancient Bards", "Ancient Bards", List.of()));
        assertTrue(ProviderCandidateEvidenceEvaluator.artistNameScore("Ancient Bards", "Ancient Bards ITA", List.of()) >= 65);
        assertEquals(100, ProviderCandidateEvidenceEvaluator.artistNameScore(
                "Rhapsody of Fire",
                "Rhapsody",
                List.of("Rhapsody of Fire")));
    }

    @Test
    void localCheckedAndFuzzyEvidenceContributeWithExpectedWeights() {
        var evidence = ProviderCandidateEvidenceEvaluator.evaluate(
                "Ancient Bards",
                List.of(
                        album(1, "The Alliance of the Kings", "2010", true, true),
                        album(2, "A New Religion", "2014", true, false),
                        album(3, "Soulless Child", "2011", true, true)),
                "Ancient Bards",
                100,
                List.of(),
                List.of(
                        releaseGroup("The Alliance of the Kings", "2010"),
                        releaseGroup("A New Religion?", "2014"),
                        releaseGroup("Soulless Childe", "2011")));

        assertEquals(96, evidence.albumEvidenceScore());
        assertEquals(9, evidence.yearBonus());
        assertEquals(100, evidence.nameScore());
        assertEvidence(evidence.albumEvidence().get(0), "exact", 100, 40, "local");
        assertEvidence(evidence.albumEvidence().get(1), "normalized", 96, 24, "checked");
        assertEvidence(evidence.albumEvidence().get(2), "fuzzy", 99, 32, "local");
    }

    @Test
    void genericTitleEvidenceContributesHalfWeightAndCannotAutoMatchAlone() {
        var evidence = ProviderCandidateEvidenceEvaluator.evaluate(
                "Generic Band",
                List.of(
                        album(1, "Live", "2001", true, true),
                        album(2, "Best Of", "2002", true, true),
                        album(3, "Anthology", "2003", true, true),
                        album(4, "Essential", "2004", true, true)),
                "Generic Band",
                100,
                List.of(),
                List.of(
                        releaseGroup("Live", "2001"),
                        releaseGroup("Best Of", "2002"),
                        releaseGroup("Anthology", "2003"),
                        releaseGroup("Essential", "2004")));
        var candidate = ArtistProviderBulkMatchServiceTest.candidate("Generic Band", 100, evidence);

        assertEquals(80, evidence.albumEvidenceScore());
        assertTrue(evidence.albumEvidence().stream().allMatch(ArtistProviderCandidateAlbum::genericTitle));
        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void uncheckedProviderCreatedEvidenceIsDisplayOnly() {
        var evidence = ProviderCandidateEvidenceEvaluator.evaluate(
                "Ancient Bards",
                List.of(album(1, "A New Religion", "2014", false, false)),
                "Ancient Bards",
                100,
                List.of(),
                List.of(releaseGroup("A New Religion?", "2014")));

        assertEquals(0, evidence.albumEvidenceScore());
        assertEvidence(evidence.albumEvidence().get(0), "normalized", 96, 0, "unchecked");
    }

    @Test
    void providerImportCanLinkUncheckedProviderCreatedAlbumWithoutCandidateEvidenceScore() {
        Album providerAlbum = providerAlbum(1, "Twilight of Days", "2001");
        ArtistProviderCandidateAlbum evidence = ProviderCandidateEvidenceEvaluator.albumEvidence(
                List.of(providerAlbum),
                releaseGroup("Twilight Of Days", "2000"));

        assertEquals(0, evidence.evidenceStrength());
        assertEquals("unchecked", evidence.localEvidenceKind());
        assertTrue(ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, providerAlbum));
    }

    @Test
    void albumEvidenceAutoLinksStrongProviderTitleVariantsToLocalAlbums() {
        List<Album> localAlbums = List.of(
                album(1, "The Alliance of the Kings", "2010", true, true),
                album(2, "Origine (The Black Crystal Sword Saga, Pt. 2)", "2019", true, true),
                album(3, "Artifex", "2025", true, true));

        ArtistProviderCandidateAlbum alliance = ProviderCandidateEvidenceEvaluator.albumEvidence(
                localAlbums,
                releaseGroup("The Alliance of the Kings - The Black Crystal Sword Saga Pt. 1", "2010"));
        ArtistProviderCandidateAlbum origine = ProviderCandidateEvidenceEvaluator.albumEvidence(
                localAlbums,
                releaseGroup("Origine: The Black Crystal Sword Saga Part 2", "2019"));

        assertTrue(ProviderCandidateEvidenceEvaluator.canAutoLinkAlbum(alliance));
        assertTrue(ProviderCandidateEvidenceEvaluator.canAutoLinkAlbum(origine));
        assertEquals(1L, alliance.localAlbumId());
        assertEquals(2L, origine.localAlbumId());
        assertEquals("fuzzy", alliance.matchType());
        assertEquals("fuzzy", origine.matchType());
        assertTrue(ProviderTitles.titleConflict(alliance.localTitle(), alliance.title()));
        assertTrue(ProviderTitles.titleConflict(origine.localTitle(), origine.title()));
    }

    @Test
    void albumEvidenceKeepsDifferentNumberedVolumesSeparate() {
        List<Album> localAlbums = List.of(
                album(1, "Hymn of the High Seas, Vol. 1", "2020", true, true));

        ArtistProviderCandidateAlbum sameVolume = ProviderCandidateEvidenceEvaluator.albumEvidence(
                localAlbums,
                releaseGroup("Hymn of the High Seas (Volume 1)", "2020"));
        ArtistProviderCandidateAlbum differentVolume = ProviderCandidateEvidenceEvaluator.albumEvidence(
                localAlbums,
                releaseGroup("Hymn of the High Seas (Volume 2)", "2020"));

        assertEquals(1L, sameVolume.localAlbumId());
        assertTrue(ProviderCandidateEvidenceEvaluator.canAutoLinkAlbum(sameVolume));
        assertNull(differentVolume.localAlbumId());
        assertFalse(ProviderCandidateEvidenceEvaluator.canAutoLinkAlbum(differentVolume));
    }

    @Test
    void providerTitleCleanupOnlySuppressesParserSpacingArtifacts() {
        assertEquals("A New Religion?", ProviderTitles.clean("A New Religion ?"));
        assertFalse(ProviderTitles.titleConflict("A New Religion?", "A New Religion ?"));
        assertTrue(ProviderTitles.titleConflict("A New Religion", "A New Religion ?"));
    }

    @Test
    void checkedNonLocalEvidenceContributesButCannotAutoMatchAlone() {
        var evidence = ProviderCandidateEvidenceEvaluator.evaluate(
                "Ancient Bards",
                List.of(
                        album(1, "The Alliance of the Kings", "2010", true, false),
                        album(2, "Soulless Child", "2011", true, false),
                        album(3, "A New Religion", "2014", true, false)),
                "Ancient Bards",
                100,
                List.of(),
                List.of(
                        releaseGroup("The Alliance of the Kings", "2010"),
                        releaseGroup("Soulless Child", "2011"),
                        releaseGroup("A New Religion?", "2014")));
        var candidate = ArtistProviderBulkMatchServiceTest.candidate("Ancient Bards", 100, evidence);

        assertEquals(72, evidence.albumEvidenceScore());
        assertFalse(ArtistProviderBulkMatchService.isHighConfidenceProviderMatch(candidate));
    }

    @Test
    void albumEvidenceCanOutrankExactNameCandidateWithWeakAlbumEvidence() {
        var exactName = ProviderCandidateEvidenceEvaluator.evaluate(
                "Athena",
                List.of(),
                "Athena",
                100,
                List.of(),
                List.of());
        var albumBackedRename = ProviderCandidateEvidenceEvaluator.evaluate(
                "Athena",
                List.of(
                        album(1, "Everflow Part 1: Frames of Humanity", "2024", true, true),
                        album(2, "Twilight of Days", "2001", true, true),
                        album(3, "A New Religion", "1998", true, true)),
                "Athena XIX",
                95,
                List.of(),
                List.of(
                        releaseGroup("Everflow Part 1: Frames of Humanity", "2024"),
                        releaseGroup("Twilight of Days", "2001"),
                        releaseGroup("A New Religion", "1998")));

        assertTrue(albumBackedRename.finalScore() > exactName.finalScore());
    }

    @Test
    void releaseYearsAreScoreCompatibleWithinOneYear() {
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(2000, 1999));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(2000, 2000));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(2000, 2001));
    }

    @Test
    void releaseYearsAreNotScoreCompatibleOutsideOneYear() {
        assertFalse(ArtistProviderMatchService.releaseYearsScoreCompatible(2000, 2002));
    }

    @Test
    void releaseYearsAreScoreCompatibleWhenEitherYearIsMissing() {
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(null, 2002));
        assertTrue(ArtistProviderMatchService.releaseYearsScoreCompatible(2000, null));
    }

    private static void assertEvidence(ArtistProviderCandidateAlbum evidence, String matchType, int titleScore,
            int evidenceStrength, String localEvidenceKind) {
        assertEquals(matchType, evidence.matchType());
        assertEquals(titleScore, evidence.titleScore());
        assertEquals(evidenceStrength, evidence.evidenceStrength());
        assertEquals(localEvidenceKind, evidence.localEvidenceKind());
    }

    static Album album(long id, String title, String yearText, boolean checked, boolean onDisk) {
        return new Album(
                id,
                List.of(1L),
                new AlbumCollection(1, "Artist"),
                "Ancient Bards",
                title,
                org.kroky.musiclib.model.ReleaseYears.fromDate(yearText),
                null,
                MetadataSource.AUTO,
                checked,
                onDisk ? "album" : null,
                onDisk ? "/music/album" : null,
                onDisk,
                List.of(),
                null,
                "2026-01-01T00:00:00",
                "2026-01-01T00:00:00");
    }

    static Album providerAlbum(long id, String title, String yearText) {
        return new Album(
                id,
                List.of(1L),
                new AlbumCollection(1, "Artist"),
                "Ancient Bards",
                title,
                org.kroky.musiclib.model.ReleaseYears.fromDate(yearText),
                null,
                MetadataSource.AUTO,
                false,
                null,
                null,
                false,
                List.of(new AlbumProviderLink(
                        id,
                        id,
                        "musicbrainz",
                        "mb-" + id,
                        title,
                        org.kroky.musiclib.model.ReleaseYears.fromDate(yearText),
                        "https://musicbrainz.org/release-group/mb-" + id,
                        null,
                        null,
                        false,
                        false,
                        "2026-01-01T00:00:00",
                        "2026-01-01T00:00:00")),
                null,
                "2026-01-01T00:00:00",
                "2026-01-01T00:00:00");
    }

    static RemoteReleaseGroup releaseGroup(String title, String yearText) {
        return new RemoteReleaseGroup(
                "musicbrainz",
                title,
                title,
                org.kroky.musiclib.model.ReleaseYears.fromDate(yearText),
                "Album",
                List.of(),
                "https://musicbrainz.org/release-group/" + title);
    }
}
