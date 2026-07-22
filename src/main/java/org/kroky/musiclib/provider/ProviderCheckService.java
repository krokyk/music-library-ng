package org.kroky.musiclib.provider;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;
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
                int foundAlbums, int newAlbums, int existingAlbums, int releaseYearConflicts, int titleConflicts,
                int errors) {
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
    ProviderCheckReportWriter reportWriter;

    @Inject
    MusicBrainzClient musicBrainz;

    @Inject
    ProviderDiscographyReconciliationService discographyReconciliation;

    public ProviderCheckSummary checkArtist(long artistId, Long collectionId) {
        if (collectionId != null) {
            MusicCollection target = collections.find(collectionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
            if (target.type() != org.kroky.musiclib.model.CollectionType.ARTIST) {
                throw new IllegalArgumentException("Provider checks are not available for title-centric collections.");
            }
        }
        if (albums.majorArtistCollection(artistId) == null) {
            throw new IllegalArgumentException("Provider checks are not available for title-centric artists.");
        }
        List<ArtistProviderLink> links = providerLinks.listByArtist(artistId).stream()
                .filter(ArtistProviderLink::enabled)
                .toList();
        int skippedArtists = links.isEmpty() ? 1 : 0;
        String artistName = artists.find(artistId).map(artist -> artist.name()).orElse("artist " + artistId);
        ProviderCheckReport report = new ProviderCheckReport(artistName + " provider check");
        if (links.isEmpty()) {
            report.artistSkipped(artistName + ": no enabled provider links");
        }
        return checkLinks(report, links, skippedArtists, collectionId, 0, false, ProgressListener.NONE);
    }

    public ProviderCheckSummary checkCollection(long collectionId, int batchRescanDelayMinutes,
            ProgressListener progress) {
        MusicCollection collection = collections.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        if (collection.type() != org.kroky.musiclib.model.CollectionType.ARTIST) {
            throw new IllegalArgumentException("Provider checks are not available for title-centric collections.");
        }
        List<ArtistProviderLink> links = providerLinks.listEnabledByCollection(collection.id());
        int artistCount = artists.list(null, collection.id()).size();
        int artistsWithProviders = (int) links.stream().map(ArtistProviderLink::artistId).distinct().count();
        int skippedArtists = Math.max(0, artistCount - artistsWithProviders);
        ProviderCheckReport report = new ProviderCheckReport(collection.name() + " provider check");
        if (skippedArtists > 0) {
            report.artistSkipped(skippedArtists + " artists without enabled provider links");
        }
        return checkLinks(report, links, skippedArtists, collection.id(), batchRescanDelayMinutes, true, progress);
    }

    private ProviderCheckSummary checkLinks(ProviderCheckReport report, List<ArtistProviderLink> links,
            int skippedArtists,
            Long collectionId, int batchRescanDelayMinutes, boolean skipRecentlyChecked,
            ProgressListener progress) {
        int processedArtists = 0;
        int initialSkippedArtists = skippedArtists;
        int recentlySkippedArtists = 0;
        int processedItems = skippedArtists;
        int foundAlbums = 0;
        int newAlbums = 0;
        int existingAlbums = 0;
        int releaseYearConflicts = 0;
        int titleConflicts = 0;
        int ignoredProviderRecords = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();
        Set<Long> reconciledArtistIds = new HashSet<>();
        progress.started(links.size() + initialSkippedArtists, skippedArtists);

        if (links.isEmpty()) {
            String message = skippedArtists > 0
                    ? "No enabled provider links found; skipped " + skippedArtists + " artists."
                    : "No enabled provider links found.";
            report.note(message);
            return providerSummary(report, "SKIPPED", 0, skippedArtists, 0, 0, 0, 0, 0, 0, 0, List.of(message));
        }

        for (ArtistProviderLink link : links) {
            if (skipRecentlyChecked && recentlyChecked(link, batchRescanDelayMinutes)) {
                skippedArtists++;
                recentlySkippedArtists++;
                processedItems++;
                String reason = "successfully checked within " + delayLabel(batchRescanDelayMinutes);
                String message = "Skipped " + link.artistName() + ": " + reason + ".";
                messages.add(message);
                report.artistSkipped(message);
                progress.itemSkipped(link, processedItems, skippedArtists, reason);
                continue;
            }
            progress.artistStarted(link);
            try {
                processedArtists++;
                if (!reconciledArtistIds.contains(link.artistId())) {
                    removeStaleLocalPaths(link);
                    reconciledArtistIds.add(link.artistId());
                }
                FetchedDiscography fetched = fetchDiscography(link, report);
                foundAlbums += fetched.foundCount();
                ignoredProviderRecords += fetched.ignoredCount();
                var result = discographyReconciliation.reconcile(
                        link,
                        fetched.country(),
                        fetched.active(),
                        fetched.releases(),
                        collectionId);
                reportReconciliation(link, result, report);
                newAlbums += result.createdAlbumCount();
                existingAlbums += result.existingAlbumCount();
                releaseYearConflicts += result.releaseYearConflictCount();
                titleConflicts += result.titleConflictCount();
                if (result.createdAlbumCount() == 0
                        && result.releaseYearConflictCount() == 0
                        && result.titleConflictCount() == 0
                        && fetched.ignoredCount() == 0
                        && !result.changedLibraryMetadata()) {
                    report.noChange(link.artistName() + " (" + providerLabel(link.providerId()) + ")");
                }
            } catch (Exception e) {
                errors++;
                String errorDetail = ProviderException.describe(e);
                String message = "Provider check failed for " + link.artistName() + ": " + errorDetail;
                messages.add(message);
                markProviderError(link, errorDetail);
                report.error(link.artistName() + " (" + providerLabel(link.providerId()) + "): " + errorDetail);
                LOG.errorf(e, "Provider check failed artistId=%d providerLinkId=%d artist=%s: %s",
                        link.artistId(), link.id(), link.artistName(), errorDetail);
            }
            processedItems++;
            progress.itemFinished(link, processedItems, skippedArtists, foundAlbums, newAlbums, existingAlbums,
                    releaseYearConflicts, titleConflicts, errors);
        }

        String status = errors == 0 ? "DONE" : "FAILED";
        String message = "Checked " + processedArtists + " provider links, found " + foundAlbums
                + " provider albums, added " + newAlbums + " unchecked albums"
                + (releaseYearConflicts > 0 ? ", " + releaseYearConflicts + " release year conflicts" : "")
                + (titleConflicts > 0 ? ", " + titleConflicts + " title conflicts" : "")
                + skippedSummary(skippedArtists, initialSkippedArtists, recentlySkippedArtists) + ".";
        messages.add(message);
        return providerSummary(report, status, processedArtists, skippedArtists, foundAlbums, newAlbums,
                existingAlbums, releaseYearConflicts, titleConflicts, ignoredProviderRecords, errors, messages);
    }

    void removeStaleLocalPaths(ArtistProviderLink link) {
        List<String> stalePaths = albums.list(link.artistId(), null, null, true, null).stream()
                .filter(album -> !album.onDisk())
                .map(Album::resolvedPath)
                .toList();
        if (stalePaths.isEmpty()) {
            return;
        }
        int removed = albums.removeStaleLocalPathsForArtist(link.artistId());
        if (removed > 0) {
            LOG.warnf("Removed %d stale local path row%s before provider refresh for artist id=%d name=%s: %s",
                    removed,
                    removed == 1 ? "" : "s",
                    link.artistId(),
                    link.artistName(),
                    String.join(", ", stalePaths));
        }
    }

    private ProviderCheckSummary providerSummary(ProviderCheckReport report, String status, int processedArtists,
            int skippedArtists, int foundAlbums, int newAlbums, int existingAlbums, int releaseYearConflicts,
            int titleConflicts, int ignoredProviderRecords, int errors, List<String> messages) {
        String message = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        report.finish(status, processedArtists, skippedArtists, foundAlbums, existingAlbums, newAlbums,
                releaseYearConflicts, titleConflicts, ignoredProviderRecords, errors, message);
        return new ProviderCheckSummary(processedArtists, skippedArtists, foundAlbums, newAlbums, existingAlbums,
                releaseYearConflicts, titleConflicts, errors, messages, writeReport(report));
    }

    private List<ReportArtifact> writeReport(ProviderCheckReport report) {
        try {
            return List.of(reportWriter.write(report));
        } catch (Exception e) {
            LOG.warnf("Unable to write provider check report for %s: %s", report.subject(), e.getMessage());
            return List.of();
        }
    }

    private FetchedDiscography fetchDiscography(ArtistProviderLink link, ProviderCheckReport report)
            throws ProviderException {
        if (MusicBrainzClient.PROVIDER_ID.equals(link.providerId())) {
            requireMusicBrainzIdentity(link);
            var artist = musicBrainz.fetchArtist(link.providerArtistId());
            List<RemoteReleaseGroup> fetched = musicBrainz.fetchReleaseGroups(link.providerArtistId());
            List<RemoteReleaseGroup> eligible = new ArrayList<>();
            int ignored = 0;
            for (RemoteReleaseGroup release : fetched) {
                String reason = ignoredMusicBrainzReason(release);
                if (reason == null) {
                    eligible.add(release);
                    continue;
                }
                ignored++;
                report.ignoredProviderRecord(link.artistName() + " (MusicBrainz): "
                        + blankValue(release == null ? null : release.title()) + " | reason: " + reason);
            }
            return new FetchedDiscography(artist.country(), artist.active(), eligible, fetched.size(), ignored);
        }

        DiscographyProvider provider = providers.find(link.providerId(), link.providerUrl());
        ProviderArtistDetails details = provider.fetchArtistDetails(link.providerUrl());
        List<RemoteAlbum> remoteAlbums = details.albums() == null ? List.of() : details.albums();
        List<RemoteReleaseGroup> releases = remoteAlbums.stream()
                .map(album -> remoteReleaseGroup(link.providerId(), providerReleaseGroupId(album), album))
                .toList();
        return new FetchedDiscography(details.country(), details.active(), releases, remoteAlbums.size(), 0);
    }

    private static String ignoredMusicBrainzReason(RemoteReleaseGroup release) {
        if (release == null || release.title() == null || release.title().isBlank()) {
            return "Blank provider album title";
        }
        if (release.providerReleaseGroupId() == null || release.providerReleaseGroupId().isBlank()) {
            return "Missing release-group id";
        }
        if (!"Album".equalsIgnoreCase(release.primaryType())) {
            return "Unsupported primary type: " + release.primaryType();
        }
        if (release.secondaryTypes() != null && !release.secondaryTypes().isEmpty()) {
            return "Secondary type: " + String.join(", ", release.secondaryTypes());
        }
        return null;
    }

    private static void requireMusicBrainzIdentity(ArtistProviderLink link) {
        if (link.providerArtistId() == null || link.providerArtistId().isBlank()) {
            throw new IllegalArgumentException("MusicBrainz artist MBID is required");
        }
    }

    private static String providerReleaseGroupId(RemoteAlbum remoteAlbum) {
        return remoteAlbum.sourceUrl() == null || remoteAlbum.sourceUrl().isBlank()
                ? remoteAlbum.title()
                : remoteAlbum.sourceUrl();
    }

    private static RemoteReleaseGroup remoteReleaseGroup(String providerId, String providerReleaseGroupId,
            RemoteAlbum remoteAlbum) {
        return new RemoteReleaseGroup(
                providerId,
                providerReleaseGroupId,
                remoteAlbum.title(),
                remoteAlbum.releaseYear(),
                "Album",
                List.of(),
                remoteAlbum.sourceUrl());
    }

    private static void reportReconciliation(ArtistProviderLink link,
            ProviderDiscographyReconciliationService.Result result, ProviderCheckReport report) {
        for (var outcome : result.outcomes()) {
            if (outcome.created()) {
                report.addedAsUnchecked(providerAlbumRow(link, outcome.release(), outcome.album()));
                continue;
            }
            if (outcome.releaseYearConflict()) {
                report.releaseYearConflict(releaseYearConflictRow(link, outcome.album(), outcome.release()));
            }
            if (outcome.titleConflict()) {
                report.titleConflict(titleConflictRow(link, outcome.album(), outcome.release()));
            }
            if (!outcome.releaseYearConflict() && !outcome.titleConflict()) {
                String row = providerAlbumRow(link, outcome.release(), outcome.album());
                report.alreadyInLibrary(outcome.releaseYearFilled()
                        ? row + " | filled missing local release year"
                        : row);
            }
        }
    }

    private void markProviderError(ArtistProviderLink link, String errorDetail) {
        try {
            providerLinks.markError(link.id(), errorDetail);
        } catch (Exception markError) {
            LOG.errorf(markError, "Unable to record provider failure providerLinkId=%d artist=%s",
                    link.id(), link.artistName());
        }
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "<blank>" : value;
    }

    private static String blankValue(Integer value) {
        return value == null ? "<blank>" : value.toString();
    }

    private static String releaseYearConflictRow(ArtistProviderLink link, Album album, RemoteReleaseGroup release) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + album.title()
                + " | local: " + blankValue(album.releaseYear())
                + " | provider: " + blankValue(release.releaseYear())
                + " | provider title: " + blankValue(release.title());
    }

    private static String titleConflictRow(ArtistProviderLink link, Album album, RemoteReleaseGroup release) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + album.title()
                + " | provider title: " + blankValue(release.title())
                + " | release year: " + blankValue(release.releaseYear());
    }

    private static String providerAlbumRow(ArtistProviderLink link, RemoteReleaseGroup release, Album album) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + blankValue(release.title())
                + " | release year: " + blankValue(release.releaseYear())
                + (album == null ? "" : " | local album: " + album.title());
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

    private record FetchedDiscography(
            String country,
            Boolean active,
            List<RemoteReleaseGroup> releases,
            int foundCount,
            int ignoredCount) {
    }
}
