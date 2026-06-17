package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumImportCandidate;
import org.kroky.musiclib.model.AlbumMatchOption;
import org.kroky.musiclib.model.AlbumReviewDecision;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderRefreshResult;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ProviderCheckRunRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderRefreshService {

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository albumProviderLinks;

    @Inject
    ProviderCheckRunRepository runs;

    @Inject
    MusicBrainzClient musicBrainz;

    public ProviderRefreshResult refreshArtist(long artistId) throws ProviderException {
        ArtistProviderLink link = enabledMusicBrainzProvider(artistId);
        long runId = runs.start(artistId, link.id());
        try {
            ProviderRefreshResult result = importMusicBrainz(runId, link);
            runs.finish(runId, "DONE", 1, result.foundReleaseGroupCount(), result.createdAlbumCount(),
                    result.linkedExistingCount(), 0, result.messages().get(result.messages().size() - 1));
            providerLinks.markSuccess(link.id());
            return result;
        } catch (Exception e) {
            String message = "MusicBrainz refresh failed for " + link.artistName() + ": " + e.getMessage();
            runs.event(runId, link.artistId(), link.id(), "ERROR", message);
            runs.finish(runId, "FAILED", 1, 0, 0, 0, 1, message);
            providerLinks.markError(link.id(), e.getMessage());
            if (e instanceof ProviderException providerException) {
                throw providerException;
            }
            throw new ProviderException(message, e);
        }
    }

    public ProviderRefreshResult importMusicBrainz(long runId, ArtistProviderLink link) throws ProviderException {
        requireMusicBrainzIdentity(link);
        List<RemoteReleaseGroup> releaseGroups = musicBrainz.fetchReleaseGroups(link.providerArtistId());
        List<Album> localAlbums = new ArrayList<>(albums.list(link.artistId(), null, null, null, null));
        List<AlbumImportCandidate> reviewCandidates = new ArrayList<>();
        int linkedExisting = 0;
        int createdAlbums = 0;
        int skipped = 0;

        runs.event(runId, link.artistId(), link.id(), "INFO",
                "Found " + releaseGroups.size() + " MusicBrainz release groups for " + link.artistName());

        for (RemoteReleaseGroup releaseGroup : releaseGroups) {
            AlbumImportCandidate candidate = classify(releaseGroup, localAlbums);
            switch (candidate.decision()) {
                case "SKIP_ALREADY_LINKED", "SKIP_UNSUPPORTED" -> skipped++;
                case "AUTO_MATCH_EXISTING" -> {
                    Album album = albumById(localAlbums, candidate.matchedAlbumId());
                    albums.updateReleaseDateIfMissing(album.id(), releaseGroup.releaseDate());
                    linkAlbum(album.id(), releaseGroup, "AUTO");
                    linkedExisting++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Linked MusicBrainz release group to album: " + album.title());
                }
                case "AUTO_CREATE" -> {
                    Album album = albums.create(link.artistId(), releaseGroup.title(), releaseGroup.releaseDate(),
                            false, null, null);
                    linkAlbum(album.id(), releaseGroup, "AUTO");
                    localAlbums.add(album);
                    createdAlbums++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Added unchecked MusicBrainz album: " + album.title());
                }
                case "REVIEW" -> reviewCandidates.add(candidate);
                default -> throw new ProviderException("Unsupported album import decision: " + candidate.decision());
            }
        }

        String message = "MusicBrainz refresh found " + releaseGroups.size()
                + " release groups, linked " + linkedExisting
                + ", created " + createdAlbums
                + ", review " + reviewCandidates.size()
                + ", skipped " + skipped + ".";
        runs.event(runId, link.artistId(), link.id(), reviewCandidates.isEmpty() ? "INFO" : "WARN", message);
        return new ProviderRefreshResult(
                runId,
                link.artistId(),
                link.artistName(),
                link.providerId(),
                releaseGroups.size(),
                linkedExisting,
                createdAlbums,
                reviewCandidates.size(),
                skipped,
                reviewCandidates,
                List.of(message));
    }

    public ProviderRefreshResult applyDecisions(long artistId, List<AlbumReviewDecision> decisions)
            throws ProviderException {
        ArtistProviderLink link = enabledMusicBrainzProvider(artistId);
        long runId = runs.start(artistId, link.id());
        try {
            Map<String, RemoteReleaseGroup> releaseGroups = new HashMap<>();
            for (RemoteReleaseGroup releaseGroup : musicBrainz.fetchReleaseGroups(link.providerArtistId())) {
                releaseGroups.put(releaseGroup.providerReleaseGroupId(), releaseGroup);
            }

            int linkedExisting = 0;
            int createdAlbums = 0;
            int skipped = 0;
            List<String> messages = new ArrayList<>();
            for (AlbumReviewDecision decision : decisions == null ? List.<AlbumReviewDecision>of() : decisions) {
                if (!MusicBrainzClient.PROVIDER_ID.equals(decision.providerId())) {
                    throw new IllegalArgumentException("Unsupported provider: " + decision.providerId());
                }
                RemoteReleaseGroup releaseGroup = releaseGroups.get(decision.providerReleaseGroupId());
                if (releaseGroup == null) {
                    throw new IllegalArgumentException("Unknown MusicBrainz release group: "
                            + decision.providerReleaseGroupId());
                }
                if (albumProviderLinks.findAlbumId(MusicBrainzClient.PROVIDER_ID,
                        releaseGroup.providerReleaseGroupId()).isPresent()) {
                    skipped++;
                    continue;
                }
                switch (decision.action()) {
                    case "LINK_EXISTING" -> {
                        Album album = requireArtistAlbum(artistId, decision.albumId());
                        linkAlbum(album.id(), releaseGroup, "MANUAL");
                        linkedExisting++;
                        messages.add("Linked " + releaseGroup.title() + " to " + album.title());
                    }
                    case "CREATE" -> {
                        Album album = albums.create(artistId, releaseGroup.title(), releaseGroup.releaseDate(),
                                false, null, null);
                        linkAlbum(album.id(), releaseGroup, "MANUAL");
                        createdAlbums++;
                        messages.add("Created unchecked album " + album.title());
                    }
                    case "SKIP" -> skipped++;
                    default -> throw new IllegalArgumentException("Unsupported review action: " + decision.action());
                }
            }

            String message = "Applied MusicBrainz decisions: linked " + linkedExisting
                    + ", created " + createdAlbums
                    + ", skipped " + skipped + ".";
            messages.add(message);
            runs.event(runId, artistId, link.id(), "INFO", message);
            runs.finish(runId, "DONE", 1, decisions == null ? 0 : decisions.size(), createdAlbums,
                    linkedExisting, 0, message);
            providerLinks.markSuccess(link.id());
            return new ProviderRefreshResult(runId, artistId, link.artistName(), link.providerId(),
                    decisions == null ? 0 : decisions.size(), linkedExisting, createdAlbums, 0, skipped,
                    List.of(), messages);
        } catch (Exception e) {
            String message = "MusicBrainz decisions failed for " + link.artistName() + ": " + e.getMessage();
            runs.event(runId, artistId, link.id(), "ERROR", message);
            runs.finish(runId, "FAILED", 1, 0, 0, 0, 1, message);
            providerLinks.markError(link.id(), e.getMessage());
            if (e instanceof ProviderException providerException) {
                throw providerException;
            }
            throw new ProviderException(message, e);
        }
    }

    private AlbumImportCandidate classify(RemoteReleaseGroup releaseGroup, List<Album> localAlbums) {
        if (albumProviderLinks.findAlbumId(releaseGroup.providerId(), releaseGroup.providerReleaseGroupId()).isPresent()) {
            return candidate(releaseGroup, "SKIP_ALREADY_LINKED", null, null, "Already linked", List.of());
        }
        if (releaseGroup.title() == null || releaseGroup.title().isBlank()) {
            return candidate(releaseGroup, "SKIP_UNSUPPORTED", null, null, "Blank release group title", List.of());
        }
        if (!isSupportedPrimaryType(releaseGroup.primaryType())) {
            return candidate(releaseGroup, "SKIP_UNSUPPORTED", null, null,
                    "Unsupported primary type: " + releaseGroup.primaryType(), List.of());
        }

        List<Album> exactTitleMatches = localAlbums.stream()
                .filter(album -> Names.normalize(album.title()).equals(Names.normalize(releaseGroup.title())))
                .toList();
        List<AlbumMatchOption> options = matchOptions(releaseGroup, localAlbums);

        if (!releaseGroup.secondaryTypes().isEmpty()) {
            return candidate(releaseGroup, "REVIEW", firstOptionAlbumId(options), firstOptionTitle(options),
                    "Secondary type: " + String.join(", ", releaseGroup.secondaryTypes()), options);
        }
        if (exactTitleMatches.size() == 1) {
            Album album = exactTitleMatches.get(0);
            if (dateCompatible(album.releaseDate(), releaseGroup.releaseDate())) {
                return candidate(releaseGroup, "AUTO_MATCH_EXISTING", album.id(), album.title(),
                        "Exact title and compatible release date", options);
            }
            return candidate(releaseGroup, "REVIEW", album.id(), album.title(),
                    "Exact title but release date differs", options);
        }
        if (exactTitleMatches.size() > 1) {
            return candidate(releaseGroup, "REVIEW", null, null, "Multiple exact local title matches", options);
        }
        if (options.stream().anyMatch(option -> option.matchScore() >= 80)) {
            AlbumMatchOption option = options.get(0);
            return candidate(releaseGroup, "REVIEW", option.albumId(), option.title(),
                    "Similar local album title", options);
        }
        return candidate(releaseGroup, "AUTO_CREATE", null, null, "No likely local duplicate", options);
    }

    private List<AlbumMatchOption> matchOptions(RemoteReleaseGroup releaseGroup, List<Album> localAlbums) {
        return localAlbums.stream()
                .map(album -> new AlbumMatchOption(
                        album.id(),
                        album.title(),
                        album.releaseDate(),
                        titleMatchScore(releaseGroup.title(), album.title()),
                        matchReason(releaseGroup, album)))
                .filter(option -> option.matchScore() >= 55)
                .sorted(Comparator.comparingInt(AlbumMatchOption::matchScore).reversed()
                        .thenComparing(AlbumMatchOption::title, String::compareToIgnoreCase))
                .limit(5)
                .toList();
    }

    private String matchReason(RemoteReleaseGroup releaseGroup, Album album) {
        if (Names.normalize(releaseGroup.title()).equals(Names.normalize(album.title()))) {
            return dateCompatible(album.releaseDate(), releaseGroup.releaseDate())
                    ? "Exact title and compatible release date"
                    : "Exact title with different release date";
        }
        return "Similar title";
    }

    private void linkAlbum(long albumId, RemoteReleaseGroup releaseGroup, String matchSource) {
        albumProviderLinks.linkAlbum(
                albumId,
                releaseGroup.providerId(),
                releaseGroup.providerReleaseGroupId(),
                releaseGroup.title(),
                releaseGroup.releaseDate(),
                releaseGroup.providerUrl(),
                matchSource);
    }

    private ArtistProviderLink enabledMusicBrainzProvider(long artistId) {
        ArtistProviderLink link = providerLinks.findByArtist(artistId)
                .orElseThrow(() -> new IllegalArgumentException("No provider assigned to artist " + artistId));
        if (!link.enabled()) {
            throw new IllegalArgumentException("Artist provider is disabled");
        }
        requireMusicBrainzIdentity(link);
        return link;
    }

    private static void requireMusicBrainzIdentity(ArtistProviderLink link) {
        if (!MusicBrainzClient.PROVIDER_ID.equals(link.providerId())) {
            throw new IllegalArgumentException("Artist provider is not MusicBrainz");
        }
        if (link.providerArtistId() == null || link.providerArtistId().isBlank()) {
            throw new IllegalArgumentException("MusicBrainz artist MBID is required");
        }
    }

    private Album requireArtistAlbum(long artistId, Long albumId) {
        if (albumId == null) {
            throw new IllegalArgumentException("albumId is required for LINK_EXISTING");
        }
        Album album = albums.find(albumId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown album: " + albumId));
        if (!album.artistIds().contains(artistId)) {
            throw new IllegalArgumentException("Album does not belong to artist " + artistId);
        }
        return album;
    }

    private static Album albumById(List<Album> albums, Long albumId) {
        return albums.stream()
                .filter(album -> album.id() == albumId)
                .findFirst()
                .orElseThrow();
    }

    private static AlbumImportCandidate candidate(RemoteReleaseGroup releaseGroup, String decision,
            Long matchedAlbumId, String matchedAlbumTitle, String reason, List<AlbumMatchOption> options) {
        return new AlbumImportCandidate(releaseGroup, decision, matchedAlbumId, matchedAlbumTitle, reason, options);
    }

    private static Long firstOptionAlbumId(List<AlbumMatchOption> options) {
        return options.isEmpty() ? null : options.get(0).albumId();
    }

    private static String firstOptionTitle(List<AlbumMatchOption> options) {
        return options.isEmpty() ? null : options.get(0).title();
    }

    private static boolean isSupportedPrimaryType(String primaryType) {
        return "Album".equalsIgnoreCase(primaryType) || "EP".equalsIgnoreCase(primaryType);
    }

    private static boolean dateCompatible(String localReleaseDate, String remoteReleaseDate) {
        if (localReleaseDate == null || remoteReleaseDate == null) {
            return true;
        }
        return localReleaseDate.equals(remoteReleaseDate)
                || releaseYear(localReleaseDate).equals(releaseYear(remoteReleaseDate));
    }

    private static String releaseYear(String releaseDate) {
        return releaseDate == null || releaseDate.length() < 4 ? "" : releaseDate.substring(0, 4);
    }

    private static int titleMatchScore(String leftTitle, String rightTitle) {
        String left = Names.normalize(leftTitle);
        String right = Names.normalize(rightTitle);
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        if (left.equals(right)) {
            return 100;
        }
        if (left.contains(right) || right.contains(left)) {
            return Math.max(80, (int) Math.round(100.0 * Math.min(left.length(), right.length())
                    / Math.max(left.length(), right.length())));
        }
        int distance = levenshtein(left, right);
        return Math.max(0, 100 - (int) Math.round(100.0 * distance / Math.max(left.length(), right.length())));
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
