package org.kroky.musiclib.scan;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.ScanJobStatus;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

@ApplicationScoped
public class ScanJobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ScanJob> currentJob = new AtomicReference<>();

    @Inject
    ScanService scanService;

    @Inject
    MusicCollectionRepository collectionRepository;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ScanJobStatus start(Long collectionId) {
        ScanJob existing = currentJob.get();
        if (existing != null && existing.isRunning()) {
            return existing.status();
        }

        ScanJob job = new ScanJob(collectionId, collectionName(collectionId));
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
                public void collectionStarted(long collectionId, int itemTotal) {
                    job.collectionStarted(collectionId, itemTotal);
                }

                @Override
                public void phaseStarted(long collectionId, String message) {
                    job.phaseStarted(collectionId, message);
                }

                @Override
                public void itemProcessed(long collectionId, int processedItems) {
                    job.itemProcessed(collectionId, processedItems);
                }

                @Override
                public void artistStarted(long collectionId, Long artistId, String artistName) {
                    job.artistStarted(collectionId, artistId, artistName);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelRequested.get();
                }
            };

            List<ScanSummary> summaries = runScan(job, progress);
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Scan cancelled.", summaries);
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
        return job.requestedCollectionId == null
                ? scanService.scanAllEnabled(progress)
                : List.of(scanService.scan(job.requestedCollectionId, progress));
    }

    private String summarize(ScanJob job, List<ScanSummary> summaries) {
        if (summaries.size() == 1 && !summaries.get(0).messages().isEmpty()) {
            List<String> messages = summaries.get(0).messages();
            return messages.get(messages.size() - 1);
        }
        int artists = summaries.stream().mapToInt(ScanSummary::artistCount).sum();
        int parsed = summaries.stream().mapToInt(ScanSummary::parsedCount).sum();
        int created = summaries.stream().mapToInt(ScanSummary::createdCount).sum();
        int existing = summaries.stream().mapToInt(ScanSummary::updatedCount).sum();
        int skipped = summaries.stream().mapToInt(ScanSummary::skippedCount).sum();
        int missing = summaries.stream().mapToInt(ScanSummary::missingCount).sum();
        String collectionName = job.collectionLabel();
        String artistText = countWithLabel(artists, "artist", "artists");
        String parsedText = countWithLabel(parsed, parsedItemSingular(job), parsedItemPlural(job));
        return collectionName + " scan complete: " + artistText + ", " + parsedText + " parsed, " + created
                + " created, " + existing + " existing, " + missing + " local paths removed, "
                + skipped + " skipped.";
    }

    private String parsedItemSingular(ScanJob job) {
        if (job.requestedCollectionId == null) {
            return "item";
        }
        return collectionRepository.find(job.requestedCollectionId)
                .map(collection -> collection.type() == CollectionType.TITLE ? "title" : "album")
                .orElse("item");
    }

    private String parsedItemPlural(ScanJob job) {
        if (job.requestedCollectionId == null) {
            return "items";
        }
        return collectionRepository.find(job.requestedCollectionId)
                .map(collection -> collection.type() == CollectionType.TITLE ? "titles" : "albums")
                .orElse("items");
    }

    private static String countWithLabel(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private String collectionName(Long collectionId) {
        if (collectionId == null) {
            return "all collections";
        }
        return collectionRepository.find(collectionId)
                .map(collection -> collection.name())
                .orElse("collection " + collectionId);
    }

    private static ScanJobStatus idleStatus() {
        return new ScanJobStatus("IDLE", "COLLECTION", null, null, null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, false,
                null,
                List.of());
    }

    private class ScanJob {
        private final String kind;
        private final Long requestedCollectionId;
        private final String requestedCollectionName;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private String status = "RUNNING";
        private Long activeCollectionId;
        private String activeCollectionName;
        private Long activeArtistId;
        private String activeArtistName;
        private int itemTotal;
        private int itemProcessed;
        private int artistCount;
        private int parsedCount;
        private int createdCount;
        private int skippedCount;
        private String message;
        private List<ReportArtifact> reports = List.of();

        private ScanJob(Long requestedCollectionId, String requestedCollectionName) {
            this.kind = "COLLECTION";
            this.requestedCollectionId = requestedCollectionId;
            this.requestedCollectionName = requestedCollectionName;
            this.message = runningMessage(collectionLabel());
        }

        synchronized boolean isRunning() {
            return "RUNNING".equals(status);
        }

        synchronized void collectionStarted(long collectionId, int itemTotal) {
            this.activeCollectionId = collectionId;
            this.activeCollectionName = collectionName(collectionId);
            this.activeArtistId = null;
            this.activeArtistName = null;
            this.itemTotal = itemTotal;
            this.itemProcessed = 0;
            this.message = runningMessage(activeCollectionName);
        }

        synchronized void phaseStarted(long collectionId, String message) {
            if (!Long.valueOf(collectionId).equals(activeCollectionId)) {
                this.activeCollectionId = collectionId;
                this.activeCollectionName = collectionName(collectionId);
                this.activeArtistId = null;
                this.activeArtistName = null;
                this.itemTotal = 0;
                this.itemProcessed = 0;
            }
            this.message = message;
        }

        synchronized void artistStarted(long collectionId, Long artistId, String artistName) {
            if (Long.valueOf(collectionId).equals(activeCollectionId)) {
                this.activeArtistId = artistId;
                this.activeArtistName = artistName;
            }
        }

        synchronized void itemProcessed(long collectionId, int itemProcessed) {
            if (Long.valueOf(collectionId).equals(activeCollectionId)) {
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
            this.artistCount = summaries.stream().mapToInt(ScanSummary::artistCount).sum();
            this.parsedCount = summaries.stream().mapToInt(ScanSummary::parsedCount).sum();
            this.createdCount = summaries.stream().mapToInt(ScanSummary::createdCount).sum();
            this.skippedCount = summaries.stream().mapToInt(ScanSummary::skippedCount).sum();
            this.reports = summaries.stream().flatMap(summary -> summary.reports().stream()).toList();
            finish(status, message);
        }

        synchronized ScanJobStatus status() {
            return new ScanJobStatus(
                    status,
                    kind,
                    requestedCollectionId,
                    requestedCollectionName,
                    null,
                    null,
                    activeCollectionId,
                    activeCollectionName,
                    activeArtistId,
                    activeArtistName,
                    itemTotal,
                    itemProcessed,
                    artistCount,
                    parsedCount,
                    createdCount,
                    skippedCount,
                    cancelRequested.get(),
                    message,
                    reports);
        }

        private String collectionLabel() {
            if (activeCollectionName != null) {
                return activeCollectionName;
            }
            return requestedCollectionName == null ? "all collections" : requestedCollectionName;
        }

        private String runningMessage(String collectionName) {
            return "Scanning collection " + collectionName + ".";
        }
    }
}
