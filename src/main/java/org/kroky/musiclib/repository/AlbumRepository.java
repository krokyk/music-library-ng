package org.kroky.musiclib.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.kroky.musiclib.provider.ProviderTitles;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumRepository {

    private static final Logger LOG = Logger.getLogger(AlbumRepository.class);
    private static final Set<String> GENERIC_TITLES = Set.of(
            "Greatest Hits",
            "Best Of",
            "Live",
            "Anthology",
            "Collection",
            "The Collection",
            "Essential",
            "The Essential").stream()
            .map(Names::normalize)
            .collect(Collectors.toUnmodifiableSet());

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
            List<AlbumRow> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(AlbumRow.from(rs));
                }
            }
            List<Album> albums = new ArrayList<>();
            for (AlbumRow row : rows) {
                albums.add(mapAlbum(connection, row));
            }
            return albums;
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
            AlbumRow row = null;
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    row = AlbumRow.from(rs);
                }
            }
            return row == null ? Optional.empty() : Optional.of(mapAlbum(connection, row));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album " + id, e);
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
                        Optional<Long> fuzzyDuplicateAlbumId = normalizedArtistIds.isEmpty()
                                ? Optional.empty()
                                : findScannedFuzzyDuplicateId(connection, normalizedArtistIds, title, releaseDate);
                        if (fuzzyDuplicateAlbumId.isPresent()) {
                            albumId = fuzzyDuplicateAlbumId.get();
                            updateScannedReleaseDate(connection, albumId, releaseDate);
                            markScannedAlbumChecked(connection, albumId);
                        } else {
                            albumId = insertAlbum(connection, title, releaseDate, sortName, true, null);
                            created = true;
                        }
                    }
                }
                if (!normalizedArtistIds.isEmpty()) {
                    replaceArtists(connection, albumId, normalizedArtistIds);
                }
                assignToCollection(connection, albumId, collectionId);
                upsertLocalPath(connection, albumId, collectionId, relativePath);
                for (long artistId : normalizedArtistIds) {
                    mergeProviderOnlyDuplicates(connection, albumId, artistId, title, releaseDate);
                }
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

    public Map<String, LocalPathSnapshot> localPathSnapshot(String collectionId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return Map.of();
        }
        String sql = """
                SELECT lp.album_id, lp.relative_path, aa.artist_id
                FROM album_local_paths lp
                LEFT JOIN album_artists aa ON aa.album_id = lp.album_id
                WHERE lp.collection_id = ?
                ORDER BY lp.relative_path, aa.position
                """;
        Map<String, MutableLocalPathSnapshot> snapshots = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String relativePath = rs.getString("relative_path");
                    MutableLocalPathSnapshot snapshot = snapshots.get(relativePath);
                    if (snapshot == null) {
                        snapshot = new MutableLocalPathSnapshot(rs.getLong("album_id"), relativePath);
                        snapshots.put(relativePath, snapshot);
                    }
                    long artistId = rs.getLong("artist_id");
                    if (!rs.wasNull()) {
                        snapshot.artistIds().add(artistId);
                    }
                }
            }
            Map<String, LocalPathSnapshot> result = new LinkedHashMap<>();
            for (var entry : snapshots.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toSnapshot());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load local path snapshot for collection " + collectionId, e);
        }
    }

    public int mergeProviderOnlyDuplicates(long keepAlbumId, long artistId, String title, String releaseDate) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int merged = mergeProviderOnlyDuplicates(connection, keepAlbumId, artistId, title, releaseDate);
                connection.commit();
                return merged;
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

    public boolean removeFromCollection(String collectionId, long albumId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return false;
        }
        String sql = """
                DELETE FROM collection_albums
                WHERE collection_id = ? AND album_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            statement.setLong(2, albumId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove album " + albumId
                    + " from collection " + collectionId, e);
        }
    }

    public int removeStaleLocalPaths(String collectionId, long albumId) {
        String normalizedCollectionId = blankToNull(collectionId);
        return normalizedCollectionId == null ? 0 : removeStaleLocalPaths(normalizedCollectionId, albumId, null);
    }

    public int removeStaleLocalPaths(long albumId) {
        return removeStaleLocalPaths(null, albumId, null);
    }

    public int removeStaleLocalPathsForArtist(String collectionId, long artistId) {
        String normalizedCollectionId = blankToNull(collectionId);
        return normalizedCollectionId == null ? 0 : removeStaleLocalPaths(normalizedCollectionId, null, artistId);
    }

    public int removeStaleLocalPathsForArtist(long artistId) {
        return removeStaleLocalPaths(null, null, artistId);
    }

    public boolean hasOnDiskLocalPath(String collectionId, long albumId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return false;
        }
        String sql = """
                SELECT c.relative_path AS collection_relative_path, lp.relative_path
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE lp.collection_id = ? AND lp.album_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            statement.setLong(2, albumId);
            List<LocalPathProbe> paths = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    paths.add(new LocalPathProbe(null,
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path")));
                }
            }
            return paths.stream().anyMatch(path -> isOnDiskPath(
                    path.collectionRelativePath(),
                    path.albumRelativePath()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to check album local paths", e);
        }
    }

    public boolean hasOnDiskLocalPathForArtist(String collectionId, long artistId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return false;
        }
        String sql = """
                SELECT c.relative_path AS collection_relative_path, lp.relative_path
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE lp.collection_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM album_artists aa
                      WHERE aa.album_id = lp.album_id
                        AND aa.artist_id = ?
                  )
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            statement.setLong(2, artistId);
            List<LocalPathProbe> paths = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    paths.add(new LocalPathProbe(null,
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path")));
                }
            }
            return paths.stream().anyMatch(path -> isOnDiskPath(
                    path.collectionRelativePath(),
                    path.albumRelativePath()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to check artist local paths", e);
        }
    }

    public int countOnDiskLocalAlbumsForArtist(String collectionId, long artistId) {
        String sql = """
                SELECT lp.album_id, c.relative_path AS collection_relative_path, lp.relative_path
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE (? IS NULL OR lp.collection_id = ?)
                  AND EXISTS (
                      SELECT 1
                      FROM album_artists aa
                      WHERE aa.album_id = lp.album_id
                        AND aa.artist_id = ?
                  )
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedCollectionId = blankToNull(collectionId);
            statement.setString(1, normalizedCollectionId);
            statement.setString(2, normalizedCollectionId);
            statement.setLong(3, artistId);
            List<LocalPathProbe> paths = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    paths.add(new LocalPathProbe(
                            rs.getLong("album_id"),
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path")));
                }
            }
            Set<Long> albumIds = new HashSet<>();
            for (LocalPathProbe path : paths) {
                if (isOnDiskPath(path.collectionRelativePath(), path.albumRelativePath())) {
                    albumIds.add(path.albumId());
                }
            }
            return albumIds.size();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to count artist local albums", e);
        }
    }

    private int removeStaleLocalPaths(String collectionId, Long albumId, Long artistId) {
        if (collectionId == null && albumId == null && artistId == null) {
            return 0;
        }
        String select = """
                SELECT lp.id, c.relative_path AS collection_relative_path, lp.relative_path
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE (? IS NULL OR lp.collection_id = ?)
                  AND (? IS NULL OR lp.album_id = ?)
                  AND (? IS NULL OR EXISTS (
                      SELECT 1
                      FROM album_artists aa
                      WHERE aa.album_id = lp.album_id
                        AND aa.artist_id = ?
                  ))
                """;
        String delete = "DELETE FROM album_local_paths WHERE id = ?";
        int removed = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement selectStatement = connection.prepareStatement(select)) {
            selectStatement.setString(1, collectionId);
            selectStatement.setString(2, collectionId);
            setNullableLong(selectStatement, 3, albumId);
            setNullableLong(selectStatement, 4, albumId);
            setNullableLong(selectStatement, 5, artistId);
            setNullableLong(selectStatement, 6, artistId);
            List<StaleLocalPathCandidate> candidates = new ArrayList<>();
            try (ResultSet rs = selectStatement.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new StaleLocalPathCandidate(
                            rs.getLong("id"),
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path")));
                }
            }
            List<Long> staleIds = new ArrayList<>();
            for (StaleLocalPathCandidate candidate : candidates) {
                if (!isOnDiskPath(candidate.collectionRelativePath(), candidate.albumRelativePath())) {
                    staleIds.add(candidate.id());
                }
            }
            if (staleIds.isEmpty()) {
                return 0;
            }
            try (PreparedStatement deleteStatement = connection.prepareStatement(delete)) {
                for (long staleId : staleIds) {
                    deleteStatement.setLong(1, staleId);
                    removed += deleteStatement.executeUpdate();
                }
            }
            return removed;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove stale local paths", e);
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

    private void updateScannedReleaseDate(Connection connection, long albumId, String releaseDate)
            throws Exception {
        String normalizedReleaseDate = blankToNull(releaseDate);
        if (normalizedReleaseDate == null) {
            return;
        }
        String selectSql = """
                SELECT title, release_date, sort_name_source
                FROM albums
                WHERE id = ?
        """;
        String title = null;
        String currentReleaseDate = null;
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    title = rs.getString("title");
                    currentReleaseDate = rs.getString("release_date");
                }
            }
        }
        if (title == null || normalizedReleaseDate.equals(currentReleaseDate)) {
            return;
        }
        String autoSortName = TitleSortNames.create(title, normalizedReleaseDate);
        String updateSql = """
                UPDATE albums
                SET release_date = ?,
                    sort_name = CASE
                        WHEN sort_name_source = 'MANUAL' THEN sort_name
                        ELSE ?
                    END,
                    normalized_sort_name = CASE
                        WHEN sort_name_source = 'MANUAL' THEN normalized_sort_name
                        ELSE ?
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, normalizedReleaseDate);
            statement.setString(2, autoSortName);
            statement.setString(3, Names.normalize(autoSortName));
            statement.setLong(4, albumId);
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

    private Optional<Long> findScannedFuzzyDuplicateId(Connection connection, List<Long> artistIds, String title,
            String releaseDate) throws Exception {
        ScannedAlbumMatch best = null;
        for (long artistId : artistIds) {
            Optional<ScannedAlbumMatch> match = findScannedFuzzyDuplicateId(connection, artistId, title, releaseDate);
            if (match.isPresent() && (best == null || match.get().compareTo(best) < 0)) {
                best = match.get();
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.albumId());
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

    private Optional<ScannedAlbumMatch> findScannedFuzzyDuplicateId(Connection connection, long artistId, String title,
            String releaseDate) throws Exception {
        String sql = """
                SELECT a.id, a.title,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM album_provider_links apl
                           WHERE apl.album_id = a.id
                       ) THEN 0 ELSE 1 END AS provider_rank
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE aa.artist_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM album_local_paths lp
                      WHERE lp.album_id = a.id
                  )
                  AND (
                      a.checked = 1
                      OR EXISTS (
                          SELECT 1 FROM album_provider_links apl
                          WHERE apl.album_id = a.id
                      )
                  )
                  AND %s
                ORDER BY provider_rank, a.id
        """.formatted(fuzzyCompatibleReleaseDatePredicate());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            setCompatibleReleaseDatePredicateParameters(statement, 2, releaseDate);
            try (ResultSet rs = statement.executeQuery()) {
                ScannedAlbumMatch best = null;
                while (rs.next()) {
                    var match = ProviderTitles.titleMatch(title, rs.getString("title"));
                    boolean accepted = ProviderTitles.MATCH_EXACT.equals(match.type())
                            || ProviderTitles.MATCH_NORMALIZED.equals(match.type())
                            || (ProviderTitles.MATCH_FUZZY.equals(match.type())
                                    && match.score() >= ProviderTitles.FUZZY_HIGH_CONFIDENCE_THRESHOLD);
                    if (!accepted) {
                        continue;
                    }
                    ScannedAlbumMatch candidate = new ScannedAlbumMatch(
                            rs.getLong("id"),
                            match.score(),
                            rs.getInt("provider_rank"));
                    if (best == null || candidate.compareTo(best) < 0) {
                        best = candidate;
                    }
                }
                return Optional.ofNullable(best);
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

    private static String fuzzyCompatibleReleaseDatePredicate() {
        return """
                (
                    coalesce(a.release_date, '') = coalesce(?, '')
                    OR a.release_date IS NULL
                    OR ? IS NULL
                    OR (
                        length(a.release_date) >= 4
                        AND length(?) >= 4
                        AND abs(
                            CAST(substr(a.release_date, 1, 4) AS INTEGER)
                            - CAST(substr(?, 1, 4) AS INTEGER)
                        ) <= 1
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

    private static void setCompatibleReleaseDatePredicateParameters(PreparedStatement statement, int startIndex,
            String releaseDate) throws Exception {
        String normalizedReleaseDate = blankToNull(releaseDate);
        for (int i = 0; i < 4; i++) {
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
                SELECT a.id, a.title, a.release_date
                FROM albums a
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE a.id <> ?
                  AND aa.artist_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM album_local_paths lp
                      WHERE lp.album_id = a.id
                  )
                  AND EXISTS (
                      SELECT 1 FROM album_provider_links apl
                      WHERE apl.album_id = a.id
                  )
                ORDER BY a.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, keepAlbumId);
            statement.setLong(2, artistId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    if (providerOnlyDuplicateMatch(
                            title,
                            releaseDate,
                            rs.getString("title"),
                            rs.getString("release_date"))) {
                        ids.add(rs.getLong("id"));
                    }
                }
                return ids;
            }
        }
    }

    private int mergeProviderOnlyDuplicates(Connection connection, long keepAlbumId, long artistId, String title,
            String releaseDate) throws Exception {
        List<Long> duplicates = providerOnlyDuplicateIds(connection, keepAlbumId, artistId, title, releaseDate);
        for (long duplicateId : duplicates) {
            transferCollectionMemberships(connection, keepAlbumId, duplicateId);
            transferProviderLinks(connection, keepAlbumId, duplicateId);
            deleteAlbum(connection, duplicateId);
        }
        return duplicates.size();
    }

    private static boolean providerOnlyDuplicateMatch(String keepTitle, String keepReleaseDate,
            String candidateTitle, String candidateReleaseDate) {
        var match = ProviderTitles.titleMatch(keepTitle, candidateTitle);
        if (!strongProviderDuplicateTitleMatch(match.type(), match.score())) {
            return false;
        }
        if (genericTitle(keepTitle) || genericTitle(candidateTitle)) {
            return knownReleaseYearsEqual(keepReleaseDate, candidateReleaseDate);
        }
        return releaseYearsCompatible(keepReleaseDate, candidateReleaseDate);
    }

    private static boolean strongProviderDuplicateTitleMatch(String matchType, int titleScore) {
        return ProviderTitles.MATCH_EXACT.equals(matchType)
                || ProviderTitles.MATCH_NORMALIZED.equals(matchType)
                || (ProviderTitles.MATCH_FUZZY.equals(matchType)
                        && titleScore >= ProviderTitles.FUZZY_HIGH_CONFIDENCE_THRESHOLD);
    }

    private static boolean genericTitle(String title) {
        return GENERIC_TITLES.contains(Names.normalize(title));
    }

    private static boolean releaseYearsCompatible(String leftReleaseDate, String rightReleaseDate) {
        Integer leftYear = releaseYearValue(leftReleaseDate);
        Integer rightYear = releaseYearValue(rightReleaseDate);
        return leftYear == null || rightYear == null || Math.abs(leftYear - rightYear) <= 1;
    }

    private static boolean knownReleaseYearsEqual(String leftReleaseDate, String rightReleaseDate) {
        Integer leftYear = releaseYearValue(leftReleaseDate);
        Integer rightYear = releaseYearValue(rightReleaseDate);
        return leftYear != null && leftYear.equals(rightYear);
    }

    private static Integer releaseYearValue(String releaseDate) {
        String year = releaseYear(releaseDate);
        if (year == null) {
            return null;
        }
        try {
            return Integer.parseInt(year);
        } catch (NumberFormatException e) {
            return null;
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

    private Album mapAlbum(Connection connection, AlbumRow row) throws Exception {
        long albumId = row.id();
        List<AlbumLocalPath> localPaths = listPaths(connection, albumId);
        boolean hasLocalPath = !localPaths.isEmpty();
        boolean onDisk = localPaths.stream().anyMatch(AlbumLocalPath::onDisk);
        return new Album(
                albumId,
                parseArtistIds(row.artistIds()),
                listCollections(connection, albumId),
                row.artistName(),
                row.title(),
                row.releaseDate(),
                row.sortName(),
                MetadataSource.valueOf(row.sortNameSource()),
                row.checked(),
                hasLocalPath,
                onDisk,
                localPaths,
                listProviderLinks(connection, albumId, row.title(), row.releaseDate()),
                row.notes(),
                row.createdAt(),
                row.updatedAt());
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
            List<LocalPathRow> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LocalPathRow(
                            rs.getLong("id"),
                            rs.getLong("album_id"),
                            rs.getString("collection_id"),
                            rs.getString("collection_name"),
                            rs.getString("collection_relative_path"),
                            rs.getString("relative_path"),
                            rs.getString("first_seen_at"),
                            rs.getString("last_seen_at")));
                }
            }
            List<AlbumLocalPath> paths = new ArrayList<>();
            for (LocalPathRow row : rows) {
                String resolvedPath = resolvedPath(row.collectionRelativePath(), row.relativePath());
                paths.add(new AlbumLocalPath(
                        row.id(),
                        row.albumId(),
                        row.collectionId(),
                        row.collectionName(),
                        row.relativePath(),
                        resolvedPath,
                        resolvedPath != null && Files.isDirectory(Path.of(resolvedPath)),
                        row.firstSeenAt(),
                        row.lastSeenAt()));
            }
            return paths;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list album local paths", e);
        }
    }

    private List<AlbumProviderLink> listProviderLinks(Connection connection, long albumId, String localTitle,
            String localReleaseDate) {
        String sql = """
                SELECT id, album_id, provider_id, provider_release_group_id,
                       provider_title, provider_release_date, provider_url,
                       release_date_resolution, title_resolution, created_at, updated_at
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
                    String releaseDateResolution = rs.getString("release_date_resolution");
                    String titleResolution = rs.getString("title_resolution");
                    String providerTitle = rs.getString("provider_title");
                    links.add(new AlbumProviderLink(
                            rs.getLong("id"),
                            rs.getLong("album_id"),
                            rs.getString("provider_id"),
                            rs.getString("provider_release_group_id"),
                            providerTitle,
                            providerReleaseDate,
                            rs.getString("provider_url"),
                            releaseDateResolution,
                            titleResolution,
                            releaseDateResolution == null && releaseDateConflict(localReleaseDate, providerReleaseDate),
                            titleResolution == null && ProviderTitles.titleConflict(localTitle, providerTitle),
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

    private boolean isOnDiskPath(String collectionRelativePath, String albumRelativePath) {
        String resolved = resolvedPath(collectionRelativePath, albumRelativePath);
        return resolved != null && Files.isDirectory(Path.of(resolved));
    }

    private record AlbumRow(
            long id,
            String artistIds,
            String artistName,
            String title,
            String releaseDate,
            String sortName,
            String sortNameSource,
            boolean checked,
            String notes,
            String createdAt,
            String updatedAt) {

        static AlbumRow from(ResultSet rs) throws Exception {
            return new AlbumRow(
                    rs.getLong("id"),
                    rs.getString("artist_ids"),
                    rs.getString("artist_name"),
                    rs.getString("title"),
                    rs.getString("release_date"),
                    rs.getString("sort_name"),
                    rs.getString("sort_name_source"),
                    rs.getInt("checked") == 1,
                    rs.getString("notes"),
                    rs.getString("created_at"),
                    rs.getString("updated_at"));
        }
    }

    private record LocalPathProbe(Long albumId, String collectionRelativePath, String albumRelativePath) {
    }

    private record StaleLocalPathCandidate(long id, String collectionRelativePath, String albumRelativePath) {
    }

    public record LocalPathSnapshot(long albumId, String relativePath, List<Long> artistIds) {
    }

    private record MutableLocalPathSnapshot(long albumId, String relativePath, List<Long> artistIds) {
        MutableLocalPathSnapshot(long albumId, String relativePath) {
            this(albumId, relativePath, new ArrayList<>());
        }

        LocalPathSnapshot toSnapshot() {
            return new LocalPathSnapshot(albumId, relativePath, List.copyOf(artistIds));
        }
    }

    private record LocalPathRow(
            long id,
            long albumId,
            String collectionId,
            String collectionName,
            String collectionRelativePath,
            String relativePath,
            String firstSeenAt,
            String lastSeenAt) {
    }

    private record ScannedAlbumMatch(long albumId, int titleScore, int providerRank)
            implements Comparable<ScannedAlbumMatch> {

        @Override
        public int compareTo(ScannedAlbumMatch other) {
            int byScore = Integer.compare(other.titleScore, titleScore);
            if (byScore != 0) {
                return byScore;
            }
            int byProvider = Integer.compare(providerRank, other.providerRank);
            if (byProvider != 0) {
                return byProvider;
            }
            return Long.compare(albumId, other.albumId);
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
