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
        return matchProviderArtists(providerId, requestedArtistIds, ProgressListener.NONE);
    }

    public ArtistProviderBulkMatchResult matchProviderArtists(String providerId, List<Long> requestedArtistIds,
            ProgressListener progress) {
        List<Long> artistIds = artistIds(requestedArtistIds).stream()
                .filter(artistId -> albums.majorArtistCollection(artistId) != null)
                .toList();
        List<ArtistProviderBulkMatchItem> items = new ArrayList<>();
        progress.started(artistIds.size());
        for (Long artistId : artistIds) {
            if (progress.isCancelled()) {
                break;
            }
            Artist artist = artists.find(artistId).orElse(null);
            progress.artistStarted(artistId, artist == null ? "artist " + artistId : artist.name());
            ArtistProviderBulkMatchItem item = artist == null
                    ? errorItem(artistId, "Unknown artist: " + artistId)
                    : matchArtist(providerId, artist);
            items.add(item);
            progress.itemFinished(item, items.size());
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

    private ArtistProviderBulkMatchItem matchArtist(String providerId, Artist artist) {
        try {
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
            List<ArtistProviderCandidate> candidates = matches.searchBulkCandidates(artist.id(), providerId);
            if (candidates.isEmpty()) {
                return item(artist, STATUS_NO_MATCH, "No " + label + " candidates found.", null, null, List.of());
            }

            ArtistProviderCandidate top = candidates.get(0);
            ArtistProviderCandidate accepted = candidates.stream()
                    .filter(ArtistProviderBulkMatchService::isHighConfidenceProviderMatch)
                    .findFirst()
                    .orElse(null);
            if (accepted != null) {
                ArtistProviderCandidate selected = enrichSelectedCandidate(accepted);
                String providerUrl = canonicalProviderUrl(selected);
                ArtistProviderLink link = providerLinks.upsertForArtist(
                        artist.id(),
                        selected.providerId(),
                        selected.providerArtistId(),
                        selected.providerArtistName(),
                        providerUrl,
                        selected.country(),
                        selected.disambiguation(),
                        selected.active(),
                        true);
                return item(artist, STATUS_MATCHED,
                        "Auto-linked " + label + " provider: " + selected.providerArtistName()
                                + " (" + selected.evidenceSummary() + ")",
                        link,
                        summaryCandidate(selected),
                        List.of());
            }
            return item(artist, STATUS_NEEDS_MANUAL,
                    label + " candidates need manual selection before linking.",
                    null,
                    null,
                    List.of(summaryCandidate(top)));
        } catch (Exception e) {
            return errorItem(artist.id(), e.getMessage());
        }
    }

    private ArtistProviderCandidate enrichSelectedCandidate(ArtistProviderCandidate candidate) {
        return matches.enrichSelectedCandidate(candidate);
    }

    static boolean isHighConfidenceProviderMatch(ArtistProviderCandidate candidate) {
        return ProviderCandidateEvidenceEvaluator.isHighConfidenceMatch(candidate);
    }

    public static String providerLabel(String providerId) {
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

    private static ArtistProviderBulkMatchItem errorItem(long artistId, String message) {
        return new ArtistProviderBulkMatchItem(
                artistId,
                null,
                STATUS_ERROR,
                message,
                null,
                null,
                List.of());
    }

    private static ArtistProviderCandidate summaryCandidate(ArtistProviderCandidate candidate) {
        return new ArtistProviderCandidate(
                candidate.providerId(),
                candidate.providerArtistId(),
                candidate.providerArtistName(),
                candidate.providerUrl(),
                candidate.country(),
                candidate.disambiguation(),
                candidate.active(),
                candidate.providerScore(),
                candidate.finalScore(),
                candidate.nameScore(),
                candidate.albumEvidenceScore(),
                candidate.yearBonus(),
                candidate.evidenceSummary(),
                List.of());
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void started(int itemTotal) {
        }

        default void artistStarted(long artistId, String artistName) {
        }

        default void itemFinished(ArtistProviderBulkMatchItem item, int itemProcessed) {
        }

        default boolean isCancelled() {
            return false;
        }
    }
}
