package org.kroky.musiclib.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;
import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.MusicRootInfo;
import org.kroky.musiclib.model.RootCandidate;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class MusicRootService {

    private static final Logger LOG = Logger.getLogger(MusicRootService.class);

    @Inject
    MusicLibraryConfig config;

    @Inject
    PathResolver pathResolver;

    @Inject
    Flyway flyway;

    private volatile Path cachedRoot;

    @PostConstruct
    void validateAtStartup() {
        Path root = requireRoot();
        LOG.infof("Using music root: %s", root);
        flyway.migrate();
    }

    public MusicRootInfo info() {
        Path selected = requireRoot();
        return new MusicRootInfo(
                selected.toString(),
                config.musicRoot().isEmpty(),
                isValidRoot(selected),
                config.rootDetection().markers(),
                candidates());
    }

    public Path requireRoot() {
        Path root = cachedRoot;
        if (root != null) {
            return root;
        }
        synchronized (this) {
            if (cachedRoot == null) {
                cachedRoot = resolveRoot();
            }
            return cachedRoot;
        }
    }

    public Path resolveCollection(String collectionRelativePath) {
        return requireRoot().resolve(collectionRelativePath);
    }

    public Path resolveAlbum(String collectionRelativePath, String albumRelativePath) {
        if (albumRelativePath == null || albumRelativePath.isBlank()) {
            return null;
        }
        return resolveCollection(collectionRelativePath).resolve(albumRelativePath);
    }

    public List<Path> listDirectChildDirectories() {
        try (var stream = Files.list(requireRoot())) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list music root folders", e);
        }
    }

    private Path resolveRoot() {
        Optional<String> configured = config.musicRoot();
        if (configured.isPresent()) {
            String rawRoot = configured.get();
            if (rawRoot == null || rawRoot.isBlank()) {
                throw new IllegalStateException("""
                        The supplied music-library.music-root JVM property is blank.
                        Start the app with a valid per-machine JVM property:
                          -Dmusic-library.music-root="E:/Google Drive/Music/_vyber"
                        """);
            }
            Path resolved = pathResolver.resolve(rawRoot.trim());
            if (isValidRoot(resolved)) {
                return resolved;
            }
            throw new IllegalStateException("""
                    The supplied music-library.music-root JVM property is invalid.
                    Supplied: %s
                    Resolved: %s
                    It must exist, be a non-empty directory, and contain these marker playlists directly in the root: %s
                    """.formatted(rawRoot, resolved, String.join(", ", config.rootDetection().markers())));
        }

        return candidates().stream()
                .filter(RootCandidate::markersFound)
                .map(candidate -> Path.of(candidate.resolvedPath()))
                .findFirst()
                .orElseThrow(this::autodetectionFailure);
    }

    private IllegalStateException autodetectionFailure() {
        String checkedPaths = config.rootDetection().candidates().stream()
                .map(candidate -> {
                    Path resolved = pathResolver.resolve(candidate);
                    return "  " + candidate + " -> " + resolved;
                })
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("  <no candidates configured>");
        return new IllegalStateException("""
                Music root was not configured and autodetection failed.
                Checked:
                %s
                The music-library.music-root JVM property MUST be supplied because those expected locations are invalid.
                A valid root must exist, be a non-empty directory, and contain these marker playlists directly in the root: %s
                Start the app with:
                  -Dmusic-library.music-root="E:/Google Drive/Music/_vyber"
                """.formatted(checkedPaths, String.join(", ", config.rootDetection().markers())));
    }

    private List<RootCandidate> candidates() {
        return config.rootDetection().candidates().stream()
                .map(this::candidate)
                .toList();
    }

    private RootCandidate candidate(String configuredPath) {
        Path resolved = pathResolver.resolve(configuredPath);
        return new RootCandidate(
                configuredPath,
                resolved.toString(),
                Files.isDirectory(resolved),
                isValidRoot(resolved));
    }

    private boolean isValidRoot(Path root) {
        return Files.isDirectory(root) && isNotEmpty(root) && hasAllMarkers(root);
    }

    private boolean isNotEmpty(Path root) {
        try (var stream = Files.list(root)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasAllMarkers(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        return config.rootDetection().markers().stream()
                .allMatch(marker -> Files.isRegularFile(root.resolve(marker)));
    }
}
