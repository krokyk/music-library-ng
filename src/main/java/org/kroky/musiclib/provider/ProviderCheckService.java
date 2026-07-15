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
import org.kroky.musiclib.model.ArtistProviderCandidateAlbum;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
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
                int foundAlbums, int newAlbums, int existingAlbums, int releaseDateConflicts, int titleConflicts,
                int errors) {
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
    AlbumProviderLinkRepository albumProviderLinks;

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderCheckReportWriter reportWriter;

    @Inject
    ArtistProviderRefreshService artistProviderRefresh;

    public ProviderCheckSummary checkArtist(long artistId, String collectionId) {
        if (collectionId != null && collections.find(collectionId).isEmpty()) {
            throw new IllegalArgumentException("Unknown collection: " + collectionId);
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

    public ProviderCheckSummary checkCollection(String collectionId, int batchRescanDelayMinutes,
            ProgressListener progress) {
        MusicCollection collection = collections.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
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

    public ProviderCheckSummary checkAll(int batchRescanDelayMinutes, ProgressListener progress) {
        ProviderCheckReport report = new ProviderCheckReport("all provider checks");
        return checkLinks(report, providerLinks.listEnabled(), 0, null, batchRescanDelayMinutes, true, progress);
    }

    private ProviderCheckSummary checkLinks(ProviderCheckReport report, List<ArtistProviderLink> links,
            int skippedArtists,
            String collectionId, int batchRescanDelayMinutes, boolean skipRecentlyChecked,
            ProgressListener progress) {
        int processedArtists = 0;
        int initialSkippedArtists = skippedArtists;
        int recentlySkippedArtists = 0;
        int processedItems = skippedArtists;
        int foundAlbums = 0;
        int newAlbums = 0;
        int existingAlbums = 0;
        int releaseDateConflicts = 0;
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
            if (progress.isCancelled()) {
                String message = "Provider check cancelled.";
                messages.add(message);
                report.note(message);
                return providerSummary(report, "CANCELLED", processedArtists, skippedArtists, foundAlbums, newAlbums,
                        existingAlbums, releaseDateConflicts, titleConflicts, ignoredProviderRecords, errors, messages);
            }
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
                if (MusicBrainzClient.PROVIDER_ID.equals(link.providerId())) {
                    var result = artistProviderRefresh.importMusicBrainz(link, collectionId, report);
                    foundAlbums += result.foundReleaseGroupCount();
                    newAlbums += result.createdAlbumCount();
                    existingAlbums += result.existingAlbumCount();
                    releaseDateConflicts += result.releaseDateConflictCount();
                    titleConflicts += result.titleConflictCount();
                    ignoredProviderRecords += result.skippedCount();
                    messages.addAll(result.messages());
                    providerLinks.markSuccess(link.id());
                    processedItems++;
                    progress.itemFinished(link, processedItems, skippedArtists, foundAlbums, newAlbums,
                            existingAlbums, releaseDateConflicts, titleConflicts, errors);
                    continue;
                }
                DiscographyProvider provider = providers.find(link.providerId(), link.providerUrl());
                ProviderArtistDetails details = provider.fetchArtistDetails(link.providerUrl());
                providerLinks.updateProviderMetadata(link.id(), details.country(), details.active());
                List<RemoteAlbum> remoteAlbums = details.albums();
                foundAlbums += remoteAlbums.size();
                int linkExistingAlbums = 0;
                int linkNewAlbums = 0;
                int linkReleaseDateConflicts = 0;
                int linkTitleConflicts = 0;
                List<Album> localAlbums = new ArrayList<>(albums.list(link.artistId(), null, null, null, null));
                for (RemoteAlbum remoteAlbum : remoteAlbums) {
                    String providerReleaseGroupId = providerReleaseGroupId(remoteAlbum);
                    RemoteReleaseGroup releaseGroup = remoteReleaseGroup(
                            link.providerId(), providerReleaseGroupId, remoteAlbum);
                    ArtistProviderCandidateAlbum evidence = ProviderCandidateEvidenceEvaluator.albumEvidence(
                            localAlbums,
                            releaseGroup);
                    Album evidenceAlbum = evidence.localAlbumId() == null
                            ? null
                            : localAlbums.stream()
                                    .filter(localAlbum -> evidence.localAlbumId().equals(localAlbum.id()))
                                    .findFirst()
                                    .orElseGet(() -> albums.find(evidence.localAlbumId()).orElse(null));
                    var linkedAlbumId = albumProviderLinks.findAlbumId(link.providerId(), providerReleaseGroupId);
                    if (linkedAlbumId.isPresent()) {
                        Album album = albums.find(linkedAlbumId.get()).orElse(null);
                        if (shouldRelinkProviderOnlyAlbum(album, evidenceAlbum, evidence)) {
                            mergeProviderOnlyDuplicates(evidenceAlbum.id(), link.artistId(), remoteAlbum);
                            linkAlbum(evidenceAlbum.id(), link.providerId(), remoteAlbum);
                            album = albums.find(evidenceAlbum.id()).orElse(evidenceAlbum);
                        } else if (album != null) {
                            mergeProviderOnlyDuplicates(album.id(), link.artistId(), remoteAlbum);
                            album = albums.find(album.id()).orElse(album);
                        }
                        assignToCollectionIfUnassigned(album, collectionId);
                        ProviderAlbumOutcome outcome = reportProviderAlbumOutcome(link, album, remoteAlbum, report);
                        existingAlbums += outcome.existingAlbums();
                        linkExistingAlbums += outcome.existingAlbums();
                        releaseDateConflicts += outcome.releaseDateConflicts();
                        linkReleaseDateConflicts += outcome.releaseDateConflicts();
                        titleConflicts += outcome.titleConflicts();
                        linkTitleConflicts += outcome.titleConflicts();
                        continue;
                    }

                    if (ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, evidenceAlbum)) {
                        Album album = evidenceAlbum;
                        if (album == null) {
                            report.ignoredProviderRecord(link.artistName() + " (" + providerLabel(link.providerId())
                                    + "): " + remoteAlbum.title()
                                    + " | reason: matched local album is no longer available");
                            ignoredProviderRecords++;
                            continue;
                        }
                        linkAlbum(album.id(), link.providerId(), remoteAlbum);
                        mergeProviderOnlyDuplicates(album.id(), link.artistId(), remoteAlbum);
                        album = albums.find(album.id()).orElse(album);
                        assignToCollectionIfUnassigned(album, collectionId);
                        ProviderAlbumOutcome outcome = reportProviderAlbumOutcome(link, album, remoteAlbum, report);
                        existingAlbums += outcome.existingAlbums();
                        linkExistingAlbums += outcome.existingAlbums();
                        releaseDateConflicts += outcome.releaseDateConflicts();
                        linkReleaseDateConflicts += outcome.releaseDateConflicts();
                        titleConflicts += outcome.titleConflicts();
                        linkTitleConflicts += outcome.titleConflicts();
                        continue;
                    }
                    Album album = albums.create(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseDate(), false, null,
                            collectionId);
                    linkAlbum(album.id(), link.providerId(), remoteAlbum);
                    localAlbums.add(album);
                    newAlbums++;
                    linkNewAlbums++;
                    report.addedAsUnchecked(providerAlbumRow(link, remoteAlbum, album));
                }
                if (linkNewAlbums == 0 && linkReleaseDateConflicts == 0 && linkTitleConflicts == 0) {
                    report.noChange(link.artistName() + " (" + providerLabel(link.providerId()) + ")");
                }
                providerLinks.markSuccess(link.id());
            } catch (Exception e) {
                errors++;
                String errorDetail = ProviderException.describe(e);
                String message = "Provider check failed for " + link.artistName() + ": " + errorDetail;
                messages.add(message);
                providerLinks.markError(link.id(), errorDetail);
                report.error(link.artistName() + " (" + providerLabel(link.providerId()) + "): " + errorDetail);
                LOG.errorf(e, "Provider check failed artistId=%d providerLinkId=%d artist=%s: %s",
                        link.artistId(), link.id(), link.artistName(), errorDetail);
            }
            processedItems++;
            progress.itemFinished(link, processedItems, skippedArtists, foundAlbums, newAlbums, existingAlbums,
                    releaseDateConflicts, titleConflicts, errors);
        }

        String status = errors == 0 ? "DONE" : "FAILED";
        String message = "Checked " + processedArtists + " provider links, found " + foundAlbums
                + " provider albums, added " + newAlbums + " unchecked albums"
                + (releaseDateConflicts > 0 ? ", " + releaseDateConflicts + " release date conflicts" : "")
                + (titleConflicts > 0 ? ", " + titleConflicts + " title conflicts" : "")
                + skippedSummary(skippedArtists, initialSkippedArtists, recentlySkippedArtists) + ".";
        messages.add(message);
        return providerSummary(report, status, processedArtists, skippedArtists, foundAlbums, newAlbums,
                existingAlbums, releaseDateConflicts, titleConflicts, ignoredProviderRecords, errors, messages);
    }

    void removeStaleLocalPaths(ArtistProviderLink link) {
        List<String> stalePaths = albums.list(link.artistId(), null, null, null, null).stream()
                .flatMap(album -> album.localPaths().stream())
                .filter(localPath -> !localPath.onDisk())
                .map(localPath -> localPath.resolvedPath())
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

    private void assignToCollectionIfUnassigned(org.kroky.musiclib.model.Album album, String collectionId) {
        if (album != null && collectionId != null && album.collections().isEmpty() && album.localPaths().isEmpty()) {
            albums.assignToCollection(album.id(), collectionId);
        }
    }

    private ProviderCheckSummary providerSummary(ProviderCheckReport report, String status, int processedArtists,
            int skippedArtists, int foundAlbums, int newAlbums, int existingAlbums, int releaseDateConflicts,
            int titleConflicts, int ignoredProviderRecords, int errors, List<String> messages) {
        String message = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        report.finish(status, processedArtists, skippedArtists, foundAlbums, existingAlbums, newAlbums,
                releaseDateConflicts, titleConflicts, ignoredProviderRecords, errors, message);
        return new ProviderCheckSummary(processedArtists, skippedArtists, foundAlbums, newAlbums, existingAlbums,
                releaseDateConflicts, titleConflicts, errors, messages, writeReport(report));
    }

    private List<ReportArtifact> writeReport(ProviderCheckReport report) {
        try {
            return List.of(reportWriter.write(report));
        } catch (Exception e) {
            LOG.warnf("Unable to write provider check report for %s: %s", report.subject(), e.getMessage());
            return List.of();
        }
    }

    private void linkAlbum(long albumId, String providerId, RemoteAlbum remoteAlbum) {
        albumProviderLinks.linkAlbum(
                albumId,
                providerId,
                providerReleaseGroupId(remoteAlbum),
                remoteAlbum.title(),
                remoteAlbum.releaseDate(),
                remoteAlbum.sourceUrl());
    }

    private void mergeProviderOnlyDuplicates(long keepAlbumId, long artistId, RemoteAlbum remoteAlbum) {
        albums.mergeProviderOnlyDuplicates(
                keepAlbumId,
                artistId,
                remoteAlbum.title(),
                remoteAlbum.releaseDate());
    }

    private static boolean shouldRelinkProviderOnlyAlbum(Album linkedAlbum, Album evidenceAlbum,
            ArtistProviderCandidateAlbum evidence) {
        return linkedAlbum != null
                && evidenceAlbum != null
                && linkedAlbum.id() != evidenceAlbum.id()
                && linkedAlbum.localPaths().isEmpty()
                && ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, evidenceAlbum);
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
                remoteAlbum.releaseDate(),
                "Album",
                List.of(),
                remoteAlbum.sourceUrl());
    }

    private ProviderAlbumOutcome reportProviderAlbumOutcome(ArtistProviderLink link, Album album,
            RemoteAlbum remoteAlbum, ProviderCheckReport report) {
        if (album == null) {
            report.alreadyInLibrary(providerAlbumRow(link, remoteAlbum, null));
            return new ProviderAlbumOutcome(1, 0, 0);
        }
        boolean releaseDateConflict = releaseDateConflict(album.releaseDate(), remoteAlbum.releaseDate());
        boolean titleConflict = ProviderTitles.titleConflict(album.title(), remoteAlbum.title());
        if (releaseDateConflict) {
            report.releaseDateConflict(releaseDateConflictRow(link, album, remoteAlbum));
        }
        if (titleConflict) {
            report.titleConflict(titleConflictRow(link, album, remoteAlbum));
        }
        if (!releaseDateConflict && !titleConflict) {
            report.alreadyInLibrary(providerAlbumRow(link, remoteAlbum, album));
        }
        return new ProviderAlbumOutcome(
                releaseDateConflict || titleConflict ? 0 : 1,
                releaseDateConflict ? 1 : 0,
                titleConflict ? 1 : 0);
    }

    private static boolean releaseDateConflict(String localReleaseDate, String providerReleaseDate) {
        String localYear = releaseYear(localReleaseDate);
        String providerYear = releaseYear(providerReleaseDate);
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static String releaseYear(String releaseDate) {
        String normalized = releaseDate == null || releaseDate.isBlank() ? null : releaseDate.trim();
        if (normalized == null || normalized.length() < 4) {
            return null;
        }
        return normalized.substring(0, 4);
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "<blank>" : value;
    }

    private static String releaseDateConflictRow(ArtistProviderLink link, Album album, RemoteAlbum remoteAlbum) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + album.title()
                + " | local: " + blankValue(album.releaseDate())
                + " | provider: " + blankValue(remoteAlbum.releaseDate())
                + " | provider title: " + blankValue(remoteAlbum.title());
    }

    private static String titleConflictRow(ArtistProviderLink link, Album album, RemoteAlbum remoteAlbum) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + album.title()
                + " | provider title: " + blankValue(remoteAlbum.title())
                + " | release: " + blankValue(remoteAlbum.releaseDate());
    }

    private static String providerAlbumRow(ArtistProviderLink link, RemoteAlbum remoteAlbum, Album album) {
        return link.artistName() + " (" + providerLabel(link.providerId()) + "): " + blankValue(remoteAlbum.title())
                + " | release: " + blankValue(remoteAlbum.releaseDate())
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

    private record ProviderAlbumOutcome(int existingAlbums, int releaseDateConflicts, int titleConflicts) {
    }
}
