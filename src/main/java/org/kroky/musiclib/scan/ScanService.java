package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.ArtistNames;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.CollectionTitleItemRepository;
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
    CollectionTitleItemRepository titleItemRepository;

    @Inject
    ScanRunRepository scanRunRepository;

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

        try {
            Path collectionRoot = musicRootService.resolveCollection(collection.relativePath());
            LOG.infof("Scanning collection %s at %s using %s", collection.id(), collectionRoot, collection.parser());
            scanRunRepository.event(runId, "INFO", "Scanning " + collection.name() + " at " + collectionRoot);
            if (!Files.isDirectory(collectionRoot)) {
                String message = "Collection directory does not exist: " + collectionRoot;
                messages.add(message);
                scanRunRepository.event(runId, "WARN", message);
                scanRunRepository.finish(runId, "SKIPPED", parsed, created, updated, missing, skipped + 1, message);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                        skipped + 1, messages);
            }

            Set<String> seenArtistNames = new HashSet<>();
            List<Path> folders;
            try (var stream = Files.list(collectionRoot)) {
                folders = stream.filter(Files::isDirectory).toList();
            }

            progress.collectionStarted(collection.id(), folders.size());

            int processedFolders = 0;
            if (collection.type() == CollectionType.TITLE) {
                Set<String> seenPaths = new HashSet<>();
                for (Path folder : folders) {
                    if (progress.isCancelled()) {
                        String message = "Scan cancelled for " + collection.name() + ".";
                        messages.add(message);
                        scanRunRepository.event(runId, "INFO", message);
                        scanRunRepository.finish(runId, "SKIPPED", parsed, created, updated, missing, skipped, message);
                        collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                        return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                                skipped, messages);
                    }

                    var parsedTitle = parser.parseTitleItem(folder, collection.id());
                    seenPaths.add(folder.getFileName().toString());
                    parsed++;
                    UpsertResult result = titleItemRepository.upsertParsed(parsedTitle);
                    if (result.created()) {
                        created++;
                    } else {
                        existing++;
                    }
                    List<Long> artistIds = upsertArtists(parsedTitle.artistName(), collection.id());
                    if (!artistIds.isEmpty()) {
                        albumRepository.upsertScanned(
                                artistIds,
                                parsedTitle.title(),
                                parsedTitle.releaseDate(),
                                folder.getFileName().toString(),
                                collection.id());
                    }
                    processedFolders++;
                    progress.itemProcessed(collection.id(), processedFolders);
                }

                missing = titleItemRepository.markMissingPaths(collection.id(), seenPaths);
                String message = "Scanned " + collection.name() + ": " + parsed + " titles, " + created
                        + " created, " + existing + " existing, " + missing + " missing.";
                messages.add(message);
                scanRunRepository.event(runId, "INFO", message);
                scanRunRepository.finish(runId, "DONE", parsed, created, updated, missing, skipped, message);
                collectionRepository.markScanned(collection.id(), "DONE", message);
                LOG.info(message);
                return new ScanSummary(runId, collection.id(), "DONE", parsed, created, updated, missing, skipped,
                        messages);
            }

            for (Path folder : folders) {
                if (progress.isCancelled()) {
                    String message = "Scan cancelled for " + collection.name() + ".";
                    messages.add(message);
                    scanRunRepository.event(runId, "INFO", message);
                    scanRunRepository.finish(runId, "SKIPPED", parsed, created, updated, missing, skipped, message);
                    collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                    return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                            skipped, messages);
                }
                var parsedAlbum = parser.parse(folder, collection.parser(), collection.id());
                if (parsedAlbum.isEmpty()) {
                    skipped++;
                    LOG.debugf("Skipped unmatched folder %s for collection %s", folder, collection.id());
                    if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                        scanRunRepository.event(runId, "SKIPPED", "Unmatched folder: " + folder.getFileName());
                        skippedExamples++;
                    }
                } else {
                    String artistName = parsedAlbum.get().artistName();
                    String normalizedArtistName = Names.normalize(artistName);
                    if (!normalizedArtistName.isBlank() && seenArtistNames.add(normalizedArtistName)) {
                        parsed++;
                        LOG.tracef("Discovered artist '%s' in collection %s", artistName, collection.id());
                        UpsertResult result = artistRepository.upsertByName(artistName);
                        artistRepository.assignToCollection(result.id(), collection.id());
                        if (result.created()) {
                            created++;
                        } else {
                            existing++;
                        }
                    }
                }
                processedFolders++;
                progress.itemProcessed(collection.id(), processedFolders);
            }
            if (progress.isCancelled()) {
                String message = "Scan cancelled for " + collection.name() + ".";
                messages.add(message);
                scanRunRepository.event(runId, "INFO", message);
                scanRunRepository.finish(runId, "SKIPPED", parsed, created, updated, missing, skipped, message);
                collectionRepository.markScanned(collection.id(), "SKIPPED", message);
                return new ScanSummary(runId, collection.id(), "SKIPPED", parsed, created, updated, missing,
                        skipped, messages);
            }

            String message = "Scanned " + collection.name() + ": " + parsed + " artists, " + created
                    + " created, " + existing + " existing, " + skipped + " skipped.";
            messages.add(message);
            scanRunRepository.event(runId, "INFO", message);
            if (skipped > skippedExamples) {
                scanRunRepository.event(runId, "INFO",
                        "Skipped " + (skipped - skippedExamples) + " more unmatched folders.");
            }
            scanRunRepository.finish(runId, "DONE", parsed, created, updated, missing, skipped, message);
            collectionRepository.markScanned(collection.id(), "DONE", message);
            LOG.info(message);
            return new ScanSummary(runId, collection.id(), "DONE", parsed, created, updated, missing, skipped, messages);
        } catch (Exception e) {
            String message = "Scan failed for " + collection.name() + ": " + e.getMessage();
            messages.add(message);
            scanRunRepository.event(runId, "ERROR", message);
            scanRunRepository.finish(runId, "FAILED", parsed, created, updated, missing, skipped, message);
            collectionRepository.markScanned(collection.id(), "FAILED", message);
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private List<Long> upsertArtists(String artistName, String collectionId) {
        List<Long> artistIds = new ArrayList<>();
        for (String name : ArtistNames.splitList(artistName)) {
            UpsertResult artistResult = artistRepository.upsertByName(name);
            artistRepository.assignToCollection(artistResult.id(), collectionId);
            artistIds.add(artistResult.id());
        }
        return artistIds;
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void collectionStarted(String collectionId, int itemTotal) {
        }

        default void itemProcessed(String collectionId, int processedItems) {
        }

        default boolean isCancelled() {
            return false;
        }
    }
}
