package org.kroky.musiclib.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class ProviderCheckReport {

    private final String subject;
    private final Instant startedAt = Instant.now();
    private final List<String> errors = new ArrayList<>();
    private final List<String> addedAsUnchecked = new ArrayList<>();
    private final List<String> alreadyInLibrary = new ArrayList<>();
    private final List<String> releaseYearConflicts = new ArrayList<>();
    private final List<String> titleConflicts = new ArrayList<>();
    private final List<String> ignoredProviderRecords = new ArrayList<>();
    private final List<String> artistsSkipped = new ArrayList<>();
    private final List<String> noChanges = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Instant finishedAt;
    private String status;
    private int artistsChecked;
    private int artistsSkippedCount;
    private int providerAlbumsFound;
    private int alreadyInLibraryCount;
    private int addedAsUncheckedCount;
    private int releaseYearConflictCount;
    private int titleConflictCount;
    private int ignoredProviderRecordCount;
    private int errorCount;
    private String summaryMessage;

    ProviderCheckReport(String subject) {
        this.subject = subject == null || subject.isBlank() ? "provider-check" : subject.trim();
    }

    void finish(String status, int artistsChecked, int artistsSkippedCount, int providerAlbumsFound,
            int alreadyInLibraryCount, int addedAsUncheckedCount, int releaseYearConflictCount,
            int titleConflictCount, int ignoredProviderRecordCount, int errorCount, String summaryMessage) {
        this.finishedAt = Instant.now();
        this.status = status;
        this.artistsChecked = artistsChecked;
        this.artistsSkippedCount = artistsSkippedCount;
        this.providerAlbumsFound = providerAlbumsFound;
        this.alreadyInLibraryCount = alreadyInLibraryCount;
        this.addedAsUncheckedCount = addedAsUncheckedCount;
        this.releaseYearConflictCount = releaseYearConflictCount;
        this.titleConflictCount = titleConflictCount;
        this.ignoredProviderRecordCount = ignoredProviderRecordCount;
        this.errorCount = errorCount;
        this.summaryMessage = summaryMessage;
    }

    void error(String row) {
        errors.add(row);
    }

    void addedAsUnchecked(String row) {
        addedAsUnchecked.add(row);
    }

    void alreadyInLibrary(String row) {
        alreadyInLibrary.add(row);
    }

    void releaseYearConflict(String row) {
        releaseYearConflicts.add(row);
    }

    void titleConflict(String row) {
        titleConflicts.add(row);
    }

    void ignoredProviderRecord(String row) {
        ignoredProviderRecords.add(row);
    }

    void artistSkipped(String row) {
        artistsSkipped.add(row);
    }

    void noChange(String row) {
        noChanges.add(row);
    }

    void note(String row) {
        notes.add(row);
    }

    String subject() {
        return subject;
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

    int artistsChecked() {
        return artistsChecked;
    }

    int artistsSkippedCount() {
        return artistsSkippedCount;
    }

    int providerAlbumsFound() {
        return providerAlbumsFound;
    }

    int alreadyInLibraryCount() {
        return alreadyInLibraryCount;
    }

    int addedAsUncheckedCount() {
        return addedAsUncheckedCount;
    }

    int releaseYearConflictCount() {
        return releaseYearConflictCount;
    }

    int titleConflictCount() {
        return titleConflictCount;
    }

    int ignoredProviderRecordCount() {
        return ignoredProviderRecordCount;
    }

    int errorCount() {
        return errorCount;
    }

    String summaryMessage() {
        return summaryMessage;
    }

    List<String> errors() {
        return errors;
    }

    List<String> addedAsUnchecked() {
        return addedAsUnchecked;
    }

    List<String> alreadyInLibrary() {
        return alreadyInLibrary;
    }

    List<String> releaseYearConflicts() {
        return releaseYearConflicts;
    }

    List<String> titleConflicts() {
        return titleConflicts;
    }

    List<String> ignoredProviderRecords() {
        return ignoredProviderRecords;
    }

    List<String> artistsSkipped() {
        return artistsSkipped;
    }

    List<String> noChanges() {
        return noChanges;
    }

    List<String> notes() {
        return notes;
    }
}
