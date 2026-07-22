package org.kroky.musiclib.provider.html;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.kroky.musiclib.model.ReleaseYears;
import org.kroky.musiclib.provider.CountryCodes;
import org.kroky.musiclib.provider.DiscographyProvider;
import org.kroky.musiclib.provider.ProviderArtistDetails;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.ProviderArtistSearchResult;
import org.kroky.musiclib.provider.ProviderStatuses;
import org.kroky.musiclib.provider.ProviderUrlNormalizer;
import org.kroky.musiclib.provider.RemoteAlbum;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MetalArchivesProvider implements DiscographyProvider {

    private static final String BASE_URL = "https://www.metal-archives.com";
    private static final String BAND_SEARCH_PATH = "/search/ajax-band-search/?field=name&query=%s";
    private static final String USER_AGENT = "music-library-ng";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Override
    public ProviderArtistDetails fetchArtistDetails(String providerUrl) throws ProviderException {
        try {
            URI bandUrl = bandPageUrl(providerUrl);
            URI discographyUrl = discographyUrl(providerUrl);
            Document bandPage = Jsoup.parse(fetch(bandUrl, BASE_URL + "/search"), bandUrl.toString());
            return new ProviderArtistDetails(
                    countryFromBandPage(bandPage),
                    activeFromBandPage(bandPage),
                    parseMainDiscography(fetch(discographyUrl, bandUrl.toString())));
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Metal Archives artist details from " + providerUrl, e);
        }
    }

    public List<ProviderArtistSearchResult> searchArtists(String artistName, int limit) throws ProviderException {
        try {
            URI uri = URI.create(BASE_URL + BAND_SEARCH_PATH.formatted(
                    URLEncoder.encode(artistName, StandardCharsets.UTF_8)));
            return parseBandSearchResults(fetch(uri, BASE_URL + "/search"), Math.max(1, limit));
        } catch (Exception e) {
            throw new ProviderException("Unable to search Metal Archives artists for " + artistName, e);
        }
    }

    static URI discographyUrl(String providerUrl) {
        return URI.create(ProviderUrlNormalizer.metalArchivesDiscographyUrl(providerUrl));
    }

    static URI bandPageUrl(String providerUrl) {
        return URI.create(ProviderUrlNormalizer.normalizeMetalArchives(providerUrl));
    }

    static String bandId(String providerUrl) {
        return ProviderUrlNormalizer.metalArchivesBandId(providerUrl);
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
                    parseReleaseYear(cells.get(2).text()),
                    link.absUrl("href")));
        }
        return albums;
    }

    static ProviderArtistDetails parseBandDetails(String html) {
        Document doc = Jsoup.parse(html, BASE_URL);
        return new ProviderArtistDetails(
                countryFromBandPage(doc),
                activeFromBandPage(doc),
                parseMainDiscography(html));
    }

    static List<ProviderArtistSearchResult> parseBandSearchResults(String json, int limit) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        List<ProviderArtistSearchResult> results = new ArrayList<>();
        int index = 0;
        for (JsonNode row : root.path("aaData")) {
            if (results.size() >= Math.max(1, limit)) {
                break;
            }
            if (!row.isArray() || row.size() < 3) {
                continue;
            }
            Element link = Jsoup.parse(row.get(0).asText(), BASE_URL).selectFirst("a[href*=/bands/]");
            if (link == null || link.text().isBlank()) {
                continue;
            }
            String url = link.absUrl("href");
            results.add(new ProviderArtistSearchResult(
                    "metal_archives",
                    bandId(url),
                    link.text().trim(),
                    url,
                    CountryCodes.normalize(text(row, 2)),
                    disambiguation(row.get(0).asText()),
                    null,
                    Math.max(1, 100 - index * 8)));
            index++;
        }
        return results;
    }

    private static String text(JsonNode row, int index) {
        if (!row.has(index) || !row.get(index).isTextual()) {
            return null;
        }
        String value = Jsoup.parse(row.get(index).asText()).text().trim();
        return value.isBlank() ? null : value;
    }

    private static String disambiguation(String html) {
        String text = Jsoup.parse(html).text().trim();
        int marker = text.indexOf("a.k.a.");
        return marker >= 0 ? text.substring(marker).trim() : null;
    }

    private static String countryFromBandPage(Document doc) {
        return CountryCodes.normalize(bandStat(doc, "Country of origin:"));
    }

    private static Boolean activeFromBandPage(Document doc) {
        return ProviderStatuses.active(bandStat(doc, "Status:"));
    }

    private static String bandStat(Document doc, String label) {
        for (Element term : doc.select("#band_stats dt")) {
            if (!label.equalsIgnoreCase(term.text().trim())) {
                continue;
            }
            Element value = term.nextElementSibling();
            return value == null ? null : value.text().trim();
        }
        return null;
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

    private static Integer parseReleaseYear(String value) {
        try {
            return ReleaseYears.fromDate(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
