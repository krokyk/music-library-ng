package org.kroky.musiclib.provider;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;
import org.kroky.musiclib.repository.ProviderCheckRunRepository;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckService {

    private static final Logger LOG = Logger.getLogger(ProviderCheckService.class);

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void started(int itemTotal, int skippedArtists) {
        }

        default void artistStarted(ArtistProviderLink link) {
        }

        default void itemSkipped(ArtistProviderLink link, int itemProcessed, int skippedArtists, String reason) {
        }

        default void itemFinished(ArtistProviderLink link, int itemProcessed, int skippedArtists,
                int foundAlbums, int newAlbums, int existingAlbums, int errors) {
        }

        default boolean isCancelled() {
            return false;
        }
    }

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    ArtistRepository artists;

    @Inject
    MusicCollectionRepository collections;

    @Inject
    AlbumRepository albums;

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderCheckRunRepository runs;

    @Inject
    ArtistProviderRefreshService artistProviderRefresh;

    public ProviderCheckSummary checkLink(long linkId) {
        ArtistProviderLink link = providerLinks.find(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider link: " + linkId));
        long runId = runs.start(link.artistId(), link.id());
        return checkLinks(runId, List.of(link), 0, null, 0, false, ProgressListener.NONE);
    }

    public ProviderCheckSummary checkArtist(long artistId) {
        return checkArtist(artistId, null);
    }

    public ProviderCheckSummary checkArtist(long artistId, String collectionId) {
        if (collectionId != null && collections.find(collectionId).isEmpty()) {
            throw new IllegalArgumentException("Unknown collection: " + collectionId);
        }
        List<ArtistProviderLink> links = providerLinks.listByArtist(artistId).stream()
                .filter(ArtistProviderLink::enabled)
                .toList();
        long runId = runs.start(artistId, null);
        int skippedArtists = links.isEmpty() ? 1 : 0;
        return checkLinks(runId, links, skippedArtists, collectionId, 0, false, ProgressListener.NONE);
    }

    public ProviderCheckSummary checkCollection(String collectionId) {
        return checkCollection(collectionId, 0, ProgressListener.NONE);
    }

    public ProviderCheckSummary checkCollection(String collectionId, int batchRescanDelayMinutes,
            ProgressListener progress) {
        MusicCollection collection = collections.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        List<ArtistProviderLink> links = providerLinks.listEnabledByCollection(collection.id());
        int artistCount = artists.list(null, collection.id()).size();
        int artistsWithProviders = (int) links.stream().map(ArtistProviderLink::artistId).distinct().count();
        int skippedArtists = Math.max(0, artistCount - artistsWithProviders);
        long runId = runs.start(null, null);
        return checkLinks(runId, links, skippedArtists, collection.id(), batchRescanDelayMinutes, true, progress);
    }

    public ProviderCheckSummary checkAll() {
        return checkAll(0, ProgressListener.NONE);
    }

    public ProviderCheckSummary checkAll(int batchRescanDelayMinutes, ProgressListener progress) {
        long runId = runs.start(null, null);
        return checkLinks(runId, providerLinks.listEnabled(), 0, null, batchRescanDelayMinutes, true, progress);
    }

    private ProviderCheckSummary checkLinks(long runId, List<ArtistProviderLink> links, int skippedArtists,
            String collectionId, int batchRescanDelayMinutes, boolean skipRecentlyChecked,
            ProgressListener progress) {
        int processedArtists = 0;
        int initialSkippedArtists = skippedArtists;
        int recentlySkippedArtists = 0;
        int processedItems = skippedArtists;
        int foundAlbums = 0;
        int newAlbums = 0;
        int existingAlbums = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();
        progress.started(links.size() + initialSkippedArtists, skippedArtists);

        if (links.isEmpty()) {
            String message = skippedArtists > 0
                    ? "No enabled provider links found; skipped " + skippedArtists + " artists."
                    : "No enabled provider links found.";
            runs.event(runId, null, null, "WARN", message);
            runs.finish(runId, "SKIPPED", 0, 0, 0, 0, 0, message);
            return new ProviderCheckSummary(runId, 0, skippedArtists, 0, 0, 0, 0, List.of(message));
        }

        for (ArtistProviderLink link : links) {
            if (progress.isCancelled()) {
                String message = "Provider check cancelled.";
                messages.add(message);
                runs.event(runId, null, null, "INFO", message);
                runs.finish(runId, "SKIPPED", processedArtists, foundAlbums, newAlbums, existingAlbums, errors, message);
                return new ProviderCheckSummary(runId, processedArtists, skippedArtists, foundAlbums, newAlbums,
                        existingAlbums, errors, messages);
            }
            if (skipRecentlyChecked && recentlyChecked(link, batchRescanDelayMinutes)) {
                skippedArtists++;
                recentlySkippedArtists++;
                processedItems++;
                String reason = "successfully checked within " + delayLabel(batchRescanDelayMinutes);
                String message = "Skipped " + link.artistName() + ": " + reason + ".";
                messages.add(message);
                runs.event(runId, link.artistId(), link.id(), "INFO", message);
                progress.itemSkipped(link, processedItems, skippedArtists, reason);
                continue;
            }
            progress.artistStarted(link);
            try {
                processedArtists++;
                if (MusicBrainzClient.PROVIDER_ID.equals(link.providerId())) {
                    var result = artistProviderRefresh.importMusicBrainz(runId, link, collectionId);
                    foundAlbums += result.foundReleaseGroupCount();
                    newAlbums += result.createdAlbumCount();
                    existingAlbums += result.existingAlbumCount();
                    messages.addAll(result.messages());
                    providerLinks.markSuccess(link.id());
                    processedItems++;
                    progress.itemFinished(link, processedItems, skippedArtists, foundAlbums, newAlbums,
                            existingAlbums, errors);
                    continue;
                }
                DiscographyProvider provider = providers.find(link.providerId(), link.providerUrl());
                ProviderArtistDetails details = provider.fetchArtistDetails(link.providerUrl());
                providerLinks.updateProviderMetadata(link.id(), details.country(), details.active());
                List<RemoteAlbum> remoteAlbums = details.albums();
                foundAlbums += remoteAlbums.size();
                runs.event(runId, link.artistId(), link.id(), "INFO",
                        "Read " + remoteAlbums.size() + " " + providerLabel(link.providerId())
                                + " albums for " + link.artistName());
                int linkExistingAlbums = 0;
                int linkNewAlbums = 0;
                for (RemoteAlbum remoteAlbum : remoteAlbums) {
                    var existing = albums.findDuplicate(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseDate());
                    if (existing.isPresent()) {
                        assignToCollectionIfUnassigned(existing.get(), collectionId);
                        existingAlbums++;
                        linkExistingAlbums++;
                        continue;
                    }
                    albums.create(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseDate(), false, null,
                            collectionId);
                    newAlbums++;
                    linkNewAlbums++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Added unchecked album: " + remoteAlbum.title());
                }
                runs.event(runId, link.artistId(), link.id(), "INFO",
                        "Provider check for " + link.artistName() + " read " + remoteAlbums.size()
                                + " " + providerLabel(link.providerId()) + " albums, already in library "
                                + linkExistingAlbums + ", added " + linkNewAlbums + " unchecked albums.");
                providerLinks.markSuccess(link.id());
            } catch (Exception e) {
                errors++;
                String errorDetail = ProviderException.describe(e);
                String message = "Provider check failed for " + link.artistName() + ": " + errorDetail;
                messages.add(message);
                providerLinks.markError(link.id(), errorDetail);
                runs.event(runId, link.artistId(), link.id(), "ERROR", message);
                LOG.errorf(e, "Provider check failed runId=%d artistId=%d providerLinkId=%d artist=%s: %s",
                        runId, link.artistId(), link.id(), link.artistName(), errorDetail);
            }
            processedItems++;
            progress.itemFinished(link, processedItems, skippedArtists, foundAlbums, newAlbums, existingAlbums, errors);
        }

        String status = errors == 0 ? "DONE" : "FAILED";
        String message = "Checked " + processedArtists + " provider links, found " + foundAlbums
                + " provider albums, added " + newAlbums + " unchecked albums"
                + skippedSummary(skippedArtists, initialSkippedArtists, recentlySkippedArtists) + ".";
        runs.finish(runId, status, processedArtists, foundAlbums, newAlbums, existingAlbums, errors, message);
        messages.add(message);
        return new ProviderCheckSummary(runId, processedArtists, skippedArtists, foundAlbums, newAlbums,
                existingAlbums, errors, messages);
    }

    private void assignToCollectionIfUnassigned(org.kroky.musiclib.model.Album album, String collectionId) {
        if (collectionId != null && album.collections().isEmpty() && album.localPaths().isEmpty()) {
            albums.assignToCollection(album.id(), collectionId);
        }
    }

    private static String skippedSummary(int skippedArtists, int initialSkippedArtists, int recentlySkippedArtists) {
        if (skippedArtists <= 0) {
            return "";
        }
        if (recentlySkippedArtists > 0 && initialSkippedArtists > 0) {
            return ", skipped " + skippedArtists + " artists (" + recentlySkippedArtists
                    + " already checked successfully, " + initialSkippedArtists + " without providers)";
        }
        if (recentlySkippedArtists > 0) {
            return ", skipped " + recentlySkippedArtists + " artists already checked successfully";
        }
        return ", skipped " + initialSkippedArtists + " artists without providers";
    }

    private static boolean recentlyChecked(ArtistProviderLink link, int batchRescanDelayMinutes) {
        if (batchRescanDelayMinutes <= 0 || link.lastSuccessAt() == null || link.lastSuccessAt().isBlank()) {
            return false;
        }
        Optional<LocalDateTime> successAt = parseTimestamp(link.lastSuccessAt());
        if (successAt.isEmpty()) {
            return false;
        }
        Optional<LocalDateTime> errorAt = link.lastErrorAt() == null || link.lastErrorAt().isBlank()
                ? Optional.empty()
                : parseTimestamp(link.lastErrorAt());
        // A newer error means the next batch run should be allowed to retry immediately.
        if (errorAt.isPresent() && !errorAt.get().isBefore(successAt.get())) {
            return false;
        }
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(batchRescanDelayMinutes);
        return !successAt.get().isBefore(cutoff);
    }

    private static Optional<LocalDateTime> parseTimestamp(String value) {
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)) {
            try {
                return Optional.of(LocalDateTime.parse(trimmed, formatter));
            } catch (DateTimeParseException e) {
                // Try the next known SQLite/ISO timestamp shape.
            }
        }
        return Optional.empty();
    }

    private static String delayLabel(int minutes) {
        if (minutes <= 0) {
            return "the batch rescan delay";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes < 1_440) {
            int hours = minutes / 60;
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        int days = minutes / 1_440;
        return days == 1 ? "1 day" : days + " days";
    }

    private static String providerLabel(String providerId) {
        return switch (providerId) {
            case "metal_archives" -> "Metal Archives";
            case "spirit_of_metal" -> "Spirit of Metal";
            case MusicBrainzClient.PROVIDER_ID -> "MusicBrainz";
            default -> providerId == null || providerId.isBlank() ? "provider" : providerId;
        };
    }
}
