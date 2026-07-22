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
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.ReportArtifact;

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

    public ReportArtifact write(ScanReport report) {
        try {
            Path reportRoot = pathResolver.resolve(config.reportDirectory()).normalize();
            Path scansRoot = reportRoot.resolve("scans");
            Files.createDirectories(scansRoot);
            String fileName = uniqueFileName(scansRoot, baseFileName(report));
            Path reportFile = scansRoot.resolve(fileName);
            String text = render(report);
            Files.writeString(reportFile, text);
            return new ReportArtifact("Scan report", "scans/" + fileName, text);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write scan report", e);
        }
    }

    private static String baseFileName(ScanReport report) {
        String collectionId = Long.toString(report.collection().id());
        String safeCollection = Names.normalize(collectionId).replace(' ', '-');
        String safeKind = Names.normalize(report.kind()).replace(' ', '-');
        return FILE_TIMESTAMP.format(report.startedAt()) + "_" + safeCollection + "_" + safeKind + ".txt";
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

    private static String render(ScanReport report) {
        StringBuilder text = new StringBuilder();
        line(text, "Scan report");
        line(text, "===========");
        line(text, "");
        line(text, "Kind: " + report.kind());
        line(text, "Collection: " + report.collection().name() + " (" + report.collection().id() + ")");
        line(text, "Collection type: " + report.collection().type());
        line(text, "Root: " + report.collectionRoot());
        line(text, "Started: " + DISPLAY_TIMESTAMP.format(report.startedAt()));
        line(text, "Finished: " + (report.finishedAt() == null ? "" : DISPLAY_TIMESTAMP.format(report.finishedAt())));
        line(text, "Duration: " + duration(report));
        line(text, "Status: " + value(report.status()));
        line(text, "");

        line(text, "Summary");
        line(text, "-------");
        line(text, "Dirs scanned: " + report.scannedDirs() + "/" + report.totalDirs());
        summaryCounts(text, report);
        line(text, "Local paths removed: " + report.missingCount());
        line(text, "Folders skipped: " + report.skippedCount());
        line(text, "");

        section(text, "Created", report.created());
        section(text, "Existing", report.existing());
        section(text, "Skipped", report.skipped());
        section(text, "Removed local paths", report.missing());
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

    private static void summaryCounts(StringBuilder text, ScanReport report) {
        if (report.collection().type() == CollectionType.TITLE) {
            line(text, "Titles parsed: " + report.parsedCount());
            line(text, "Titles created: " + report.createdCount());
            line(text, "Titles existing: " + report.existingCount());
            line(text, "Contributor artists found: " + report.artistCount());
            return;
        }
        line(text, "Artists found: " + report.artistCount());
        line(text, "Albums parsed: " + report.parsedCount());
        line(text, "Albums created: " + report.createdCount());
        line(text, "Albums existing: " + report.existingCount());
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
