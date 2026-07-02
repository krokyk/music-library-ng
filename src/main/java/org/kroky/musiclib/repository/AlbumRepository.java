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
import org.kroky.musiclib.db.TitleSortNames;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumCollection;
import org.kroky.musiclib.model.AlbumLocalPath;
import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.MetadataSource;
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
                      SELECT 1 FROM collection_albums ca
                      WHERE ca.album_id = a.id AND ca.collection_id = ?
                  ))
                  AND (? IS NULL OR a.checked = ?)
                  AND (
                    ? IS NULL
                    OR (? = 1 AND EXISTS (
                        SELECT 1 FROM album_local_paths lp
                        WHERE lp.album_id = a.id
                    ))
                    OR (? = 0 AND NOT EXISTS (
                        SELECT 1 FROM album_local_paths lp
                        WHERE lp.album_id = a.id
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

    public Optional<Album> findByArtistAndTitle(long artistId, String title) {
        try (Connection connection = dataSource.getConnection()) {
            Optional<Long> id = findByArtistAndTitleId(connection, artistId, title);
            return id.flatMap(this::find);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album by artist and title", e);
        }
    }

    public Album create(long artistId, String title, String releaseDate, boolean checked, String notes) {
        return create(List.of(artistId), title, releaseDate, checked, notes, null);
    }

    public Album create(long artistId, String title, String releaseDate, boolean checked, String notes,
            String collectionId) {
        return create(List.of(artistId), title, releaseDate, checked, notes, collectionId);
    }

    public Album create(List<Long> artistIds, String title, String releaseDate, boolean checked, String notes,
            String collectionId) {
        LOG.infof("Creating album artistId=%d title='%s' releaseDate='%s' checked=%s",
                artistIds == null || artistIds.isEmpty() ? 0 : artistIds.get(0), title, releaseDate, checked);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long albumId = insertAlbum(connection, title, releaseDate, checked, notes);
                replaceArtists(connection, albumId, artistIds);
                assignToCollection(connection, albumId, collectionId);
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
                    sort_name = CASE
                        WHEN sort_name_source = 'MANUAL' THEN sort_name
                        ELSE ?
                    END,
                    normalized_sort_name = CASE
                        WHEN sort_name_source = 'MANUAL' THEN normalized_sort_name
                        ELSE ?
                    END,
                    checked = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String autoSortName = TitleSortNames.create(title, blankToNull(releaseDate));
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            statement.setString(4, autoSortName);
            statement.setString(5, Names.normalize(autoSortName));
            statement.setInt(6, checked ? 1 : 0);
            statement.setString(7, blankToNull(notes));
            statement.setLong(8, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album " + id, e);
        }
    }

    public Optional<Album> updateReleaseDateIfMissing(long id, String releaseDate) {
        String normalizedReleaseDate = blankToNull(releaseDate);
        if (normalizedReleaseDate == null) {
            return find(id);
        }
        String sql = """
                UPDATE albums
                SET release_date = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND release_date IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedReleaseDate);
            statement.setLong(2, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update missing album release date " + id, e);
        }
    }

    public Album upsertManual(List<Long> artistIds, String title, String releaseDate, String sortName,
            boolean checked, String collectionId) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Long> normalizedArtistIds = uniqueArtistIds(artistIds);
                Optional<Long> existing = normalizedArtistIds.isEmpty()
                        ? findDuplicateIdByTitle(connection, title, releaseDate)
                        : findDuplicateId(connection, normalizedArtistIds, title, releaseDate);
                long albumId = existing.isPresent()
                        ? existing.get()
                        : insertAlbum(connection, title, releaseDate, checked, null);
                updateTitleMetadata(connection, albumId, normalizedArtistIds, title, releaseDate, sortName, checked);
                assignToCollection(connection, albumId, collectionId);
                connection.commit();
                return find(albumId).orElseThrow();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert manual album " + title, e);
        }
    }

    public Optional<Album> updateTitleMetadata(long id, List<Long> artistIds, String title, String releaseDate,
            String sortName) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                updateTitleMetadata(connection, id, artistIds, title, releaseDate, sortName, null);
                connection.commit();
                return find(id);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album title metadata " + id, e);
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
        return upsertScanned(List.of(artistId), title, releaseDate, null, relativePath, collectionId);
    }

    public UpsertResult upsertScanned(List<Long> artistIds, String title, String releaseDate, String relativePath,
            String collectionId) {
        return upsertScanned(artistIds, title, releaseDate, null, relativePath, collectionId);
    }

    public UpsertResult upsertScanned(List<Long> artistIds, String title, String releaseDate, String sortName,
            String relativePath, String collectionId) {
        List<Long> normalizedArtistIds = uniqueArtistIds(artistIds);

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<Long> localAlbumId = findAlbumIdByLocalPath(connection, collectionId, relativePath);
                long albumId;
                boolean created = false;
                if (localAlbumId.isPresent()) {
                    albumId = localAlbumId.get();
                    markScannedAlbumChecked(connection, albumId);
                } else {
                    Optional<Long> duplicateAlbumId = normalizedArtistIds.isEmpty()
                            ? findDuplicateIdByTitle(connection, title, releaseDate)
                            : findScannedDuplicateId(connection, normalizedArtistIds, title, releaseDate);
                    if (duplicateAlbumId.isPresent()) {
                        albumId = duplicateAlbumId.get();
                        markScannedAlbumChecked(connection, albumId);
                    } else {
                        albumId = insertAlbum(connection, title, releaseDate, sortName, true, null);
                        created = true;
                    }
                }
                if (!normalizedArtistIds.isEmpty()) {
                    replaceArtists(connection, albumId, normalizedArtistIds);
                }
                assignToCollection(connection, albumId, collectionId);
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

    public UpsertResult upsertTitleScanned(List<Long> artistIds, String title, String releaseDate, String sortName,
            String relativePath, String collectionId) {
        List<Long> normalizedArtistIds = uniqueArtistIds(artistIds);

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<Long> localAlbumId = findAlbumIdByLocalPath(connection, collectionId, relativePath);
                long albumId;
                boolean created = false;
                if (localAlbumId.isPresent()) {
                    albumId = localAlbumId.get();
                    markScannedAlbumChecked(connection, albumId);
                } else {
                    albumId = insertAlbum(connection, title, releaseDate, sortName, true, null);
                    created = true;
                }
                if (!normalizedArtistIds.isEmpty()) {
                    replaceArtists(connection, albumId, normalizedArtistIds);
                }
                assignToCollection(connection, albumId, collectionId);
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
            throw new IllegalStateException("Unable to upsert scanned title album " + title, e);
        }
    }

    public void upsertLocalPath(long albumId, String collectionId, String relativePath) {
        try (Connection connection = dataSource.getConnection()) {
            assignToCollection(connection, albumId, collectionId);
            upsertLocalPath(connection, albumId, collectionId, relativePath);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert album local path", e);
        }
    }

    public void updateLocalPath(long albumId, long localPathId, String relativePath) {
        String sql = """
                UPDATE album_local_paths
                SET relative_path = ?, last_seen_at = CURRENT_TIMESTAMP
                WHERE id = ? AND album_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, relativePath);
            statement.setLong(2, localPathId);
            statement.setLong(3, albumId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown local path " + localPathId + " for album " + albumId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album local path " + localPathId, e);
        }
    }

    public void assignToCollection(long albumId, String collectionId) {
        try (Connection connection = dataSource.getConnection()) {
            assignToCollection(connection, albumId, collectionId);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to assign album " + albumId + " to collection " + collectionId, e);
        }
    }

    public int mergeProviderOnlyDuplicates(long keepAlbumId, long artistId, String title, String releaseDate) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Long> duplicates = providerOnlyDuplicateIds(connection, keepAlbumId, artistId, title, releaseDate);
                for (long duplicateId : duplicates) {
                    transferCollectionMemberships(connection, keepAlbumId, duplicateId);
                    transferProviderLinks(connection, keepAlbumId, duplicateId);
                    deleteAlbum(connection, duplicateId);
                }
                connection.commit();
                return duplicates.size();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to merge provider-only duplicate albums", e);
        }
    }

    public int removeUnseenLocalPaths(String collectionId, Set<String> seenPaths) {
        return removeUnseenLocalPaths(collectionId, null, seenPaths);
    }

    public int removeUnseenLocalPathsForArtist(String collectionId, long artistId, Set<String> seenPaths) {
        return removeUnseenLocalPaths(collectionId, artistId, seenPaths);
    }

    public int removeLocalPaths(String collectionId, long albumId) {
        String sql = """
                DELETE FROM album_local_paths
                WHERE collection_id = ? AND album_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            statement.setLong(2, albumId);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove album local paths", e);
        }
    }


    private int removeUnseenLocalPaths(String collectionId, Long artistId, Set<String> seenPaths) {
        Set<String> normalizedSeen = new HashSet<>(seenPaths);
        String select = """
                SELECT relative_path
                FROM album_local_paths
                WHERE collection_id = ?
                  AND (? IS NULL OR EXISTS (
                      SELECT 1
                      FROM album_artists aa
                      WHERE aa.album_id = album_local_paths.album_id
                        AND aa.artist_id = ?
                  ))
                """;
        String delete = """
                DELETE FROM album_local_paths
                WHERE collection_id = ? AND relative_path = ?
                """;
        int missing = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement selectStatement = connection.prepareStatement(select);
                PreparedStatement deleteStatement = connection.prepareStatement(delete)) {
            selectStatement.setString(1, collectionId);
            setNullableLong(selectStatement, 2, artistId);
            setNullableLong(selectStatement, 3, artistId);
            try (ResultSet rs = selectStatement.executeQuery()) {
                while (rs.next()) {
                    String relativePath = rs.getString("relative_path");
                    if (normalizedSeen.contains(relativePath)) {
                        continue;
                    }
                    deleteStatement.setString(1, collectionId);
                    deleteStatement.setString(2, relativePath);
                    missing += deleteStatement.executeUpdate();
                }
            }
            return missing;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove unseen local paths", e);
        }
    }

    public void deleteOrphanAlbums() {
        String sql = """
                DELETE FROM albums
                WHERE NOT EXISTS (
                    SELECT 1 FROM album_artists aa
                    WHERE aa.album_id = albums.id
                )
                  AND NOT EXISTS (
                    SELECT 1 FROM collection_albums ca
                    WHERE ca.album_id = albums.id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM album_local_paths lp
                    WHERE lp.album_id = albums.id
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
                       a.title, a.release_date, a.sort_name, a.sort_name_source,
                       a.checked, a.notes, a.created_at, a.updated_at
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
        return insertAlbum(connection, title, releaseDate, null, checked, notes);
    }

    private long insertAlbum(Connection connection, String title, String releaseDate, String sortName, boolean checked, String notes)
            throws Exception {
        String effectiveSortName = blankToNull(sortName);
        if (effectiveSortName == null) {
            effectiveSortName = TitleSortNames.create(title, blankToNull(releaseDate));
        }
        String sql = """
                INSERT INTO albums (
                    title, normalized_title, release_date,
                    sort_name, normalized_sort_name, sort_name_source,
                    checked, notes
                )
                VALUES (?, ?, ?, ?, ?, 'AUTO', ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(releaseDate));
            statement.setString(4, effectiveSortName);
            statement.setString(5, Names.normalize(effectiveSortName));
            statement.setInt(6, checked ? 1 : 0);
            statement.setString(7, blankToNull(notes));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new IllegalStateException("Album insert returned no id");
        }
    }

    private void markScannedAlbumChecked(Connection connection, long albumId)
            throws Exception {
        String sql = """
                UPDATE albums
                SET checked = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.executeUpdate();
        }
    }

    private void updateTitleMetadata(Connection connection, long albumId, List<Long> artistIds, String title,
            String releaseDate, String sortName, Boolean checked) throws Exception {
        String normalizedReleaseDate = blankToNull(releaseDate);
        String effectiveSortName = blankToNull(sortName);
        MetadataSource sortNameSource = MetadataSource.MANUAL;
        if (effectiveSortName == null) {
            effectiveSortName = TitleSortNames.create(title, normalizedReleaseDate);
            sortNameSource = MetadataSource.AUTO;
        }
        String sql = """
                UPDATE albums
                SET title = ?,
                    normalized_title = ?,
                    release_date = ?,
                    sort_name = ?,
                    normalized_sort_name = ?,
                    sort_name_source = ?,
                    checked = COALESCE(?, checked),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, normalizedReleaseDate);
            statement.setString(4, effectiveSortName);
            statement.setString(5, Names.normalize(effectiveSortName));
            statement.setString(6, sortNameSource.name());
            if (checked == null) {
                statement.setObject(7, null);
            } else {
                statement.setInt(7, checked ? 1 : 0);
            }
            statement.setLong(8, albumId);
            statement.executeUpdate();
        }
        replaceArtists(connection, albumId, artistIds);
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

    private Optional<Long> findScannedDuplicateId(Connection connection, List<Long> artistIds, String title,
            String releaseDate) throws Exception {
        for (long artistId : artistIds) {
            Optional<Long> albumId = findScannedDuplicateId(connection, artistId, title, releaseDate);
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

    private Optional<Long> findByArtistAndTitleId(Connection connection, long artistId, String title)
            throws Exception {
        String sql = """
                SELECT a.id
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE aa.artist_id = ? AND a.normalized_title = ?
                ORDER BY
                    CASE
                        WHEN EXISTS (
                            SELECT 1 FROM album_local_paths lp
                            WHERE lp.album_id = a.id
                        ) THEN 0
                        ELSE 1
                    END,
                    a.id
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, Names.normalize(title));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }

    private Optional<Long> findScannedDuplicateId(Connection connection, long artistId, String title, String releaseDate)
            throws Exception {
        String sql = """
                SELECT a.id
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE aa.artist_id = ? AND a.normalized_title = ?
                  AND %s
                ORDER BY %s
                LIMIT 1
                """.formatted(compatibleReleaseDatePredicate(), compatibleReleaseDateOrder());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, Names.normalize(title));
            setCompatibleReleaseDateParameters(statement, 3, releaseDate);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }

    private Optional<Long> findDuplicateIdByTitle(Connection connection, String title, String releaseDate)
            throws Exception {
        String sql = """
                SELECT a.id
                FROM albums a
                WHERE a.normalized_title = ?
                  AND coalesce(a.release_date, '') = coalesce(?, '')
                ORDER BY a.id
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Names.normalize(title));
            statement.setString(2, blankToNull(releaseDate));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }

    private static String compatibleReleaseDatePredicate() {
        return """
                (
                    coalesce(a.release_date, '') = coalesce(?, '')
                    OR a.release_date IS NULL
                    OR ? IS NULL
                    OR (
                        length(a.release_date) >= 4
                        AND length(?) >= 4
                        AND substr(a.release_date, 1, 4) = substr(?, 1, 4)
                    )
                )
                """;
    }

    private static String compatibleReleaseDateOrder() {
        return """
                CASE
                    WHEN coalesce(a.release_date, '') = coalesce(?, '') THEN 0
                    WHEN a.release_date IS NOT NULL
                        AND ? IS NOT NULL
                        AND length(a.release_date) >= 4
                        AND length(?) >= 4
                        AND substr(a.release_date, 1, 4) = substr(?, 1, 4) THEN 1
                    WHEN a.release_date IS NULL THEN 2
                    ELSE 3
                END,
                a.id
                """;
    }

    private static void setCompatibleReleaseDateParameters(PreparedStatement statement, int startIndex,
            String releaseDate) throws Exception {
        String normalizedReleaseDate = blankToNull(releaseDate);
        for (int i = 0; i < 8; i++) {
            statement.setString(startIndex + i, normalizedReleaseDate);
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

    private List<Long> providerOnlyDuplicateIds(Connection connection, long keepAlbumId, long artistId, String title,
            String releaseDate) throws Exception {
        String sql = """
                SELECT a.id
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE a.id <> ?
                  AND aa.artist_id = ?
                  AND a.normalized_title = ?
                  AND coalesce(a.release_date, '') = coalesce(?, '')
                  AND NOT EXISTS (
                      SELECT 1 FROM album_local_paths lp
                      WHERE lp.album_id = a.id
                  )
                ORDER BY a.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, keepAlbumId);
            statement.setLong(2, artistId);
            statement.setString(3, Names.normalize(title));
            statement.setString(4, blankToNull(releaseDate));
            try (ResultSet rs = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
                return ids;
            }
        }
    }

    private void transferCollectionMemberships(Connection connection, long keepAlbumId, long duplicateAlbumId)
            throws Exception {
        String sql = """
                INSERT OR IGNORE INTO collection_albums (collection_id, album_id, created_at, updated_at)
                SELECT collection_id, ?, created_at, CURRENT_TIMESTAMP
                FROM collection_albums
                WHERE album_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, keepAlbumId);
            statement.setLong(2, duplicateAlbumId);
            statement.executeUpdate();
        }
    }

    private void transferProviderLinks(Connection connection, long keepAlbumId, long duplicateAlbumId)
            throws Exception {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE OR IGNORE album_provider_links
                SET album_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE album_id = ?
                """);
                PreparedStatement deleteIgnored = connection.prepareStatement("""
                        DELETE FROM album_provider_links
                        WHERE album_id = ?
                        """)) {
            update.setLong(1, keepAlbumId);
            update.setLong(2, duplicateAlbumId);
            update.executeUpdate();
            deleteIgnored.setLong(1, duplicateAlbumId);
            deleteIgnored.executeUpdate();
        }
    }

    private void deleteAlbum(Connection connection, long albumId) throws Exception {
        deleteAlbumRows(connection, "album_provider_links", "album_id", albumId);
        deleteAlbumRows(connection, "album_local_paths", "album_id", albumId);
        deleteAlbumRows(connection, "collection_albums", "album_id", albumId);
        deleteAlbumRows(connection, "album_artists", "album_id", albumId);
        deleteAlbumRows(connection, "albums", "id", albumId);
    }

    private void deleteAlbumRows(Connection connection, String table, String column, long albumId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + column + " = ?")) {
            statement.setLong(1, albumId);
            statement.executeUpdate();
        }
    }

    private void assignToCollection(Connection connection, long albumId, String collectionId) throws Exception {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        String sql = """
                INSERT INTO collection_albums (collection_id, album_id, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(collection_id, album_id) DO UPDATE SET
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            statement.setLong(2, albumId);
            statement.executeUpdate();
        }
    }

    private void upsertLocalPath(Connection connection, long albumId, String collectionId, String relativePath)
            throws Exception {
        String sql = """
                INSERT INTO album_local_paths (album_id, collection_id, relative_path, last_seen_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(collection_id, relative_path) DO UPDATE SET
                    album_id = excluded.album_id,
                    last_seen_at = CURRENT_TIMESTAMP
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
        boolean hasLocalPath = !localPaths.isEmpty();
        boolean onDisk = localPaths.stream().anyMatch(AlbumLocalPath::onDisk);
        return new Album(
                albumId,
                parseArtistIds(rs.getString("artist_ids")),
                listCollections(connection, albumId),
                rs.getString("artist_name"),
                rs.getString("title"),
                rs.getString("release_date"),
                rs.getString("sort_name"),
                MetadataSource.valueOf(rs.getString("sort_name_source")),
                rs.getInt("checked") == 1,
                hasLocalPath,
                onDisk,
                localPaths,
                listProviderLinks(connection, albumId, rs.getString("release_date")),
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private List<AlbumCollection> listCollections(Connection connection, long albumId) {
        String sql = """
                SELECT c.id, c.name
                FROM collection_albums ca
                JOIN collections c ON c.id = ca.collection_id
                WHERE ca.album_id = ?
                ORDER BY c.name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AlbumCollection> collections = new ArrayList<>();
                while (rs.next()) {
                    collections.add(new AlbumCollection(
                            rs.getString("id"),
                            rs.getString("name")));
                }
                return collections;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list album collections", e);
        }
    }

    private List<AlbumLocalPath> listPaths(Connection connection, long albumId) {
        String sql = """
                SELECT lp.id, lp.album_id, lp.collection_id, c.name AS collection_name, c.relative_path AS collection_relative_path,
                       lp.relative_path, lp.first_seen_at, lp.last_seen_at
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
                            rs.getString("last_seen_at")));
                }
                return paths;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list album local paths", e);
        }
    }

    private List<AlbumProviderLink> listProviderLinks(Connection connection, long albumId, String localReleaseDate) {
        String sql = """
                SELECT id, album_id, provider_id, provider_release_group_id,
                       provider_title, provider_release_date, provider_url,
                       release_date_resolution, created_at, updated_at
                FROM album_provider_links
                WHERE album_id = ?
                ORDER BY provider_id, provider_title
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AlbumProviderLink> links = new ArrayList<>();
                while (rs.next()) {
                    String providerReleaseDate = rs.getString("provider_release_date");
                    String resolution = rs.getString("release_date_resolution");
                    links.add(new AlbumProviderLink(
                            rs.getLong("id"),
                            rs.getLong("album_id"),
                            rs.getString("provider_id"),
                            rs.getString("provider_release_group_id"),
                            rs.getString("provider_title"),
                            providerReleaseDate,
                            rs.getString("provider_url"),
                            resolution,
                            resolution == null && releaseDateConflict(localReleaseDate, providerReleaseDate),
                            rs.getString("created_at"),
                            rs.getString("updated_at")));
                }
                return links;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list album provider links", e);
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

    private static boolean releaseDateConflict(String localReleaseDate, String providerReleaseDate) {
        String localYear = releaseYear(localReleaseDate);
        String providerYear = releaseYear(providerReleaseDate);
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static String releaseYear(String releaseDate) {
        String normalized = blankToNull(releaseDate);
        if (normalized == null || normalized.length() < 4) {
            return null;
        }
        return normalized.substring(0, 4);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }
}
