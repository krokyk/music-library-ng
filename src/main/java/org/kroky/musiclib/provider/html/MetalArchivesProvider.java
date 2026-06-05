package org.kroky.musiclib.provider.html;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.kroky.musiclib.provider.DiscographyProvider;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.RemoteAlbum;
import org.kroky.musiclib.model.ReleaseDates;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MetalArchivesProvider implements DiscographyProvider {

    @Override
    public String providerId() {
        return "metal_archives";
    }

    @Override
    public boolean supports(String providerUrl) {
        return providerUrl != null && providerUrl.contains("metal-archives");
    }

    @Override
    public List<RemoteAlbum> fetchAlbums(String providerUrl) throws ProviderException {
        try {
            Document doc = Jsoup.connect(providerUrl).get();
            List<RemoteAlbum> albums = new ArrayList<>();
            for (Element tr : doc.getElementsByTag("tr")) {
                Elements links = tr.getElementsByTag("a");
                if (links.isEmpty()) {
                    continue;
                }
                String title = links.get(0).text();
                String releaseDate = null;
                for (Element td : tr.getElementsByTag("td")) {
                    releaseDate = parseReleaseDate(td.text());
                    if (releaseDate != null) {
                        break;
                    }
                }
                if (!title.isBlank()) {
                    albums.add(new RemoteAlbum(title, releaseDate, providerUrl));
                }
            }
            return albums;
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Metal Archives albums", e);
        }
    }

    private static String parseReleaseDate(String value) {
        try {
            return ReleaseDates.normalize(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
