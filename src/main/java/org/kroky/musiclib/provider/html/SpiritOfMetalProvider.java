package org.kroky.musiclib.provider.html;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.kroky.musiclib.provider.DiscographyProvider;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.RemoteAlbum;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SpiritOfMetalProvider implements DiscographyProvider {

    @Override
    public String providerId() {
        return "spirit_of_metal";
    }

    @Override
    public boolean supports(String providerUrl) {
        return providerUrl != null && providerUrl.contains("spirit-of-metal");
    }

    @Override
    public List<RemoteAlbum> fetchAlbums(String providerUrl) throws ProviderException {
        try {
            Document doc = Jsoup.connect(providerUrl).get();
            List<RemoteAlbum> albums = new ArrayList<>();
            Element discography = doc.getElementById("discography");
            if (discography == null) {
                return albums;
            }
            discography.select("h4[itemprop=name]").forEach(h4 -> {
                Integer year = null;
                for (Element el : h4.siblingElements()) {
                    if (el.tagName().equals("div") && "datePublished".equals(el.attr("itemprop"))) {
                        year = parseYear(el.text());
                        break;
                    }
                }
                albums.add(new RemoteAlbum(h4.text(), year, null, providerUrl));
            });
            return albums;
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Spirit of Metal albums", e);
        }
    }

    private static Integer parseYear(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
