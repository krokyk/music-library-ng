package org.kroky.musiclib.provider.html;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.kroky.musiclib.model.ReleaseDates;
import org.kroky.musiclib.provider.DiscographyProvider;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.RemoteAlbum;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SpiritOfMetalProvider implements DiscographyProvider {

    private static final String USER_AGENT = "music-library-ng";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
            return parseAlbumDiscography(fetch(URI.create(providerUrl.trim())), providerUrl);
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Spirit of Metal albums from " + providerUrl, e);
        }
    }

    static List<RemoteAlbum> parseAlbumDiscography(String html, String providerUrl) {
        Document doc = Jsoup.parse(html, providerUrl);
        List<RemoteAlbum> albums = new ArrayList<>();
        Element discography = doc.getElementById("discography");
        if (discography == null) {
            return albums;
        }
        for (Element release : discography.select("a.release[itemprop=album]")) {
            Element title = release.selectFirst("h4[itemprop=name]");
            if (title == null || title.text().isBlank()) {
                continue;
            }
            Element releaseDate = release.selectFirst("div[itemprop=datePublished]");
            albums.add(new RemoteAlbum(
                    title.text().trim(),
                    releaseDate == null ? null : parseReleaseDate(releaseDate.text()),
                    release.absUrl("href").isBlank() ? providerUrl : release.absUrl("href")));
        }
        return albums;
    }

    private String fetch(URI url) throws ProviderException {
        ProviderException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request(url), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                lastError = new ProviderException("Spirit of Metal request failed with HTTP " + status
                        + " for " + url + " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                if (!isTransientStatus(status) || attempt == MAX_ATTEMPTS) {
                    throw lastError;
                }
            } catch (ProviderException e) {
                throw e;
            } catch (Exception e) {
                lastError = new ProviderException("Unable to call Spirit of Metal " + url
                        + " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")", e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastError;
                }
            }
            sleepBeforeRetry(attempt);
        }
        throw lastError == null ? new ProviderException("Unable to call Spirit of Metal " + url) : lastError;
    }

    private static HttpRequest request(URI url) {
        return HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();
    }

    private static boolean isTransientStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void sleepBeforeRetry(int attempt) throws ProviderException {
        try {
            Thread.sleep(attempt * 1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while waiting to retry Spirit of Metal", e);
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
