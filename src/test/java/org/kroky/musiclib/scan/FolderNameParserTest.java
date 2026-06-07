package org.kroky.musiclib.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ParserType;

class FolderNameParserTest {

    private final FolderNameParser parser = new FolderNameParser();

    @Test
    void parsesArtistYearAlbumFolders() {
        var parsed = parser.parse(Path.of("Dark Tranquillity - 2007 - Fiction"),
                ParserType.FLAT_ARTIST_YEAR_ALBUM, "metal");

        assertTrue(parsed.isPresent());
        assertEquals("Dark Tranquillity", parsed.get().artistName());
        assertEquals("Fiction", parsed.get().title());
        assertEquals("2007", parsed.get().releaseDate());
        assertEquals("Fiction | 2007", parsed.get().sortName());
    }

    @Test
    void parsesTitleFoldersWithInnerParenthesesAndFinalArtistYearSuffix() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)"),
                "soundtracks");

        assertEquals("Kevin Kiner", parsed.artistName());
        assertEquals("Ahsoka - Vol. 1 (Episodes 1-4)", parsed.title());
        assertEquals("2023", parsed.releaseDate());
        assertEquals("Ahsoka - Vol. 1 (Episodes 1-4) | 2023", parsed.sortName());
    }

    @Test
    void parsesTitleFoldersWithFullReleaseDate() {
        var parsed = parser.parseTitleAlbum(
                Path.of("V for Vendetta (Dario Marianelli, 2006-03-13)"),
                "soundtracks");

        assertEquals("V for Vendetta", parsed.title());
        assertEquals("Dario Marianelli", parsed.artistName());
        assertEquals("2006-03-13", parsed.releaseDate());
    }

    @Test
    void parsesTitleFoldersWithMultipleArtists() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Ad Astra (Max Richter, Lorne Balfe, 2019)"),
                "soundtracks");

        assertEquals("Ad Astra", parsed.title());
        assertEquals("Max Richter, Lorne Balfe", parsed.artistName());
        assertEquals("2019", parsed.releaseDate());
    }

    @Test
    void parsesTitleFoldersWithPartialReleaseMonth() {
        var parsed = parser.parseTitleAlbum(
                Path.of("Example Score (Composer Name, 2006-03)"),
                "soundtracks");

        assertEquals("Example Score", parsed.title());
        assertEquals("Composer Name", parsed.artistName());
        assertEquals("2006-03", parsed.releaseDate());
    }

    @Test
    void parsesTitleFoldersWithYearOnlySuffix() {
        var parsed = parser.parseTitleAlbum(Path.of("Conan the Barbarian (2011)"), "soundtracks");

        assertEquals("Conan the Barbarian", parsed.title());
        assertEquals("2011", parsed.releaseDate());
    }

    @Test
    void parsesTitleFoldersWithDashYearSubtitle() {
        var parsed = parser.parseTitleAlbum(Path.of("World of Warcraft - 2007 - The Burning Crusade"), "soundtracks");

        assertEquals("World of Warcraft - The Burning Crusade", parsed.title());
        assertEquals("2007", parsed.releaseDate());
        assertEquals("World of Warcraft | 2007 | The Burning Crusade", parsed.sortName());
    }

    @Test
    void keepsAmbiguousTitleFoldersAsTitleOnly() {
        var parsed = parser.parseTitleAlbum(Path.of("Wojciech Kilar - The Best"), "soundtracks");

        assertEquals("Wojciech Kilar - The Best", parsed.title());
        assertNull(parsed.artistName());
        assertNull(parsed.releaseDate());
    }

    @Test
    void convertsAllCapsScannedArtistsToChicagoStyle() {
        var parsed = parser.parse(Path.of("AS I LAY DYING - 2007 - An Ocean Between Us"),
                ParserType.FLAT_ARTIST_YEAR_ALBUM, "metal");

        assertTrue(parsed.isPresent());
        assertEquals("As I Lay Dying", parsed.get().artistName());
    }
}
