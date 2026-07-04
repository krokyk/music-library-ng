package org.kroky.musiclib.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumLocalPath;
import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.AlbumReleaseDateConflictFolderPlan;
import org.kroky.musiclib.model.AlbumReleaseDateConflictPlan;
import org.kroky.musiclib.model.AlbumReleaseDateConflictResult;
import org.kroky.musiclib.model.AudioTagFilePlan;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderConflictService {

    private static final Pattern FLAT_FOLDER =
            Pattern.compile("^(.+?) - (\\d{4}(?:-\\d{2}(?:-\\d{2})?)?) - (.+)$");
    private static final Pattern NESTED_ALBUM_FOLDER =
            Pattern.compile("^(\\d{4}(?:-\\d{2}(?:-\\d{2})?)?) - (.+)$");
    private static final Pattern UNSAFE_FOLDER_NAME = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "m4a", "mp4", "ogg", "oga", "wav", "aif", "aiff", "wma", "dsf");

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository providerLinks;

    @Inject
    MusicCollectionRepository collections;

    public AlbumReleaseDateConflictPlan planUseProviderReleaseDate(long albumId, long providerLinkId) {
        ConflictContext context = context(albumId, providerLinkId);
        return plan(context);
    }

    public AlbumReleaseDateConflictResult keepLocalReleaseDate(long albumId, long providerLinkId) {
        ConflictContext context = context(albumId, providerLinkId);
        providerLinks.resolveMatchingReleaseDateConflicts(albumId, context.providerLink().providerReleaseDate(), "KEEP_LOCAL");
        int merged = mergeProviderOnlyDuplicates(context.album(), context.providerLink());
        return new AlbumReleaseDateConflictResult(
                albums.find(albumId).orElseThrow(),
                firstSourcePath(context),
                firstSourcePath(context),
                0,
                merged,
                0,
                List.of(),
                List.of("Provider release remains linked to the local album with local release date kept."));
    }

    public AlbumReleaseDateConflictResult resetKeepLocalReleaseDate(long albumId, long providerLinkId) {
        ConflictContext context = context(albumId, providerLinkId);
        providerLinks.resetMatchingKeepLocalReleaseDateConflicts(
                albumId,
                context.providerLink().providerReleaseDate());
        return new AlbumReleaseDateConflictResult(
                albums.find(albumId).orElseThrow(),
                firstSourcePath(context),
                firstSourcePath(context),
                0,
                0,
                0,
                List.of(),
                List.of());
    }

    public AlbumReleaseDateConflictResult useProviderReleaseDate(long albumId, long providerLinkId) {
        ConflictContext context = context(albumId, providerLinkId);
        AlbumReleaseDateConflictPlan preview = plan(context);
        for (ConflictPath folder : context.folders()) {
            if (!Files.isDirectory(folder.source())) {
                throw new IllegalStateException("Source folder does not exist: " + folder.source());
            }
            if (Files.exists(folder.target())) {
                throw new IllegalStateException("Target folder already exists: " + folder.target());
            }
            if (!safeDirectFolderName(folder.targetFolderName())) {
                throw new IllegalStateException("Target folder name contains characters that are not safe on Windows: "
                        + folder.targetRelativePath());
            }
        }

        List<MovedFolder> movedFolders = new ArrayList<>();
        for (ConflictPath folder : context.folders()) {
            try {
                moveFolder(folder.source(), folder.target());
                movedFolders.add(new MovedFolder(folder.source(), folder.target()));
            } catch (Exception e) {
                rollbackMoves(movedFolders);
                throw new IllegalStateException("Unable to rename album folder from " + folder.source()
                        + " to " + folder.target(), e);
            }
        }

        for (ConflictPath folder : context.folders()) {
            albums.updateLocalPath(albumId, folder.localPath().id(), folder.targetRelativePath());
        }
        Album updated = albums.update(albumId, context.providerLink().providerTitle(),
                context.providerLink().providerReleaseDate(), true, context.album().notes()).orElseThrow();
        providerLinks.resolveMatchingReleaseDateConflicts(albumId, context.providerLink().providerReleaseDate(), "USE_PROVIDER");
        int merged = mergeProviderOnlyDuplicates(updated, context.providerLink());
        List<AudioTagFilePlan> tagResults = new ArrayList<>();
        for (ConflictPath folder : context.folders()) {
            updateAudioYears(folder.target(), providerYear(context.providerLink())).stream()
                    .map(file -> folderFilePlan(folder, file))
                    .forEach(tagResults::add);
        }
        int updatedTags = (int) tagResults.stream().filter(file -> "UPDATED".equals(file.status())).count();
        List<String> warnings = new ArrayList<>(preview.warnings());
        tagResults.stream()
                .filter(file -> "FAILED".equals(file.status()))
                .forEach(file -> warnings.add(file.relativePath() + ": " + file.message()));

        return new AlbumReleaseDateConflictResult(
                albums.find(albumId).orElseThrow(),
                preview.sourcePath(),
                preview.targetPath(),
                context.folders().size(),
                merged,
                updatedTags,
                tagResults,
                warnings);
    }

    private AlbumReleaseDateConflictPlan plan(ConflictContext context) {
        List<String> warnings = new ArrayList<>();
        List<AlbumReleaseDateConflictFolderPlan> folders = context.folders().stream()
                .map(folder -> folderPlan(folder, providerYear(context.providerLink()), warnings))
                .toList();
        int stalePathCount = context.album().localPaths().size() - context.folders().size();
        if (stalePathCount > 0) {
            warnings.add("Ignoring " + stalePathCount + " stale local path"
                    + (stalePathCount == 1 ? "" : "s") + " that are not on disk.");
        }
        if (folders.size() > 1) {
            warnings.add("This album has " + folders.size() + " on-disk local folders; all will be renamed.");
        }
        List<AudioTagFilePlan> files = folders.stream()
                .flatMap(folder -> folder.files().stream()
                        .map(file -> new AudioTagFilePlan(
                                folder.collectionName() + "/" + file.relativePath(),
                                file.status(),
                                file.message())))
                .toList();
        int supported = (int) files.stream().filter(file -> "SUPPORTED".equals(file.status())).count();
        int unsupported = (int) files.stream().filter(file -> "UNSUPPORTED".equals(file.status())).count();
        String sourcePath = folders.size() == 1 ? folders.get(0).sourcePath() : folders.size() + " local folders";
        String targetPath = folders.size() == 1 ? folders.get(0).targetPath() : folders.size() + " local folders";
        String sourceRelativePath = folders.size() == 1
                ? folders.get(0).sourceRelativePath()
                : folders.size() + " local folders";
        String targetRelativePath = folders.size() == 1
                ? folders.get(0).targetRelativePath()
                : folders.size() + " renamed local folders";
        return new AlbumReleaseDateConflictPlan(
                context.album().id(),
                context.providerLink().id(),
                context.album().title(),
                context.album().releaseDate(),
                context.providerLink().providerTitle(),
                context.providerLink().providerReleaseDate(),
                sourcePath,
                targetPath,
                sourceRelativePath,
                targetRelativePath,
                folders.size(),
                supported,
                unsupported,
                folders,
                files,
                warnings);
    }

    private AlbumReleaseDateConflictFolderPlan folderPlan(ConflictPath folder, String providerYear,
            List<String> warnings) {
        if (!Files.isDirectory(folder.source())) {
            warnings.add("Source folder does not exist: " + folder.source());
        }
        if (Files.exists(folder.target())) {
            warnings.add("Target folder already exists: " + folder.target());
        }
        if (!safeDirectFolderName(folder.targetFolderName())) {
            warnings.add("Target folder name contains characters that are not safe on Windows: "
                    + folder.targetRelativePath());
        }
        List<AudioTagFilePlan> files = audioFiles(folder.source(), providerYear);
        int supported = (int) files.stream().filter(file -> "SUPPORTED".equals(file.status())).count();
        int unsupported = (int) files.stream().filter(file -> "UNSUPPORTED".equals(file.status())).count();
        return new AlbumReleaseDateConflictFolderPlan(
                folder.localPath().id(),
                folder.localPath().collectionId(),
                folder.localPath().collectionName(),
                folder.source().toString(),
                folder.target().toString(),
                folder.localPath().relativePath(),
                folder.targetRelativePath(),
                supported,
                unsupported,
                files);
    }

    private ConflictContext context(long albumId, long providerLinkId) {
        Album album = albums.find(albumId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown album: " + albumId));
        AlbumProviderLink providerLink = providerLinks.find(providerLinkId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider link: " + providerLinkId));
        if (providerLink.albumId() != album.id()) {
            throw new IllegalArgumentException("Provider link " + providerLinkId + " does not belong to album " + albumId);
        }
        if (!releaseDateConflict(album.releaseDate(), providerLink.providerReleaseDate())) {
            throw new IllegalArgumentException("Album does not have a provider release date conflict.");
        }
        List<ConflictPath> folders = album.localPaths().stream()
                .filter(AlbumLocalPath::onDisk)
                .map(localPath -> conflictPath(localPath, providerLink))
                .toList();
        if (folders.isEmpty()) {
            throw new IllegalArgumentException("Release date conflict actions require at least one local album folder on disk.");
        }
        return new ConflictContext(album, providerLink, folders);
    }

    private ConflictPath conflictPath(AlbumLocalPath localPath, AlbumProviderLink providerLink) {
        var collection = collections.find(localPath.collectionId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + localPath.collectionId()));
        if (collection.parser() == ParserType.FLAT_ARTIST_YEAR_ALBUM) {
            return flatConflictPath(localPath, providerLink);
        }
        if (collection.parser() == ParserType.NESTED_ARTIST_ALBUM) {
            return nestedConflictPath(localPath, providerLink);
        }
        throw new IllegalArgumentException(
                "Folder rename is only supported for flat and nested artist-album collections.");
    }

    private ConflictPath flatConflictPath(AlbumLocalPath localPath, AlbumProviderLink providerLink) {
        if (localPath.relativePath().contains("/") || localPath.relativePath().contains("\\")) {
            throw new IllegalArgumentException("Folder rename is only supported for direct album folders.");
        }
        Matcher matcher = FLAT_FOLDER.matcher(localPath.relativePath());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Local folder does not match artist - release date - album layout.");
        }
        String targetRelativePath = matcher.group(1) + " - " + providerDateSegment(providerLink) + " - "
                + providerLink.providerTitle();
        Path source = Path.of(localPath.resolvedPath());
        return new ConflictPath(localPath, targetRelativePath, targetRelativePath, source,
                source.resolveSibling(targetRelativePath));
    }

    private ConflictPath nestedConflictPath(AlbumLocalPath localPath, AlbumProviderLink providerLink) {
        String normalizedPath = localPath.relativePath().replace('\\', '/');
        int separator = normalizedPath.indexOf('/');
        if (separator <= 0 || separator != normalizedPath.lastIndexOf('/') || separator >= normalizedPath.length() - 1) {
            throw new IllegalArgumentException("Nested folder rename is only supported for artist/album folders.");
        }
        String artistFolder = normalizedPath.substring(0, separator);
        String albumFolder = normalizedPath.substring(separator + 1);
        Matcher matcher = NESTED_ALBUM_FOLDER.matcher(albumFolder);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Local folder does not match artist/release date - album layout.");
        }
        String targetAlbumFolder = providerDateSegment(providerLink) + " - " + providerLink.providerTitle();
        String targetRelativePath = artistFolder + "/" + targetAlbumFolder;
        Path source = Path.of(localPath.resolvedPath());
        return new ConflictPath(localPath, targetRelativePath, targetAlbumFolder, source,
                source.resolveSibling(targetAlbumFolder));
    }

    private int mergeProviderOnlyDuplicates(Album album, AlbumProviderLink providerLink) {
        int merged = 0;
        for (long artistId : album.artistIds()) {
            merged += albums.mergeProviderOnlyDuplicates(
                    album.id(),
                    artistId,
                    providerLink.providerTitle(),
                    providerLink.providerReleaseDate());
        }
        return merged;
    }

    private static List<AudioTagFilePlan> audioFiles(Path source, String year) {
        if (!Files.isDirectory(source)) {
            return List.of();
        }
        try (var stream = Files.walk(source)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> source.relativize(path).toString()))
                    .map(path -> audioFilePlan(source, path, year))
                    .filter(plan -> !"IGNORED".equals(plan.status()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list audio files under " + source, e);
        }
    }

    private static AudioTagFilePlan audioFilePlan(Path source, Path file, String year) {
        String relativePath = source.relativize(file).toString();
        String extension = extension(file);
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            return new AudioTagFilePlan(relativePath, "IGNORED", "Not an audio file handled by this workflow.");
        }
        if (year == null) {
            return new AudioTagFilePlan(relativePath, "UNSUPPORTED", "Provider release date has no year.");
        }
        return new AudioTagFilePlan(relativePath, "SUPPORTED", "Year will be set to " + year + ".");
    }

    private static List<AudioTagFilePlan> updateAudioYears(Path source, String year) {
        return audioFiles(source, year).stream()
                .map(file -> updateAudioYear(source, file, year))
                .toList();
    }

    private static AudioTagFilePlan folderFilePlan(ConflictPath folder, AudioTagFilePlan file) {
        return new AudioTagFilePlan(
                folder.localPath().collectionName() + "/" + file.relativePath(),
                file.status(),
                file.message());
    }

    private static AudioTagFilePlan updateAudioYear(Path source, AudioTagFilePlan file, String year) {
        if (!"SUPPORTED".equals(file.status())) {
            return file;
        }
        Path path = source.resolve(file.relativePath());
        try {
            AudioFile audioFile = AudioFileIO.read(path.toFile());
            Tag tag = audioFile.getTagOrCreateAndSetDefault();
            tag.setField(FieldKey.YEAR, year);
            audioFile.commit();
            return new AudioTagFilePlan(file.relativePath(), "UPDATED", "Year set to " + year + ".");
        } catch (Exception e) {
            return new AudioTagFilePlan(file.relativePath(), "FAILED", e.getMessage());
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void moveFolder(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(source, target);
        }
    }

    private static void rollbackMoves(List<MovedFolder> movedFolders) {
        for (int index = movedFolders.size() - 1; index >= 0; index--) {
            MovedFolder folder = movedFolders.get(index);
            try {
                if (Files.exists(folder.target()) && !Files.exists(folder.source())) {
                    Files.move(folder.target(), folder.source());
                }
            } catch (Exception ignored) {
                // Best-effort filesystem rollback; the original move failure remains the reported error.
            }
        }
    }

    private static String firstSourcePath(ConflictContext context) {
        return context.folders().isEmpty() ? "" : context.folders().get(0).source().toString();
    }

    private static String providerDateSegment(AlbumProviderLink providerLink) {
        String releaseDate = providerLink.providerReleaseDate();
        return releaseDate == null || releaseDate.isBlank() ? providerYear(providerLink) : releaseDate.trim();
    }

    private static boolean safeDirectFolderName(String value) {
        return value != null && !value.isBlank() && !UNSAFE_FOLDER_NAME.matcher(value).find();
    }

    private static String providerYear(AlbumProviderLink providerLink) {
        return releaseYear(providerLink.providerReleaseDate());
    }

    private static boolean releaseDateConflict(String localReleaseDate, String providerReleaseDate) {
        String localYear = releaseYear(localReleaseDate);
        String providerYear = releaseYear(providerReleaseDate);
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static String releaseYear(String releaseDate) {
        String normalized = releaseDate == null || releaseDate.isBlank() ? null : releaseDate.trim();
        if (normalized == null || normalized.length() < 4) {
            return null;
        }
        return normalized.substring(0, 4);
    }

    private record ConflictContext(
            Album album,
            AlbumProviderLink providerLink,
            List<ConflictPath> folders) {
    }

    private record ConflictPath(
            AlbumLocalPath localPath,
            String targetRelativePath,
            String targetFolderName,
            Path source,
            Path target) {
    }

    private record MovedFolder(
            Path source,
            Path target) {
    }
}
