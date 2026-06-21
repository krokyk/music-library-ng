package org.kroky.musiclib.scan;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kroky.musiclib.model.ScanJobStatus;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;

@ApplicationScoped
public class ScanJobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ScanJob> currentJob = new AtomicReference<>();

    @Inject
    ScanService scanService;

    @Inject
    MusicCollectionRepository collectionRepository;

    @Inject
    ArtistRepository artistRepository;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ScanJobStatus start(String collectionId) {
        return start("COLLECTION", collectionId, null);
    }

    public ScanJobStatus startLocalAlbums(String collectionId, Long artistId) {
        return start("LOCAL_ALBUMS", collectionId, artistId);
    }

    private ScanJobStatus start(String kind, String collectionId, Long artistId) {
        ScanJob existing = currentJob.get();
        if (existing != null && existing.isRunning()) {
            return existing.status();
        }

        String normalizedCollectionId = blankToNull(collectionId);
        ScanJob job = new ScanJob(UUID.randomUUID().toString(), kind, normalizedCollectionId,
                collectionName(normalizedCollectionId), artistId, artistName(artistId));
        currentJob.set(job);
        executor.submit(() -> run(job));
        return job.status();
    }

    public ScanJobStatus current() {
        ScanJob job = currentJob.get();
        return job == null ? idleStatus() : job.status();
    }

    public ScanJobStatus cancelCurrent() {
        ScanJob job = currentJob.get();
        if (job == null) {
            return idleStatus();
        }
        job.cancel();
        return job.status();
    }

    private void run(ScanJob job) {
        try {
            ScanService.ProgressListener progress = new ScanService.ProgressListener() {
                @Override
                public void collectionStarted(String collectionId, int itemTotal) {
                    job.collectionStarted(collectionId, itemTotal);
                }

                @Override
                public void itemProcessed(String collectionId, int processedItems) {
                    job.itemProcessed(collectionId, processedItems);
                }

                @Override
                public void artistStarted(String collectionId, Long artistId, String artistName) {
                    job.artistStarted(collectionId, artistId, artistName);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelRequested.get();
                }
            };

            List<ScanSummary> summaries = runScan(job, progress);
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Scan cancelled.");
            } else {
                job.finish("DONE", summarize(job, summaries), summaries);
            }
        } catch (Exception e) {
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Scan cancelled.");
            } else {
                job.finish("FAILED", e.getMessage());
            }
        }
    }

    private List<ScanSummary> runScan(ScanJob job, ScanService.ProgressListener progress) {
        if ("LOCAL_ALBUMS".equals(job.kind)) {
            return List.of(scanService.scanLocalAlbums(job.requestedCollectionId, job.requestedArtistId, progress));
        }
        return job.requestedCollectionId == null
                ? scanService.scanAllEnabled(progress)
                : List.of(scanService.scan(job.requestedCollectionId, progress));
    }

    private static String summarize(ScanJob job, List<ScanSummary> summaries) {
        int parsed = summaries.stream().mapToInt(ScanSummary::parsedCount).sum();
        int created = summaries.stream().mapToInt(ScanSummary::createdCount).sum();
        int updated = summaries.stream().mapToInt(ScanSummary::updatedCount).sum();
        int skipped = summaries.stream().mapToInt(ScanSummary::skippedCount).sum();
        int missing = summaries.stream().mapToInt(ScanSummary::missingCount).sum();
        String collectionName = job.collectionLabel();
        if ("LOCAL_ALBUMS".equals(job.kind)) {
            return collectionName + " local album scan complete: " + parsed + " albums, " + created + " new, "
                    + updated + " existing, " + missing + " missing, " + skipped + " skipped.";
        }
        return collectionName + " scan complete: " + parsed + " parsed, " + created + " created, "
                + updated + " updated, " + skipped + " skipped.";
    }

    private String collectionName(String collectionId) {
        if (collectionId == null) {
            return "all collections";
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

    private static ScanJobStatus idleStatus() {
        return new ScanJobStatus("", "IDLE", "COLLECTION", null, null, null, null, null, null, null, null, 0, 0, 0,
                0, 0, false,
                null,
                List.of());
    }

    private class ScanJob {
        private final String id;
        private final String kind;
        private final String requestedCollectionId;
        private final String requestedCollectionName;
        private final Long requestedArtistId;
        private final String requestedArtistName;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private String status = "RUNNING";
        private String activeCollectionId;
        private String activeCollectionName;
        private Long activeArtistId;
        private String activeArtistName;
        private int itemTotal;
        private int itemProcessed;
        private int parsedCount;
        private int createdCount;
        private int skippedCount;
        private String message;
        private List<Long> runIds = List.of();

        private ScanJob(String id, String kind, String requestedCollectionId, String requestedCollectionName,
                Long requestedArtistId, String requestedArtistName) {
            this.id = id;
            this.kind = kind;
            this.requestedCollectionId = requestedCollectionId;
            this.requestedCollectionName = requestedCollectionName;
            this.requestedArtistId = requestedArtistId;
            this.requestedArtistName = requestedArtistName;
            this.message = runningMessage(collectionLabel());
        }

        synchronized boolean isRunning() {
            return "RUNNING".equals(status);
        }

        synchronized void collectionStarted(String collectionId, int itemTotal) {
            this.activeCollectionId = collectionId;
            this.activeCollectionName = collectionName(collectionId);
            this.activeArtistId = requestedArtistId;
            this.activeArtistName = requestedArtistName;
            this.itemTotal = itemTotal;
            this.itemProcessed = 0;
            this.message = runningMessage(activeCollectionName);
        }

        synchronized void artistStarted(String collectionId, Long artistId, String artistName) {
            if (collectionId.equals(activeCollectionId)) {
                this.activeArtistId = artistId;
                this.activeArtistName = artistName;
            }
        }

        synchronized void itemProcessed(String collectionId, int itemProcessed) {
            if (collectionId.equals(activeCollectionId)) {
                this.itemProcessed = itemProcessed;
            }
        }

        synchronized void cancel() {
            cancelRequested.set(true);
            message = "Cancelling scan.";
        }

        synchronized void finish(String status, String message) {
            this.status = status;
            this.message = message;
        }

        synchronized void finish(String status, String message, List<ScanSummary> summaries) {
            this.parsedCount = summaries.stream().mapToInt(ScanSummary::parsedCount).sum();
            this.createdCount = summaries.stream().mapToInt(ScanSummary::createdCount).sum();
            this.skippedCount = summaries.stream().mapToInt(ScanSummary::skippedCount).sum();
            this.runIds = summaries.stream().map(ScanSummary::runId).toList();
            finish(status, message);
        }

        synchronized ScanJobStatus status() {
            return new ScanJobStatus(
                    id,
                    status,
                    kind,
                    requestedCollectionId,
                    requestedCollectionName,
                    requestedArtistId,
                    requestedArtistName,
                    activeCollectionId,
                    activeCollectionName,
                    activeArtistId,
                    activeArtistName,
                    itemTotal,
                    itemProcessed,
                    parsedCount,
                    createdCount,
                    skippedCount,
                    cancelRequested.get(),
                    message,
                    runIds);
        }

        private String collectionLabel() {
            if (activeCollectionName != null) {
                return activeCollectionName;
            }
            return requestedCollectionName == null ? "all collections" : requestedCollectionName;
        }

        private String runningMessage(String collectionName) {
            if ("LOCAL_ALBUMS".equals(kind)) {
                return requestedArtistId == null
                        ? "Scanning local albums for " + collectionName + "."
                        : "Scanning local albums in " + collectionName + " for " + requestedArtistName + ".";
            }
            return "Scanning collection " + collectionName + ".";
        }
    }
}
