package org.kroky.musiclib.provider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderCheckJobStatus;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckJobService {

    private static final String PROVIDER_ARTIST = "PROVIDER_ARTIST";
    private static final String PROVIDER_COLLECTION = "PROVIDER_COLLECTION";
    private static final String PROVIDER_ALL = "PROVIDER_ALL";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ProviderCheckJob> currentJob = new AtomicReference<>();

    @Inject
    ProviderCheckService providerChecks;

    @Inject
    ProviderSettingsService providerSettings;

    @Inject
    MusicCollectionRepository collectionRepository;

    @Inject
    ArtistRepository artistRepository;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ProviderCheckJobStatus startArtist(long artistId, String collectionId) {
        return start(PROVIDER_ARTIST, blankToNull(collectionId), artistId);
    }

    public ProviderCheckJobStatus startCollection(String collectionId) {
        return start(PROVIDER_COLLECTION, blankToNull(collectionId), null);
    }

    public ProviderCheckJobStatus startAll() {
        return start(PROVIDER_ALL, null, null);
    }

    private ProviderCheckJobStatus start(String kind, String collectionId, Long artistId) {
        ProviderCheckJob existing = currentJob.get();
        if (existing != null && existing.isRunning()) {
            return existing.status();
        }

        ProviderCheckJob job = new ProviderCheckJob(
                kind,
                collectionId,
                collectionName(collectionId),
                artistId,
                artistName(artistId));
        currentJob.set(job);
        executor.submit(() -> run(job));
        return job.status();
    }

    public ProviderCheckJobStatus current() {
        ProviderCheckJob job = currentJob.get();
        return job == null ? idleStatus() : job.status();
    }

    public ProviderCheckJobStatus cancelCurrent() {
        ProviderCheckJob job = currentJob.get();
        if (job == null) {
            return idleStatus();
        }
        job.cancel();
        return job.status();
    }

    private void run(ProviderCheckJob job) {
        try {
            ProviderCheckService.ProgressListener progress = new ProviderCheckService.ProgressListener() {
                @Override
                public void started(int itemTotal, int skippedArtists) {
                    job.started(itemTotal, skippedArtists);
                }

                @Override
                public void artistStarted(ArtistProviderLink link) {
                    job.artistStarted(link.artistId(), link.artistName());
                }

                @Override
                public void itemSkipped(ArtistProviderLink link, int itemProcessed, int skippedArtists, String reason) {
                    job.itemSkipped(link.artistId(), link.artistName(), itemProcessed, skippedArtists);
                }

                @Override
                public void itemFinished(ArtistProviderLink link, int itemProcessed, int skippedArtists,
                        int foundAlbums, int newAlbums, int existingAlbums, int releaseDateConflicts,
                        int titleConflicts, int errors) {
                    job.itemFinished(link.artistId(), itemProcessed, skippedArtists, foundAlbums, newAlbums,
                            existingAlbums, releaseDateConflicts, titleConflicts, errors);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelRequested.get();
                }
            };

            ProviderCheckSummary summary = runCheck(job, progress);
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Provider check cancelled.", summary);
            } else {
                job.finish(summary.errorCount() == 0 ? "DONE" : "FAILED", summarize(job, summary), summary);
            }
        } catch (Exception e) {
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Provider check cancelled.");
            } else {
                job.finish("FAILED", ProviderException.describe(e));
            }
        }
    }

    private ProviderCheckSummary runCheck(ProviderCheckJob job, ProviderCheckService.ProgressListener progress) {
        if (PROVIDER_ARTIST.equals(job.kind)) {
            return providerChecks.checkArtist(job.requestedArtistId, job.requestedCollectionId);
        }
        int delayMinutes = providerSettings.batchRescanDelayMinutes();
        if (PROVIDER_COLLECTION.equals(job.kind)) {
            return providerChecks.checkCollection(job.requestedCollectionId, delayMinutes, progress);
        }
        return providerChecks.checkAll(delayMinutes, progress);
    }

    private static String summarize(ProviderCheckJob job, ProviderCheckSummary summary) {
        String subject = switch (job.kind) {
            case PROVIDER_ARTIST -> job.requestedArtistName;
            case PROVIDER_COLLECTION -> job.requestedCollectionName + " provider check";
            default -> "Provider check";
        };
        return subject + " complete: " + summary.processedArtistCount() + " checked"
                + (summary.skippedArtistCount() > 0 ? ", " + summary.skippedArtistCount() + " skipped" : "")
                + ", " + summary.newAlbumCount() + " new albums"
                + (summary.releaseDateConflictCount() > 0
                        ? ", " + summary.releaseDateConflictCount() + " release date conflicts"
                        : "")
                + (summary.titleConflictCount() > 0
                        ? ", " + summary.titleConflictCount() + " title conflicts"
                        : "")
                + (summary.errorCount() > 0 ? ", " + summary.errorCount() + " errors" : "")
                + ".";
    }

    private String collectionName(String collectionId) {
        if (collectionId == null) {
            return "all";
        }
        return collectionRepository.find(collectionId)
                .map(collection -> collection.name())
                .orElse(collectionId);
    }

    private String artistName(Long artistId) {
        if (artistId == null) {
            return null;
        }
        return artistRepository.find(artistId)
                .map(artist -> artist.name())
                .orElse("artist " + artistId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProviderCheckJobStatus idleStatus() {
        return new ProviderCheckJobStatus("IDLE", PROVIDER_COLLECTION, null, null, null, null, null, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, false, null, List.of(), List.of());
    }

    private class ProviderCheckJob {
        private final String kind;
        private final String requestedCollectionId;
        private final String requestedCollectionName;
        private final Long requestedArtistId;
        private final String requestedArtistName;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private String status = "RUNNING";
        private Long activeArtistId;
        private String activeArtistName;
        private int itemTotal;
        private int itemProcessed;
        private int skippedArtistCount;
        private int foundAlbumCount;
        private int newAlbumCount;
        private int existingAlbumCount;
        private int releaseDateConflictCount;
        private int titleConflictCount;
        private int errorCount;
        private String message;
        private final LinkedHashSet<Long> artistIds = new LinkedHashSet<>();
        private List<ReportArtifact> reports = List.of();

        private ProviderCheckJob(String kind, String requestedCollectionId, String requestedCollectionName,
                Long requestedArtistId, String requestedArtistName) {
            this.kind = kind;
            this.requestedCollectionId = requestedCollectionId;
            this.requestedCollectionName = requestedCollectionName;
            this.requestedArtistId = requestedArtistId;
            this.requestedArtistName = requestedArtistName;
            this.activeArtistId = requestedArtistId;
            this.activeArtistName = requestedArtistName;
            this.itemTotal = requestedArtistId == null ? 0 : 1;
            addArtistId(requestedArtistId);
            this.message = runningMessage();
        }

        synchronized boolean isRunning() {
            return "RUNNING".equals(status);
        }

        synchronized void started(int itemTotal, int skippedArtists) {
            this.itemTotal = itemTotal;
            this.itemProcessed = Math.min(itemTotal, skippedArtists);
            this.skippedArtistCount = skippedArtists;
            this.message = runningMessage();
        }

        synchronized void artistStarted(Long artistId, String artistName) {
            addArtistId(artistId);
            this.activeArtistId = artistId;
            this.activeArtistName = artistName;
            this.message = runningMessage();
        }

        synchronized void itemSkipped(Long artistId, String artistName, int itemProcessed, int skippedArtists) {
            addArtistId(artistId);
            this.activeArtistId = null;
            this.activeArtistName = artistName;
            this.itemProcessed = itemProcessed;
            this.skippedArtistCount = skippedArtists;
            this.message = runningMessage();
        }

        synchronized void itemFinished(Long artistId, int itemProcessed, int skippedArtists, int foundAlbums,
                int newAlbums, int existingAlbums, int releaseDateConflicts, int titleConflicts, int errors) {
            addArtistId(artistId);
            this.itemProcessed = itemProcessed;
            this.skippedArtistCount = skippedArtists;
            this.foundAlbumCount = foundAlbums;
            this.newAlbumCount = newAlbums;
            this.existingAlbumCount = existingAlbums;
            this.releaseDateConflictCount = releaseDateConflicts;
            this.titleConflictCount = titleConflicts;
            this.errorCount = errors;
            this.message = runningMessage();
        }

        synchronized void cancel() {
            cancelRequested.set(true);
            message = "Cancelling provider check.";
        }

        synchronized void finish(String status, String message) {
            this.status = status;
            this.message = message;
            this.activeArtistId = null;
            this.activeArtistName = null;
        }

        synchronized void finish(String status, String message, ProviderCheckSummary summary) {
            this.itemProcessed = itemTotal;
            this.skippedArtistCount = summary.skippedArtistCount();
            this.foundAlbumCount = summary.foundAlbumCount();
            this.newAlbumCount = summary.newAlbumCount();
            this.existingAlbumCount = summary.existingAlbumCount();
            this.releaseDateConflictCount = summary.releaseDateConflictCount();
            this.titleConflictCount = summary.titleConflictCount();
            this.errorCount = summary.errorCount();
            this.reports = summary.reports();
            finish(status, message);
        }

        synchronized ProviderCheckJobStatus status() {
            return new ProviderCheckJobStatus(
                    status,
                    kind,
                    requestedCollectionId,
                    requestedCollectionName,
                    requestedArtistId,
                    requestedArtistName,
                    activeArtistId,
                    activeArtistName,
                    itemTotal,
                    itemProcessed,
                    skippedArtistCount,
                    foundAlbumCount,
                    newAlbumCount,
                    existingAlbumCount,
                    releaseDateConflictCount,
                    titleConflictCount,
                    errorCount,
                    cancelRequested.get(),
                    message,
                    List.copyOf(artistIds),
                    reports);
        }

        private void addArtistId(Long artistId) {
            if (artistId != null) {
                artistIds.add(artistId);
            }
        }

        private String runningMessage() {
            if (PROVIDER_ARTIST.equals(kind)) {
                return "Checking " + requestedArtistName + "...";
            }
            if (itemTotal <= 0) {
                if (PROVIDER_COLLECTION.equals(kind)) {
                    return "Checking " + requestedCollectionName + " providers...";
                }
                return "Checking all providers...";
            }
            String artistName = activeArtistName == null ? "providers" : activeArtistName;
            int activeIndex = Math.min(itemTotal, itemProcessed + 1);
            String skipText = skippedArtistCount > 0 ? ", " + skippedArtistCount + " skipped" : "";
            if (PROVIDER_COLLECTION.equals(kind)) {
                return "Checking " + requestedCollectionName + " artists: " + artistName
                        + " (" + activeIndex + "/" + itemTotal + skipText + ")...";
            }
            return "Checking all artists: " + artistName + " (" + activeIndex + "/" + itemTotal + skipText + ")...";
        }
    }
}
