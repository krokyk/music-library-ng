package org.kroky.musiclib.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
        String sql = selectAlbums("""
                WHERE (? IS NULL OR EXISTS (
                    SELECT 1 FROM album_artists aa_filter
                    WHERE aa_filter.album_id = a.id AND aa_filter.artist_id = ?
                ))
                  AND (? IS NULL OR EXISTS (
                      SELECT 1 FROM album_artists aa_collection
                      JOIN artist_collections ac ON ac.artist_id = aa_collection.artist_id
                      WHERE aa_collection.album_id = a.id AND ac.collection_id = ?
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
                  AND (
                    ? IS NULL
                    OR a.normalized_title LIKE '%' || ? || '%'
                    OR EXISTS (
                        SELECT 1 FROM album_artists aa_search
                        JOIN artists ar_search ON ar_search.id = aa_search.artist_id
                        WHERE aa_search.album_id = a.id
                          AND ar_search.normalized_name LIKE '%' || ? || '%'
                    )
                  )
                """);
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
                    albums.add(mapAlbum(connection, rs));
                }
                return albums;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list albums", e);
        }
    }

    public Optional<Album> find(long id) {
        LOG.tracef("Finding album id=%d", id);
        String sql = selectAlbums("WHERE a.id = ?");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapAlbum(connection, rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album " + id, e);
        }
    }

    public Optional<Album> findDuplicate(long artistId, String title, String releaseDate) {
        try (Connection connection = dataSource.getConnection()) {
            Optional<Long> id = findDuplicateId(connection, artistId, title, releaseDate);
            return id.flatMap(this::find);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album duplicate", e);
        }
    }

    public Album create(long artistId, String title, String releaseDate, boolean checked, String notes) {
        LOG.infof("Creating album artistId=%d title='%s' releaseDate='%s' checked=%s",
                artistId, title, releaseDate, checked);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long albumId = insertAlbum(connection, title, releaseDate, checked, notes);
                replaceArtists(connection, albumId, List.of(artistId));
                connection.commit();
                return find(albumId).orElseThrow();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create album " + title, e);
        }
    }

    public Optional<Album> update(long id, String title, String releaseDate, boolean checked, String notes) {
        LOG.infof("Updating album id=%d title='%s' releaseDate='%s' checked=%s", id, title, releaseDate, checked);
        String sql = """
                UPDATE albums
                SET title = ?, normalized_title = ?, release_date = ?,
                    checked = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            statement.setInt(4, checked ? 1 : 0);
            statement.setString(5, blankToNull(notes));
            statement.setLong(6, id);
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

    public UpsertResult upsertScanned(long artistId, String title, String releaseDate, String relativePath,
            String collectionId) {
        return upsertScanned(List.of(artistId), title, releaseDate, relativePath, collectionId);
    }

    public UpsertResult upsertScanned(List<Long> artistIds, String title, String releaseDate, String relativePath,
            String collectionId) {
        List<Long> normalizedArtistIds = uniqueArtistIds(artistIds);
        if (normalizedArtistIds.isEmpty()) {
            throw new IllegalArgumentException("At least one artist is required to upsert a scanned album");
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<Long> localAlbumId = findAlbumIdByLocalPath(connection, collectionId, relativePath);
                long albumId;
                boolean created = false;
                if (localAlbumId.isPresent()) {
                    albumId = localAlbumId.get();
                    updateScannedAlbum(connection, albumId, title, releaseDate);
                } else {
                    Optional<Long> duplicateAlbumId = findDuplicateId(connection, normalizedArtistIds, title, releaseDate);
                    if (duplicateAlbumId.isPresent()) {
                        albumId = duplicateAlbumId.get();
                        updateScannedAlbum(connection, albumId, title, releaseDate);
                    } else {
                        albumId = insertAlbum(connection, title, releaseDate, true, null);
                        created = true;
                    }
                }
                replaceArtists(connection, albumId, normalizedArtistIds);
                upsertLocalPath(connection, albumId, collectionId, relativePath);
                connection.commit();
                return new UpsertResult(albumId, created);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert scanned album " + title, e);
        }
    }

    public void upsertLocalPath(long albumId, String collectionId, String relativePath) {
        try (Connection connection = dataSource.getConnection()) {
            upsertLocalPath(connection, albumId, collectionId, relativePath);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert album local path", e);
        }
    }

    public int markMissingPaths(String collectionId, Set<String> seenPaths) {
        return markMissingPaths(collectionId, null, seenPaths);
    }

    public int markMissingPathsForArtist(String collectionId, long artistId, Set<String> seenPaths) {
        return markMissingPaths(collectionId, artistId, seenPaths);
    }

    public int markLocalPathMissing(String collectionId, String relativePath) {
        String sql = """
                UPDATE album_local_paths
                SET missing_since = CURRENT_TIMESTAMP
                WHERE collection_id = ? AND relative_path = ? AND missing_since IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            statement.setString(2, relativePath);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark album local path missing", e);
        }
    }

    private int markMissingPaths(String collectionId, Long artistId, Set<String> seenPaths) {
        Set<String> normalizedSeen = new HashSet<>(seenPaths);
        String select = """
                SELECT relative_path
                FROM album_local_paths
                WHERE collection_id = ? AND missing_since IS NULL
                  AND (? IS NULL OR EXISTS (
                      SELECT 1
                      FROM album_artists aa
                      WHERE aa.album_id = album_local_paths.album_id
                        AND aa.artist_id = ?
                  ))
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
            setNullableLong(selectStatement, 2, artistId);
            setNullableLong(selectStatement, 3, artistId);
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

    public void deleteOrphanAlbums() {
        String sql = """
                DELETE FROM albums
                WHERE NOT EXISTS (
                    SELECT 1 FROM album_artists aa
                    WHERE aa.album_id = albums.id
                )
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete orphan albums", e);
        }
    }

    private static String selectAlbums(String whereClause) {
        return """
                SELECT a.id,
                       coalesce((
                           SELECT group_concat(artist_id, ',') FROM (
                               SELECT aa.artist_id AS artist_id
                               FROM album_artists aa
                               JOIN artists ar ON ar.id = aa.artist_id
                               WHERE aa.album_id = a.id
                               ORDER BY aa.position, ar.name
                           )
                       ), '') AS artist_ids,
                       coalesce((
                           SELECT group_concat(name, ', ') FROM (
                               SELECT ar.name AS name
                               FROM album_artists aa
                               JOIN artists ar ON ar.id = aa.artist_id
                               WHERE aa.album_id = a.id
                               ORDER BY aa.position, ar.name
                           )
                       ), '') AS artist_name,
                       a.title, a.release_date, a.checked, a.notes, a.created_at, a.updated_at
                FROM albums a
                """
                + whereClause
                + "\n"
                + """
                ORDER BY artist_name, a.release_date, a.title
                """;
    }

    private long insertAlbum(Connection connection, String title, String releaseDate, boolean checked, String notes)
            throws Exception {
        String sql = """
                INSERT INTO albums (title, normalized_title, release_date, checked, notes)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            statement.setInt(4, checked ? 1 : 0);
            statement.setString(5, blankToNull(notes));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new IllegalStateException("Album insert returned no id");
        }
    }

    private void updateScannedAlbum(Connection connection, long albumId, String title, String releaseDate)
            throws Exception {
        String sql = """
                UPDATE albums
                SET title = ?, normalized_title = ?, release_date = ?,
                    checked = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            statement.setLong(4, albumId);
            statement.executeUpdate();
        }
    }

    private Optional<Long> findDuplicateId(Connection connection, List<Long> artistIds, String title, String releaseDate)
            throws Exception {
        for (long artistId : artistIds) {
            Optional<Long> albumId = findDuplicateId(connection, artistId, title, releaseDate);
            if (albumId.isPresent()) {
                return albumId;
            }
        }
        return Optional.empty();
    }

    private Optional<Long> findDuplicateId(Connection connection, long artistId, String title, String releaseDate)
            throws Exception {
        String sql = """
                SELECT a.id
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE aa.artist_id = ? AND a.normalized_title = ?
                  AND coalesce(a.release_date, '') = coalesce(?, '')
                ORDER BY a.id
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }

    private Optional<Long> findAlbumIdByLocalPath(Connection connection, String collectionId, String relativePath)
            throws Exception {
        String sql = """
                SELECT album_id
                FROM album_local_paths
                WHERE collection_id = ? AND relative_path = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            statement.setString(2, relativePath);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("album_id")) : Optional.empty();
            }
        }
    }

    private void replaceArtists(Connection connection, long albumId, List<Long> artistIds) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM album_artists WHERE album_id = ?");
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR REPLACE INTO album_artists (album_id, artist_id, position)
                        VALUES (?, ?, ?)
                        """)) {
            delete.setLong(1, albumId);
            delete.executeUpdate();
            int position = 0;
            for (long artistId : uniqueArtistIds(artistIds)) {
                insert.setLong(1, albumId);
                insert.setLong(2, artistId);
                insert.setInt(3, position++);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void upsertLocalPath(Connection connection, long albumId, String collectionId, String relativePath)
            throws Exception {
        String sql = """
                INSERT INTO album_local_paths (album_id, collection_id, relative_path, last_seen_at, missing_since)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, NULL)
                ON CONFLICT(collection_id, relative_path) DO UPDATE SET
                    album_id = excluded.album_id,
                    last_seen_at = CURRENT_TIMESTAMP,
                    missing_since = NULL
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.setString(2, collectionId);
            statement.setString(3, relativePath);
            statement.executeUpdate();
        }
    }

    private Album mapAlbum(Connection connection, ResultSet rs) throws Exception {
        long albumId = rs.getLong("id");
        List<AlbumLocalPath> localPaths = listPaths(connection, albumId);
        boolean hasLocalPath = localPaths.stream().anyMatch(path -> path.missingSince() == null);
        boolean onDisk = localPaths.stream().anyMatch(path -> path.missingSince() == null && path.onDisk());
        return new Album(
                albumId,
                parseArtistIds(rs.getString("artist_ids")),
                rs.getString("artist_name"),
                rs.getString("title"),
                rs.getString("release_date"),
                rs.getInt("checked") == 1,
                hasLocalPath,
                onDisk,
                localPaths,
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private List<AlbumLocalPath> listPaths(Connection connection, long albumId) {
        String sql = """
                SELECT lp.id, lp.album_id, lp.collection_id, c.name AS collection_name, c.relative_path AS collection_relative_path,
                       lp.relative_path, lp.first_seen_at, lp.last_seen_at, lp.missing_since
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE lp.album_id = ?
                ORDER BY c.name, lp.relative_path
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    private static List<Long> parseArtistIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Long> artistIds = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                artistIds.add(Long.parseLong(trimmed));
            }
        }
        return artistIds;
    }

    private static List<Long> uniqueArtistIds(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long artistId : artistIds) {
            if (artistId != null && artistId > 0) {
                unique.add(artistId);
            }
        }
        return new ArrayList<>(unique);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }
}
