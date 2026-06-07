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
import org.kroky.musiclib.model.ParserType;
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

    public List<MusicCollection> list() {
        LOG.debug("Listing music collections");
        String sql = """
                SELECT id, name, relative_path, type, parser, last_scan_at, last_scan_status, last_scan_message
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
                SELECT id, name, relative_path, type, parser, last_scan_at, last_scan_status, last_scan_message
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
                    (SELECT count(*)
                     FROM artist_collections ac
                     WHERE ac.collection_id = ?) AS artist_count,
                    (SELECT count(DISTINCT aa.artist_id)
                     FROM collection_albums ca
                     JOIN album_artists aa ON aa.album_id = ca.album_id
                     WHERE ca.collection_id = ?) AS contributor_artist_count,
                    (SELECT count(DISTINCT lp.album_id)
                     FROM album_local_paths lp
                     WHERE lp.collection_id = ? AND lp.missing_since IS NULL) AS local_album_count,
                    (SELECT count(*)
                     FROM collection_albums ca
                     WHERE ca.collection_id = ?) AS known_album_count,
                    (SELECT count(*)
                     FROM collection_albums ca
                     JOIN albums a ON a.id = ca.album_id
                     WHERE ca.collection_id = ? AND a.checked = 0) AS unchecked_album_count,
                    (SELECT count(*)
                     FROM collection_albums ca
                     JOIN albums a ON a.id = ca.album_id
                     WHERE ca.collection_id = ? AND a.checked = 1) AS checked_album_count
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
        CollectionType type = CollectionType.ARTIST;
        ParserType parser = defaultParser(type);
        LOG.infof("Creating collection id=%s name='%s' relativePath='%s' type=%s parser=%s",
                id, name, folder, type, parser);
        String sql = """
                INSERT INTO collections (id, name, relative_path, type, parser)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, folder);
            statement.setString(4, type.name());
            statement.setString(5, parser.name());
            statement.executeUpdate();
            return find(id).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create collection from folder " + folder, e);
        }
    }

    public Optional<MusicCollection> update(String id, String name, CollectionType type, ParserType parser) {
        CollectionType resolvedType = type == null ? null : type;
        ParserType resolvedParser = parser != null ? parser : defaultParser(resolvedType);
        LOG.infof("Updating collection id=%s name='%s' type=%s parser=%s", id, name, resolvedType, resolvedParser);
        String sql = """
                UPDATE collections
                SET name = COALESCE(?, name),
                    type = COALESCE(?, type),
                    parser = COALESCE(?, parser),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, blankToNull(name));
            statement.setString(2, resolvedType == null ? null : resolvedType.name());
            statement.setString(3, resolvedParser == null ? null : resolvedParser.name());
            statement.setString(4, id);
            int updated = statement.executeUpdate();
            return updated == 0 ? Optional.empty() : find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update collection " + id, e);
        }
    }

    public void delete(String id) {
        LOG.infof("Deleting collection id=%s", id);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement deleteLocalPaths = connection.prepareStatement(
                    "DELETE FROM album_local_paths WHERE collection_id = ?");
                    PreparedStatement deleteCollectionAlbums = connection.prepareStatement(
                            "DELETE FROM collection_albums WHERE collection_id = ?");
                    PreparedStatement deleteArtistMemberships = connection.prepareStatement(
                            "DELETE FROM artist_collections WHERE collection_id = ?");
                    PreparedStatement deleteCollection = connection.prepareStatement(
                            "DELETE FROM collections WHERE id = ?")) {
                deleteLocalPaths.setString(1, id);
                deleteLocalPaths.executeUpdate();
                deleteCollectionAlbums.setString(1, id);
                deleteCollectionAlbums.executeUpdate();
                deleteArtistMemberships.setString(1, id);
                deleteArtistMemberships.executeUpdate();
                deleteCollection.setString(1, id);
                deleteCollection.executeUpdate();
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
    }

    private MusicCollection map(ResultSet rs) throws Exception {
        return new MusicCollection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("relative_path"),
                resolvedCollectionPath(rs.getString("relative_path")),
                collectionExists(rs.getString("relative_path")),
                CollectionType.valueOf(rs.getString("type")),
                ParserType.valueOf(rs.getString("parser")),
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

    public static ParserType defaultParser(CollectionType type) {
        if (type == CollectionType.TITLE) {
            return ParserType.TITLE_PIPELINE;
        }
        if (type == CollectionType.ARTIST) {
            return ParserType.FLAT_ARTIST_YEAR_ALBUM;
        }
        return null;
    }

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
