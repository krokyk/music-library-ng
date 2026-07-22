package org.kroky.musiclib.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FolderNameParserTest {

    private final FolderNameParser parser = new FolderNameParser();

    @Test
    void parsesArtistYearAlbumFolders() {
        var parsed = parser.parseFlatArtistAlbum(Path.of("Dark Tranquillity - 2007 - Fiction"), "metal");

        assertTrue(parsed.isPresent());
        assertEquals("Dark Tranquillity", parsed.get().artistName());
        assertEquals("Fiction", parsed.get().title());
        assertEquals(2007, parsed.get().releaseYear());
        assertEquals("Fiction | 2007", parsed.get().sortName());
    }

    @Test
    void parsesNestedArtistAlbumFolders() {
        var parsed = parser.parseNestedArtistAlbum(
                Path.of("GLOBUS"),
                Path.of("2006 - Epicon"),
                "epic");

        assertTrue(parsed.isPresent());
        assertEquals("Globus", parsed.get().artistName());
        assertEquals("Epicon", parsed.get().title());
        assertEquals(2006, parsed.get().releaseYear());
        assertEquals("Epicon | 2006", parsed.get().sortName());
    }

    @Test
    void rejectsNestedArtistAlbumFoldersWithoutYearOnlyToken() {
        var parsed = parser.parseNestedArtistAlbum(
                Path.of("BASIL POLEDOURIS"),
                Path.of("1982-05-14 - Conan the Barbarian"),
                "soundtracks");

        assertTrue(parsed.isEmpty());
    }

    @Test
    void parsesTitleFoldersWithInnerParenthesesAndFinalArtistYearSuffix() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)"),
                "soundtracks");

        assertEquals("Kevin Kiner", parsed.artistName());
        assertEquals("Ahsoka - Vol. 1 (Episodes 1-4)", parsed.title());
        assertEquals(2023, parsed.releaseYear());
        assertEquals("Ahsoka - Vol. 1 (Episodes 1-4) | 2023", parsed.sortName());
    }

    @Test
    void keepsTitleFoldersWithFullDateSuffixAsTitleOnly() {
        var parsed = parser.parseTitleAlbum(
                Path.of("V for Vendetta (Dario Marianelli, 2006-03-13)"),
                "soundtracks");

        assertEquals("V for Vendetta (Dario Marianelli, 2006-03-13)", parsed.title());
        assertNull(parsed.artistName());
        assertNull(parsed.releaseYear());
    }

    @Test
    void parsesTitleFoldersWithMultipleArtists() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Ad Astra (Max Richter, Lorne Balfe, 2019)"),
                "soundtracks");

        assertEquals("Ad Astra", parsed.title());
        assertEquals("Max Richter, Lorne Balfe", parsed.artistName());
        assertEquals(2019, parsed.releaseYear());
    }

    @Test
    void keepsTitleFoldersWithYearMonthSuffixAsTitleOnly() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Example Score (Composer Name, 2006-03)"),
                "soundtracks");

        assertEquals("Example Score (Composer Name, 2006-03)", parsed.title());
        assertNull(parsed.artistName());
        assertNull(parsed.releaseYear());
    }

    @Test
    void parsesTitleFoldersWithYearOnlySuffix() {
        var parsed = parser.parseTitleAlbum(Path.of("Conan the Barbarian (2011)"), "soundtracks");

        assertEquals("Conan the Barbarian", parsed.title());
        assertEquals(2011, parsed.releaseYear());
    }

    @Test
    void parsesTitleFoldersWithDashYearSubtitle() {
        var parsed = parser.parseTitleAlbum(Path.of("World of Warcraft - 2007 - The Burning Crusade"), "soundtracks");

        assertEquals("World of Warcraft - The Burning Crusade", parsed.title());
        assertEquals(2007, parsed.releaseYear());
        assertEquals("World of Warcraft | 2007 | The Burning Crusade", parsed.sortName());
    }

    @Test
    void keepsAmbiguousTitleFoldersAsTitleOnly() {
        var parsed = parser.parseTitleAlbum(Path.of("Wojciech Kilar - The Best"), "soundtracks");

        assertEquals("Wojciech Kilar - The Best", parsed.title());
        assertNull(parsed.artistName());
        assertNull(parsed.releaseYear());
    }

    @Test
    void convertsAllCapsScannedArtistsToChicagoStyle() {
        var parsed = parser.parseFlatArtistAlbum(Path.of("AS I LAY DYING - 2007 - An Ocean Between Us"), "metal");

        assertTrue(parsed.isPresent());
        assertEquals("As I Lay Dying", parsed.get().artistName());
    }
}
