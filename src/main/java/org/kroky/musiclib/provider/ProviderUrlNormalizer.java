package org.kroky.musiclib.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderUrlNormalizer {

    public static final String SPIRIT_OF_METAL = "spirit_of_metal";
    public static final String METAL_ARCHIVES = "metal_archives";

    private static final String METAL_ARCHIVES_BASE_URL = "https://www.metal-archives.com";
    private static final String METAL_ARCHIVES_DISCOGRAPHY_PATH = "/band/discography/id/%s/tab/main";
    private static final String METAL_ARCHIVES_BAND_PATH = "/bands/%s/%s";
    private static final Pattern METAL_ARCHIVES_BAND_URL_ID = Pattern.compile("/bands/([^/]+)/(\\d+)/?$");
    private static final Pattern METAL_ARCHIVES_DISCOGRAPHY_URL_ID =
            Pattern.compile("/band/discography/id/(\\d+)(?:/tab/main)?/?$");
    private static final Pattern SPIRIT_OF_METAL_BAND_PATH = Pattern.compile("/en/band/[^/]+/?$");

    private ProviderUrlNormalizer() {
    }

    public static String normalize(String providerId, String providerUrl) {
        return switch (providerId) {
            case SPIRIT_OF_METAL -> normalizeSpiritOfMetal(providerUrl);
            case METAL_ARCHIVES -> normalizeMetalArchives(providerUrl);
            default -> providerUrl == null ? null : providerUrl.trim();
        };
    }

    public static String normalizeSpiritOfMetal(String providerUrl) {
        URI uri = parseRequired(providerUrl, "Spirit of Metal URL is required");
        requireHttps(uri, "Spirit of Metal URL must start with https://");
        requireHost(uri, "www.spirit-of-metal.com", "Spirit of Metal URL must use www.spirit-of-metal.com");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null
                || !SPIRIT_OF_METAL_BAND_PATH.matcher(uri.getPath()).matches()) {
            throw new IllegalArgumentException(
                    "Spirit of Metal URL must match https://www.spirit-of-metal.com/en/band/<name>");
        }
        return providerUrl.trim();
    }

    public static String normalizeMetalArchives(String providerUrl) {
        return normalizeMetalArchives(providerUrl, null);
    }

    public static String normalizeMetalArchives(String providerUrl, String artistName) {
        URI uri = parseRequired(providerUrl, "Metal Archives URL is required");
        requireHttps(uri, "Metal Archives URL must start with https://");
        requireMetalArchivesHost(uri);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Metal Archives URL must not include query or fragment parts");
        }
        Matcher bandMatch = METAL_ARCHIVES_BAND_URL_ID.matcher(uri.getPath());
        if (bandMatch.find()) {
            return METAL_ARCHIVES_BASE_URL + METAL_ARCHIVES_BAND_PATH.formatted(bandMatch.group(1), bandMatch.group(2));
        }
        Matcher discographyMatch = METAL_ARCHIVES_DISCOGRAPHY_URL_ID.matcher(uri.getPath());
        if (discographyMatch.find()) {
            return METAL_ARCHIVES_BASE_URL
                    + METAL_ARCHIVES_BAND_PATH.formatted(metalArchivesBandNameSegment(artistName),
                            discographyMatch.group(1));
        }
        throw new IllegalArgumentException("Metal Archives band URL is not recognized: " + providerUrl);
    }

    public static String metalArchivesDiscographyUrl(String providerUrl) {
        return METAL_ARCHIVES_BASE_URL + METAL_ARCHIVES_DISCOGRAPHY_PATH.formatted(metalArchivesBandId(providerUrl));
    }

    public static String metalArchivesExternalArtistUrl(String providerUrl) {
        return normalizeMetalArchives(providerUrl) + "#band_tab_discography";
    }

    public static String metalArchivesBandId(String providerUrl) {
        URI uri = parseRequired(providerUrl, "Metal Archives URL is required");
        requireHttps(uri, "Metal Archives URL must start with https://");
        requireMetalArchivesHost(uri);
        Matcher bandMatch = METAL_ARCHIVES_BAND_URL_ID.matcher(uri.getPath());
        if (bandMatch.find()) {
            return bandMatch.group(2);
        }
        Matcher discographyMatch = METAL_ARCHIVES_DISCOGRAPHY_URL_ID.matcher(uri.getPath());
        if (discographyMatch.find()) {
            return discographyMatch.group(1);
        }
        throw new IllegalArgumentException("Metal Archives band URL is not recognized: " + providerUrl);
    }

    private static String metalArchivesBandNameSegment(String artistName) {
        if (artistName == null || artistName.isBlank()) {
            return "_";
        }
        String segment = artistName.trim().replaceAll("[\\s/]+", "_");
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI parseRequired(String providerUrl, String blankMessage) {
        if (providerUrl == null || providerUrl.isBlank()) {
            throw new IllegalArgumentException(blankMessage);
        }
        try {
            return URI.create(providerUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Provider URL is invalid: " + providerUrl, e);
        }
    }

    private static void requireHttps(URI uri, String message) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireHost(URI uri, String expectedHost, String message) {
        String host = uri.getHost();
        if (host == null || !expectedHost.equals(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireMetalArchivesHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Metal Archives URL must use metal-archives.com");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!"www.metal-archives.com".equals(normalized) && !"metal-archives.com".equals(normalized)) {
            throw new IllegalArgumentException("Metal Archives URL must use metal-archives.com");
        }
    }
}
