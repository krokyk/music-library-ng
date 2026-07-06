package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.provider.CountryCodes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistRepository {

    private static final Logger LOG = Logger.getLogger(ArtistRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    AlbumRepository albums;

    @Inject
    ArtistProviderLinkRepository providerLinks;

    public List<Artist> list(String search) {
        return list(search, null);
    }

    public List<Artist> list(String search, String collectionId) {
        LOG.debugf("Listing artists search='%s' collectionId=%s", search, collectionId);
        String sql = selectArtists("""
                WHERE (? IS NULL OR a.normalized_name LIKE '%' || ? || '%')
                  AND (? IS NULL OR EXISTS (
                      SELECT 1
                      FROM collection_albums ca_filter
                      JOIN album_artists aa_filter ON aa_filter.album_id = ca_filter.album_id
                      WHERE aa_filter.artist_id = a.id AND ca_filter.collection_id = ?
                  ) OR EXISTS (
                      SELECT 1
                      FROM artist_collections ac_filter
                      WHERE ac_filter.artist_id = a.id
                        AND ac_filter.collection_id = ?
                        AND (ac_filter.local = 1 OR ac_filter.last_local_scan_error_message IS NOT NULL)
                  ))
                """);
        try {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            String normalizedCollectionId = blankToNull(collectionId);
            List<ArtistRow> rows = queryArtistRows(sql, statement -> {
                statement.setString(1, normalizedCollectionId);
                statement.setString(2, normalizedCollectionId);
                statement.setString(3, normalizedCollectionId);
                statement.setString(4, normalizedCollectionId);
                statement.setString(5, normalizedSearch);
                statement.setString(6, normalizedSearch);
                statement.setString(7, normalizedCollectionId);
                statement.setString(8, normalizedCollectionId);
                statement.setString(9, normalizedCollectionId);
            });
            return mapRows(rows, normalizedCollectionId);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list artists", e);
        }
    }

    public Optional<Artist> find(long id) {
        return find(id, null);
    }

    public Optional<Artist> find(long id, String collectionId) {
        LOG.tracef("Finding artist id=%d", id);
        String sql = selectArtists("WHERE a.id = ?");
        try {
            String normalizedCollectionId = blankToNull(collectionId);
            List<ArtistRow> rows = queryArtistRows(sql, statement -> {
                statement.setString(1, normalizedCollectionId);
                statement.setString(2, normalizedCollectionId);
                statement.setString(3, normalizedCollectionId);
                statement.setString(4, normalizedCollectionId);
                statement.setLong(5, id);
            });
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            List<ArtistProviderLink> links = providerLinks.listByArtist(rows.get(0).id());
            return Optional.of(map(rows.get(0), normalizedCollectionId, links));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + id, e);
        }
    }

    public UpsertResult upsertByName(String name) {
        LOG.debugf("Finding or creating artist by name '%s'", name);
        String normalizedName = Names.normalize(name);
        Optional<Long> existing = findIdByNormalizedName(normalizedName);
        if (existing.isPresent()) {
            return new UpsertResult(existing.get(), false);
        }
        return new UpsertResult(create(name, null, null, null).id(), true);
    }

    public Artist create(String name, String sortName, String countryOverride, Boolean activeOverride) {
        LOG.infof("Creating artist '%s'", name);
        String sql = """
                INSERT INTO artists (name, normalized_name, sort_name, country_override, active_override)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, Names.normalize(name));
                statement.setString(3, blankToNull(sortName));
                statement.setString(4, countryCode(countryOverride));
                setNullableBoolean(statement, 5, activeOverride);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        connection.commit();
                        return find(id).orElseThrow();
                    }
                }
                throw new IllegalStateException("Artist insert returned no id");
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create artist " + name, e);
        }
    }

    public Optional<Artist> update(long id, String name, String sortName, String countryOverride,
            Boolean activeOverride) {
        LOG.infof("Updating artist id=%d name='%s'", id, name);
        String sql = """
                UPDATE artists
                SET name = ?,
                    normalized_name = ?,
                    sort_name = ?,
                    country_override = ?,
                    active_override = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, Names.normalize(name));
                statement.setString(3, blankToNull(sortName));
                statement.setString(4, countryCode(countryOverride));
                setNullableBoolean(statement, 5, activeOverride);
                statement.setLong(6, id);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    connection.rollback();
                    return Optional.empty();
                }
                connection.commit();
                return find(id);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update artist " + id, e);
        }
    }

    public void assignToCollection(long artistId, String collectionId) {
        assignToCollection(artistId, collectionId, false);
    }

    public void assignToCollection(long artistId, String collectionId, boolean local) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        String sql = """
                INSERT INTO artist_collections (artist_id, collection_id, local)
                VALUES (?, ?, ?)
                ON CONFLICT(artist_id, collection_id) DO UPDATE SET
                    local = CASE
                        WHEN excluded.local = 1 THEN 1
                        ELSE artist_collections.local
                    END
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, normalizedCollectionId);
            statement.setInt(3, local ? 1 : 0);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to assign artist " + artistId
                    + " to collection " + collectionId, e);
        }
    }

    public void setCollectionLocal(long artistId, String collectionId, boolean local) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        String sql = """
                UPDATE artist_collections
                SET local = ?
                WHERE artist_id = ? AND collection_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, local ? 1 : 0);
            statement.setLong(2, artistId);
            statement.setString(3, normalizedCollectionId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update local state for artist " + artistId
                    + " in collection " + collectionId, e);
        }
    }

    public void replaceLocalArtistsForCollection(String collectionId, Set<Long> localArtistIds) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement clear = connection.prepareStatement("""
                    UPDATE artist_collections
                    SET local = 0
                    WHERE collection_id = ?
                    """);
                    PreparedStatement mark = connection.prepareStatement("""
                            UPDATE artist_collections
                            SET local = 1
                            WHERE artist_id = ? AND collection_id = ?
                            """)) {
                clear.setString(1, normalizedCollectionId);
                clear.executeUpdate();
                for (Long artistId : localArtistIds) {
                    if (artistId == null) {
                        continue;
                    }
                    mark.setLong(1, artistId);
                    mark.setString(2, normalizedCollectionId);
                    mark.addBatch();
                }
                mark.executeBatch();
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to replace local artists for collection " + collectionId, e);
        }
    }

    public void delete(long id) {
        LOG.infof("Deleting artist id=%d", id);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteExclusiveAlbums = connection.prepareStatement("""
                        DELETE FROM albums
                        WHERE id IN (
                            SELECT aa.album_id
                            FROM album_artists aa
                            WHERE aa.artist_id = ?
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM album_artists aa_other
                                  WHERE aa_other.album_id = aa.album_id
                                    AND aa_other.artist_id <> ?
                              )
                        )
                        """)) {
                    deleteExclusiveAlbums.setLong(1, id);
                    deleteExclusiveAlbums.setLong(2, id);
                    deleteExclusiveAlbums.executeUpdate();
                }
                try (PreparedStatement deleteArtist = connection.prepareStatement("DELETE FROM artists WHERE id = ?")) {
                    deleteArtist.setLong(1, id);
                    deleteArtist.executeUpdate();
                }
                connection.commit();
                albums.deleteOrphanAlbums();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete artist " + id, e);
        }
    }

    public boolean removeFromCollection(long id, String collectionId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return false;
        }
        LOG.infof("Removing artist id=%d from collection=%s", id, normalizedCollectionId);
        albums.removeStaleLocalPathsForArtist(normalizedCollectionId, id);
        if (albums.hasOnDiskLocalPathForArtist(normalizedCollectionId, id)) {
            throw new IllegalArgumentException("At least 1 album still present on disk in this collection.");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int removedAlbums;
                try (PreparedStatement deleteAlbums = connection.prepareStatement("""
                        DELETE FROM collection_albums
                        WHERE collection_id = ?
                          AND album_id IN (
                              SELECT aa.album_id
                              FROM album_artists aa
                              WHERE aa.artist_id = ?
                          )
                        """)) {
                    deleteAlbums.setString(1, normalizedCollectionId);
                    deleteAlbums.setLong(2, id);
                    removedAlbums = deleteAlbums.executeUpdate();
                }
                int removedMemberships;
                try (PreparedStatement deleteMembership = connection.prepareStatement("""
                        DELETE FROM artist_collections
                        WHERE artist_id = ? AND collection_id = ?
                        """)) {
                    deleteMembership.setLong(1, id);
                    deleteMembership.setString(2, normalizedCollectionId);
                    removedMemberships = deleteMembership.executeUpdate();
                }
                connection.commit();
                LOG.infof("Removed artist id=%d from collection=%s membershipRows=%d albumRows=%d",
                        id, normalizedCollectionId, removedMemberships, removedAlbums);
                return removedMemberships > 0 || removedAlbums > 0;
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove artist " + id
                    + " from collection " + collectionId, e);
        }
    }

    public void clearLocalScanError(long artistId, String collectionId) {
        markLocalScanError(artistId, collectionId, null);
    }

    public void clearLocalScanErrorsForCollection(String collectionId) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        String sql = """
                UPDATE artist_collections
                SET last_local_scan_error_at = NULL,
                    last_local_scan_error_message = NULL
                WHERE collection_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedCollectionId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clear local scan errors for collection " + collectionId, e);
        }
    }

    public void markLocalScanError(long artistId, String collectionId, String message) {
        String normalizedCollectionId = blankToNull(collectionId);
        if (normalizedCollectionId == null) {
            return;
        }
        String sql = message == null
                ? """
                        UPDATE artist_collections
                        SET last_local_scan_error_at = NULL,
                            last_local_scan_error_message = NULL
                        WHERE artist_id = ? AND collection_id = ?
                        """
                : """
                        UPDATE artist_collections
                        SET last_local_scan_error_at = CURRENT_TIMESTAMP,
                            last_local_scan_error_message = ?
                        WHERE artist_id = ? AND collection_id = ?
                        """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (message == null) {
                statement.setLong(1, artistId);
                statement.setString(2, normalizedCollectionId);
            } else {
                statement.setString(1, message);
                statement.setLong(2, artistId);
                statement.setString(3, normalizedCollectionId);
            }
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update local scan error for artist " + artistId
                    + " in collection " + collectionId, e);
        }
    }

    private Optional<Long> findIdByNormalizedName(String normalizedName) {
        String sql = """
                SELECT id
                FROM artists
                WHERE normalized_name = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + normalizedName, e);
        }
    }

    private List<ArtistRow> queryArtistRows(String sql, StatementBinder binder) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                List<ArtistRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(ArtistRow.from(rs));
                }
                return rows;
            }
        }
    }

    private List<Artist> mapRows(List<ArtistRow> rows, String collectionId) {
        Map<Long, List<ArtistProviderLink>> linksByArtist = providerLinks.listByArtistIds(
                rows.stream().map(ArtistRow::id).toList());
        List<Artist> artists = new ArrayList<>();
        for (ArtistRow row : rows) {
            artists.add(map(row, collectionId, linksByArtist.getOrDefault(row.id(), List.of())));
        }
        return artists;
    }

    private static String selectArtists(String whereClause) {
        return """
                SELECT a.id, a.name, a.sort_name, a.country_override, a.active_override,
                       a.created_at, a.updated_at,
                       count(al.id) AS album_count,
                       coalesce(sum(CASE WHEN al.checked = 1 THEN 1 ELSE 0 END), 0) AS checked_album_count,
                       coalesce(sum(CASE WHEN al.checked = 0 THEN 1 ELSE 0 END), 0) AS unchecked_album_count,
                       (SELECT count(*)
                        FROM artist_provider_links apl_count
                        WHERE apl_count.artist_id = a.id) AS provider_link_count,
                       (SELECT apl.provider_id
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_id,
                       (SELECT apl.provider_artist_id
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_artist_id,
                       (SELECT apl.provider_artist_name
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_artist_name,
                       (SELECT apl.provider_url
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_url,
                       (SELECT apl.country
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_country,
                       (SELECT apl.disambiguation
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_disambiguation,
                       (SELECT apl.active
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_active,
                       (SELECT apl.last_error_message
                        FROM artist_provider_links apl
                        WHERE apl.artist_id = a.id
                        ORDER BY apl.id
                        LIMIT 1) AS provider_last_error_message,
                       (SELECT count(DISTINCT ca.album_id)
                        FROM collection_albums ca
                        JOIN album_artists aa_ca ON aa_ca.album_id = ca.album_id
                        WHERE aa_ca.artist_id = a.id
                          AND (? IS NULL OR ca.collection_id = ?)) AS collection_album_count,
                       (SELECT ac.last_local_scan_error_message
                        FROM artist_collections ac
                        WHERE ac.artist_id = a.id
                          AND ac.last_local_scan_error_message IS NOT NULL
                          AND (? IS NULL OR ac.collection_id = ?)
                        ORDER BY ac.last_local_scan_error_at DESC
                        LIMIT 1) AS local_scan_error_message,
                       (SELECT group_concat(collection_id, ',') FROM (
                           SELECT ca.collection_id AS collection_id
                           FROM collection_albums ca
                           JOIN album_artists aa_collection
                             ON aa_collection.album_id = ca.album_id
                            AND aa_collection.artist_id = a.id
                           UNION
                           SELECT ac.collection_id AS collection_id
                           FROM artist_collections ac
                           WHERE ac.artist_id = a.id
                             AND (ac.local = 1 OR ac.last_local_scan_error_message IS NOT NULL)
                           ORDER BY collection_id
                       )) AS collection_ids,
                       (SELECT group_concat(collection_id, ',') FROM (
                           SELECT ac.collection_id
                           FROM artist_collections ac
                           WHERE ac.artist_id = a.id AND ac.local = 1
                           ORDER BY ac.collection_id
                       )) AS local_collection_ids
                FROM artists a
                LEFT JOIN album_artists aa ON aa.artist_id = a.id
                LEFT JOIN albums al ON al.id = aa.album_id
                """
                + whereClause
                + "\n"
                + """
                GROUP BY a.id
                ORDER BY COALESCE(a.sort_name, a.name), a.name
                """;
    }

    private Artist map(ArtistRow row, String collectionId, List<ArtistProviderLink> providerLinks) {
        return new Artist(
                row.id(),
                row.name(),
                row.sortName(),
                row.countryOverride(),
                row.activeOverride(),
                parseCollectionIds(row.collectionIds()),
                parseCollectionIds(row.localCollectionIds()),
                row.albumCount(),
                row.checkedAlbumCount(),
                row.uncheckedAlbumCount(),
                albums.countOnDiskLocalAlbumsForArtist(collectionId, row.id()),
                row.providerLinkCount(),
                row.providerId(),
                row.providerArtistId(),
                row.providerArtistName(),
                row.providerUrl(),
                row.providerCountry(),
                row.providerDisambiguation(),
                row.providerActive(),
                row.providerLastErrorMessage(),
                List.copyOf(providerLinks),
                row.collectionAlbumCount(),
                row.localScanErrorMessage(),
                row.createdAt(),
                row.updatedAt());
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws Exception;
    }

    private record ArtistRow(
            long id,
            String name,
            String sortName,
            String countryOverride,
            Boolean activeOverride,
            int albumCount,
            int checkedAlbumCount,
            int uncheckedAlbumCount,
            int providerLinkCount,
            String providerId,
            String providerArtistId,
            String providerArtistName,
            String providerUrl,
            String providerCountry,
            String providerDisambiguation,
            Boolean providerActive,
            String providerLastErrorMessage,
            int collectionAlbumCount,
            String localScanErrorMessage,
            String collectionIds,
            String localCollectionIds,
            String createdAt,
            String updatedAt) {

        static ArtistRow from(ResultSet rs) throws Exception {
            return new ArtistRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("sort_name"),
                    rs.getString("country_override"),
                    nullableBoolean(rs, "active_override"),
                    rs.getInt("album_count"),
                    rs.getInt("checked_album_count"),
                    rs.getInt("unchecked_album_count"),
                    rs.getInt("provider_link_count"),
                    rs.getString("provider_id"),
                    rs.getString("provider_artist_id"),
                    rs.getString("provider_artist_name"),
                    rs.getString("provider_url"),
                    rs.getString("provider_country"),
                    rs.getString("provider_disambiguation"),
                    nullableBoolean(rs, "provider_active"),
                    rs.getString("provider_last_error_message"),
                    rs.getInt("collection_album_count"),
                    rs.getString("local_scan_error_message"),
                    rs.getString("collection_ids"),
                    rs.getString("local_collection_ids"),
                    rs.getString("created_at"),
                    rs.getString("updated_at"));
        }
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static List<String> parseCollectionIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> collectionIds = new ArrayList<>();
        for (String item : value.split(",")) {
            String collectionId = blankToNull(item);
            if (collectionId != null) {
                collectionIds.add(collectionId);
            }
        }
        return collectionIds;
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
            return;
        }
        statement.setInt(index, value ? 1 : 0);
    }

    private static String countryCode(String value) {
        return CountryCodes.normalize(value);
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
