package org.kroky.musiclib.provider.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.provider.RemoteAlbum;

class SpiritOfMetalProviderTest {

    @Test
    void parsesAlbumDiscographyRows() {
        List<RemoteAlbum> albums = SpiritOfMetalProvider.parseAlbumDiscography("""
                <section id="discography">
                  <a href="https://www.spirit-of-metal.com/en/album/First/1" class="row release" itemprop="album">
                    <h4 itemprop="name">First</h4>
                    Album - <div itemprop="datePublished">2010</div>
                  </a>
                  <a href="https://www.spirit-of-metal.com/en/album/Second/2" class="row release" itemprop="album">
                    <h4 itemprop="name">Second</h4>
                    Album - <div itemprop="datePublished">2014-05</div>
                  </a>
                  <a href="https://www.spirit-of-metal.com/en/album/Untitled/3" class="row release" itemprop="album">
                    Album - <div itemprop="datePublished">2015</div>
                  </a>
                </section>
                """, "https://www.spirit-of-metal.com/en/band/Band");

        assertEquals(2, albums.size());
        assertEquals("First", albums.get(0).title());
        assertEquals("2010", albums.get(0).releaseDate());
        assertEquals("https://www.spirit-of-metal.com/en/album/First/1", albums.get(0).sourceUrl());
        assertEquals("Second", albums.get(1).title());
        assertEquals("2014-05", albums.get(1).releaseDate());
    }

    @Test
    void returnsEmptyListWhenDiscographyIsMissing() {
        assertEquals(List.of(), SpiritOfMetalProvider.parseAlbumDiscography(
                "<section id=\"biography\"></section>",
                "https://www.spirit-of-metal.com/en/band/Band"));
    }
}
