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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.TitleSortNames;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumCollection;
import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.MetadataSource;
import org.kroky.musiclib.model.ReleaseYears;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.provider.ProviderTitles;
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

    public List<Album> list(Long artistId, Long collectionId, Boolean checked, Boolean hasLocalPath, String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT a.id
                FROM albums a
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        if (artistId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM album_artists aa_filter WHERE aa_filter.album_id=a.id AND aa_filter.artist_id=?)");
            parameters.add(artistId);
        }
        if (collectionId != null) {
            sql.append(" AND a.collection_id = ?");
            parameters.add(collectionId);
        }
        if (checked != null) {
            sql.append(" AND a.checked = ?");
            parameters.add(checked ? 1 : 0);
        }
        if (hasLocalPath != null) {
            sql.append(hasLocalPath ? " AND a.local_relative_path IS NOT NULL" : " AND a.local_relative_path IS NULL");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (a.normalized_title LIKE ? OR EXISTS (SELECT 1 FROM album_artists sax JOIN artists sar ON sar.id = sax.artist_id WHERE sax.album_id = a.id AND sar.normalized_name LIKE ?))");
            String term = "%" + Names.normalize(search) + "%";
            parameters.add(term);
            parameters.add(term);
        }
        sql.append(" ORDER BY a.release_year, a.title, a.id");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParameters(statement, parameters);
            List<Album> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(mapAlbum(connection, rs.getLong(1)));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list albums", e);
        }
    }

    public Optional<Album> find(long id) {
        try (Connection connection = dataSource.getConnection()) {
            return exists(connection, id) ? Optional.of(mapAlbum(connection, id)) : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album " + id, e);
        }
    }

    public Album create(long artistId, String title, Integer releaseYear, boolean checked, String notes,
            long collectionId) {
        return create(List.of(artistId), title, releaseYear, checked, notes, collectionId);
    }

    public Album create(List<Long> artistIds, String title, Integer releaseYear, boolean checked, String notes,
            long collectionId) {
        ReleaseYears.normalize(releaseYear);
        try (Connection connection = dataSource.getConnection()) {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) connection.setAutoCommit(false);
            try {
                long id = insertAlbum(connection, collectionId, title, releaseYear, checked, notes, null, null,
                        MetadataSource.AUTO);
                replaceArtists(connection, id, artistIds);
                if (ownsTransaction) connection.commit();
                return mapAlbum(connection, id);
            } catch (Exception e) {
                if (ownsTransaction) rollbackQuietly(connection);
                throw e;
            } finally {
                if (ownsTransaction) connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create album " + title, e);
        }
    }

    public Optional<Album> update(long id, String title, Integer releaseYear, boolean checked, String notes) {
        ReleaseYears.normalize(releaseYear);
        String sortName = TitleSortNames.create(title, releaseYear);
        String sql = """
                UPDATE albums
                SET title = ?, normalized_title = ?, release_year = ?,
                    sort_name = CASE WHEN sort_name_source = 'MANUAL' THEN sort_name ELSE ? END,
                    normalized_sort_name = CASE WHEN sort_name_source = 'MANUAL' THEN normalized_sort_name ELSE ? END,
                    checked = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireText(title, "Album title"));
            statement.setString(2, Names.normalize(title));
            setInteger(statement, 3, releaseYear);
            statement.setString(4, sortName);
            statement.setString(5, Names.normalize(sortName));
            statement.setInt(6, checked ? 1 : 0);
            statement.setString(7, blankToNull(notes));
            statement.setLong(8, id);
            return statement.executeUpdate() == 0 ? Optional.empty() : Optional.of(mapAlbum(connection, id));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album " + id, e);
        }
    }

    public Optional<Album> updateReleaseYearIfMissing(long id, Integer releaseYear) {
        ReleaseYears.normalize(releaseYear);
        if (releaseYear == null) return find(id);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("UPDATE albums SET release_year = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND release_year IS NULL")) {
            statement.setInt(1, releaseYear);
            statement.setLong(2, id);
            statement.executeUpdate();
            return exists(connection, id) ? Optional.of(mapAlbum(connection, id)) : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fill album release year " + id, e);
        }
    }

    public UpsertResult upsertScanned(long artistId, String title, Integer releaseYear, String relativePath,
            long collectionId) {
        return upsertScanned(List.of(artistId), title, releaseYear, null, MetadataSource.AUTO, relativePath,
                collectionId);
    }

    public UpsertResult upsertScanned(List<Long> artistIds, String title, Integer releaseYear, String relativePath,
            long collectionId) {
        return upsertScanned(artistIds, title, releaseYear, null, MetadataSource.AUTO, relativePath, collectionId);
    }

    public UpsertResult upsertScanned(List<Long> artistIds, String title, Integer releaseYear, String sortName,
            MetadataSource sortNameSource, String relativePath, long collectionId) {
        return upsertPhysical(artistIds, title, releaseYear, sortName, sortNameSource, relativePath, collectionId);
    }

    public UpsertResult upsertTitleScanned(List<Long> artistIds, String title, Integer releaseYear, String sortName,
            String relativePath, long collectionId) {
        return upsertPhysical(artistIds, title, releaseYear, sortName, MetadataSource.AUTO, relativePath, collectionId);
    }

    private UpsertResult upsertPhysical(List<Long> artistIds, String title, Integer releaseYear, String sortName,
            MetadataSource sortNameSource, String relativePath, long collectionId) {
        String path = requireText(relativePath, "Local relative path");
        try (Connection connection = dataSource.getConnection()) {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) connection.setAutoCommit(false);
            try {
                Long id = findByPath(connection, collectionId, path);
                boolean created = false;
                if (id == null) {
                    id = findCompatibleAlbum(connection, artistIds, title, releaseYear);
                    if (id != null) {
                        AlbumHome home = home(connection, id);
                        if (home.localRelativePath() != null
                                && isOnDisk(home.collectionRelativePath(), home.localRelativePath())) {
                            throw new IllegalStateException("Album already has a physical folder: " + home.localRelativePath());
                        }
                        if (home.type() != collectionType(connection, collectionId)) {
                            id = null;
                        } else {
                            setHome(connection, id, collectionId, path);
                        }
                    }
                    if (id == null) {
                        id = insertAlbum(connection, collectionId, title, releaseYear, true, null, path, sortName,
                                sortNameSource);
                        created = true;
                    }
                }
                updateMetadata(connection, id, title, releaseYear, sortName, sortNameSource, true, null);
                replaceArtists(connection, id, artistIds);
                if (ownsTransaction) connection.commit();
                return new UpsertResult(id, created);
            } catch (Exception e) {
                if (ownsTransaction) rollbackQuietly(connection);
                throw e;
            } finally {
                if (ownsTransaction) connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to scan album " + title, e);
        }
    }

    public Map<String, LocalPathSnapshot> localPathSnapshot(long collectionId) {
        String sql = """
                SELECT a.id, a.local_relative_path
                FROM albums a
                WHERE a.collection_id = ? AND a.local_relative_path IS NOT NULL
                ORDER BY a.local_relative_path
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, collectionId);
            Map<String, LocalPathSnapshot> result = new LinkedHashMap<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long albumId = rs.getLong("id");
                    String path = rs.getString("local_relative_path");
                    result.put(path, new LocalPathSnapshot(albumId, path, artistIds(connection, albumId)));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load local paths for collection " + collectionId, e);
        }
    }

    public int removeUnseenLocalPaths(long collectionId, Set<String> seenPaths) {
        Set<String> seen = seenPaths == null ? Set.of() : seenPaths;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement("SELECT id, local_relative_path FROM albums WHERE collection_id = ? AND local_relative_path IS NOT NULL")) {
            select.setLong(1, collectionId);
            List<Long> stale = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) if (!seen.contains(rs.getString("local_relative_path"))) stale.add(rs.getLong("id"));
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE albums SET local_relative_path = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                for (long id : stale) { update.setLong(1, id); update.addBatch(); }
                update.executeBatch();
            }
            return stale.size();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clear unseen album paths", e);
        }
    }

    public int removeStaleLocalPathsForArtist(long artistId) {
        return clearStalePaths(artistId);
    }

    public int countOnDiskLocalAlbumsForArtist(Long collectionId, long artistId) {
        return (int) list(artistId, collectionId, null, true, null).stream().filter(Album::onDisk).count();
    }

    public Long majorArtistCollection(long artistId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.id
                        FROM albums a
                        JOIN collections c ON c.id = a.collection_id AND c.type = 'ARTIST'
                        JOIN album_artists aa ON aa.album_id = a.id
                        WHERE aa.artist_id = ?
                        GROUP BY c.id, c.name
                        ORDER BY COUNT(*) DESC, lower(c.name), c.id
                        LIMIT 1
                        """)) {
            statement.setLong(1, artistId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to choose provider album collection", e);
        }
    }

    public Album reassignCollection(long albumId, long collectionId) {
        try (Connection connection = dataSource.getConnection()) {
            AlbumHome current = home(connection, albumId);
            if (current.localRelativePath() != null) {
                throw new IllegalArgumentException("A local album cannot be reassigned to another collection.");
            }
            if (current.type() != collectionType(connection, collectionId)) {
                throw new IllegalArgumentException("Album collection type cannot change.");
            }
            setHome(connection, albumId, collectionId, null);
            return mapAlbum(connection, albumId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to reassign album " + albumId, e);
        }
    }

    public void updateLocalRelativePath(long albumId, String relativePath) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("UPDATE albums SET local_relative_path=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setString(1, blankToNull(relativePath));
            statement.setLong(2, albumId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown album: " + albumId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album local path", e);
        }
    }

    public int mergeProviderOnlyDuplicates(long keepAlbumId, long artistId, String title, Integer releaseYear) {
        try (Connection connection = dataSource.getConnection()) {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) connection.setAutoCommit(false);
            try {
                List<Long> duplicates = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT a.id, a.title, a.release_year
                        FROM albums a JOIN album_artists aa ON aa.album_id = a.id
                        WHERE aa.artist_id = ? AND a.id <> ? AND a.local_relative_path IS NULL
                        """)) {
                    statement.setLong(1, artistId);
                    statement.setLong(2, keepAlbumId);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            if (providerOnlyDuplicateMatch(title, releaseYear, rs.getString("title"), nullableInteger(rs, "release_year"))) {
                                duplicates.add(rs.getLong("id"));
                            }
                        }
                    }
                }
                for (long duplicateId : duplicates) {
                    transferProviderLinks(connection, keepAlbumId, duplicateId);
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM albums WHERE id = ?")) {
                        delete.setLong(1, duplicateId);
                        delete.executeUpdate();
                    }
                }
                if (ownsTransaction) connection.commit();
                return duplicates.size();
            } catch (Exception e) {
                if (ownsTransaction) rollbackQuietly(connection);
                throw e;
            } finally {
                if (ownsTransaction) connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to merge provider-only duplicate albums", e);
        }
    }

    private int clearStalePaths(long artistId) {
        String sql = """
                SELECT DISTINCT a.id, c.relative_path, a.local_relative_path
                FROM albums a
                JOIN collections c ON c.id = a.collection_id
                JOIN album_artists aa ON aa.album_id = a.id
                WHERE a.local_relative_path IS NOT NULL
                  AND aa.artist_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            List<Long> stale = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (!isOnDisk(rs.getString("relative_path"), rs.getString("local_relative_path"))) stale.add(rs.getLong("id"));
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE albums SET local_relative_path = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                for (long id : stale) { update.setLong(1, id); update.addBatch(); }
                update.executeBatch();
            }
            return stale.size();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clear stale album paths", e);
        }
    }

    private Album mapAlbum(Connection connection, long albumId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.*, c.name AS collection_name, c.relative_path AS collection_relative_path,
                       (SELECT ar.name FROM album_artists aa JOIN artists ar ON ar.id = aa.artist_id WHERE aa.album_id = a.id ORDER BY aa.position, ar.id LIMIT 1) AS artist_name
                FROM albums a JOIN collections c ON c.id = a.collection_id WHERE a.id = ?
                """)) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown album: " + albumId);
                String localPath = rs.getString("local_relative_path");
                String resolved = localPath == null ? null : resolvedPath(rs.getString("collection_relative_path"), localPath);
                String title = rs.getString("title");
                Integer year = nullableInteger(rs, "release_year");
                return new Album(
                        albumId,
                        artistIds(connection, albumId),
                        new AlbumCollection(rs.getLong("collection_id"), rs.getString("collection_name")),
                        rs.getString("artist_name"),
                        title,
                        year,
                        rs.getString("sort_name"),
                        MetadataSource.valueOf(rs.getString("sort_name_source")),
                        rs.getInt("checked") == 1,
                        localPath,
                        resolved,
                        resolved != null && Files.isDirectory(Path.of(resolved)),
                        providerLinks(connection, albumId, title, year),
                        rs.getString("notes"),
                        rs.getString("created_at"),
                        rs.getString("updated_at"));
            }
        }
    }

    private List<AlbumProviderLink> providerLinks(Connection connection, long albumId, String localTitle,
            Integer localYear) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, album_id, provider_id, provider_release_group_id, provider_title,
                       provider_release_year, provider_url, release_year_resolution, title_resolution,
                       created_at, updated_at
                FROM album_provider_links WHERE album_id = ? ORDER BY provider_id, provider_title
                """)) {
            statement.setLong(1, albumId);
            List<AlbumProviderLink> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Integer providerYear = nullableInteger(rs, "provider_release_year");
                    String yearResolution = rs.getString("release_year_resolution");
                    String titleResolution = rs.getString("title_resolution");
                    String providerTitle = rs.getString("provider_title");
                    result.add(new AlbumProviderLink(
                            rs.getLong("id"), albumId, rs.getString("provider_id"),
                            rs.getString("provider_release_group_id"), providerTitle, providerYear,
                            rs.getString("provider_url"), yearResolution, titleResolution,
                            yearResolution == null && localYear != null && providerYear != null && !localYear.equals(providerYear),
                            titleResolution == null && ProviderTitles.titleConflict(localTitle, providerTitle),
                            rs.getString("created_at"), rs.getString("updated_at")));
                }
            }
            return result;
        }
    }

    private long insertAlbum(Connection connection, long collectionId, String title, Integer releaseYear,
            boolean checked, String notes, String localPath, String requestedSortName, MetadataSource source) throws Exception {
        String normalizedTitle = Names.normalize(requireText(title, "Album title"));
        String sortName = source == MetadataSource.MANUAL && requestedSortName != null && !requestedSortName.isBlank()
                ? requestedSortName.trim() : TitleSortNames.create(title, releaseYear);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO albums (collection_id, title, normalized_title, local_relative_path, release_year,
                                    sort_name, normalized_sort_name, sort_name_source, checked, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, collectionId);
            statement.setString(2, title.trim());
            statement.setString(3, normalizedTitle);
            statement.setString(4, localPath);
            setInteger(statement, 5, releaseYear);
            statement.setString(6, sortName);
            statement.setString(7, Names.normalize(sortName));
            statement.setString(8, source.name());
            statement.setInt(9, checked ? 1 : 0);
            statement.setString(10, blankToNull(notes));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new IllegalStateException("Album insert returned no id");
        }
    }

    private void updateMetadata(Connection connection, long id, String title, Integer releaseYear, String sortName,
            MetadataSource source, boolean checked, String notes) throws Exception {
        ReleaseYears.normalize(releaseYear);
        MetadataSource effectiveSource = source == null ? MetadataSource.AUTO : source;
        String effectiveSort = effectiveSource == MetadataSource.MANUAL && sortName != null && !sortName.isBlank()
                ? sortName.trim() : TitleSortNames.create(title, releaseYear);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE albums SET title=?, normalized_title=?, release_year=?, sort_name=?, normalized_sort_name=?,
                                  sort_name_source=?, checked=?, notes=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
                """)) {
            statement.setString(1, requireText(title, "Album title"));
            statement.setString(2, Names.normalize(title));
            setInteger(statement, 3, releaseYear);
            statement.setString(4, effectiveSort);
            statement.setString(5, Names.normalize(effectiveSort));
            statement.setString(6, effectiveSource.name());
            statement.setInt(7, checked ? 1 : 0);
            statement.setString(8, blankToNull(notes));
            statement.setLong(9, id);
            statement.executeUpdate();
        }
    }

    private Long findCompatibleAlbum(Connection connection, List<Long> artistIds, String title, Integer releaseYear)
            throws Exception {
        if (artistIds == null || artistIds.isEmpty()) return null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.release_year
                FROM albums a JOIN album_artists aa ON aa.album_id = a.id
                WHERE aa.artist_id = ? AND a.normalized_title = ?
                ORDER BY CASE WHEN a.release_year = ? THEN 0 WHEN a.release_year IS NULL OR ? IS NULL THEN 1 ELSE 2 END, a.id
                """)) {
            statement.setLong(1, artistIds.get(0));
            statement.setString(2, Names.normalize(title));
            setInteger(statement, 3, releaseYear);
            setInteger(statement, 4, releaseYear);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Integer candidate = nullableInteger(rs, "release_year");
                    if (candidate == null || releaseYear == null || Math.abs(candidate - releaseYear) <= 1) return rs.getLong("id");
                }
            }
            return null;
        }
    }

    private static boolean providerOnlyDuplicateMatch(String leftTitle, Integer leftYear, String rightTitle,
            Integer rightYear) {
        var match = ProviderTitles.titleMatch(leftTitle, rightTitle);
        boolean strong = ProviderTitles.MATCH_EXACT.equals(match.type())
                || ProviderTitles.MATCH_NORMALIZED.equals(match.type())
                || ProviderTitles.MATCH_FUZZY.equals(match.type()) && match.score() >= ProviderTitles.FUZZY_HIGH_CONFIDENCE_THRESHOLD;
        return strong && (leftYear == null || rightYear == null || Math.abs(leftYear - rightYear) <= 1);
    }

    private void transferProviderLinks(Connection connection, long keepId, long duplicateId) throws Exception {
        try (PreparedStatement update = connection.prepareStatement("UPDATE OR IGNORE album_provider_links SET album_id=?, updated_at=CURRENT_TIMESTAMP WHERE album_id=?");
                PreparedStatement delete = connection.prepareStatement("DELETE FROM album_provider_links WHERE album_id=?")) {
            update.setLong(1, keepId); update.setLong(2, duplicateId); update.executeUpdate();
            delete.setLong(1, duplicateId); delete.executeUpdate();
        }
    }

    private void replaceArtists(Connection connection, long albumId, List<Long> artistIds) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM album_artists WHERE album_id=?");
                PreparedStatement insert = connection.prepareStatement("INSERT INTO album_artists (album_id, artist_id, position) VALUES (?, ?, ?)")) {
            delete.setLong(1, albumId); delete.executeUpdate();
            int position = 0;
            for (long artistId : new java.util.LinkedHashSet<>(artistIds == null ? List.<Long>of() : artistIds)) {
                insert.setLong(1, albumId); insert.setLong(2, artistId); insert.setInt(3, position++); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<Long> artistIds(Connection connection, long albumId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT artist_id FROM album_artists WHERE album_id=? ORDER BY position, artist_id")) {
            statement.setLong(1, albumId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
            return ids;
        }
    }

    private AlbumHome home(Connection connection, long albumId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT a.collection_id,a.local_relative_path,c.relative_path,c.type FROM albums a JOIN collections c ON c.id=a.collection_id WHERE a.id=?")) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown album: " + albumId);
                return new AlbumHome(rs.getLong(1), rs.getString(2), rs.getString(3), CollectionType.valueOf(rs.getString(4)));
            }
        }
    }

    private CollectionType collectionType(Connection connection, long collectionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT type FROM collections WHERE id=?")) {
            statement.setLong(1, collectionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown collection: " + collectionId);
                return CollectionType.valueOf(rs.getString(1));
            }
        }
    }

    private void setHome(Connection connection, long albumId, long collectionId, String localPath) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE albums SET collection_id=?, local_relative_path=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setLong(1, collectionId); statement.setString(2, localPath); statement.setLong(3, albumId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown album: " + albumId);
        }
    }

    private Long findByPath(Connection connection, long collectionId, String path) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM albums WHERE collection_id=? AND local_relative_path=?")) {
            statement.setLong(1, collectionId); statement.setString(2, path);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }

    private boolean exists(Connection connection, long albumId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM albums WHERE id=?")) {
            statement.setLong(1, albumId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        }
    }

    private boolean isOnDisk(String collectionPath, String albumPath) {
        String resolved = resolvedPath(collectionPath, albumPath);
        return resolved != null && Files.isDirectory(Path.of(resolved));
    }

    private String resolvedPath(String collectionPath, String albumPath) {
        try {
            return musicRootService.resolveAlbum(collectionPath, albumPath).toString();
        } catch (Exception e) {
            LOG.debugf("Cannot resolve album path collection=%s album=%s: %s", collectionPath, albumPath, e.getMessage());
            return null;
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.trim();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void rollbackQuietly(Connection connection) { try { connection.rollback(); } catch (Exception ignored) { } }
    private static void setInteger(PreparedStatement statement, int index, Integer value) throws Exception {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER); else statement.setInt(index, value);
    }
    private static Integer nullableInteger(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static void setParameters(PreparedStatement statement, List<Object> values) throws Exception {
        for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
    }

    public record LocalPathSnapshot(long albumId, String relativePath, List<Long> artistIds) { }
    private record AlbumHome(long collectionId, String localRelativePath, String collectionRelativePath, CollectionType type) { }
}
