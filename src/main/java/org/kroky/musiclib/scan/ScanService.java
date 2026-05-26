package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
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
    FolderNameParser parser;

    @Inject
    MusicRootService musicRootService;

    public List<ScanSummary> scanAllEnabled() {
        LOG.info("Starting scan for all enabled collections");
        return collectionRepository.list().stream()
                .filter(MusicCollection::enabled)
                .map(this::scan)
                .toList();
    }

    public ScanSummary scan(String collectionId) {
        MusicCollection collection = collectionRepository.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        return scan(collection);
    }

    public ScanSummary scan(MusicCollection collection) {
        long runId = scanRunRepository.start(collection.id());
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int updated = 0;
        int missing = 0;
        int skipped = 0;
        int skippedExamples = 0;
        Set<String> seenPaths = new HashSet<>();

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

            try (var stream = Files.walk(collectionRoot)) {
                for (Path folder : stream.filter(Files::isDirectory).filter(path -> !path.equals(collectionRoot)).toList()) {
                    var parsedAlbum = parser.parse(folder, collection.parser(), collection.id());
                    if (parsedAlbum.isEmpty()) {
                        skipped++;
                        LOG.debugf("Skipped unmatched folder %s for collection %s", folder, collection.id());
                        if (skippedExamples < MAX_SKIPPED_EXAMPLES) {
                            scanRunRepository.event(runId, "SKIPPED", "Unmatched folder: " + folder.getFileName());
                            skippedExamples++;
                        }
                        continue;
                    }
                    parsed++;
                    ParsedAlbum album = new ParsedAlbum(
                            parsedAlbum.get().artistName(),
                            parsedAlbum.get().title(),
                            parsedAlbum.get().releaseYear(),
                            collectionRoot.relativize(folder),
                            parsedAlbum.get().collectionId());
                    seenPaths.add(album.relativePath().toString());
                    LOG.tracef("Parsed album folder %s as artist='%s', title='%s', year=%s",
                            folder, album.artistName(), album.title(), album.releaseYear());
                    UpsertResult result = upsert(album);
                    if (result.created()) {
                        created++;
                    } else {
                        updated++;
                    }
                }
            }
            missing = albumRepository.markMissingPaths(collection.id(), seenPaths);

            String message = "Scanned " + collection.name() + ": " + parsed + " parsed, " + created
                    + " created, " + updated + " updated, " + missing + " missing, " + skipped + " skipped.";
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

    private UpsertResult upsert(ParsedAlbum parsedAlbum) {
        UpsertResult artist = artistRepository.upsertByName(parsedAlbum.artistName());
        artistRepository.assignToCollection(artist.id(), parsedAlbum.collectionId());
        return albumRepository.upsertScanned(
                artist.id(),
                parsedAlbum.title(),
                parsedAlbum.releaseYear(),
                parsedAlbum.relativePath().toString(),
                parsedAlbum.collectionId());
    }
}
