package org.kroky.musiclib.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.db.Names;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ScanReportWriter {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Inject
    MusicLibraryConfig config;

    @Inject
    PathResolver pathResolver;

    public String write(ScanReport report) {
        try {
            Path reportRoot = pathResolver.resolve(config.reportDirectory()).normalize();
            Path scansRoot = reportRoot.resolve("scans");
            Files.createDirectories(scansRoot);
            String fileName = fileName(report);
            Path reportFile = scansRoot.resolve(fileName);
            Files.writeString(reportFile, render(report));
            return "scans/" + fileName;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write scan report", e);
        }
    }

    private static String fileName(ScanReport report) {
        String collectionId = report.collection().id() == null ? "collection" : report.collection().id();
        String safeCollection = Names.normalize(collectionId).replace(' ', '-');
        String safeKind = Names.normalize(report.kind()).replace(' ', '-');
        return FILE_TIMESTAMP.format(report.startedAt()) + "_" + safeCollection + "_" + safeKind
                + "_" + report.runId() + ".txt";
    }

    private static String render(ScanReport report) {
        StringBuilder text = new StringBuilder();
        line(text, "Scan report");
        line(text, "===========");
        line(text, "");
        line(text, "Run id: " + report.runId());
        line(text, "Kind: " + report.kind());
        line(text, "Collection: " + report.collection().name() + " (" + report.collection().id() + ")");
        line(text, "Collection type: " + report.collection().type());
        line(text, "Parser: " + report.collection().parser());
        line(text, "Root: " + report.collectionRoot());
        line(text, "Started: " + DISPLAY_TIMESTAMP.format(report.startedAt()));
        line(text, "Finished: " + (report.finishedAt() == null ? "" : DISPLAY_TIMESTAMP.format(report.finishedAt())));
        line(text, "Duration: " + duration(report));
        line(text, "Status: " + value(report.status()));
        line(text, "");

        line(text, "Summary");
        line(text, "-------");
        line(text, "Dirs scanned: " + report.scannedDirs() + "/" + report.totalDirs());
        line(text, "Parsed: " + report.parsedCount());
        line(text, "Created: " + report.createdCount());
        line(text, "Existing: " + report.existingCount());
        line(text, "Missing: " + report.missingCount());
        line(text, "Skipped: " + report.skippedCount());
        line(text, "Message: " + value(report.summaryMessage()));
        line(text, "");

        section(text, "Created", report.created());
        section(text, "Existing", report.existing());
        section(text, "Skipped", report.skipped());
        section(text, "Missing", report.missing());
        section(text, "Warnings", report.warnings());
        section(text, "Notes", report.notes());
        return text.toString();
    }

    private static String duration(ScanReport report) {
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
