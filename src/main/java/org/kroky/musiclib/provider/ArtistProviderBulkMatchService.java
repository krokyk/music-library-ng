package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderBulkMatchItem;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.AlbumRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderBulkMatchService {

    public static final String STATUS_MATCHED = "MATCHED";
    public static final String STATUS_NEEDS_MANUAL = "NEEDS_MANUAL";
    public static final String STATUS_NO_MATCH = "NO_MATCH";
    public static final String STATUS_SKIPPED_EXISTING = "SKIPPED_EXISTING";
    public static final String STATUS_ERROR = "ERROR";

    @Inject
    ArtistRepository artists;

    @Inject
    AlbumRepository albums;

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    ArtistProviderMatchService matches;

    public ArtistProviderBulkMatchResult matchProviderArtists(String providerId, List<Long> requestedArtistIds) {
        List<Long> artistIds = artistIds(requestedArtistIds);
        List<ArtistProviderBulkMatchItem> items = new ArrayList<>();
        for (Long artistId : artistIds) {
            if (artistId == null) {
                continue;
            }
            if (albums.majorArtistCollection(artistId) == null) {
                continue;
            }
            items.add(matchArtist(providerId, artistId));
        }

        int matched = count(items, STATUS_MATCHED);
        int manual = count(items, STATUS_NEEDS_MANUAL);
        int noMatch = count(items, STATUS_NO_MATCH);
        int skipped = count(items, STATUS_SKIPPED_EXISTING);
        int errors = count(items, STATUS_ERROR);
        int processed = Math.max(0, items.size() - skipped);
        String label = providerLabel(providerId);
        String message = label + " bulk match: matched " + matched
                + ", manual " + manual
                + ", no match " + noMatch
                + ", skipped " + skipped
                + ", errors " + errors + ".";
        return new ArtistProviderBulkMatchResult(
                items.size(),
                processed,
                matched,
                manual,
                noMatch,
                skipped,
                errors,
                items,
                List.of(message));
    }

    private ArtistProviderBulkMatchItem matchArtist(String providerId, long artistId) {
        try {
            Artist artist = artists.find(artistId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
            var existing = providerLinks.findByArtistAndProvider(artist.id(), providerId);
            if (existing.isPresent()) {
                ArtistProviderLink link = existing.get();
                return item(artist, STATUS_SKIPPED_EXISTING,
                        "Existing " + providerLabel(providerId) + " provider configured.",
                        link,
                        null,
                        List.of());
            }

            String label = providerLabel(providerId);
            List<ArtistProviderCandidate> candidates = matches.searchCandidates(artist.id(), providerId);
            if (candidates.isEmpty()) {
                return item(artist, STATUS_NO_MATCH, "No " + label + " candidates found.", null, null, candidates);
            }

            ArtistProviderCandidate top = candidates.get(0);
            ArtistProviderCandidate runnerUp = candidates.size() > 1 ? candidates.get(1) : null;
            if (isHighConfidenceProviderMatch(artist.name(), top, runnerUp)) {
                String providerUrl = canonicalProviderUrl(top);
                ArtistProviderLink link = providerLinks.upsertForArtist(
                        artist.id(),
                        top.providerId(),
                        top.providerArtistId(),
                        top.providerArtistName(),
                        providerUrl,
                        top.country(),
                        top.disambiguation(),
                        top.active(),
                        true);
                return item(artist, STATUS_MATCHED,
                        "Auto-linked " + label + " provider: " + top.providerArtistName()
                                + " (" + top.evidenceSummary() + ")",
                        link,
                        top,
                        candidates);
            }
            return item(artist, STATUS_NEEDS_MANUAL,
                    label + " candidates need manual selection before linking.",
                    null,
                    null,
                    candidates);
        } catch (Exception e) {
            return new ArtistProviderBulkMatchItem(
                    artistId,
                    null,
                    STATUS_ERROR,
                    e.getMessage(),
                    null,
                    null,
                    List.of());
        }
    }

    static boolean isHighConfidenceMusicBrainzMatch(String artistName, ArtistProviderCandidate candidate,
            ArtistProviderCandidate runnerUp) {
        return isHighConfidenceProviderMatch(artistName, candidate, runnerUp);
    }

    static boolean isHighConfidenceProviderMatch(String artistName, ArtistProviderCandidate candidate,
            ArtistProviderCandidate runnerUp) {
        return ProviderCandidateEvidenceEvaluator.isHighConfidenceMatch(candidate, runnerUp);
    }

    private static String providerLabel(String providerId) {
        return switch (providerId) {
            case "musicbrainz" -> "MusicBrainz";
            case ProviderUrlNormalizer.SPIRIT_OF_METAL -> "Spirit of Metal";
            case ProviderUrlNormalizer.METAL_ARCHIVES -> "Metal Archives";
            default -> providerId == null || providerId.isBlank() ? "Provider" : providerId;
        };
    }

    private static String canonicalProviderUrl(ArtistProviderCandidate candidate) {
        if (ProviderUrlNormalizer.METAL_ARCHIVES.equals(candidate.providerId())) {
            return ProviderUrlNormalizer.normalizeMetalArchives(candidate.providerUrl(), candidate.providerArtistName());
        }
        return candidate.providerUrl();
    }

    private List<Long> artistIds(List<Long> requestedArtistIds) {
        if (requestedArtistIds == null || requestedArtistIds.isEmpty()) {
            return List.of();
        }
        Set<Long> deduped = new LinkedHashSet<>();
        for (Long artistId : requestedArtistIds) {
            if (artistId != null) {
                deduped.add(artistId);
            }
        }
        return List.copyOf(deduped);
    }

    private static int count(List<ArtistProviderBulkMatchItem> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private static ArtistProviderBulkMatchItem item(Artist artist, String status, String message,
            ArtistProviderLink providerLink, ArtistProviderCandidate acceptedCandidate,
            List<ArtistProviderCandidate> candidates) {
        return new ArtistProviderBulkMatchItem(
                artist.id(),
                artist.name(),
                status,
                message,
                providerLink,
                acceptedCandidate,
                candidates == null ? List.of() : candidates);
    }
}
