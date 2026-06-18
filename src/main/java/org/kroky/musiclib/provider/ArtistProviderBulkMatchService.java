package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderBulkMatchItem;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderBulkMatchService {

    public static final String STATUS_MATCHED = "MATCHED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_NO_MATCH = "NO_MATCH";
    public static final String STATUS_SKIPPED_EXISTING = "SKIPPED_EXISTING";
    public static final String STATUS_ERROR = "ERROR";

    @Inject
    ArtistRepository artists;

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    ArtistProviderMatchService matches;

    public ArtistProviderBulkMatchResult matchMusicBrainzArtists(List<Long> requestedArtistIds) {
        List<Long> artistIds = artistIds(requestedArtistIds);
        List<ArtistProviderBulkMatchItem> items = new ArrayList<>();
        for (Long artistId : artistIds) {
            if (artistId == null) {
                continue;
            }
            items.add(matchArtist(artistId));
        }

        int matched = count(items, STATUS_MATCHED);
        int review = count(items, STATUS_NEEDS_REVIEW);
        int noMatch = count(items, STATUS_NO_MATCH);
        int skipped = count(items, STATUS_SKIPPED_EXISTING);
        int errors = count(items, STATUS_ERROR);
        int processed = Math.max(0, items.size() - skipped);
        String message = "MusicBrainz bulk match: matched " + matched
                + ", review " + review
                + ", no match " + noMatch
                + ", skipped " + skipped
                + ", errors " + errors + ".";
        return new ArtistProviderBulkMatchResult(
                items.size(),
                processed,
                matched,
                review,
                noMatch,
                skipped,
                errors,
                items,
                List.of(message));
    }

    private ArtistProviderBulkMatchItem matchArtist(long artistId) {
        try {
            Artist artist = artists.find(artistId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
            var existing = providerLinks.findByArtist(artist.id());
            if (existing.isPresent()) {
                ArtistProviderLink link = existing.get();
                return item(artist, STATUS_SKIPPED_EXISTING,
                        "Existing provider configured: " + link.providerId(),
                        link,
                        null,
                        List.of());
            }

            List<ArtistProviderCandidate> candidates = matches.searchMusicBrainzCandidates(artist.id());
            if (candidates.isEmpty()) {
                return item(artist, STATUS_NO_MATCH, "No MusicBrainz candidates found.", null, null, candidates);
            }

            ArtistProviderCandidate top = candidates.get(0);
            ArtistProviderCandidate runnerUp = candidates.size() > 1 ? candidates.get(1) : null;
            if (isHighConfidenceMusicBrainzMatch(artist.name(), top, runnerUp)) {
                ArtistProviderLink link = providerLinks.upsertForArtist(
                        artist.id(),
                        MusicBrainzClient.PROVIDER_ID,
                        top.providerArtistId(),
                        top.providerArtistName(),
                        top.providerUrl(),
                        top.type(),
                        top.country(),
                        top.disambiguation(),
                        top.active(),
                        true);
                return item(artist, STATUS_MATCHED,
                        "Auto-linked MusicBrainz provider: " + top.providerArtistName(),
                        link,
                        top,
                        candidates);
            }
            return item(artist, STATUS_NEEDS_REVIEW,
                    "MusicBrainz candidates need review before linking.",
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
        if (candidate == null) {
            return false;
        }
        int matchedAlbumCount = candidate.matchedLocalAlbums() == null ? 0 : candidate.matchedLocalAlbums().size();
        if (matchedAlbumCount == 0 || candidate.providerScore() < 80) {
            return false;
        }
        int margin = runnerUp == null ? 100 : candidate.matchScore() - runnerUp.matchScore();
        if (margin < 8) {
            return false;
        }
        if (matchedAlbumCount >= 2 && candidate.matchScore() >= 85) {
            return true;
        }
        boolean exactName = Names.normalize(artistName).equals(Names.normalize(candidate.providerArtistName()));
        return exactName
                && matchedAlbumCount >= 1
                && candidate.providerScore() >= 95
                && candidate.matchScore() >= 75
                && margin >= 12;
    }

    private List<Long> artistIds(List<Long> requestedArtistIds) {
        if (requestedArtistIds == null) {
            return artists.list(null, null).stream().map(Artist::id).toList();
        }
        if (requestedArtistIds.isEmpty()) {
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
