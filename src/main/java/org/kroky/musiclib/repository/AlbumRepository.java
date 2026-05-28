package org.kroky.musiclib.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumLocalPath;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumRepository {

    private static final Logger LOG = Logger.getLogger(AlbumRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    MusicRootService musicRootService;

    public List<Album> list(Long artistId, Boolean checked, Boolean hasLocalPath, String search) {
        return list(artistId, null, checked, hasLocalPath, search);
    }

    public List<Album> list(Long artistId, String collectionId, Boolean checked, Boolean hasLocalPath, String search) {
        LOG.debugf("Listing albums artistId=%s collectionId=%s checked=%s hasLocalPath=%s search='%s'",
                artistId, collectionId, checked, hasLocalPath, search);
        String sql = """
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.release_date,
                       a.checked, a.notes, a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                WHERE (? IS NULL OR a.artist_id = ?)
                  AND (? IS NULL OR EXISTS (
                      SELECT 1 FROM artist_collections ac
                      WHERE ac.artist_id = a.artist_id AND ac.collection_id = ?
                  ))
                  AND (? IS NULL OR a.checked = ?)
                  AND (
                    ? IS NULL
                    OR (? = 1 AND EXISTS (
                        SELECT 1 FROM album_local_paths lp
                        WHERE lp.album_id = a.id AND lp.missing_since IS NULL
                    ))
                    OR (? = 0 AND NOT EXISTS (
                        SELECT 1 FROM album_local_paths lp
                        WHERE lp.album_id = a.id AND lp.missing_since IS NULL
                    ))
                  )
                  AND (? IS NULL OR a.normalized_title LIKE '%' || ? || '%' OR ar.normalized_name LIKE '%' || ? || '%')
                ORDER BY ar.name, a.release_year, a.title
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            String normalizedCollectionId = blankToNull(collectionId);
            setNullableLong(statement, 1, artistId);
            setNullableLong(statement, 2, artistId);
            statement.setString(3, normalizedCollectionId);
            statement.setString(4, normalizedCollectionId);
            setNullableBooleanInt(statement, 5, checked);
            setNullableBooleanInt(statement, 6, checked);
            setNullableBooleanInt(statement, 7, hasLocalPath);
            setNullableBooleanInt(statement, 8, hasLocalPath);
            setNullableBooleanInt(statement, 9, hasLocalPath);
            statement.setString(10, normalizedSearch);
            statement.setString(11, normalizedSearch);
            statement.setString(12, normalizedSearch);
            try (ResultSet rs = statement.executeQuery()) {
                List<Album> albums = new ArrayList<>();
                while (rs.next()) {
                    albums.add(mapAlbum(rs));
                }
                return albums;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list albums", e);
        }
    }

    public Optional<Album> find(long id) {
        LOG.tracef("Finding album id=%d", id);
        String sql = """
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.release_date,
                       a.checked, a.notes, a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                WHERE a.id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapAlbum(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album " + id, e);
        }
    }

    public Optional<Album> findDuplicate(long artistId, String title, Integer releaseYear) {
        String sql = """
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.release_date,
                       a.checked, a.notes, a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                WHERE a.artist_id = ? AND a.normalized_title = ?
                  AND coalesce(a.release_year, -1) = coalesce(?, -1)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, Names.normalize(title));
            setNullableInt(statement, 3, releaseYear);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapAlbum(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album duplicate", e);
        }
    }

    public Album create(long artistId, String title, Integer releaseYear, String releaseDate, boolean checked,
            String notes) {
        LOG.infof("Creating album artistId=%d title='%s' year=%s checked=%s",
                artistId, title, releaseYear, checked);
        String sql = """
                INSERT INTO albums (artist_id, title, normalized_title, release_year, release_date, checked, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, artistId);
            statement.setString(2, title);
            statement.setString(3, Names.normalize(title));
            setNullableInt(statement, 4, releaseYear);
            statement.setString(5, blankToNull(releaseDate));
            statement.setInt(6, checked ? 1 : 0);
            statement.setString(7, blankToNull(notes));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return find(keys.getLong(1)).orElseThrow();
                }
            }
            throw new IllegalStateException("Album insert returned no id");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create album " + title, e);
        }
    }

    public Optional<Album> update(long id, String title, Integer releaseYear, String releaseDate, boolean checked,
            String notes) {
        LOG.infof("Updating album id=%d title='%s' year=%s checked=%s", id, title, releaseYear, checked);
        String sql = """
                UPDATE albums
                SET title = ?, normalized_title = ?, release_year = ?, release_date = ?,
                    checked = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            setNullableInt(statement, 3, releaseYear);
            statement.setString(4, blankToNull(releaseDate));
            statement.setInt(5, checked ? 1 : 0);
            statement.setString(6, blankToNull(notes));
            statement.setLong(7, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album " + id, e);
        }
    }

    public void delete(long id) {
        LOG.infof("Deleting album id=%d", id);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM albums WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete album " + id, e);
        }
    }

    public UpsertResult upsertScanned(long artistId, String title, Integer releaseYear, String relativePath,
            String collectionId) {
        Optional<Album> existing = findDuplicate(artistId, title, releaseYear);
        if (existing.isPresent()) {
            if (!existing.get().checked()) {
                markChecked(existing.get().id());
            }
            upsertLocalPath(existing.get().id(), collectionId, relativePath);
            return new UpsertResult(existing.get().id(), false);
        }
        Album created = create(artistId, title, releaseYear, null, true, null);
        upsertLocalPath(created.id(), collectionId, relativePath);
        return new UpsertResult(created.id(), true);
    }

    public void upsertLocalPath(long albumId, String collectionId, String relativePath) {
        String sql = """
                INSERT INTO album_local_paths (album_id, collection_id, relative_path, last_seen_at, missing_since)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, NULL)
                ON CONFLICT(collection_id, relative_path) DO UPDATE SET
                    album_id = excluded.album_id,
                    last_seen_at = CURRENT_TIMESTAMP,
                    missing_since = NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.setString(2, collectionId);
            statement.setString(3, relativePath);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert album local path", e);
        }
    }

    private void markChecked(long albumId) {
        String sql = """
                UPDATE albums
                SET checked = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND checked = 0
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark scanned album checked " + albumId, e);
        }
    }

    public int markMissingPaths(String collectionId, Set<String> seenPaths) {
        Set<String> normalizedSeen = new HashSet<>(seenPaths);
        String select = """
                SELECT relative_path
                FROM album_local_paths
                WHERE collection_id = ? AND missing_since IS NULL
                """;
        String update = """
                UPDATE album_local_paths
                SET missing_since = CURRENT_TIMESTAMP
                WHERE collection_id = ? AND relative_path = ? AND missing_since IS NULL
                """;
        int missing = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement selectStatement = connection.prepareStatement(select);
                PreparedStatement updateStatement = connection.prepareStatement(update)) {
            selectStatement.setString(1, collectionId);
            try (ResultSet rs = selectStatement.executeQuery()) {
                while (rs.next()) {
                    String relativePath = rs.getString("relative_path");
                    if (normalizedSeen.contains(relativePath)) {
                        continue;
                    }
                    updateStatement.setString(1, collectionId);
                    updateStatement.setString(2, relativePath);
                    missing += updateStatement.executeUpdate();
                }
            }
            return missing;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark missing paths", e);
        }
    }

    private Album mapAlbum(ResultSet rs) throws Exception {
        long albumId = rs.getLong("id");
        List<AlbumLocalPath> localPaths = listPaths(albumId);
        boolean hasLocalPath = localPaths.stream().anyMatch(path -> path.missingSince() == null);
        boolean onDisk = localPaths.stream().anyMatch(path -> path.missingSince() == null && path.onDisk());
        return new Album(
                albumId,
                rs.getLong("artist_id"),
                rs.getString("artist_name"),
                rs.getString("title"),
                (Integer) rs.getObject("release_year"),
                rs.getString("release_date"),
                rs.getInt("checked") == 1,
                hasLocalPath,
                onDisk,
                localPaths,
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private List<AlbumLocalPath> listPaths(long albumId) {
        String sql = """
                SELECT lp.id, lp.album_id, lp.collection_id, c.name AS collection_name, c.relative_path AS collection_relative_path,
                       lp.relative_path, lp.first_seen_at, lp.last_seen_at, lp.missing_since
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE lp.album_id = ?
                ORDER BY c.name, lp.relative_path
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AlbumLocalPath> paths = new ArrayList<>();
                while (rs.next()) {
                    String resolvedPath = resolvedPath(
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path"));
                    paths.add(new AlbumLocalPath(
                            rs.getLong("id"),
                            rs.getLong("album_id"),
                            rs.getString("collection_id"),
                            rs.getString("collection_name"),
                            rs.getString("relative_path"),
                            resolvedPath,
                            resolvedPath != null && Files.isDirectory(Path.of(resolvedPath)),
                            rs.getString("first_seen_at"),
                            rs.getString("last_seen_at"),
                            rs.getString("missing_since")));
                }
                return paths;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list album local paths", e);
        }
    }

    private String resolvedPath(String collectionRelativePath, String albumRelativePath) {
        if (collectionRelativePath == null || albumRelativePath == null) {
            return null;
        }
        try {
            return musicRootService.resolveAlbum(collectionRelativePath, albumRelativePath).toString();
        } catch (IllegalStateException e) {
            LOG.debugf("Cannot resolve album path collection=%s album=%s: %s",
                    collectionRelativePath, albumRelativePath, e.getMessage());
            return null;
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableBooleanInt(PreparedStatement statement, int index, Boolean value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value ? 1 : 0);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
