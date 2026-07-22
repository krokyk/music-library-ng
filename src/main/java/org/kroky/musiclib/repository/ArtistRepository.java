package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.UpsertResult;
import org.kroky.musiclib.provider.ArtistProviderMetadata;
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
                      SELECT 1 FROM album_artists aa_filter JOIN albums al_filter ON al_filter.id=aa_filter.album_id
                      WHERE aa_filter.artist_id=a.id AND al_filter.collection_id=?))
                """);
        try {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            String normalizedCollectionId = blankToNull(collectionId);
            List<ArtistRow> rows = queryArtistRows(sql, statement -> {
                statement.setString(1, normalizedCollectionId); statement.setString(2, normalizedCollectionId);
                statement.setString(3, normalizedCollectionId); statement.setString(4, normalizedCollectionId);
                statement.setString(5, normalizedSearch); statement.setString(6, normalizedSearch);
                statement.setString(7, normalizedCollectionId); statement.setString(8, normalizedCollectionId);
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
                statement.setString(1, normalizedCollectionId); statement.setString(2, normalizedCollectionId);
                statement.setString(3, normalizedCollectionId); statement.setString(4, normalizedCollectionId);
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
            if (autoCommit) connection.setAutoCommit(false);
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
            if (autoCommit) connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, Names.normalize(name));
                statement.setString(3, blankToNull(sortName));
                statement.setString(4, countryCode(countryOverride));
                setNullableBoolean(statement, 5, activeOverride);
                statement.setLong(6, id);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    if (autoCommit) connection.rollback();
                    return Optional.empty();
                }
                if (autoCommit) connection.commit();
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

    public int deleteAlbumlessArtists() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM artists WHERE NOT EXISTS (SELECT 1 FROM album_artists aa WHERE aa.artist_id=artists.id)")) {
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete albumless artists", e);
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
                       count(CASE WHEN (? IS NULL OR al.collection_id=?) THEN al.id END) AS album_count,
                       coalesce(sum(CASE WHEN (? IS NULL OR al.collection_id=?) AND al.checked=0 THEN 1 ELSE 0 END),0) AS unchecked_album_count,
                       (SELECT group_concat(collection_id, ',') FROM (
                           SELECT DISTINCT al_collection.collection_id AS collection_id
                           FROM albums al_collection JOIN album_artists aa_collection ON aa_collection.album_id=al_collection.id
                           WHERE aa_collection.artist_id=a.id
                           ORDER BY collection_id
                       )) AS collection_ids
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
                row.albumCount(),
                row.uncheckedAlbumCount(),
                albums.countOnDiskLocalAlbumsForArtist(collectionId, row.id()),
                ArtistProviderMetadata.countryConsensus(providerLinks),
                ArtistProviderMetadata.activeConsensus(providerLinks),
                List.copyOf(providerLinks));
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
            int uncheckedAlbumCount,
            String collectionIds) {

        static ArtistRow from(ResultSet rs) throws Exception {
            return new ArtistRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("sort_name"),
                    rs.getString("country_override"),
                    nullableBoolean(rs, "active_override"),
                    rs.getInt("album_count"),
                    rs.getInt("unchecked_album_count"),
                    rs.getString("collection_ids"));
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
