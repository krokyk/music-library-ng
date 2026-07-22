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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.kroky.musiclib.provider.RemoteAlbum;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SpiritOfMetalProvider implements DiscographyProvider {

    private static final String USER_AGENT = "music-library-ng";
    private static final String SEARCH_URL = "https://www.spirit-of-metal.com/find.php?l=en&nom=%s";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern BAND_ID = Pattern.compile("PopInfoGroupe\\('([^']+)'");
    private static final Pattern BAND_DESCRIPTOR = Pattern.compile("\\(([^()]*)\\)");

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

    @Override
    public ProviderArtistDetails fetchArtistDetails(String providerUrl) throws ProviderException {
        try {
            return parseArtistDetails(fetch(URI.create(providerUrl.trim())), providerUrl);
        } catch (Exception e) {
            throw new ProviderException("Unable to fetch Spirit of Metal artist details from " + providerUrl, e);
        }
    }

    public List<ProviderArtistSearchResult> searchArtists(String artistName, int limit) throws ProviderException {
        try {
            URI uri = URI.create(SEARCH_URL.formatted(URLEncoder.encode(artistName, StandardCharsets.UTF_8)));
            return parseBandSearchResults(fetch(uri), Math.max(1, limit));
        } catch (Exception e) {
            throw new ProviderException("Unable to search Spirit of Metal artists for " + artistName, e);
        }
    }

    static List<ProviderArtistSearchResult> parseBandSearchResults(String html, int limit) {
        Document doc = Jsoup.parse(html, "https://www.spirit-of-metal.com");
        Element content = bandResultsContent(doc);
        if (content == null) {
            return List.of();
        }
        List<ProviderArtistSearchResult> results = new ArrayList<>();
        Elements links = content.select("ul.Find a.results[href*=/en/band/], ul.Find a.Results[href*=/en/band/]");
        int index = 0;
        for (Element link : links) {
            if (results.size() >= Math.max(1, limit)) {
                break;
            }
            String name = link.text().trim();
            String url = link.absUrl("href");
            if (name.isBlank() || url.isBlank()) {
                continue;
            }
            String descriptor = descriptor(link.parent(), name);
            String[] typeAndCountry = typeAndCountry(descriptor);
            results.add(new ProviderArtistSearchResult(
                    "spirit_of_metal",
                    bandId(link, url),
                    name,
                    url,
                    CountryCodes.normalize(typeAndCountry[1]),
                    aliasText(link.parent()),
                    null,
                    Math.max(1, 100 - index * 8)));
            index++;
        }
        return results;
    }

    static ProviderArtistDetails parseArtistDetails(String html, String providerUrl) {
        Document doc = Jsoup.parse(html, providerUrl);
        return new ProviderArtistDetails(
                countryFromProfile(doc),
                activeFromProfile(doc),
                parseAlbumDiscography(html, providerUrl));
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
            Element releaseYear = release.selectFirst("div[itemprop=datePublished]");
            albums.add(new RemoteAlbum(
                    title.text().trim(),
                    releaseYear == null ? null : parseReleaseYear(releaseYear.text()),
                    release.absUrl("href").isBlank() ? providerUrl : release.absUrl("href")));
        }
        return albums;
    }

    private static Element bandResultsContent(Document doc) {
        for (Element heading : doc.select("h2.ribbon")) {
            if (!heading.text().toLowerCase().contains("results in the bands section")) {
                continue;
            }
            Element section = heading.parent();
            if (section == null) {
                continue;
            }
            Element content = section.selectFirst("div.content");
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    private static String bandId(Element link, String url) {
        Matcher matcher = BAND_ID.matcher(link.attr("onmouseover"));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return url;
    }

    private static String descriptor(Element row, String name) {
        if (row == null) {
            return null;
        }
        String text = row.text();
        if (text.startsWith(name)) {
            text = text.substring(name.length()).trim();
        }
        Matcher matcher = BAND_DESCRIPTOR.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String[] typeAndCountry(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return new String[] { null, null };
        }
        int separator = descriptor.indexOf('-');
        if (separator <= 0 || separator >= descriptor.length() - 1) {
            return new String[] { descriptor.trim(), null };
        }
        return new String[] {
                descriptor.substring(0, separator).trim(),
                descriptor.substring(separator + 1).trim().replace('-', ' ')
        };
    }

    private static String countryFromProfile(Document doc) {
        return CountryCodes.normalize(profileValue(doc, "Country"));
    }

    private static Boolean activeFromProfile(Document doc) {
        return ProviderStatuses.active(profileValue(doc, "Status"));
    }

    private static String profileValue(Document doc, String label) {
        for (Element row : doc.select("#profile > div")) {
            Elements spans = row.select("> span");
            if (spans.size() < 2) {
                continue;
            }
            if (label.equalsIgnoreCase(spans.get(0).text().trim())) {
                return spans.get(1).text().trim();
            }
        }
        return null;
    }

    private static String aliasText(Element row) {
        if (row == null) {
            return null;
        }
        Element alias = row.selectFirst("em");
        if (alias == null || alias.text().isBlank()) {
            return null;
        }
        return alias.text().trim();
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

    private static Integer parseReleaseYear(String value) {
        try {
            return ReleaseYears.fromDate(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
