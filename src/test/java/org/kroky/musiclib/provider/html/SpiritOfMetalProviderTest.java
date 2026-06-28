package org.kroky.musiclib.provider.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.provider.ProviderArtistSearchResult;
import org.kroky.musiclib.provider.RemoteAlbum;

class SpiritOfMetalProviderTest {

    @Test
    void parsesOnlyBandSearchResults() {
        List<ProviderArtistSearchResult> results = SpiritOfMetalProvider.parseBandSearchResults("""
                <section class="large-box">
                  <h2 class="ribbon">Results in the bands section (<span>2</span>)</h2>
                  <div class="content">
                    <ul class="Find">
                      <a href="https://www.spirit-of-metal.com/en/band/Angara_Damana" class="results"
                         onMouseOver="PopInfoGroupe('76599','en','1');">Angara Damana</a>
                      (Ambiant Black-Iran) - <font><em>Other name : Angra Demana</em></font>
                    </ul>
                    <ul class="Find">
                      <a href="https://www.spirit-of-metal.com/en/band/Angra" class="results"
                         onMouseOver="PopInfoGroupe('5','en','1');">Angra</a>
                      (Symphonic power-Brazil)
                    </ul>
                  </div>
                </section>
                <section class="large-box">
                  <h2 class="ribbon">Results in the albums section (<span>1</span>)</h2>
                  <div class="content">
                    <ul class="Find"><a href="https://www.spirit-of-metal.com/en/album/Angra/1" class="results">Angra : Angra</a></ul>
                  </div>
                </section>
                """, 10);

        assertEquals(2, results.size());
        assertEquals("Angara Damana", results.get(0).providerArtistName());
        assertEquals("76599", results.get(0).providerArtistId());
        assertEquals("Ambiant Black", results.get(0).type());
        assertEquals("Iran", results.get(0).country());
        assertEquals("Other name : Angra Demana", results.get(0).disambiguation());
        assertEquals("Angra", results.get(1).providerArtistName());
        assertEquals("5", results.get(1).providerArtistId());
        assertEquals("Symphonic power", results.get(1).type());
        assertEquals("Brazil", results.get(1).country());
    }

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
