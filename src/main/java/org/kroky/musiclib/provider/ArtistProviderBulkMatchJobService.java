package org.kroky.musiclib.provider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kroky.musiclib.model.ArtistProviderBulkMatchItem;
import org.kroky.musiclib.model.ArtistProviderBulkMatchJobStatus;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderBulkMatchJobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<BulkMatchJob> currentJob = new AtomicReference<>();

    @Inject
    ArtistProviderBulkMatchService bulkMatches;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ArtistProviderBulkMatchJobStatus start(String providerId, List<Long> artistIds) {
        BulkMatchJob existing = currentJob.get();
        if (existing != null && existing.isRunning()) {
            return existing.status();
        }
        BulkMatchJob job = new BulkMatchJob(providerId, artistIds == null ? 0 : artistIds.size());
        currentJob.set(job);
        executor.submit(() -> run(job, artistIds));
        return job.status();
    }

    public ArtistProviderBulkMatchJobStatus current() {
        BulkMatchJob job = currentJob.get();
        return job == null ? idleStatus() : job.status();
    }

    public ArtistProviderBulkMatchJobStatus cancelCurrent() {
        BulkMatchJob job = currentJob.get();
        if (job == null) {
            return idleStatus();
        }
        job.cancel();
        return job.status();
    }

    private void run(BulkMatchJob job, List<Long> artistIds) {
        try {
            ArtistProviderBulkMatchResult result = bulkMatches.matchProviderArtists(
                    job.providerId,
                    artistIds,
                    new ArtistProviderBulkMatchService.ProgressListener() {
                        @Override
                        public void started(int itemTotal) {
                            job.started(itemTotal);
                        }

                        @Override
                        public void artistStarted(long artistId, String artistName) {
                            job.artistStarted(artistId, artistName);
                        }

                        @Override
                        public void itemFinished(ArtistProviderBulkMatchItem item, int itemProcessed) {
                            job.itemFinished(item, itemProcessed);
                        }

                        @Override
                        public boolean isCancelled() {
                            return job.cancelRequested.get();
                        }
                    });
            if (job.cancelRequested.get()) {
                job.finish("CANCELLED", job.providerName + " bulk match cancelled.", result);
            } else {
                String status = result.errorCount() == 0 ? "DONE" : "FAILED";
                String message = result.messages().isEmpty()
                        ? job.providerName + " bulk match complete."
                        : result.messages().get(0);
                job.finish(status, message, result);
            }
        } catch (Exception e) {
            job.finish(job.cancelRequested.get() ? "CANCELLED" : "FAILED", ProviderException.describe(e), null);
        }
    }

    private static ArtistProviderBulkMatchJobStatus idleStatus() {
        return new ArtistProviderBulkMatchJobStatus(
                "IDLE", "PROVIDER_BULK_MATCH", null, null, null, null,
                0, 0, 0, 0, 0, 0, 0, false, null, null);
    }

    private static class BulkMatchJob {
        private final String providerId;
        private final String providerName;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private String status = "RUNNING";
        private Long activeArtistId;
        private String activeArtistName;
        private int itemTotal;
        private int itemProcessed;
        private int matchedCount;
        private int manualCount;
        private int noMatchCount;
        private int skippedCount;
        private int errorCount;
        private String message;
        private ArtistProviderBulkMatchResult result;

        private BulkMatchJob(String providerId, int itemTotal) {
            this.providerId = providerId;
            this.providerName = ArtistProviderBulkMatchService.providerLabel(providerId);
            this.itemTotal = itemTotal;
            this.message = "Matching " + providerName + " providers.";
        }

        synchronized boolean isRunning() {
            return "RUNNING".equals(status);
        }

        synchronized void started(int itemTotal) {
            this.itemTotal = itemTotal;
        }

        synchronized void artistStarted(long artistId, String artistName) {
            this.activeArtistId = artistId;
            this.activeArtistName = artistName;
            this.message = "Matching " + providerName + " for " + artistName + ".";
        }

        synchronized void itemFinished(ArtistProviderBulkMatchItem item, int itemProcessed) {
            this.itemProcessed = itemProcessed;
            switch (item.status()) {
                case ArtistProviderBulkMatchService.STATUS_MATCHED -> matchedCount++;
                case ArtistProviderBulkMatchService.STATUS_NEEDS_MANUAL -> manualCount++;
                case ArtistProviderBulkMatchService.STATUS_NO_MATCH -> noMatchCount++;
                case ArtistProviderBulkMatchService.STATUS_SKIPPED_EXISTING -> skippedCount++;
                case ArtistProviderBulkMatchService.STATUS_ERROR -> errorCount++;
                default -> throw new IllegalArgumentException("Unknown bulk match status: " + item.status());
            }
        }

        synchronized void cancel() {
            cancelRequested.set(true);
            message = "Cancelling " + providerName + " bulk match.";
        }

        synchronized void finish(String status, String message, ArtistProviderBulkMatchResult result) {
            this.status = status;
            this.message = message;
            this.result = result;
            this.activeArtistId = null;
            this.activeArtistName = null;
        }

        synchronized ArtistProviderBulkMatchJobStatus status() {
            return new ArtistProviderBulkMatchJobStatus(
                    status,
                    "PROVIDER_BULK_MATCH",
                    providerId,
                    providerName,
                    activeArtistId,
                    activeArtistName,
                    itemTotal,
                    itemProcessed,
                    matchedCount,
                    manualCount,
                    noMatchCount,
                    skippedCount,
                    errorCount,
                    cancelRequested.get(),
                    message,
                    result);
        }
    }
}
