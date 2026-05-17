package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.AlbumStatus;
import org.kroky.musiclib.model.MusicSource;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.ScanRunRepository;
import org.kroky.musiclib.repository.SourceRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);
    private static final int MAX_SKIPPED_EXAMPLES = 25;

    @Inject
    SourceRepository sourceRepository;

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

    @Inject
    MusicLibraryConfig config;

    public List<ScanSummary> scanAllEnabled() {
        LOG.info("Starting scan for all enabled sources");
        return sourceRepository.list().stream()
                .filter(MusicSource::enabled)
                .map(this::scan)
                .toList();
    }

    public ScanSummary scan(String sourceId) {
        MusicSource source = sourceRepository.find(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + sourceId));
        return scan(source);
    }

    public ScanSummary scan(MusicSource source) {
        long runId = scanRunRepository.start(source.id());
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int skippedExamples = 0;

        try {
            Path sourceRoot = musicRootService.resolveSource(source.relativePath());
            LOG.infof("Scanning source %s at %s using %s", source.id(), sourceRoot, source.parser());
            scanRunRepository.event(runId, "INFO", "Scanning " + source.name() + " at " + sourceRoot);
            if (!Files.isDirectory(sourceRoot)) {
                String message = "Source directory does not exist: " + sourceRoot;
                messages.add(message);
                scanRunRepository.event(runId, "WARN", message);
                scanRunRepository.finish(runId, "SKIPPED", parsed, created, updated, skipped + 1, message);
                sourceRepository.markScanned(source.id(), "SKIPPED", message);
                return new ScanSummary(runId, source.id(), "SKIPPED", parsed, created, updated, skipped + 1, messages);
            }

            AlbumStatus defaultStatus = AlbumStatus.valueOf(config.scan().defaultStatus());
            try (var stream = Files.walk(sourceRoot)) {
                for (Path folder : stream.filter(Files::isDirectory).filter(path -> !path.equals(sourceRoot)).toList()) {
                    var parsedAlbum = parser.parse(folder, source.parser(), source.id());
                    if (parsedAlbum.isEmpty()) {
                        skipped++;
                        LOG.debugf("Skipped unmatched folder %s for source %s", folder, source.id());
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
                            sourceRoot.relativize(folder),
                            parsedAlbum.get().sourceId());
                    LOG.tracef("Parsed album folder %s as artist='%s', title='%s', year=%s",
                            folder, album.artistName(), album.title(), album.releaseYear());
                    UpsertResult result = upsert(album, defaultStatus);
                    if (result.created()) {
                        created++;
                    } else {
                        updated++;
                    }
                }
            }

            String message = "Scanned " + source.name() + ": " + parsed + " parsed, " + created
                    + " created, " + updated + " updated, " + skipped + " skipped.";
            messages.add(message);
            scanRunRepository.event(runId, "INFO", message);
            if (skipped > skippedExamples) {
                scanRunRepository.event(runId, "INFO",
                        "Skipped " + (skipped - skippedExamples) + " more unmatched folders.");
            }
            scanRunRepository.finish(runId, "DONE", parsed, created, updated, skipped, message);
            sourceRepository.markScanned(source.id(), "DONE", message);
            LOG.info(message);
            return new ScanSummary(runId, source.id(), "DONE", parsed, created, updated, skipped, messages);
        } catch (Exception e) {
            String message = "Scan failed for " + source.name() + ": " + e.getMessage();
            messages.add(message);
            scanRunRepository.event(runId, "ERROR", message);
            scanRunRepository.finish(runId, "FAILED", parsed, created, updated, skipped, message);
            sourceRepository.markScanned(source.id(), "FAILED", message);
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private UpsertResult upsert(ParsedAlbum parsedAlbum, AlbumStatus defaultStatus) {
        UpsertResult artist = artistRepository.upsertByName(parsedAlbum.artistName());
        return albumRepository.upsertScanned(
                artist.id(),
                parsedAlbum.title(),
                parsedAlbum.releaseYear(),
                defaultStatus,
                parsedAlbum.relativePath().toString(),
                parsedAlbum.sourceId());
    }
}
