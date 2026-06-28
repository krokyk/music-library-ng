package org.kroky.musiclib.provider.html;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.kroky.musiclib.model.ReleaseDates;
import org.kroky.musiclib.provider.DiscographyProvider;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.ProviderUrlNormalizer;
import org.kroky.musiclib.provider.RemoteAlbum;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MetalArchivesProvider implements DiscographyProvider {

    private static final String BASE_URL = "https://www.metal-archives.com";
    private static final String MAIN_DISCOGRAPHY_PATH = "/band/discography/id/%s/tab/main";
    private static final String USER_AGENT = "music-library-ng";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern BAND_URL_ID = Pattern.compile("/bands/[^/]+/(\\d+)(?:[/?#].*)?$");
    private static final Pattern DISCOGRAPHY_URL_ID = Pattern.compile("/band/discography/id/(\\d+)(?:/.*)?$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
            return parseMainDiscography(fetch(discographyUrl(providerUrl), providerUrl));
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Metal Archives albums from " + providerUrl, e);
        }
    }

    static URI discographyUrl(String providerUrl) {
        return URI.create(ProviderUrlNormalizer.normalizeMetalArchives(providerUrl));
    }

    static String bandId(String providerUrl) {
        URI uri = URI.create(ProviderUrlNormalizer.normalizeMetalArchives(providerUrl));
        String path = uri.getPath();
        Matcher bandMatch = BAND_URL_ID.matcher(path);
        if (bandMatch.find()) {
            return bandMatch.group(1);
        }
        Matcher discographyMatch = DISCOGRAPHY_URL_ID.matcher(path);
        if (discographyMatch.find()) {
            return discographyMatch.group(1);
        }
        throw new IllegalArgumentException("Metal Archives band URL is not recognized: " + providerUrl);
    }

    static List<RemoteAlbum> parseMainDiscography(String html) {
        Document doc = Jsoup.parse(html, BASE_URL);
        List<RemoteAlbum> albums = new ArrayList<>();
        for (Element row : doc.select("table.discog tbody tr")) {
            Elements cells = row.select("td");
            if (cells.size() < 3 || !"Full-length".equalsIgnoreCase(cells.get(1).text().trim())) {
                continue;
            }
            Element link = cells.get(0).selectFirst("a");
            if (link == null || link.text().isBlank()) {
                continue;
            }
            albums.add(new RemoteAlbum(
                    link.text().trim(),
                    parseReleaseDate(cells.get(2).text()),
                    link.absUrl("href")));
        }
        return albums;
    }

    private String fetch(URI url, String refererUrl) throws ProviderException {
        ProviderException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request(url, refererUrl),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                lastError = new ProviderException("Metal Archives request failed with HTTP " + status
                        + " for " + url + " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                if (!isTransientStatus(status) || attempt == MAX_ATTEMPTS) {
                    throw lastError;
                }
            } catch (ProviderException e) {
                throw e;
            } catch (Exception e) {
                lastError = new ProviderException("Unable to call Metal Archives " + url
                        + " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")", e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastError;
                }
            }
            sleepBeforeRetry(attempt);
        }
        throw lastError == null ? new ProviderException("Unable to call Metal Archives " + url) : lastError;
    }

    private static HttpRequest request(URI url, String refererUrl) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET();
        if (refererUrl != null && !refererUrl.isBlank()) {
            builder.header("Referer", refererUrl.trim());
        }
        return builder.build();
    }

    private static boolean isTransientStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void sleepBeforeRetry(int attempt) throws ProviderException {
        try {
            Thread.sleep(attempt * 1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while waiting to retry Metal Archives", e);
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
