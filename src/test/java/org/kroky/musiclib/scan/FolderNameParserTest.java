package org.kroky.musiclib.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ParserType;

class FolderNameParserTest {

    private final FolderNameParser parser = new FolderNameParser();

    @Test
    void parsesArtistYearAlbumFolders() {
        var parsed = parser.parse(Path.of("Dark Tranquillity - 2007 - Fiction"),
                ParserType.ARTIST_YEAR_ALBUM, "metal");

        assertTrue(parsed.isPresent());
        assertEquals("Dark Tranquillity", parsed.get().artistName());
        assertEquals("Fiction", parsed.get().title());
        assertEquals(2007, parsed.get().releaseYear());
    }

    @Test
    void parsesSoundtrackAndMusicalFolders() {
        var parsed = parser.parse(Path.of("The Fountain (Clint Mansell, 2006)"),
                ParserType.TITLE_ARTIST_YEAR, "soundtracks");

        assertTrue(parsed.isPresent());
        assertEquals("Clint Mansell", parsed.get().artistName());
        assertEquals("The Fountain", parsed.get().title());
        assertEquals(2006, parsed.get().releaseYear());
    }
}
