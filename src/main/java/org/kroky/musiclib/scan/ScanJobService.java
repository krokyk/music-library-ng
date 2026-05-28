package org.kroky.musiclib.scan;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kroky.musiclib.model.ScanJobStatus;
import org.kroky.musiclib.model.ScanSummary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;

@ApplicationScoped
public class ScanJobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ScanJob> currentJob = new AtomicReference<>();

    @Inject
    ScanService scanService;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ScanJobStatus start(String collectionId) {
        ScanJob existing = currentJob.get();
        if (existing != null && existing.isRunning()) {
            return existing.status();
        }

        ScanJob job = new ScanJob(UUID.randomUUID().toString(), blankToNull(collectionId));
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
                public void collectionStarted(String collectionId, int artistTotal) {
                    job.collectionStarted(collectionId, artistTotal);
                }

                @Override
                public void itemProcessed(String collectionId, int itemProcessed) {
                    job.itemProcessed(collectionId, itemProcessed);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelRequested.get();
                }
            };

            List<ScanSummary> summaries = job.requestedCollectionId == null
                    ? scanService.scanAllEnabled(progress)
                    : List.of(scanService.scan(job.requestedCollectionId, progress));
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Scan cancelled.");
            } else {
                job.finish("DONE", summarize(summaries));
            }
        } catch (Exception e) {
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", "Scan cancelled.");
            } else {
                job.finish("FAILED", e.getMessage());
            }
        }
    }

    private static String summarize(List<ScanSummary> summaries) {
        int parsed = summaries.stream().mapToInt(ScanSummary::parsedCount).sum();
        int created = summaries.stream().mapToInt(ScanSummary::createdCount).sum();
        int updated = summaries.stream().mapToInt(ScanSummary::updatedCount).sum();
        int skipped = summaries.stream().mapToInt(ScanSummary::skippedCount).sum();
        return "Scan complete: " + parsed + " parsed, " + created + " created, "
                + updated + " updated, " + skipped + " skipped.";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ScanJobStatus idleStatus() {
        return new ScanJobStatus("", "IDLE", null, null, 0, 0, false, null);
    }

    private static class ScanJob {
        private final String id;
        private final String requestedCollectionId;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private String status = "RUNNING";
        private String activeCollectionId;
        private int itemTotal;
        private int itemProcessed;
        private String message = "Scan starting.";

        private ScanJob(String id, String requestedCollectionId) {
            this.id = id;
            this.requestedCollectionId = requestedCollectionId;
        }

        synchronized boolean isRunning() {
            return "RUNNING".equals(status);
        }

        synchronized void collectionStarted(String collectionId, int itemTotal) {
            this.activeCollectionId = collectionId;
            this.itemTotal = itemTotal;
            this.itemProcessed = 0;
            this.message = "Scanning " + collectionId + ".";
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

        synchronized ScanJobStatus status() {
            return new ScanJobStatus(
                    id,
                    status,
                    requestedCollectionId,
                    activeCollectionId,
                    itemTotal,
                    itemProcessed,
                    cancelRequested.get(),
                    message);
        }
    }
}
