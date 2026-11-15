package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    static final String IDENTITY_MEMBERS_SQL = """
            SELECT member.id FROM artists member
            WHERE coalesce(member.canonical_artist_id, member.id) =
                (SELECT coalesce(requested.canonical_artist_id, requested.id) FROM artists requested WHERE requested.id = ?)
            """;

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

    public List<Artist> list(String search, Long collectionId) {
        LOG.debugf("Listing artists search='%s' collectionId=%s", search, collectionId);
        String sql = selectArtists("""
                WHERE (? IS NULL OR a.normalized_name LIKE '%' || ? || '%')
                  AND (? IS NULL OR EXISTS (
                      SELECT 1 FROM album_artists aa_filter JOIN albums al_filter ON al_filter.id=aa_filter.album_id
                      WHERE aa_filter.artist_id=a.id AND al_filter.collection_id=?))
                """);
        try {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            Long normalizedCollectionId = collectionId;
            List<ArtistRow> rows = queryArtistRows(sql, statement -> {
                statement.setObject(1, normalizedCollectionId); statement.setObject(2, normalizedCollectionId);
                statement.setObject(3, normalizedCollectionId); statement.setObject(4, normalizedCollectionId);
                statement.setString(5, normalizedSearch); statement.setString(6, normalizedSearch);
                statement.setObject(7, normalizedCollectionId); statement.setObject(8, normalizedCollectionId);
            });
            return mapRows(rows, normalizedCollectionId);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list artists", e);
        }
    }

    public Optional<Artist> find(long id) {
        return find(id, null);
    }

    public Optional<Artist> find(long id, Long collectionId) {
        LOG.tracef("Finding artist id=%d", id);
        String sql = selectArtists("WHERE a.id = ?");
        try {
            Long normalizedCollectionId = collectionId;
            List<ArtistRow> rows = queryArtistRows(sql, statement -> {
                statement.setObject(1, normalizedCollectionId); statement.setObject(2, normalizedCollectionId);
                statement.setObject(3, normalizedCollectionId); statement.setObject(4, normalizedCollectionId);
                statement.setLong(5, id);
            });
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(mapRows(rows, normalizedCollectionId).get(0));
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
                try (PreparedStatement metadata = connection.prepareStatement(
                        "UPDATE artists SET country_override=?, active_override=?, updated_at=CURRENT_TIMESTAMP WHERE id IN ("
                                + IDENTITY_MEMBERS_SQL + ")")) {
                    metadata.setString(1, countryCode(countryOverride));
                    setNullableBoolean(metadata, 2, activeOverride);
                    metadata.setLong(3, id);
                    metadata.executeUpdate();
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
        if (identityArtistIds(id).size() > 1) {
            throw new IllegalArgumentException("This artist has shared credited names; deleting the combined identity requires an explicit group deletion workflow.");
        }
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
                        """
                        DELETE FROM artists WHERE NOT EXISTS (
                            SELECT 1 FROM album_artists aa JOIN artists member ON member.id=aa.artist_id
                            WHERE coalesce(member.canonical_artist_id, member.id)=coalesce(artists.canonical_artist_id, artists.id)
                        )
                        """)) {
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete albumless artists", e);
        }
    }

    public long canonicalArtistId(long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            return canonicalArtistId(connection, artistId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve artist identity " + artistId, e);
        }
    }

    public List<Long> identityArtistIds(long artistId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(IDENTITY_MEMBERS_SQL + " ORDER BY member.id")) {
            statement.setLong(1, artistId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list credited artist names", e);
        }
    }

    public Artist linkCreditedName(long canonicalArtistId, long creditedArtistId) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long target = canonicalArtistId(connection, canonicalArtistId);
                long source = canonicalArtistId(connection, creditedArtistId);
                if (source != target) {
                    ArtistOverrides targetOverrides = overrides(connection, target);
                    ArtistOverrides sourceOverrides = overrides(connection, source);
                    if (targetOverrides.country() != null && sourceOverrides.country() != null
                            && !targetOverrides.country().equals(sourceOverrides.country())
                            || targetOverrides.active() != null && sourceOverrides.active() != null
                            && !targetOverrides.active().equals(sourceOverrides.active())) {
                        throw new IllegalArgumentException("Resolve the artists' differing country or status overrides before linking their names.");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE artists SET canonical_artist_id=?, updated_at=CURRENT_TIMESTAMP
                            WHERE id=? OR canonical_artist_id=?
                            """)) {
                        statement.setLong(1, target);
                        statement.setLong(2, source);
                        statement.setLong(3, source);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE artists SET country_override=?, active_override=?, updated_at=CURRENT_TIMESTAMP
                            WHERE id=? OR canonical_artist_id=?
                            """)) {
                        statement.setString(1, targetOverrides.country() != null ? targetOverrides.country() : sourceOverrides.country());
                        setNullableBoolean(statement, 2, targetOverrides.active() != null ? targetOverrides.active() : sourceOverrides.active());
                        statement.setLong(3, target);
                        statement.setLong(4, target);
                        statement.executeUpdate();
                    }
                    LOG.infof("Linked credited artist name id=%d to canonical artist id=%d; album credits and paths retained", creditedArtistId, target);
                }
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to link credited artist names", e);
        }
        return find(creditedArtistId).orElseThrow();
    }

    private static long canonicalArtistId(Connection connection, long artistId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT coalesce(canonical_artist_id, id) FROM artists WHERE id=?")) {
            statement.setLong(1, artistId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown artist: " + artistId);
                return rs.getLong(1);
            }
        }
    }

    private static ArtistOverrides overrides(Connection connection, long artistId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT country_override, active_override FROM artists WHERE id=?")) {
            statement.setLong(1, artistId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown artist: " + artistId);
                return new ArtistOverrides(rs.getString(1), nullableBoolean(rs, "active_override"));
            }
        }
    }

    private Map<Long, List<ArtistCredit>> identityCredits(List<ArtistRow> rows) {
        List<Long> roots = rows.stream().map(ArtistRow::canonicalArtistId).distinct().toList();
        if (roots.isEmpty()) return Map.of();
        String placeholders = String.join(",", roots.stream().map(id -> "?").toList());
        String sql = "SELECT id, name, coalesce(canonical_artist_id,id) AS identity_id FROM artists WHERE coalesce(canonical_artist_id,id) IN ("
                + placeholders + ") ORDER BY name, id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < roots.size(); index++) statement.setLong(index + 1, roots.get(index));
            Map<Long, List<ArtistCredit>> credits = new LinkedHashMap<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) credits.computeIfAbsent(rs.getLong("identity_id"), ignored -> new ArrayList<>())
                        .add(new ArtistCredit(rs.getLong("id"), rs.getString("name")));
            }
            return credits;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load artist credited names", e);
        }
    }

    private record ArtistCredit(long id, String name) { }
    private record ArtistOverrides(String country, Boolean active) { }

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

    private List<Artist> mapRows(List<ArtistRow> rows, Long collectionId) {
        Map<Long, List<ArtistProviderLink>> linksByArtist = providerLinks.listByArtistIds(
                rows.stream().map(ArtistRow::id).toList());
        Map<Long, List<ArtistCredit>> credits = identityCredits(rows);
        List<Artist> artists = new ArrayList<>();
        for (ArtistRow row : rows) {
            artists.add(map(row, collectionId, linksByArtist.getOrDefault(row.id(), List.of()),
                    credits.getOrDefault(row.canonicalArtistId(), List.of(new ArtistCredit(row.id(), row.name())))));
        }
        return artists;
    }

    private static String selectArtists(String whereClause) {
        return """
                SELECT a.id, a.name, a.sort_name, identity.country_override, identity.active_override,
                       identity.id AS canonical_artist_id, identity.name AS canonical_name,
                       count(CASE WHEN (? IS NULL OR al.collection_id=?) THEN al.id END) AS album_count,
                       coalesce(sum(CASE WHEN (? IS NULL OR al.collection_id=?) AND al.checked=0 THEN 1 ELSE 0 END),0) AS unchecked_album_count,
                       (SELECT group_concat(collection_id, ',') FROM (
                           SELECT DISTINCT al_collection.collection_id AS collection_id
                           FROM albums al_collection JOIN album_artists aa_collection ON aa_collection.album_id=al_collection.id
                           WHERE aa_collection.artist_id=a.id
                           ORDER BY collection_id
                       )) AS collection_ids
                FROM artists a
                JOIN artists identity ON identity.id=coalesce(a.canonical_artist_id, a.id)
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

    private Artist map(ArtistRow row, Long collectionId, List<ArtistProviderLink> providerLinks, List<ArtistCredit> credits) {
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
                List.copyOf(providerLinks),
                row.canonicalArtistId(),
                row.canonicalName(),
                credits.stream().map(ArtistCredit::id).toList(),
                credits.stream().map(ArtistCredit::name).toList());
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
            long canonicalArtistId,
            String canonicalName,
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
                    rs.getLong("canonical_artist_id"),
                    rs.getString("canonical_name"),
                    rs.getInt("album_count"),
                    rs.getInt("unchecked_album_count"),
                    rs.getString("collection_ids"));
        }
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static List<Long> parseCollectionIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Long> collectionIds = new ArrayList<>();
        for (String item : value.split(",")) {
            collectionIds.add(Long.parseLong(item));
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
