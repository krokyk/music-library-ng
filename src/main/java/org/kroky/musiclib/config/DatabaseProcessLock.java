package org.kroky.musiclib.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.jboss.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DatabaseProcessLock implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DatabaseProcessLock.class);

    private Path lockDirectory;

    public synchronized void acquire(Path databasePath) {
        if (lockDirectory != null) {
            return;
        }

        Path lockPath = lockPath(databasePath);
        boolean directoryCreated = false;
        try {
            Files.createDirectories(lockPath.getParent());
            Files.createDirectory(lockPath);
            directoryCreated = true;
            Files.writeString(
                    lockPath.resolve("owner"),
                    "pid=%d%nos=%s%nstartedAtMillis=%d%n".formatted(
                            ProcessHandle.current().pid(),
                            System.getProperty("os.name"),
                            ProcessHandle.current().info().startInstant().map(start -> start.toEpochMilli()).orElse(0L)),
                    StandardOpenOption.CREATE_NEW);
            lockDirectory = lockPath;
            LOG.infof("Acquired application lock: %s", lockPath);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (directoryCreated) {
                deleteLockDirectory(lockPath);
            }
            throw alreadyRunning(lockPath);
        } catch (IOException e) {
            if (directoryCreated) {
                deleteLockDirectory(lockPath);
            }
            throw new IllegalStateException("Unable to acquire application lock: " + lockPath, e);
        }
    }

    static Path lockPath(Path databasePath) {
        Path absoluteDatabasePath = databasePath.toAbsolutePath().normalize();
        return absoluteDatabasePath.resolveSibling(absoluteDatabasePath.getFileName() + ".lock");
    }

    private static IllegalStateException alreadyRunning(Path lockPath) {
        return new IllegalStateException(
                "Another Music Library NG process may already be using this database. "
                        + "If no app process is running, remove the stale application lock directory: " + lockPath);
    }

    private static void deleteLockDirectory(Path lockPath) {
        try {
            Files.deleteIfExists(lockPath.resolve("owner"));
            Files.deleteIfExists(lockPath);
        } catch (IOException ignored) {
            // The original failure is more useful than a cleanup failure.
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (lockDirectory == null) {
            return;
        }
        Path acquiredLockDirectory = lockDirectory;
        lockDirectory = null;
        try {
            Files.deleteIfExists(acquiredLockDirectory.resolve("owner"));
            Files.deleteIfExists(acquiredLockDirectory);
        } catch (IOException e) {
            LOG.warnf(e, "Unable to remove application lock directory: %s", acquiredLockDirectory);
        }
    }
}
