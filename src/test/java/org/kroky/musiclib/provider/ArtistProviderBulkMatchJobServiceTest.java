package org.kroky.musiclib.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.kroky.musiclib.model.ArtistProviderBulkMatchItem;
import org.kroky.musiclib.model.ArtistProviderBulkMatchJobStatus;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;

class ArtistProviderBulkMatchJobServiceTest {

    @Test
    void reportsActiveArtistAndCompletedResult() throws Exception {
        BlockingBulkMatchService bulkMatches = new BlockingBulkMatchService();
        ArtistProviderBulkMatchJobService jobs = new ArtistProviderBulkMatchJobService();
        jobs.bulkMatches = bulkMatches;
        try {
            jobs.start("musicbrainz", List.of(7L));
            assertTrue(bulkMatches.started.await(2, TimeUnit.SECONDS));

            ArtistProviderBulkMatchJobStatus running = jobs.current();
            assertEquals("RUNNING", running.status());
            assertEquals("Test Artist", running.activeArtistName());
            assertEquals(1, running.itemTotal());
            assertEquals(0, running.itemProcessed());

            bulkMatches.release.countDown();
            ArtistProviderBulkMatchJobStatus completed = awaitCompletion(jobs);
            assertEquals("DONE", completed.status());
            assertEquals(1, completed.itemProcessed());
            assertEquals(1, completed.matchedCount());
            assertNotNull(completed.result());
        } finally {
            bulkMatches.release.countDown();
            jobs.shutdown();
        }
    }

    private static ArtistProviderBulkMatchJobStatus awaitCompletion(ArtistProviderBulkMatchJobService jobs)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ArtistProviderBulkMatchJobStatus status = jobs.current();
            if (!"RUNNING".equals(status.status())) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Bulk match job did not finish");
    }

    private static class BlockingBulkMatchService extends ArtistProviderBulkMatchService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ArtistProviderBulkMatchResult matchProviderArtists(String providerId, List<Long> artistIds,
                ProgressListener progress) {
            progress.started(1);
            progress.artistStarted(7L, "Test Artist");
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            ArtistProviderBulkMatchItem item = new ArtistProviderBulkMatchItem(
                    7L, "Test Artist", STATUS_MATCHED, "Matched.", null, null, List.of());
            progress.itemFinished(item, 1);
            return new ArtistProviderBulkMatchResult(
                    1, 1, 1, 0, 0, 0, 0, List.of(item), List.of("MusicBrainz bulk match complete."));
        }
    }
}
