package org.kroky.musiclib.repository;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.CollectionMetadata;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.CollectionFolderCandidate;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.scan.FolderNameParser;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MusicCollectionRepository {

    private static final Logger LOG = Logger.getLogger(MusicCollectionRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    MusicRootService musicRootService;

    @Inject
    FolderNameParser folderNameParser;

    public List<MusicCollection> list() {
        LOG.debug("Listing music collections");
        String sql = """
                SELECT id, name, relative_path, type, last_scan_at, last_scan_status, last_scan_message
                FROM collections
                ORDER BY name
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<MusicCollection> collections = new ArrayList<>();
            while (rs.next()) {
                collections.add(map(rs));
            }
            return collections;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list music collections", e);
        }
    }

    public Optional<MusicCollection> find(String id) {
        LOG.tracef("Finding music collection id=%s", id);
        String sql = """
                SELECT id, name, relative_path, type, last_scan_at, last_scan_status, last_scan_message
                FROM collections
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find collection " + id, e);
        }
    }

    public void markScanned(String collectionId, String status, String message) {
        LOG.infof("Marking collection %s scanned with status %s", collectionId, status);
        String sql = """
                UPDATE collections
                SET last_scan_at = CURRENT_TIMESTAMP, last_scan_status = ?, last_scan_message = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, message);
            statement.setString(3, collectionId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark collection scanned " + collectionId, e);
        }
    }

    public Optional<CollectionMetadata> metadata(String id) {
        if (find(id).isEmpty()) {
            return Optional.empty();
        }
        String sql = """
                SELECT
                  (SELECT count(DISTINCT aa.artist_id) FROM albums a JOIN album_artists aa ON aa.album_id=a.id WHERE a.collection_id=?) artist_count,
                  (SELECT count(DISTINCT aa.artist_id) FROM albums a JOIN album_artists aa ON aa.album_id=a.id WHERE a.collection_id=?) contributor_artist_count,
                  (SELECT count(*) FROM albums WHERE collection_id=? AND local_relative_path IS NOT NULL) local_album_count,
                  (SELECT count(*) FROM albums WHERE collection_id=?) known_album_count,
                  (SELECT count(*) FROM albums WHERE collection_id=? AND checked=0) unchecked_album_count,
                  (SELECT count(*) FROM albums WHERE collection_id=? AND checked=1) checked_album_count
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 6; i++) {
                statement.setString(i, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CollectionMetadata(
                        id,
                        rs.getInt("artist_count"),
                        rs.getInt("contributor_artist_count"),
                        rs.getInt("local_album_count"),
                        rs.getInt("known_album_count"),
                        rs.getInt("unchecked_album_count"),
                        rs.getInt("checked_album_count")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load collection metadata " + id, e);
        }
    }

    public List<CollectionFolderCandidate> listFolderCandidates() {
        List<String> existingPaths = list().stream()
                .map(MusicCollection::relativePath)
                .map(Names::normalize)
                .toList();
        return musicRootService.listDirectChildDirectories().stream()
                .filter(path -> !existingPaths.contains(Names.normalize(path.getFileName().toString())))
                .map(path -> new CollectionFolderCandidate(
                        path.getFileName().toString(),
                        path.getFileName().toString(),
                        Names.chicagoStyle(path.getFileName().toString())))
                .toList();
    }

    public MusicCollection createFromFolder(String relativePath) {
        String folder = blankToNull(relativePath);
        if (folder == null || folder.contains("/") || folder.contains("\\") || folder.contains("..")) {
            throw new IllegalArgumentException("Collection folder must be a direct music-root child");
        }
        if (!Files.isDirectory(musicRootService.resolveCollection(folder))) {
            throw new IllegalArgumentException("Collection folder does not exist: " + folder);
        }
        String id = uniqueId(Names.slug(folder));
        String name = Names.chicagoStyle(folder);
        CollectionType effectiveType = inferType(musicRootService.resolveCollection(folder));
        LOG.infof("Creating collection id=%s name='%s' relativePath='%s' type=%s",
                id, name, folder, effectiveType);
        String sql = """
                INSERT INTO collections (id, name, relative_path, type)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, folder);
            statement.setString(4, effectiveType.name());
            statement.executeUpdate();
            return find(id).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create collection from folder " + folder, e);
        }
    }

    public Optional<MusicCollection> update(String id, String name, CollectionType type) {
        MusicCollection current = find(id).orElse(null);
        if (current == null) return Optional.empty();
        CollectionType effectiveType = type == null ? current.type() : type;
        if (effectiveType != current.type() && albumCount(id) > 0) {
            throw new IllegalArgumentException("Collection type cannot change after albums have been added.");
        }
        LOG.infof("Updating collection id=%s name='%s' type=%s", id, name, effectiveType);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("UPDATE collections SET name=?, type=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setString(1, blankToNull(name));
            statement.setString(2, effectiveType.name());
            statement.setString(3, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update collection " + id, e);
        }
    }

    public DeletePreview deletePreview(String id) {
        if (find(id).isEmpty()) throw new IllegalArgumentException("Unknown collection: " + id);
        String sql = """
                SELECT (SELECT count(*) FROM albums WHERE collection_id=?) album_count,
                       (SELECT count(*) FROM artists ar WHERE EXISTS (SELECT 1 FROM album_artists aa JOIN albums a ON a.id=aa.album_id WHERE aa.artist_id=ar.id AND a.collection_id=?) AND NOT EXISTS (SELECT 1 FROM album_artists aa JOIN albums a ON a.id=aa.album_id WHERE aa.artist_id=ar.id AND a.collection_id<>?)) artist_count
                """;
        try (Connection connection=dataSource.getConnection();PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,id);statement.setString(2,id);statement.setString(3,id);
            try(ResultSet rs=statement.executeQuery()){rs.next();return new DeletePreview(id,rs.getInt(1),rs.getInt(2));}
        } catch(Exception e){throw new IllegalStateException("Unable to preview collection delete " + id,e);}
    }

    public DeleteResult delete(String id) {
        LOG.infof("Deleting collection id=%s", id);
        DeletePreview preview = deletePreview(id);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement deleteCollection = connection.prepareStatement(
                            "DELETE FROM collections WHERE id = ?")) {
                deleteCollection.setString(1, id);
                deleteCollection.executeUpdate();
                try (PreparedStatement orphaned = connection.prepareStatement("DELETE FROM artists WHERE NOT EXISTS (SELECT 1 FROM album_artists aa WHERE aa.artist_id=artists.id)")) {
                    orphaned.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete collection " + id, e);
        }
        return new DeleteResult(id, preview.albumCount(), preview.artistCount());
    }

    private MusicCollection map(ResultSet rs) throws Exception {
        return new MusicCollection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("relative_path"),
                resolvedCollectionPath(rs.getString("relative_path")),
                collectionExists(rs.getString("relative_path")),
                CollectionType.valueOf(rs.getString("type")),
                rs.getString("last_scan_at"),
                rs.getString("last_scan_status"),
                rs.getString("last_scan_message"));
    }

    private String resolvedCollectionPath(String relativePath) {
        try {
            return musicRootService.resolveCollection(relativePath).toString();
        } catch (IllegalStateException e) {
            LOG.debugf("Cannot resolve collection relative path %s: %s", relativePath, e.getMessage());
            return null;
        }
    }

    private boolean collectionExists(String relativePath) {
        try {
            return java.nio.file.Files.isDirectory(musicRootService.resolveCollection(relativePath));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private String uniqueId(String baseId) {
        String root = baseId == null || baseId.isBlank() ? "collection" : baseId;
        String candidate = root;
        int suffix = 2;
        while (find(candidate).isPresent()) {
            candidate = root + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private int albumCount(String collectionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM albums WHERE collection_id=?")) {
            statement.setString(1, collectionId);
            try (ResultSet rs = statement.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to count collection albums " + collectionId, e);
        }
    }

    private CollectionType inferType(java.nio.file.Path root) {
        int artistItems = 0;
        int titleItems = 0;
        try (var folders = Files.list(root)) {
            for (var folder : folders.filter(Files::isDirectory).toList()) {
                if (folderNameParser.parseFlatArtistAlbum(folder, "").isPresent()) {
                    artistItems++;
                    continue;
                }
                int nestedAlbums;
                try (var children = Files.list(folder)) {
                    nestedAlbums = (int) children.filter(Files::isDirectory)
                            .filter(child -> folderNameParser.parseNestedArtistAlbum(folder, child, "").isPresent())
                            .count();
                }
                if (nestedAlbums > 0) artistItems += nestedAlbums;
                else titleItems++;
            }
        } catch (Exception e) {
            LOG.debugf("Unable to infer collection type for %s: %s", root, e.getMessage());
        }
        return artistItems >= titleItems ? CollectionType.ARTIST : CollectionType.TITLE;
    }

    public record DeletePreview(String collectionId, int albumCount, int artistCount) { }
    public record DeleteResult(String collectionId, int albumsDeleted, int artistsDeleted) { }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // Preserve the original database failure.
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
