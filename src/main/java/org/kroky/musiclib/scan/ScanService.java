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
import org.kroky.musiclib.model.ReportArtifact;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
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
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int updated = 0;
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
                        updated, missing, skipped + 1, messages, report);
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
                        report.artistCount(seenArtistIds.size());
                        report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                        collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                        return scanSummary(collection.id(), "SKIPPED", seenArtistIds.size(), parsed,
                                created, updated, missing, skipped, messages, report);
                    }

                    var parsedTitle = parser.parseTitleAlbum(folder, collection.id());
                    seenPaths.add(folder.getFileName().toString());
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

                missing = albumRepository.removeUnseenLocalPaths(collection.id(), seenPaths);
                String message = "Scanned " + collection.name() + ": " + countWithLabel(parsed, "title", "titles")
                        + ", " + created
                        + " created, " + existing + " existing, " + missing + " local paths removed.";
                messages.add(message);
                report.artistCount(seenArtistIds.size());
                report.finish("DONE", parsed, created, existing, missing, skipped, message);
                collectionRepository.markScanned(collection.id(), "DONE", message);
                LOG.info(message);
                return scanSummary(collection.id(), "DONE", seenArtistIds.size(), parsed, created,
                        updated, missing, skipped, messages, report);
            }

            Set<String> seenPaths = new HashSet<>();
            for (Path folder : folders) {
                if (progress.isCancelled()) {
                    String message = "Scan cancelled for " + collection.name() + ".";
                    messages.add(message);
                    report.artistCount(seenArtistIds.size());
                    report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                    collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                    return scanSummary(collection.id(), "SKIPPED", seenArtistIds.size(), parsed, created,
                            updated, missing, skipped, messages, report);
                }
                Optional<ParsedAlbum> parsedAlbum = parser.parse(folder, collection.parser(), collection.id());
                if (parsedAlbum.isPresent()) {
                    String parsedArtistName = parsedAlbum.get().artistName();
                    UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                    progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                    artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                    seenArtistIds.add(artistResult.id());
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
                    } else {
                        String parsedArtistName = nestedArtist.get().artistName();
                        UpsertResult artistResult = artistRepository.upsertByName(parsedArtistName);
                        progress.artistStarted(collection.id(), artistResult.id(), parsedArtistName);
                        artistRepository.assignToCollection(artistResult.id(), collection.id(), true);
                        seenArtistIds.add(artistResult.id());
                        for (Path unmatchedAlbumFolder : nestedArtist.get().unmatchedAlbumFolders()) {
                            skipped++;
                            String relativePath = nestedRelativePath(folder, unmatchedAlbumFolder);
                            report.skipped("Unmatched nested album folder: " + relativePath);
                        }
                        if (nestedArtist.get().albums().isEmpty()) {
                            report.note((artistResult.created() ? "Created" : "Existing")
                                    + " artist without parsed albums: " + parsedArtistName
                                    + " | folder: " + folder.getFileName());
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
                report.artistCount(seenArtistIds.size());
                report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return scanSummary(collection.id(), "SKIPPED", seenArtistIds.size(), parsed, created,
                        updated, missing, skipped, messages, report);
            }

            missing = albumRepository.removeUnseenLocalPaths(collection.id(), seenPaths);
            artistRepository.replaceLocalArtistsForCollection(collection.id(), seenArtistIds);
            artistRepository.clearLocalScanErrorsForCollection(collection.id());

            String message = "Scanned " + collection.name() + ": "
                    + countWithLabel(seenArtistIds.size(), "artist", "artists")
                    + ", " + countWithLabel(parsed, "album", "albums") + " parsed, " + created
                    + " created, " + existing + " existing, " + missing + " local paths removed, " + skipped
                    + " skipped.";
            messages.add(message);
            report.artistCount(seenArtistIds.size());
            report.finish("DONE", parsed, created, existing, missing, skipped, message);
            collectionRepository.markScanned(collection.id(), "DONE", message);
            LOG.info(message);
            return scanSummary(collection.id(), "DONE", seenArtistIds.size(), parsed, created, updated,
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

    public ScanSummary scanLocalAlbums(String collectionId, Long artistId, ProgressListener progress) {
        MusicCollection collection = collectionRepository.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        return scanLocalAlbums(collection, artistId, progress);
    }

    public ScanSummary scanLocalAlbums(MusicCollection collection, Long artistId, ProgressListener progress) {
        if (collection.type() != CollectionType.ARTIST) {
            throw new IllegalArgumentException("Local album scan is only available for artist collections");
        }

        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int existing = 0;
        int missing = 0;
        int skipped = 0;
        Set<Long> seenLocalArtistIds = new HashSet<>();
        ScanReport report = null;

        try {
            Path collectionRoot = musicRootService.resolveCollection(collection.relativePath());
            report = new ScanReport(artistId == null ? "LOCAL_ALBUMS" : "LOCAL_ALBUMS_ARTIST", collection,
                    collectionRoot);
            LOG.infof("Scanning local albums collection=%s artistId=%s at %s using %s",
                    collection.id(), artistId, collectionRoot, collection.parser());
            if (!Files.isDirectory(collectionRoot)) {
                String message = "Collection directory does not exist: " + collectionRoot;
                messages.add(message);
                report.warning(message);
                report.finish("SKIPPED", parsed, created, existing, missing, skipped + 1, message);
                return scanSummary(collection.id(), "SKIPPED", seenLocalArtistIds.size(), parsed, created,
                        existing, missing, skipped + 1, messages, report);
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
            int processedFolders = 0;
            for (Path folder : folders) {
                if (progress.isCancelled()) {
                    String message = "Local album scan cancelled for " + collection.name() + ".";
                    messages.add(message);
                    report.artistCount(seenLocalArtistIds.size());
                    report.finish("SKIPPED", parsed, created, existing, missing, skipped, message);
                    return scanSummary(collection.id(), "SKIPPED", seenLocalArtistIds.size(), parsed,
                            created, existing, missing, skipped, messages, report);
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
                            }
                            if (nestedArtist.get().albums().isEmpty()) {
                                report.note((artistResult.created() ? "Created" : "Existing")
                                        + " artist without parsed albums: " + parsedArtistName
                                        + " | folder: " + folder.getFileName());
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
            String message = "Local album scan complete for " + scope + ": "
                    + countWithLabel(seenLocalArtistIds.size(), "artist", "artists")
                    + ", " + countWithLabel(parsed, "album", "albums") + ", " + created
                    + " new, " + existing + " existing, " + missing + " local paths removed, " + skipped
                    + " skipped.";
            messages.add(message);
            report.artistCount(seenLocalArtistIds.size());
            report.finish("DONE", parsed, created, existing, missing, skipped, message);
            LOG.info(message);
            return scanSummary(collection.id(), "DONE", seenLocalArtistIds.size(), parsed, created,
                    existing, missing, skipped, messages, report);
        } catch (Exception e) {
            String message = "Local album scan failed for " + collection.name() + ": " + e.getMessage();
            if (artistId != null) {
                artistRepository.markLocalScanError(artistId, collection.id(), e.getMessage());
            }
            messages.add(message);
            if (report != null) {
                report.warning(message);
                report.artistCount(seenLocalArtistIds.size());
                report.finish("FAILED", parsed, created, existing, missing, skipped, message);
            }
            writeReport(report);
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
