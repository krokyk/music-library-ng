package org.kroky.musiclib.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseProcessLockTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsASecondProcessUntilTheFirstReleasesTheDatabase() {
        Path databasePath = tempDir.resolve("data/music-library-ng.sqlite");

        try (var first = new DatabaseProcessLock(); var second = new DatabaseProcessLock()) {
            first.acquire(databasePath);
            assertThrows(IllegalStateException.class, () -> second.acquire(databasePath));

            first.close();
            assertDoesNotThrow(() -> second.acquire(databasePath));
        }
    }
}
