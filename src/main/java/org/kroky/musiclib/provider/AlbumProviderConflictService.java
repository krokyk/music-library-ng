package org.kroky.musiclib.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderConflictService {

    private static final Logger LOG = Logger.getLogger(AlbumProviderConflictService.class);
    private static final Pattern FLAT = Pattern.compile("^(.+?) - (\\d{4}) - (.+)$");
    private static final Pattern NESTED = Pattern.compile("^(\\d{4}) - (.+)$");
    private static final Pattern UNSAFE = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern TRAILING = Pattern.compile("[ .]+$");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "flac", "m4a", "mp4", "ogg", "oga", "wav", "aif", "aiff", "wma", "dsf");

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository providerLinks;

    @Inject
    ArtistRepository artists;

    @Inject
    ArtistProviderLinkRepository artistProviderLinks;

    @Inject
    MusicCollectionRepository collections;

    public ResolutionResult resolve(ResolutionRequest request) {
        try {
            require(request != null, "Resolution request is required.");
            Kind kind = Kind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT));
            return switch (kind) {
                case COUNTRY -> resolveCountry(request, kind);
                case STATUS -> resolveStatus(request, kind);
                case TITLE, YEAR -> resolveAlbum(request, kind);
            };
        } catch (Exception e) {
            LOG.warnf(e, "Provider conflict resolution failed kind=%s action=%s artist=%s album=%s: %s",
                    request == null ? null : request.kind(), request == null ? null : request.action(),
                    request == null ? null : request.artistId(), request == null ? null : request.albumId(),
                    ProviderException.describe(e));
            if (hasCause(e, FolderRenameException.class)) {
                return error(request, "FOLDER_RENAME_FAILED", "Folder rename failed", ProviderException.describe(e));
            }
            return error(request, "RESOLUTION_FAILED", "Provider conflict was not resolved.", ProviderException.describe(e));
        }
    }

    private ResolutionResult resolveCountry(ResolutionRequest request, Kind kind) {
        Artist artist = artist(request.artistId());
        require(request.country() != null && !request.country().isBlank(), "Country is required.");
        Artist updated = artists.update(artist.id(), artist.name(), artist.sortName(), request.country(), artist.activeOverride()).orElseThrow();
        LOG.infof("Country conflict for %s resolved using local override old=%s result=%s", updated.name(), artist.countryOverride(), updated.countryOverride());
        return success(kind, null, request, updated, null);
    }

    private ResolutionResult resolveStatus(ResolutionRequest request, Kind kind) {
        Artist artist = artist(request.artistId());
        require(request.active() != null, "Status is required.");
        Artist updated = artists.update(artist.id(), artist.name(), artist.sortName(), artist.countryOverride(), request.active()).orElseThrow();
        LOG.infof("Status conflict for %s resolved using local override old=%s result=%s", updated.name(), artist.activeOverride(), updated.activeOverride() ? "Active" : "Inactive");
        return success(kind, null, request, updated, null);
    }

    private ResolutionResult resolveAlbum(ResolutionRequest request, Kind kind) {
        require(request.albumId() != null, "Album is required.");
        Action action = Action.valueOf(requireText(request.action(), "Action").toUpperCase(Locale.ROOT));
        Album before = albums.find(request.albumId()).orElseThrow(() -> new IllegalArgumentException("Unknown album: " + request.albumId()));
        require(before.artistIds().contains(request.artistId()), "Album does not belong to artist.");
        Artist conflictArtist = artist(request.artistId());
        AlbumProviderLink chosen = request.providerLinkId() == null ? null
                : providerLinks.find(request.providerLinkId()).orElseThrow(() -> new IllegalArgumentException("Unknown provider link: " + request.providerLinkId()));
        if (chosen != null) require(chosen.albumId() == before.id(), "Provider link does not belong to album.");
        if (action == Action.USE_PROVIDER || action == Action.RESET_KEEP_LOCAL) require(chosen != null, "Provider link is required.");

        if (action == Action.RESET_KEEP_LOCAL) {
            QuarkusTransaction.requiringNew().run(() -> {
                if (kind == Kind.TITLE) providerLinks.resetMatchingKeepLocalTitleConflicts(before.id(), chosen.providerTitle());
                else providerLinks.resetMatchingKeepLocalReleaseYearConflicts(before.id(), chosen.providerReleaseYear());
            });
            Album canonical = albums.find(before.id()).orElseThrow();
            logAlbumResolution(kind, action, conflictArtist, before, canonical, chosen);
            return success(kind, action, request, conflictArtist, canonical);
        }

        if (action == Action.KEEP_LOCAL) {
            QuarkusTransaction.requiringNew().run(() -> {
                if (kind == Kind.TITLE) providerLinks.resolveAlbumTitleUsingLocal(before.id(), before.title());
                else providerLinks.resolveAllReleaseYearConflictsUsingLocal(before.id(), before.releaseYear());
            });
            Album canonical = albums.find(before.id()).orElseThrow();
            logAlbumResolution(kind, action, conflictArtist, before, canonical, chosen);
            return success(kind, action, request, conflictArtist, canonical);
        }

        String targetTitle = kind == Kind.TITLE ? ProviderTitles.clean(chosen.providerTitle()) : before.title();
        Integer targetYear = kind == Kind.YEAR ? chosen.providerReleaseYear() : before.releaseYear();
        require(targetTitle != null && !targetTitle.isBlank(), "Selected title is blank.");
        if (kind == Kind.YEAR) require(targetYear != null, "Selected provider year is missing.");
        FolderMove move;
        try {
            move = folderMove(before, targetTitle, targetYear);
        } catch (Exception e) {
            throw new FolderRenameException(e);
        }

        boolean[] moved = { false };
        Album canonical;
        try {
            canonical = QuarkusTransaction.requiringNew().call(() -> {
                albums.update(before.id(), targetTitle, targetYear, before.checked(), before.notes()).orElseThrow();
                if (kind == Kind.TITLE) {
                    providerLinks.resolveAlbumTitleUsingProvider(before.id(), chosen.providerTitle());
                } else {
                    providerLinks.resolveAlbumReleaseYearUsingProvider(before.id(), chosen.providerReleaseYear());
                }
                if (move != null && !move.source().equals(move.target())) {
                    try {
                        moveFolder(move.source(), move.target());
                    } catch (Exception e) {
                        throw new FolderRenameException(e);
                    }
                    moved[0] = true;
                    albums.updateLocalRelativePath(before.id(), move.targetRelativePath());
                }
                albums.mergeProviderOnlyDuplicates(before.id(), request.artistId(), targetTitle, targetYear);
                return albums.find(before.id()).orElseThrow();
            });
        } catch (Exception e) {
            if (moved[0]) {
                try {
                    restoreFolder(move);
                } catch (Exception restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }

        List<String> tagDetails = updateTags(canonical, kind, targetTitle, targetYear);
        List<ResolutionMessage> messages = new ArrayList<>();
        if (moved[0]) {
            messages.add(new ResolutionMessage("INFO", "FOLDER_RENAMED", "Folder renamed",
                    List.of(before.localRelativePath(), canonical.localRelativePath())));
        }
        if (!tagDetails.isEmpty()) {
            String field = kind == Kind.YEAR ? "YEAR" : "ALBUM";
            tagDetails.forEach(detail -> LOG.warnf("%s tag update failed for artist=%s album=%d: %s", field, conflictArtist.name(), before.id(), detail));
            messages.add(new ResolutionMessage("WARNING", "AUDIO_TAG_WARNINGS",
                    tagDetails.size() + " " + field + " tag" + (tagDetails.size() == 1 ? "" : "s") + " failed",
                    tagDetails));
        }
        logAlbumResolution(kind, action, conflictArtist, before, canonical, chosen);
        return new ResolutionResult(kind.name(), action.name(), request.artistId(), before.id(), conflictArtist, canonical, List.copyOf(messages));
    }

    private FolderMove folderMove(Album album, String title, Integer year) {
        if (album.localRelativePath() == null) return null;
        require(album.onDisk(), "Album folder does not exist: " + album.resolvedPath());
        var collection = collections.find(album.collection().id()).orElseThrow();
        require(collection.type() == CollectionType.ARTIST, "Folder rename is not supported for title collections.");
        String relative = album.localRelativePath().replace('\\', '/');
        String targetRelative;
        if (!relative.contains("/")) {
            Matcher matcher = FLAT.matcher(relative);
            require(matcher.matches() && !relative.contains("/"), "Local folder does not match flat artist-year-album layout.");
            targetRelative = safe(matcher.group(1)) + " - " + requireYear(year) + " - " + safe(title);
        } else {
            int slash = relative.indexOf('/');
            require(slash > 0 && slash == relative.lastIndexOf('/'), "Local folder does not match nested artist-album layout.");
            Matcher matcher = NESTED.matcher(relative.substring(slash + 1));
            require(matcher.matches(), "Local folder does not match nested artist-year-album layout.");
            targetRelative = relative.substring(0, slash) + "/" + requireYear(year) + " - " + safe(title);
        }
        Path source = Path.of(album.resolvedPath());
        Path target = source.resolveSibling(targetRelative.substring(targetRelative.lastIndexOf('/') + 1));
        if (!source.equals(target) && Files.exists(target)) throw new IllegalStateException("Target folder already exists: " + target);
        return new FolderMove(source, target, targetRelative);
    }

    private List<String> updateTags(Album album, Kind kind, String title, Integer year) {
        if (album.resolvedPath() == null || !Files.isDirectory(Path.of(album.resolvedPath()))) return List.of();
        List<String> failures = new ArrayList<>();
        try (var paths = Files.walk(Path.of(album.resolvedPath()))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!AUDIO_EXTENSIONS.contains(extension(path))) continue;
                try {
                    var audio = AudioFileIO.read(path.toFile());
                    var tag = audio.getTagOrCreateAndSetDefault();
                    if (kind == Kind.YEAR) {
                        String expected = year.toString();
                        String existing = tag.getFirst(FieldKey.YEAR);
                        if (!yearTagNeedsUpdate(existing, year)) continue;
                        tag.setField(FieldKey.YEAR, expected);
                    } else {
                        if (title.equals(tag.getFirst(FieldKey.ALBUM))) continue;
                        tag.setField(FieldKey.ALBUM, title);
                    }
                    audio.commit();
                } catch (Exception e) {
                    failures.add(path + ": " + ProviderException.describe(e));
                }
            }
        } catch (Exception e) {
            failures.add(album.resolvedPath() + ": " + ProviderException.describe(e));
        }
        return failures;
    }

    public ProviderConflictSnapshot open(long artistId) {
        artist(artistId);
        albums.removeStaleLocalPathsForArtist(artistId);
        return new ProviderConflictSnapshot(
                artistProviderLinks.listCountryConflicts().stream().filter(c -> c.artistId() == artistId).toList(),
                artistProviderLinks.listStatusConflicts().stream().filter(c -> c.artistId() == artistId).toList(),
                providerLinks.listTitleConflicts().stream().filter(c -> c.artistId() == artistId).toList(),
                providerLinks.listReleaseYearConflicts().stream().filter(c -> c.artistId() == artistId).toList());
    }

    private Artist artist(long id) { return artists.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + id)); }
    private static void moveFolder(Path source, Path target) throws Exception { try { Files.move(source,target,StandardCopyOption.ATOMIC_MOVE); } catch(Exception e) { Files.move(source,target); } }
    private static void restoreFolder(FolderMove move) throws Exception {
        if (!Files.exists(move.target())) {
            throw new IllegalStateException("Cannot restore renamed folder because the target is missing: " + move.target());
        }
        if (Files.exists(move.source())) {
            throw new IllegalStateException("Cannot restore renamed folder because the original path is occupied: " + move.source());
        }
        Files.move(move.target(), move.source());
    }
    private static String extension(Path path){String n=path.getFileName().toString();int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1).toLowerCase(Locale.ROOT);}
    static boolean yearTagNeedsUpdate(String existing, Integer year){return year!=null&&(existing==null||!existing.trim().startsWith(year.toString()));}
    private static String safe(String value){String rendered=value.replace('—','-').replaceAll("[\\\\/|<>]","-").replace(":"," -").replaceAll("[?*]","").replace('"','\'').replace('“','\'').replace('”','\'').replaceAll("\\s+"," ").trim();rendered=TRAILING.matcher(rendered).replaceAll("");require(!rendered.isBlank()&&!UNSAFE.matcher(rendered).find(),"Unsafe folder name.");return rendered;}
    private static String requireYear(Integer year){require(year!=null,"A local album folder requires a release year.");return year.toString();}
    private static String requireText(String value,String label){require(value!=null&&!value.isBlank(),label+" is required.");return value.trim();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}

    private ResolutionResult success(Kind kind,Action action,ResolutionRequest request,Artist artist,Album album){return new ResolutionResult(kind.name(),action==null?null:action.name(),request.artistId(),request.albumId(),artist,album,List.of());}
    private ResolutionResult error(ResolutionRequest request,String code,String summary,String detail){return new ResolutionResult(request==null?null:request.kind(),request==null?null:request.action(),request==null?0:request.artistId(),request==null?null:request.albumId(),null,null,List.of(new ResolutionMessage("ERROR",code,summary,List.of(detail))));}
    private static boolean hasCause(Throwable error,Class<? extends Throwable> type){for(Throwable current=error;current!=null;current=current.getCause())if(type.isInstance(current))return true;return false;}
    private static void logAlbumResolution(Kind kind, Action action, Artist artist, Album before, Album result, AlbumProviderLink source) {
        Object chosen = action == Action.KEEP_LOCAL
                ? kind == Kind.TITLE ? before.title() : before.releaseYear()
                : kind == Kind.TITLE ? source.providerTitle() : source.providerReleaseYear();
        LOG.infof("%s conflict for %s album=%d resolved as %s using %s provider=%s oldTitle=%s oldYear=%s resultTitle=%s resultYear=%s folder=%s -> %s",
                kind == Kind.TITLE ? "Title" : "Year", artist.name(), before.id(), action, chosen,
                source == null ? "local" : source.providerId(), before.title(), before.releaseYear(), result.title(), result.releaseYear(),
                before.localRelativePath(), result.localRelativePath());
    }

    public enum Kind { TITLE, YEAR, COUNTRY, STATUS }
    public enum Action { USE_PROVIDER, KEEP_LOCAL, RESET_KEEP_LOCAL }
    public record ResolutionRequest(String kind,String action,long artistId,Long albumId,Long providerLinkId,String country,Boolean active){}
    public record ResolutionMessage(String severity,String code,String summary,List<String> details){}
    public record ResolutionResult(String kind,String action,long artistId,Long albumId,Artist artist,Album album,List<ResolutionMessage> messages){}
    public record ProviderConflictSnapshot(List<org.kroky.musiclib.model.ArtistCountryConflict> countries,List<org.kroky.musiclib.model.ArtistStatusConflict> statuses,List<org.kroky.musiclib.model.ProviderTitleConflict> titles,List<org.kroky.musiclib.model.ProviderReleaseYearConflict> years){}
    private record FolderMove(Path source,Path target,String targetRelativePath){}
    private static final class FolderRenameException extends RuntimeException { private FolderRenameException(Throwable cause){super(cause);} }
}
