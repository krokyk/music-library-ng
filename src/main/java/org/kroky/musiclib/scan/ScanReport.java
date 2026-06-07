package org.kroky.musiclib.scan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.model.MusicCollection;

class ScanReport {

    private final long runId;
    private final String kind;
    private final MusicCollection collection;
    private final Path collectionRoot;
    private final Instant startedAt = Instant.now();
    private final List<String> created = new ArrayList<>();
    private final List<String> existing = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Instant finishedAt;
    private String status;
    private int totalDirs;
    private int scannedDirs;
    private int parsedCount;
    private int createdCount;
    private int existingCount;
    private int missingCount;
    private int skippedCount;
    private String summaryMessage;

    ScanReport(long runId, String kind, MusicCollection collection, Path collectionRoot) {
        this.runId = runId;
        this.kind = kind;
        this.collection = collection;
        this.collectionRoot = collectionRoot;
    }

    void totalDirs(int totalDirs) {
        this.totalDirs = totalDirs;
    }

    void scannedDirs(int scannedDirs) {
        this.scannedDirs = scannedDirs;
    }

    void created(String item) {
        created.add(item);
    }

    void existing(String item) {
        existing.add(item);
    }

    void skipped(String item) {
        skipped.add(item);
    }

    void missing(String item) {
        missing.add(item);
    }

    void warning(String item) {
        warnings.add(item);
    }

    void note(String item) {
        notes.add(item);
    }

    void finish(String status, int parsedCount, int createdCount, int existingCount, int missingCount,
            int skippedCount, String summaryMessage) {
        this.finishedAt = Instant.now();
        this.status = status;
        this.parsedCount = parsedCount;
        this.createdCount = createdCount;
        this.existingCount = existingCount;
        this.missingCount = missingCount;
        this.skippedCount = skippedCount;
        this.summaryMessage = summaryMessage;
    }

    long runId() {
        return runId;
    }

    String kind() {
        return kind;
    }

    MusicCollection collection() {
        return collection;
    }

    Path collectionRoot() {
        return collectionRoot;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    String status() {
        return status;
    }

    int totalDirs() {
        return totalDirs;
    }

    int scannedDirs() {
        return scannedDirs;
    }

    int parsedCount() {
        return parsedCount;
    }

    int createdCount() {
        return createdCount;
    }

    int existingCount() {
        return existingCount;
    }

    int missingCount() {
        return missingCount;
    }

    int skippedCount() {
        return skippedCount;
    }

    String summaryMessage() {
        return summaryMessage;
    }

    List<String> created() {
        return created;
    }

    List<String> existing() {
        return existing;
    }

    List<String> skipped() {
        return skipped;
    }

    List<String> missing() {
        return missing;
    }

    List<String> warnings() {
        return warnings;
    }

    List<String> notes() {
        return notes;
    }
}
