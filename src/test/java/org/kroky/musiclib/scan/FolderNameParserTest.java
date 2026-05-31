package org.kroky.musiclib.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ParseStatus;
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
        assertEquals(2007, parsed.get().releaseYear());
    }

    @Test
    void parsesTitleFoldersWithInnerParenthesesAndFinalArtistYearSuffix() {
        var parsed = parser.parseTitleItem(
                Path.of("Ahsoka - Vol. 1 (Episodes 1-4) (Kevin Kiner, 2023)"),
                "soundtracks");

        assertEquals("Kevin Kiner", parsed.artistName());
        assertEquals("Ahsoka - Vol. 1 (Episodes 1-4)", parsed.title());
        assertEquals(2023, parsed.year());
        assertEquals(ParseStatus.EXACT, parsed.parseStatus());
    }

    @Test
    void parsesTitleFoldersWithYearOnlySuffix() {
        var parsed = parser.parseTitleItem(Path.of("Conan the Barbarian (2011)"), "soundtracks");

        assertEquals("Conan the Barbarian", parsed.title());
        assertEquals(2011, parsed.year());
        assertEquals(ParseStatus.PARTIAL, parsed.parseStatus());
    }

    @Test
    void parsesTitleFoldersWithDashYearSubtitle() {
        var parsed = parser.parseTitleItem(Path.of("World of Warcraft - 2007 - The Burning Crusade"), "soundtracks");

        assertEquals("World of Warcraft - The Burning Crusade", parsed.title());
        assertEquals(2007, parsed.year());
        assertEquals(ParseStatus.PARTIAL, parsed.parseStatus());
    }

    @Test
    void keepsAmbiguousTitleFoldersAsTitleOnly() {
        var parsed = parser.parseTitleItem(Path.of("Wojciech Kilar - The Best"), "soundtracks");

        assertEquals("Wojciech Kilar - The Best", parsed.title());
        assertNull(parsed.artistName());
        assertNull(parsed.year());
        assertEquals(ParseStatus.TITLE_ONLY, parsed.parseStatus());
    }

    @Test
    void convertsAllCapsScannedArtistsToChicagoStyle() {
        var parsed = parser.parse(Path.of("AS I LAY DYING - 2007 - An Ocean Between Us"),
                ParserType.FLAT_ARTIST_YEAR_ALBUM, "metal");

        assertTrue(parsed.isPresent());
        assertEquals("As I Lay Dying", parsed.get().artistName());
    }
}
