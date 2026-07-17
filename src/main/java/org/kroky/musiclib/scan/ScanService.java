package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.ArtistNames;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.repository.AlbumRepository.LocalPathSnapshot;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);

    @Inject
    MusicCollectionRepository collectionRepository;

    @Inject
    ArtistRepository artistRepository;

    @Inject
    AlbumRepository albumRepository;

    @Inject
    ScanReportWriter scanReportWriter;

    @Inject
    FolderNameParser parser;

    @Inject
    MusicRootService musicRootService;

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

    public ScanSummary scan(String collectionId, ProgressListener progress) {
        MusicCollection collection = collectionRepository.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        return scan(collection, progress);
    }

    public ScanSummary scan(MusicCollection collection, ProgressListener progress) {
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int missing = 0;
        int skipped = 0;
        int existing = 0;
        Set<Long> seenArtistIds = new HashSet<>();
        ScanReport report = null;

        try {
            Path collectionRoot = musicRootService.resolveCollection(collection.relativePath());
            report = new ScanReport("COLLECTION", collection, collectionRoot);
            LOG.infof("Scanning collection %s at %s using %s", collection.id(), collectionRoot, collection.parser());
            if (!Files.isDirectory(collectionRoot)) {
                String message = "Collection directory does not exist: " + collectionRoot;
                messages.add(message);
                report.warning(message);
                report.finish("SKIPPED", parsed, created, existing, missing, skipped + 1, message);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return scanSummary(collection.id(), "SKIPPED", seenArtistIds.size(), parsed, created,
                        existing, missing, skipped + 1, messages, report);
            }

            progress.phaseStarted(collection.id(), "Comparing DB and local folders");
            Map<String, LocalPathSnapshot> knownPaths = albumRepository.localPathSnapshot(collection.id());
            List<Path> folders = directChildDirectories(collectionRoot);

            if (collection.type() == CollectionType.TITLE) {
                Set<String> diskPaths = directRelativePaths(folders);
                int unchanged = addKnownPathArtists(knownPaths, diskPaths, seenArtistIds);
                List<Path> foldersToProcess = directFoldersToProcess(folders, knownPaths);
                report.totalDirs(foldersToProcess.size());
                report.note(snapshotNote(knownPaths.size(), diskPaths.size(), foldersToProcess.size(), unchanged));
                progress.collectionStarted(collection.id(), foldersToProcess.size());
                if (foldersToProcess.isEmpty()) {
                    progress.phaseStarted(collection.id(), "No new local folders to process");
                }

                int processedFolders = 0;
                for (Path folder : foldersToProcess) {
                    if (progress.isCancelled()) {
                        return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                                missing, skipped, messages);
                    }

                    var parsedTitle = parser.parseTitleAlbum(folder, collection.id());
                    parsed++;
                    List<Long> artistIds = upsertContributorArtists(parsedTitle.artistName());
                    seenArtistIds.addAll(artistIds);
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
                if (progress.isCancelled()) {
                    return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                            missing, skipped, messages);
                }

                missing = albumRepository.removeUnseenLocalPaths(collection.id(), diskPaths);
                String message = "Scanned " + collection.name() + ": " + countWithLabel(parsed, "title", "titles")
                        + ", " + created
                        + " created, " + existing + " existing, " + unchanged + " unchanged, "
                        + missing + " local paths removed.";
                messages.add(message);
                report.artistCount(seenArtistIds.size());
                report.finish("DONE", parsed, created, existing, missing, skipped, message);
                collectionRepository.markScanned(collection.id(), "DONE", message);
                LOG.info(message);
                return scanSummary(collection.id(), "DONE", seenArtistIds.size(), parsed, created,
                        existing, missing, skipped, messages, report);
            }

            ArtistDeltaScanPlan plan = artistDeltaScanPlan(folders, collection, knownPaths);
            seenArtistIds.addAll(plan.unchangedArtistIds());
            report.totalDirs(plan.itemTotal());
            report.note(snapshotNote(knownPaths.size(), plan.diskPaths().size(), plan.itemTotal(),
                    plan.unchangedCount()));
            progress.collectionStarted(collection.id(), plan.itemTotal());
            if (plan.itemTotal() == 0) {
                progress.phaseStarted(collection.id(), "No new local folders to process");
            }

            int processedFolders = 0;
            for (FlatArtistFolder folder : plan.flatFolders()) {
                if (progress.isCancelled()) {
                    return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                            missing, skipped, messages);
                }
                Optional<ParsedAlbum> parsedAlbum = parser.parse(folder.folder(), collection.parser(), collection.id());
                if (parsedAlbum.isEmpty()) {
                    skipped++;
                    processedFolders++;
                    LOG.debugf("Skipped unmatched folder %s for collection %s", folder.folder(), collection.id());
                    report.skipped("Unmatched folder: " + folder.folder().getFileName());
                    report.scannedDirs(processedFolders);
                    progress.itemProcessed(collection.id(), processedFolders);
                    continue;
                }
                String parsedArtistName = parsedAlbum.get().artistName();
                UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                seenArtistIds.add(artistResult.id());
                UpsertResult albumResult = albumRepository.upsertScanned(
                        artistResult.id(),
                        parsedAlbum.get().title(),
                        parsedAlbum.get().releaseDate(),
                        folder.relativePath(),
                        collection.id());
                parsed++;
                processedFolders++;
                if (albumResult.created()) {
                    created++;
                    report.created(albumReportRow(folder.relativePath(), parsedArtistName, parsedAlbum.get().title(),
                            parsedAlbum.get().releaseDate()));
                } else {
                    existing++;
                    report.existing(albumReportRow(folder.relativePath(), parsedArtistName, parsedAlbum.get().title(),
                            parsedAlbum.get().releaseDate()));
                }
                report.scannedDirs(processedFolders);
                progress.itemProcessed(collection.id(), processedFolders);
            }

            Map<Path, ArtistScanContext> nestedArtistContexts = new java.util.HashMap<>();
            for (NestedArtistDiskFolder nestedFolder : plan.nestedFolders()) {
                if (progress.isCancelled()) {
                    return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                            missing, skipped, messages);
                }
                if (!nestedFolder.artistFolder()) {
                    skipped++;
                    LOG.debugf("Skipped unmatched folder %s for collection %s", nestedFolder.folder(), collection.id());
                    report.skipped("Unmatched folder: " + nestedFolder.folder().getFileName());
                    continue;
                }
                UpsertResult artistResult = artistRepository.upsertByName(nestedFolder.artistName());
                progress.artistStarted(collection.id(), artistResult.id(), nestedFolder.artistName());
                artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                seenArtistIds.add(artistResult.id());
                nestedArtistContexts.put(nestedFolder.folder(),
                        new ArtistScanContext(artistResult.id(), nestedFolder.artistName(), artistResult.created()));
                if (nestedFolder.albumFolders().isEmpty()) {
                    report.note((artistResult.created() ? "Created" : "Existing")
                            + " artist without parsed albums: " + nestedFolder.artistName()
                            + " | folder: " + nestedFolder.folder().getFileName());
                }
            }

            for (NestedAlbumFolder folder : plan.nestedAlbumsToProcess()) {
                if (progress.isCancelled()) {
                    return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                            missing, skipped, messages);
                }
                ArtistScanContext artist = nestedArtistContexts.get(folder.artistFolder());
                if (artist == null) {
                    skipped++;
                    processedFolders++;
                    report.skipped("Unmatched nested album folder: " + folder.relativePath());
                    report.scannedDirs(processedFolders);
                    progress.itemProcessed(collection.id(), processedFolders);
                    continue;
                }
                Optional<ParsedAlbum> parsedAlbum = parser.parseNestedArtistAlbum(
                        folder.artistFolder(), folder.albumFolder(), collection.id());
                if (parsedAlbum.isEmpty()) {
                    skipped++;
                    processedFolders++;
                    report.skipped("Unmatched nested album folder: " + folder.relativePath());
                    report.scannedDirs(processedFolders);
                    progress.itemProcessed(collection.id(), processedFolders);
                    continue;
                }
                UpsertResult albumResult = albumRepository.upsertScanned(
                        artist.id(),
                        parsedAlbum.get().title(),
                        parsedAlbum.get().releaseDate(),
                        folder.relativePath(),
                        collection.id());
                parsed++;
                processedFolders++;
                if (albumResult.created()) {
                    created++;
                    report.created(albumReportRow(folder.relativePath(), artist.name(),
                            parsedAlbum.get().title(), parsedAlbum.get().releaseDate()));
                } else {
                    existing++;
                    report.existing(albumReportRow(folder.relativePath(), artist.name(),
                            parsedAlbum.get().title(), parsedAlbum.get().releaseDate()));
                }
                report.scannedDirs(processedFolders);
                progress.itemProcessed(collection.id(), processedFolders);
            }

            if (progress.isCancelled()) {
                return cancelledScanSummary(collection, report, seenArtistIds, parsed, created, existing,
                        missing, skipped, messages);
            }

            missing = albumRepository.removeUnseenLocalPaths(collection.id(), plan.diskPaths());
            artistRepository.replaceLocalArtistsForCollection(collection.id(), seenArtistIds);
            artistRepository.clearLocalScanErrorsForCollection(collection.id());

            String message = "Scanned " + collection.name() + ": "
                    + countWithLabel(seenArtistIds.size(), "artist", "artists")
                    + ", " + countWithLabel(parsed, "album", "albums") + " parsed, " + created
                    + " created, " + existing + " existing, " + plan.unchangedCount() + " unchanged, "
                    + missing + " local paths removed, " + skipped + " skipped.";
            messages.add(message);
            report.artistCount(seenArtistIds.size());
            report.finish("DONE", parsed, created, existing, missing, skipped, message);
            collectionRepository.markScanned(collection.id(), "DONE", message);
            LOG.info(message);
            return scanSummary(collection.id(), "DONE", seenArtistIds.size(), parsed, created, existing,
                    missing, skipped, messages, report);
        } catch (Exception e) {
            String message = "Scan failed for " + collection.name() + ": " + e.getMessage();
            messages.add(message);
            if (report != null) {
                report.warning(message);
                report.artistCount(seenArtistIds.size());
                report.finish("FAILED", parsed, created, existing, missing, skipped, message);
            }
            writeReport(report);
            collectionRepository.markScanned(collection.id(), "FAILED", message);
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private ScanSummary cancelledScanSummary(MusicCollection collection, ScanReport report, Set<Long> seenArtistIds,
            int parsed, int created, int existing, int missing, int skipped, List<String> messages) {
        String message = "Scan cancelled for " + collection.name() + ".";
        messages.add(message);
        report.artistCount(seenArtistIds.size());
        report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
        collectionRepository.markScanned(collection.id(), "SKIPPED", message);
        return scanSummary(collection.id(), "SKIPPED", seenArtistIds.size(), parsed, created, existing,
                missing, skipped, messages, report);
    }

    private ArtistDeltaScanPlan artistDeltaScanPlan(List<Path> folders, MusicCollection collection,
            Map<String, LocalPathSnapshot> knownPaths) throws Exception {
        if (collection.parser() == ParserType.NESTED_ARTIST_ALBUM) {
            return nestedArtistDeltaScanPlan(folders, knownPaths);
        }

        Set<String> diskPaths = directRelativePaths(folders);
        Set<Long> unchangedArtistIds = new HashSet<>();
        int unchanged = addKnownPathArtists(knownPaths, diskPaths, unchangedArtistIds);
        List<FlatArtistFolder> flatFolders = directFoldersToProcess(folders, knownPaths).stream()
                .map(folder -> new FlatArtistFolder(folder, folder.getFileName().toString()))
                .toList();
        return new ArtistDeltaScanPlan(
                diskPaths,
                unchangedArtistIds,
                unchanged,
                flatFolders.size(),
                flatFolders,
                List.of(),
                List.of());
    }

    private ArtistDeltaScanPlan nestedArtistDeltaScanPlan(List<Path> folders,
            Map<String, LocalPathSnapshot> knownPaths) throws Exception {
        Set<String> diskPaths = new HashSet<>();
        List<NestedArtistDiskFolder> nestedFolders = new ArrayList<>();
        List<NestedAlbumFolder> albumsToProcess = new ArrayList<>();
        for (Path artistFolder : folders) {
            List<Path> albumFolders = directChildDirectories(artistFolder);
            boolean artistFolderHasAlbums = !albumFolders.isEmpty();
            boolean isArtistFolder = artistFolderHasAlbums || !hasDirectRegularFile(artistFolder);
            String artistName = isArtistFolder ? Names.chicagoStyle(cleanFolderName(artistFolder)) : null;
            nestedFolders.add(new NestedArtistDiskFolder(artistFolder, artistName, isArtistFolder, albumFolders));
            if (!isArtistFolder) {
                continue;
            }
            for (Path albumFolder : albumFolders) {
                String relativePath = nestedRelativePath(artistFolder, albumFolder);
                diskPaths.add(relativePath);
                if (!knownPaths.containsKey(relativePath)) {
                    albumsToProcess.add(new NestedAlbumFolder(artistFolder, albumFolder, relativePath));
                }
            }
        }

        Set<Long> unchangedArtistIds = new HashSet<>();
        int unchanged = addKnownPathArtists(knownPaths, diskPaths, unchangedArtistIds);
        return new ArtistDeltaScanPlan(
                diskPaths,
                unchangedArtistIds,
                unchanged,
                albumsToProcess.size(),
                List.of(),
                nestedFolders,
                albumsToProcess);
    }

    private static Set<String> directRelativePaths(List<Path> folders) {
        Set<String> paths = new HashSet<>();
        for (Path folder : folders) {
            paths.add(folder.getFileName().toString());
        }
        return paths;
    }

    private static List<Path> directFoldersToProcess(List<Path> folders, Map<String, LocalPathSnapshot> knownPaths) {
        return folders.stream()
                .filter(folder -> !knownPaths.containsKey(folder.getFileName().toString()))
                .toList();
    }

    private static int addKnownPathArtists(Map<String, LocalPathSnapshot> knownPaths, Set<String> diskPaths,
            Set<Long> seenArtistIds) {
        int unchanged = 0;
        for (String diskPath : diskPaths) {
            LocalPathSnapshot knownPath = knownPaths.get(diskPath);
            if (knownPath == null) {
                continue;
            }
            unchanged++;
            seenArtistIds.addAll(knownPath.artistIds());
        }
        return unchanged;
    }

    private static String snapshotNote(int dbPathCount, int diskPathCount, int processCount, int unchangedCount) {
        return "Compared DB and disk snapshots: " + dbPathCount + " DB paths, " + diskPathCount
                + " disk paths, " + processCount + " paths to process, " + unchangedCount + " unchanged.";
    }

    private List<Long> upsertContributorArtists(String artistName) {
        List<Long> artistIds = new ArrayList<>();
        for (String name : ArtistNames.splitList(artistName)) {
            UpsertResult artistResult = artistRepository.upsertByName(name);
            artistIds.add(artistResult.id());
        }
        return artistIds;
    }

    private static List<Path> directChildDirectories(Path folder) throws Exception {
        try (var stream = Files.list(folder)) {
            return stream.filter(Files::isDirectory)
                    .sorted(ScanService::comparePathFileName)
                    .toList();
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

    private ScanSummary scanSummary(String collectionId, String status, int artistCount, int parsed, int created,
            int updated, int missing, int skipped, List<String> messages, ScanReport report) {
        ReportArtifact artifact = writeReport(report);
        List<ReportArtifact> reports = artifact == null ? List.of() : List.of(artifact);
        return new ScanSummary(collectionId, status, artistCount, parsed, created, updated, missing, skipped, messages,
                reports);
    }

    private ReportArtifact writeReport(ScanReport report) {
        if (report == null) {
            return null;
        }
        try {
            return scanReportWriter.write(report);
        } catch (Exception e) {
            LOG.warnf("Unable to write scan report for %s %s: %s", report.collection().id(), report.kind(),
                    e.getMessage());
            return null;
        }
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

    private static String countWithLabel(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static int comparePathFileName(Path left, Path right) {
        String leftName = left.getFileName().toString();
        String rightName = right.getFileName().toString();
        int result = leftName.compareToIgnoreCase(rightName);
        return result != 0 ? result : leftName.compareTo(rightName);
    }

    private record ArtistDeltaScanPlan(
            Set<String> diskPaths,
            Set<Long> unchangedArtistIds,
            int unchangedCount,
            int itemTotal,
            List<FlatArtistFolder> flatFolders,
            List<NestedArtistDiskFolder> nestedFolders,
            List<NestedAlbumFolder> nestedAlbumsToProcess) {
    }

    private record FlatArtistFolder(
            Path folder,
            String relativePath) {
    }

    private record NestedArtistDiskFolder(
            Path folder,
            String artistName,
            boolean artistFolder,
            List<Path> albumFolders) {
    }

    private record NestedAlbumFolder(
            Path artistFolder,
            Path albumFolder,
            String relativePath) {
    }

    private record ArtistScanContext(
            long id,
            String name,
            boolean created) {
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void collectionStarted(String collectionId, int itemTotal) {
        }

        default void phaseStarted(String collectionId, String message) {
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
