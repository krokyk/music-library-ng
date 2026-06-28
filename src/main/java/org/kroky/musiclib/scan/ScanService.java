package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.ArtistNames;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ScanRunRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);
    private static final int MAX_SKIPPED_EXAMPLES = 25;

    @Inject
    MusicCollectionRepository collectionRepository;

    @Inject
    ArtistRepository artistRepository;

    @Inject
    AlbumRepository albumRepository;

    @Inject
    ScanRunRepository scanRunRepository;

    @Inject
    ScanReportWriter scanReportWriter;

    @Inject
    FolderNameParser parser;

    @Inject
    MusicRootService musicRootService;

    public List<ScanSummary> scanAllEnabled() {
        return scanAllEnabled(ProgressListener.NONE);
    }

    public List<ScanSummary> scanAllEnabled(ProgressListener progress) {
        LOG.info("Starting scan for all collections");
        List<ScanSummary> summaries = new ArrayList<>();
        for (MusicCollection collection : collectionRepository.list()) {
            if (progress.isCancelled()) {
                break;
            }
            summaries.add(scan(collection, progress));
        }
        return summaries;
    }

    public ScanSummary scan(String collectionId) {
        return scan(collectionId, ProgressListener.NONE);
    }

    public ScanSummary scan(String collectionId, ProgressListener progress) {
        MusicCollection collection = collectionRepository.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        return scan(collection, progress);
    }

    public ScanSummary scan(MusicCollection collection) {
        return scan(collection, ProgressListener.NONE);
    }

    public ScanSummary scan(MusicCollection collection, ProgressListener progress) {
        long runId = scanRunRepository.start(collection.id());
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int updated = 0;
        int missing = 0;
        int skipped = 0;
        int skippedExamples = 0;
        int existing = 0;
        ScanReport report = null;

        try {
            Path collectionRoot = musicRootService.resolveCollection(collection.relativePath());
            report = new ScanReport(runId, "COLLECTION", collection, collectionRoot);
            LOG.infof("Scanning collection %s at %s using %s", collection.id(), collectionRoot, collection.parser());
            scanRunRepository.event(runId, "INFO", "Scanning " + collection.name() + " at " + collectionRoot);
            if (!Files.isDirectory(collectionRoot)) {
                String message = "Collection directory does not exist: " + collectionRoot;
                messages.add(message);
                report.warning(message);
                report.finish("SKIPPED", parsed, created, existing, missing, skipped + 1, message);
                scanRunRepository.event(runId, "WARN", message);
                finishRun(runId, "SKIPPED", parsed, created, updated, missing, skipped + 1, message, report);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                        skipped + 1, messages);
            }

            List<Path> folders;
            try (var stream = Files.list(collectionRoot)) {
                folders = stream.filter(Files::isDirectory).toList();
            }
            report.totalDirs(folders.size());

            progress.collectionStarted(collection.id(), folders.size());

            int processedFolders = 0;
            if (collection.type() == CollectionType.TITLE) {
                Set<String> seenPaths = new HashSet<>();
                for (Path folder : folders) {
                    if (progress.isCancelled()) {
                        String message = "Scan cancelled for " + collection.name() + ".";
                        messages.add(message);
                        report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                        scanRunRepository.event(runId, "INFO", message);
                        finishRun(runId, "SKIPPED", parsed, created, updated, missing, skipped, message, report);
                        collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                        return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                                skipped, messages);
                    }

                    var parsedTitle = parser.parseTitleAlbum(folder, collection.id());
                    seenPaths.add(folder.getFileName().toString());
                    parsed++;
                    List<Long> artistIds = upsertContributorArtists(parsedTitle.artistName());
                    UpsertResult result = albumRepository.upsertTitleScanned(
                            artistIds,
                            parsedTitle.title(),
                            parsedTitle.releaseDate(),
                            parsedTitle.sortName(),
                            folder.getFileName().toString(),
                            collection.id());
                    if (result.created()) {
                        created++;
                        report.created(titleReportRow(folder, parsedTitle.title(), parsedTitle.artistName(),
                                parsedTitle.releaseDate()));
                    } else {
                        existing++;
                        report.existing(titleReportRow(folder, parsedTitle.title(), parsedTitle.artistName(),
                                parsedTitle.releaseDate()));
                    }
                    processedFolders++;
                    report.scannedDirs(processedFolders);
                    progress.itemProcessed(collection.id(), processedFolders);
                }

                missing = albumRepository.removeUnseenLocalPaths(collection.id(), seenPaths);
                String message = "Scanned " + collection.name() + ": " + parsed + " titles, " + created
                        + " created, " + existing + " existing, " + missing + " local paths removed.";
                messages.add(message);
                report.finish("DONE", parsed, created, existing, missing, skipped, message);
                scanRunRepository.event(runId, "INFO", message);
                finishRun(runId, "DONE", parsed, created, updated, missing, skipped, message, report);
                collectionRepository.markScanned(collection.id(), "DONE", message);
                LOG.info(message);
                return new ScanSummary(runId, collection.id(), "DONE", parsed, created, updated, missing, skipped,
                        messages);
            }

            Set<String> seenPaths = new HashSet<>();
            Set<Long> seenLocalArtistIds = new HashSet<>();
            for (Path folder : folders) {
                if (progress.isCancelled()) {
                    String message = "Scan cancelled for " + collection.name() + ".";
                    messages.add(message);
                    report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                    scanRunRepository.event(runId, "INFO", message);
                    finishRun(runId, "SKIPPED", parsed, created, updated, missing, skipped, message, report);
                    collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                    return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                            skipped, messages);
                }
                Optional<ParsedAlbum> parsedAlbum = parser.parse(folder, collection.parser(), collection.id());
                if (parsedAlbum.isPresent()) {
                    String parsedArtistName = parsedAlbum.get().artistName();
                    UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                    progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                    artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                    seenLocalArtistIds.add(artistResult.id());
                    String relativePath = folder.getFileName().toString();
                    seenPaths.add(relativePath);
                    UpsertResult albumResult = albumRepository.upsertScanned(
                            artistResult.id(),
                            parsedAlbum.get().title(),
                            parsedAlbum.get().releaseDate(),
                            relativePath,
                            collection.id());
                    parsed++;
                    if (albumResult.created()) {
                        created++;
                        report.created(albumReportRow(relativePath, parsedArtistName, parsedAlbum.get().title(),
                                parsedAlbum.get().releaseDate()));
                    } else {
                        existing++;
                        report.existing(albumReportRow(relativePath, parsedArtistName, parsedAlbum.get().title(),
                                parsedAlbum.get().releaseDate()));
                    }
                } else {
                    Optional<NestedArtistFolder> nestedArtist = nestedArtistFolder(folder, collection.id());
                    if (nestedArtist.isEmpty()) {
                        skipped++;
                        LOG.debugf("Skipped unmatched folder %s for collection %s", folder, collection.id());
                        report.skipped("Unmatched folder: " + folder.getFileName());
                        if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                            scanRunRepository.event(runId, "SKIPPED", "Unmatched folder: " + folder.getFileName());
                            skippedExamples++;
                        }
                    } else {
                        String parsedArtistName = nestedArtist.get().artistName();
                        UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                        progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                        artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                        seenLocalArtistIds.add(artistResult.id());
                        for (Path unmatchedAlbumFolder : nestedArtist.get().unmatchedAlbumFolders()) {
                            skipped++;
                            String relativePath = nestedRelativePath(folder, unmatchedAlbumFolder);
                            report.skipped("Unmatched nested album folder: " + relativePath);
                            if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                                scanRunRepository.event(runId, "SKIPPED",
                                        "Unmatched nested album folder: " + relativePath);
                                skippedExamples++;
                            }
                        }
                        if (nestedArtist.get().albums().isEmpty()) {
                            parsed++;
                            if (artistResult.created()) {
                                created++;
                                report.created("Artist: " + parsedArtistName + " | folder: " + folder.getFileName());
                            } else {
                                existing++;
                                report.existing("Artist: " + parsedArtistName + " | folder: " + folder.getFileName());
                            }
                        }
                        for (LocalAlbumCandidate candidate : nestedArtist.get().albums()) {
                            seenPaths.add(candidate.relativePath());
                            UpsertResult albumResult = albumRepository.upsertScanned(
                                    artistResult.id(),
                                    candidate.album().title(),
                                    candidate.album().releaseDate(),
                                    candidate.relativePath(),
                                    collection.id());
                            parsed++;
                            if (albumResult.created()) {
                                created++;
                                report.created(albumReportRow(candidate.relativePath(), parsedArtistName,
                                        candidate.album().title(), candidate.album().releaseDate()));
                            } else {
                                existing++;
                                report.existing(albumReportRow(candidate.relativePath(), parsedArtistName,
                                        candidate.album().title(), candidate.album().releaseDate()));
                            }
                        }
                    }
                }
                processedFolders++;
                report.scannedDirs(processedFolders);
                progress.itemProcessed(collection.id(), processedFolders);
            }
            if (progress.isCancelled()) {
                String message = "Scan cancelled for " + collection.name() + ".";
                messages.add(message);
                report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                scanRunRepository.event(runId, "INFO", message);
                finishRun(runId, "SKIPPED", parsed, created, updated, missing, skipped, message, report);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                        skipped, messages);
            }

            missing = albumRepository.removeUnseenLocalPaths(collection.id(), seenPaths);
            artistRepository.replaceLocalArtistsForCollection(collection.id(), seenLocalArtistIds);
            artistRepository.clearLocalScanErrorsForCollection(collection.id());

            String message = "Scanned " + collection.name() + ": " + parsed + " local items, " + created
                    + " created, " + existing + " existing, " + missing + " local paths removed, " + skipped
                    + " skipped.";
            messages.add(message);
            report.finish("DONE", parsed, created, existing, missing, skipped, message);
            scanRunRepository.event(runId, "INFO", message);
            if (skipped > skippedExamples) {
                scanRunRepository.event(runId, "INFO",
                        "Skipped " + (skipped - skippedExamples) + " more unmatched folders.");
            }
            finishRun(runId, "DONE", parsed, created, updated, missing, skipped, message, report);
            collectionRepository.markScanned(collection.id(), "DONE", message);
            LOG.info(message);
            return new ScanSummary(runId, collection.id(), "DONE", parsed, created, updated, missing, skipped, messages);
        } catch (Exception e) {
            String message = "Scan failed for " + collection.name() + ": " + e.getMessage();
            messages.add(message);
            if (report != null) {
                report.warning(message);
                report.finish("FAILED", parsed, created, existing, missing, skipped, message);
            }
            scanRunRepository.event(runId, "ERROR", message);
            finishRun(runId, "FAILED", parsed, created, updated, missing, skipped, message, report);
            collectionRepository.markScanned(collection.id(), "FAILED", message);
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    public ScanSummary scanLocalAlbums(String collectionId, Long artistId, ProgressListener progress) {
        MusicCollection collection = collectionRepository.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        return scanLocalAlbums(collection, artistId, progress);
    }

    public ScanSummary scanLocalAlbums(MusicCollection collection, Long artistId, ProgressListener progress) {
        if (collection.type() != CollectionType.ARTIST) {
            throw new IllegalArgumentException("Local album scan is only available for artist collections");
        }

        long runId = scanRunRepository.start(collection.id());
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int existing = 0;
        int missing = 0;
        int skipped = 0;
        int skippedExamples = 0;
        ScanReport report = null;

        try {
            Path collectionRoot = musicRootService.resolveCollection(collection.relativePath());
            report = new ScanReport(runId, artistId == null ? "LOCAL_ALBUMS" : "LOCAL_ALBUMS_ARTIST", collection,
                    collectionRoot);
            LOG.infof("Scanning local albums collection=%s artistId=%s at %s using %s",
                    collection.id(), artistId, collectionRoot, collection.parser());
            scanRunRepository.event(runId, "INFO", "Scanning local albums in " + collection.name());
            if (!Files.isDirectory(collectionRoot)) {
                String message = "Collection directory does not exist: " + collectionRoot;
                messages.add(message);
                report.warning(message);
                report.finish("SKIPPED", parsed, created, existing, missing, skipped + 1, message);
                scanRunRepository.event(runId, "WARN", message);
                finishRun(runId, "SKIPPED", parsed, created, existing, missing, skipped + 1, message, report);
                return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, existing, missing,
                        skipped + 1, messages);
            }

            String selectedArtistName = null;
            String selectedArtistNormalizedName = null;
            if (artistId != null) {
                var artist = artistRepository.find(artistId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
                selectedArtistName = artist.name();
                selectedArtistNormalizedName = Names.normalize(artist.name());
            }

            List<Path> folders;
            try (var stream = Files.list(collectionRoot)) {
                folders = stream.filter(Files::isDirectory).toList();
            }
            report.totalDirs(folders.size());
            progress.collectionStarted(collection.id(), folders.size());

            Set<String> seenPaths = new HashSet<>();
            Set<Long> seenLocalArtistIds = new HashSet<>();
            int processedFolders = 0;
            for (Path folder : folders) {
                if (progress.isCancelled()) {
                    String message = "Local album scan cancelled for " + collection.name() + ".";
                    messages.add(message);
                    report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                    scanRunRepository.event(runId, "INFO", message);
                    finishRun(runId, "SKIPPED", parsed, created, existing, missing, skipped, message, report);
                    return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, existing, missing,
                            skipped, messages);
                }

                Optional<ParsedAlbum> parsedAlbum = parser.parse(folder, collection.parser(), collection.id());
                if (parsedAlbum.isPresent()) {
                    String parsedArtistName = parsedAlbum.get().artistName();
                    String parsedArtistNormalizedName = Names.normalize(parsedArtistName);
                    if (selectedArtistNormalizedName != null
                            && !selectedArtistNormalizedName.equals(parsedArtistNormalizedName)) {
                        skipped++;
                        report.skipped("Out of selected artist scope: " + folder.getFileName()
                                + " | parsed artist: " + parsedArtistName
                                + " | selected artist: " + selectedArtistName);
                    } else {
                        UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                        progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                        artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                        seenLocalArtistIds.add(artistResult.id());
                        String relativePath = folder.getFileName().toString();
                        seenPaths.add(relativePath);
                        UpsertResult albumResult = albumRepository.upsertScanned(
                                artistResult.id(),
                                parsedAlbum.get().title(),
                                parsedAlbum.get().releaseDate(),
                                relativePath,
                                collection.id());
                        parsed++;
                        if (albumResult.created()) {
                            created++;
                            report.created(albumReportRow(relativePath, parsedArtistName, parsedAlbum.get().title(),
                                    parsedAlbum.get().releaseDate()));
                        } else {
                            existing++;
                            report.existing(albumReportRow(relativePath, parsedArtistName, parsedAlbum.get().title(),
                                    parsedAlbum.get().releaseDate()));
                        }
                    }
                } else {
                    Optional<NestedArtistFolder> nestedArtist = nestedArtistFolder(folder, collection.id());
                    if (nestedArtist.isEmpty()) {
                        skipped++;
                        report.skipped("Unmatched folder: " + folder.getFileName());
                        if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                            scanRunRepository.event(runId, "SKIPPED", "Unmatched folder: " + folder.getFileName());
                            skippedExamples++;
                        }
                    } else {
                        String parsedArtistName = nestedArtist.get().artistName();
                        String parsedArtistNormalizedName = Names.normalize(parsedArtistName);
                        if (selectedArtistNormalizedName != null
                                && !selectedArtistNormalizedName.equals(parsedArtistNormalizedName)) {
                            skipped++;
                            report.skipped("Out of selected artist scope: " + folder.getFileName()
                                    + " | parsed artist: " + parsedArtistName
                                    + " | selected artist: " + selectedArtistName);
                        } else {
                            UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                            progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                            artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                            seenLocalArtistIds.add(artistResult.id());
                            for (Path unmatchedAlbumFolder : nestedArtist.get().unmatchedAlbumFolders()) {
                                skipped++;
                                String relativePath = nestedRelativePath(folder, unmatchedAlbumFolder);
                                report.skipped("Unmatched nested album folder: " + relativePath);
                                if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                                    scanRunRepository.event(runId, "SKIPPED",
                                            "Unmatched nested album folder: " + relativePath);
                                    skippedExamples++;
                                }
                            }
                            if (nestedArtist.get().albums().isEmpty()) {
                                parsed++;
                                if (artistResult.created()) {
                                    created++;
                                    report.created("Artist: " + parsedArtistName + " | folder: " + folder.getFileName());
                                } else {
                                    existing++;
                                    report.existing("Artist: " + parsedArtistName + " | folder: " + folder.getFileName());
                                }
                            }
                            for (LocalAlbumCandidate candidate : nestedArtist.get().albums()) {
                                seenPaths.add(candidate.relativePath());
                                UpsertResult albumResult = albumRepository.upsertScanned(
                                        artistResult.id(),
                                        candidate.album().title(),
                                        candidate.album().releaseDate(),
                                        candidate.relativePath(),
                                        collection.id());
                                parsed++;
                                if (albumResult.created()) {
                                    created++;
                                    report.created(albumReportRow(candidate.relativePath(), parsedArtistName,
                                            candidate.album().title(), candidate.album().releaseDate()));
                                } else {
                                    existing++;
                                    report.existing(albumReportRow(candidate.relativePath(), parsedArtistName,
                                            candidate.album().title(), candidate.album().releaseDate()));
                                }
                            }
                        }
                    }
                }
                processedFolders++;
                report.scannedDirs(processedFolders);
                progress.itemProcessed(collection.id(), processedFolders);
            }

            missing = artistId == null
                    ? albumRepository.removeUnseenLocalPaths(collection.id(), seenPaths)
                    : albumRepository.removeUnseenLocalPathsForArtist(collection.id(), artistId, seenPaths);
            if (artistId == null) {
                artistRepository.replaceLocalArtistsForCollection(collection.id(), seenLocalArtistIds);
                artistRepository.clearLocalScanErrorsForCollection(collection.id());
            } else {
                artistRepository.setCollectionLocal(artistId, collection.id(), seenLocalArtistIds.contains(artistId));
                artistRepository.clearLocalScanError(artistId, collection.id());
            }
            String scope = selectedArtistName == null ? collection.name() : selectedArtistName;
            String message = "Local album scan complete for " + scope + ": " + parsed + " albums, " + created
                    + " new, " + existing + " existing, " + missing + " local paths removed, " + skipped
                    + " skipped.";
            messages.add(message);
            report.finish("DONE", parsed, created, existing, missing, skipped, message);
            scanRunRepository.event(runId, "INFO", message);
            if (skipped > skippedExamples) {
                scanRunRepository.event(runId, "INFO",
                        "Skipped " + (skipped - skippedExamples) + " more folders.");
            }
            finishRun(runId, "DONE", parsed, created, existing, missing, skipped, message, report);
            LOG.info(message);
            return new ScanSummary(runId, collection.id(), "DONE", parsed, created, existing, missing, skipped, messages);
        } catch (Exception e) {
            String message = "Local album scan failed for " + collection.name() + ": " + e.getMessage();
            if (artistId != null) {
                artistRepository.markLocalScanError(artistId, collection.id(), e.getMessage());
            }
            messages.add(message);
            if (report != null) {
                report.warning(message);
                report.finish("FAILED", parsed, created, existing, missing, skipped, message);
            }
            scanRunRepository.event(runId, "ERROR", message);
            finishRun(runId, "FAILED", parsed, created, existing, missing, skipped, message, report);
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private List<Long> upsertContributorArtists(String artistName) {
        List<Long> artistIds = new ArrayList<>();
        for (String name : ArtistNames.splitList(artistName)) {
            UpsertResult artistResult = artistRepository.upsertByName(name);
            artistIds.add(artistResult.id());
        }
        return artistIds;
    }

    private List<Long> upsertArtists(String artistName, String collectionId) {
        List<Long> artistIds = new ArrayList<>();
        for (String name : ArtistNames.splitList(artistName)) {
            UpsertResult artistResult = artistRepository.upsertByName(name);
            artistRepository.assignToCollection(artistResult.id(), collectionId, false);
            artistIds.add(artistResult.id());
        }
        return artistIds;
    }

    private Optional<NestedArtistFolder> nestedArtistFolder(Path artistFolder, String collectionId) throws Exception {
        List<Path> albumFolders = directChildDirectories(artistFolder);
        List<LocalAlbumCandidate> albums = new ArrayList<>();
        List<Path> unmatchedAlbumFolders = new ArrayList<>();
        for (Path albumFolder : albumFolders) {
            Optional<ParsedAlbum> parsedAlbum = parser.parseNestedArtistAlbum(artistFolder, albumFolder, collectionId);
            if (parsedAlbum.isPresent()) {
                albums.add(new LocalAlbumCandidate(
                        parsedAlbum.get(),
                        nestedRelativePath(artistFolder, albumFolder)));
            } else {
                unmatchedAlbumFolders.add(albumFolder);
            }
        }

        if (albums.isEmpty() && hasDirectRegularFile(artistFolder)) {
            return Optional.empty();
        }

        return Optional.of(new NestedArtistFolder(
                Names.chicagoStyle(cleanFolderName(artistFolder)),
                albums,
                unmatchedAlbumFolders));
    }

    private static List<Path> directChildDirectories(Path folder) throws Exception {
        try (var stream = Files.list(folder)) {
            return stream.filter(Files::isDirectory).toList();
        }
    }

    private static boolean hasDirectRegularFile(Path folder) throws Exception {
        try (var stream = Files.list(folder)) {
            return stream.anyMatch(path -> Files.isRegularFile(path));
        }
    }

    private static String nestedRelativePath(Path artistFolder, Path albumFolder) {
        return artistFolder.getFileName() + "/" + albumFolder.getFileName();
    }

    private static String cleanFolderName(Path folder) {
        return folder.getFileName().toString().trim().replaceAll("\\s+", " ");
    }

    private void finishRun(long runId, String status, int parsed, int created, int updated, int missing,
            int skipped, String message, ScanReport report) {
        String reportPath = null;
        if (report != null) {
            try {
                reportPath = scanReportWriter.write(report);
            } catch (Exception e) {
                LOG.warnf("Unable to write scan report for run %d: %s", runId, e.getMessage());
            }
        }
        scanRunRepository.finish(runId, status, parsed, created, updated, missing, skipped, message, reportPath);
    }

    private static String titleReportRow(Path folder, String title, String artistName, String releaseDate) {
        return "Title: " + title
                + " | artist: " + blankValue(artistName)
                + " | release: " + blankValue(releaseDate)
                + " | folder: " + folder.getFileName();
    }

    private static String albumReportRow(String folder, String artistName, String title, String releaseDate) {
        return "Artist: " + artistName
                + " | album: " + title
                + " | release: " + blankValue(releaseDate)
                + " | folder: " + folder;
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "<blank>" : value;
    }

    private record LocalAlbumCandidate(
            ParsedAlbum album,
            String relativePath) {
    }

    private record NestedArtistFolder(
            String artistName,
            List<LocalAlbumCandidate> albums,
            List<Path> unmatchedAlbumFolders) {
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void collectionStarted(String collectionId, int itemTotal) {
        }

        default void artistStarted(String collectionId, Long artistId, String artistName) {
        }

        default void itemProcessed(String collectionId, int processedItems) {
        }

        default boolean isCancelled() {
            return false;
        }
    }
}
