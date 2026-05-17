package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.MusicRootInfo;
import org.kroky.musiclib.model.RootCandidate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MusicRootService {

    private static final Logger LOG = Logger.getLogger(MusicRootService.class);

    @Inject
    MusicLibraryConfig config;

    @Inject
    PathResolver pathResolver;

    private volatile Path userSelectedRoot;

    public MusicRootInfo info() {
        Optional<Path> selected = selectedRoot();
        return new MusicRootInfo(
                selected.map(Path::toString).orElse(null),
                config.musicRoot().isEmpty() && userSelectedRoot == null && selected.isPresent(),
                selected.map(this::hasAllMarkers).orElse(false),
                config.rootDetection().markers(),
                candidates());
    }

    public Optional<Path> selectedRoot() {
        if (userSelectedRoot != null) {
            return Optional.of(userSelectedRoot);
        }
        Optional<Path> configured = config.musicRoot()
                .map(pathResolver::resolve)
                .filter(this::hasAllMarkers);
        if (configured.isPresent()) {
            return configured;
        }
        return candidates().stream()
                .filter(RootCandidate::markersFound)
                .map(candidate -> Path.of(candidate.resolvedPath()))
                .findFirst();
    }

    public Path requireRoot() {
        return selectedRoot().orElseThrow(() -> new IllegalStateException(
                "Music root was not found. Configure music-library.music-root or add a valid root candidate."));
    }

    public MusicRootInfo selectRoot(String root) {
        Path resolved = pathResolver.resolve(root);
        if (!hasAllMarkers(resolved)) {
            throw new IllegalArgumentException("Selected root is invalid or missing marker playlists: " + resolved);
        }
        LOG.infof("Runtime music root selected: %s", resolved);
        userSelectedRoot = resolved;
        return info();
    }

    public Path resolveSource(String sourceRelativePath) {
        return requireRoot().resolve(sourceRelativePath);
    }

    public Path resolveAlbum(String sourceRelativePath, String albumRelativePath) {
        if (albumRelativePath == null || albumRelativePath.isBlank()) {
            return null;
        }
        return resolveSource(sourceRelativePath).resolve(albumRelativePath);
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
                hasAllMarkers(resolved));
    }

    private boolean hasAllMarkers(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        return config.rootDetection().markers().stream()
                .allMatch(marker -> Files.isRegularFile(root.resolve(marker)));
    }
}
