package org.kroky.musiclib.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.scan.PathResolver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckReportWriter {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Inject
    MusicLibraryConfig config;

    @Inject
    PathResolver pathResolver;

    public ReportArtifact write(ProviderCheckReport report) {
        try {
            Path reportRoot = pathResolver.resolve(config.reportDirectory()).normalize();
            Path providersRoot = reportRoot.resolve("providers");
            Files.createDirectories(providersRoot);
            String fileName = uniqueFileName(providersRoot, baseFileName(report));
            Path reportFile = providersRoot.resolve(fileName);
            String text = render(report);
            Files.writeString(reportFile, text);
            return new ReportArtifact("Provider Check Report", "providers/" + fileName, text);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write provider check report", e);
        }
    }

    private static String baseFileName(ProviderCheckReport report) {
        String safeSubject = Names.normalize(report.subject()).replace(' ', '-');
        if (safeSubject.isBlank()) {
            safeSubject = "provider-check";
        }
        return FILE_TIMESTAMP.format(report.startedAt()) + "_" + safeSubject + ".txt";
    }

    private static String uniqueFileName(Path reportRoot, String fileName) {
        if (!Files.exists(reportRoot.resolve(fileName))) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String extension = dot < 0 ? "" : fileName.substring(dot);
        for (int suffix = 2; suffix < 1_000; suffix++) {
            String candidate = stem + "-" + suffix + extension;
            if (!Files.exists(reportRoot.resolve(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate report file name for " + fileName);
    }

    private static String render(ProviderCheckReport report) {
        StringBuilder text = new StringBuilder();
        line(text, "Provider Check Report");
        line(text, "=====================");
        line(text, "");
        line(text, "Subject: " + report.subject());
        line(text, "Started: " + DISPLAY_TIMESTAMP.format(report.startedAt()));
        line(text, "Finished: " + (report.finishedAt() == null ? "" : DISPLAY_TIMESTAMP.format(report.finishedAt())));
        line(text, "Duration: " + duration(report));
        line(text, "Status: " + value(report.status()));
        line(text, "");

        line(text, "Summary");
        line(text, "-------");
        line(text, "Artists checked: " + report.artistsChecked());
        line(text, "Artists skipped: " + report.artistsSkippedCount());
        line(text, "Provider albums found: " + report.providerAlbumsFound());
        line(text, "Already in library: " + report.alreadyInLibraryCount());
        line(text, "Release date conflicts: " + report.releaseDateConflictCount());
        line(text, "Title conflicts: " + report.titleConflictCount());
        line(text, "Added as unchecked: " + report.addedAsUncheckedCount());
        line(text, "Provider records ignored: " + report.ignoredProviderRecordCount());
        line(text, "Errors: " + report.errorCount());
        line(text, "");

        section(text, "Errors", report.errors());
        section(text, "Added As Unchecked", report.addedAsUnchecked());
        section(text, "Already In Library", report.alreadyInLibrary());
        section(text, "Release Date Conflicts", report.releaseDateConflicts());
        section(text, "Title Conflicts", report.titleConflicts());
        section(text, "Provider Records Ignored", report.ignoredProviderRecords());
        section(text, "Artists Skipped", report.artistsSkipped());
        section(text, "No Changes", report.noChanges());
        section(text, "Notes", report.notes());
        return text.toString();
    }

    private static String duration(ProviderCheckReport report) {
        if (report.finishedAt() == null) {
            return "";
        }
        return Duration.between(report.startedAt(), report.finishedAt()).toMillis() + " ms";
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void section(StringBuilder text, String title, List<String> rows) {
        line(text, title + " (" + rows.size() + ")");
        line(text, "-".repeat(title.length() + 4 + String.valueOf(rows.size()).length()));
        if (rows.isEmpty()) {
            line(text, "<none>");
        } else {
            for (String row : rows) {
                line(text, "- " + row);
            }
        }
        line(text, "");
    }

    private static void line(StringBuilder text, String value) {
        text.append(value).append(System.lineSeparator());
    }
}
