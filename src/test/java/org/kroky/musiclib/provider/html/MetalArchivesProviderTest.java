package org.kroky.musiclib.provider.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.provider.RemoteAlbum;

class MetalArchivesProviderTest {

    @Test
    void buildsMainDiscographyUrlFromBandUrl() {
        assertEquals(
                "https://www.metal-archives.com/band/discography/id/3540329211/tab/main",
                MetalArchivesProvider.discographyUrl(
                        "https://www.metal-archives.com/bands/Asleigh_Stake/3540329211").toString());
    }

    @Test
    void acceptsExistingMainDiscographyUrl() {
        assertEquals(
                "3540329211",
                MetalArchivesProvider.bandId(
                        "https://www.metal-archives.com/band/discography/id/3540329211/tab/main"));
    }

    @Test
    void rejectsUnknownMetalArchivesUrlShape() {
        assertThrows(IllegalArgumentException.class,
                () -> MetalArchivesProvider.bandId("https://www.metal-archives.com/albums/Band/Album/123"));
    }

    @Test
    void parsesOnlyFullLengthRows() {
        List<RemoteAlbum> albums = MetalArchivesProvider.parseMainDiscography("""
                <table class="display discog">
                  <tbody>
                    <tr>
                      <td><a href="https://www.metal-archives.com/albums/Band/First/1">First</a></td>
                      <td>Full-length</td>
                      <td>2010</td>
                    </tr>
                    <tr>
                      <td><a href="https://www.metal-archives.com/albums/Band/Short/2">Short</a></td>
                      <td>EP</td>
                      <td>2012</td>
                    </tr>
                    <tr>
                      <td><a href="https://www.metal-archives.com/albums/Band/Split/3">Split</a></td>
                      <td>Split</td>
                      <td>2013</td>
                    </tr>
                    <tr>
                      <td><a href="https://www.metal-archives.com/albums/Band/Second/4">Second</a></td>
                      <td>Full-length</td>
                      <td>2014-05</td>
                    </tr>
                  </tbody>
                </table>
                """);

        assertEquals(2, albums.size());
        assertEquals("First", albums.get(0).title());
        assertEquals("2010", albums.get(0).releaseDate());
        assertEquals("Second", albums.get(1).title());
        assertEquals("2014-05", albums.get(1).releaseDate());
    }
}
