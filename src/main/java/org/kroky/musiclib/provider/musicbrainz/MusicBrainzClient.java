package org.kroky.musiclib.provider.musicbrainz;

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

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.ReleaseYears;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.provider.ProviderException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MusicBrainzClient {

    public static final String PROVIDER_ID = "musicbrainz";

    // MusicBrainz rate limits public clients per application, so throttle across all CDI instances.
    private static final Object LIMITER_LOCK = new Object();
    private static long nextRequestAtMillis = 0L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Inject
    MusicLibraryConfig config;

    @Inject
    ObjectMapper objectMapper;

    public List<MusicBrainzArtistResult> searchArtists(String artistName, int limit) throws ProviderException {
        String query = "artist:\"" + artistName + "\"";
        URI uri = uri("/artist",
                "query", query,
                "fmt", "json",
                "limit", String.valueOf(Math.max(1, limit)));
        JsonNode json = getJson(uri);
        List<MusicBrainzArtistResult> results = new ArrayList<>();
        for (JsonNode item : json.path("artists")) {
            results.add(new MusicBrainzArtistResult(
                    text(item, "id"),
                    text(item, "name"),
                    text(item, "type"),
                    text(item, "country"),
                    text(item, "disambiguation"),
                    active(item),
                    item.path("score").asInt(0)));
        }
        return results;
    }

    public MusicBrainzArtistResult fetchArtist(String artistMbid) throws ProviderException {
        URI uri = uri("/artist/" + artistMbid,
                "fmt", "json");
        JsonNode item = getJson(uri);
        return new MusicBrainzArtistResult(
                text(item, "id"),
                text(item, "name"),
                text(item, "type"),
                text(item, "country"),
                text(item, "disambiguation"),
                active(item),
                100);
    }

    public List<RemoteReleaseGroup> fetchReleaseGroups(String artistMbid) throws ProviderException {
        int limit = Math.max(1, config.providers().musicbrainz().releaseGroupPageSize());
        int offset = 0;
        int total = Integer.MAX_VALUE;
        List<RemoteReleaseGroup> releaseGroups = new ArrayList<>();
        while (offset < total) {
            URI uri = uri("/release-group",
                    "artist", artistMbid,
                    "type", "album",
                    "fmt", "json",
                    "limit", String.valueOf(limit),
                    "offset", String.valueOf(offset));
            JsonNode json = getJson(uri);
            total = count(json);
            JsonNode page = json.path("release-groups");
            for (JsonNode item : page) {
                RemoteReleaseGroup releaseGroup = mapReleaseGroup(item);
                if (isRegularAlbum(releaseGroup)) {
                    releaseGroups.add(releaseGroup);
                }
            }
            if (!page.isArray() || page.size() == 0) {
                break;
            }
            offset += page.size();
        }
        return releaseGroups;
    }

    public String artistUrl(String artistMbid) {
        return siteUrl() + "/artist/" + artistMbid;
    }

    private RemoteReleaseGroup mapReleaseGroup(JsonNode item) {
        String id = text(item, "id");
        List<String> secondaryTypes = new ArrayList<>();
        for (JsonNode secondaryType : item.path("secondary-types")) {
            if (secondaryType.isTextual() && !secondaryType.asText().isBlank()) {
                secondaryTypes.add(secondaryType.asText());
            }
        }
        return new RemoteReleaseGroup(
                PROVIDER_ID,
                id,
                text(item, "title"),
                releaseYear(text(item, "first-release-date")),
                text(item, "primary-type"),
                secondaryTypes,
                siteUrl() + "/release-group/" + id);
    }

    static boolean isRegularAlbum(RemoteReleaseGroup releaseGroup) {
        return releaseGroup != null
                && "Album".equalsIgnoreCase(releaseGroup.primaryType())
                && (releaseGroup.secondaryTypes() == null || releaseGroup.secondaryTypes().isEmpty());
    }

    private JsonNode getJson(URI uri) throws ProviderException {
        ProviderException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpResponse<String> response = sendWithLimiter(uri);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    return objectMapper.readTree(response.body());
                } catch (Exception e) {
                    throw new ProviderException("MusicBrainz returned invalid JSON", e);
                }
            }
            String message = "MusicBrainz request failed with HTTP " + status + " for " + uri;
            lastError = new ProviderException(message);
            if (!isTransient(status) || attempt == 3) {
                throw lastError;
            }
        }
        throw lastError == null ? new ProviderException("MusicBrainz request failed") : lastError;
    }

    private HttpResponse<String> sendWithLimiter(URI uri) throws ProviderException {
        synchronized (LIMITER_LOCK) {
            long now = System.currentTimeMillis();
            long waitMillis = Math.max(0, nextRequestAtMillis - now);
            if (waitMillis > 0) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("Interrupted while waiting for MusicBrainz rate limit", e);
                }
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", config.providers().musicbrainz().userAgent().orElseThrow())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                nextRequestAtMillis = System.currentTimeMillis()
                        + Math.max(1000, config.providers().musicbrainz().requestMinIntervalMs());
                return response;
            } catch (Exception e) {
                nextRequestAtMillis = System.currentTimeMillis()
                        + Math.max(1000, config.providers().musicbrainz().requestMinIntervalMs());
                throw new ProviderException("Unable to call MusicBrainz " + uri, e);
            }
        }
    }

    private URI uri(String path, String... params) {
        StringBuilder builder = new StringBuilder(baseUrl()).append(path);
        if (params.length > 0) {
            builder.append('?');
        }
        for (int i = 0; i < params.length; i += 2) {
            if (i > 0) {
                builder.append('&');
            }
            builder.append(encode(params[i])).append('=').append(encode(params[i + 1]));
        }
        return URI.create(builder.toString());
    }

    private String baseUrl() {
        return stripTrailingSlash(config.providers().musicbrainz().baseUrl());
    }

    private String siteUrl() {
        return stripTrailingSlash(config.providers().musicbrainz().siteUrl());
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int count(JsonNode json) {
        if (json.has("release-group-count")) {
            return json.path("release-group-count").asInt(0);
        }
        return json.path("count").asInt(0);
    }

    private static boolean isTransient(int status) {
        return status == 429 || status == 503 || status == 502 || status == 504;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static Boolean active(JsonNode node) {
        JsonNode lifeSpan = node.path("life-span");
        if (!lifeSpan.isObject()) {
            return null;
        }
        return !lifeSpan.path("ended").asBoolean(false);
    }

    private static Integer releaseYear(String value) {
        return ReleaseYears.fromDate(value);
    }
}
