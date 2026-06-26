package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.UpsertResult;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistRepository {

    private static final Logger LOG = Logger.getLogger(ArtistRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    AlbumRepository albums;

    public List<Artist> list(String search) {
        return list(search, null);
    }

    public List<Artist> list(String search, String collectionId) {
        LOG.debugf("Listing artists search='%s' collectionId=%s", search, collectionId);
        String sql = selectArtists("""
                WHERE (? IS NULL OR a.normalized_name LIKE '%' || ? || '%')
                  AND (? IS NULL OR EXISTS (
                      SELECT 1 FROM artist_collections ac
                      WHERE ac.artist_id = a.id AND ac.collection_id = ?
                  ))
                """);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            String normalizedCollectionId = blankToNull(collectionId);
            statement.setString(1, normalizedCollectionId);
            statement.setString(2, normalizedCollectionId);
            statement.setString(3, normalizedSearch);
            statement.setString(4, normalizedSearch);
            statement.setString(5, normalizedCollectionId);
            statement.setString(6, normalizedCollectionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Artist> artists = new ArrayList<>();
                while (rs.next()) {
                    artists.add(map(rs));
                }
                return artists;
            }
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
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedCollectionId = blankToNull(collectionId);
            statement.setString(1, normalizedCollectionId);
            statement.setString(2, normalizedCollectionId);
            statement.setLong(3, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + id, e);
        }
    }

    public UpsertResult upsertByName(String name) {
        LOG.debugf("Finding or creating artist by name '%s'", name);
        String normalizedName = Names.normalize(name);
        Optional<Artist> existing = findByNormalizedName(normalizedName);
        if (existing.isPresent()) {
            return new UpsertResult(existing.get().id(), false);
        }
        return new UpsertResult(create(name, null, null, List.of()).id(), true);
    }

    public Artist create(String name, String sortName, String notes) {
        return create(name, sortName, notes, List.of());
    }

    public Artist create(String name, String sortName, String notes, List<String> collectionIds) {
        LOG.infof("Creating artist '%s'", name);
        String sql = """
                INSERT INTO artists (name, normalized_name, sort_name, notes)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, Names.normalize(name));
                statement.setString(3, blankToNull(sortName));
                statement.setString(4, blankToNull(notes));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        replaceCollections(connection, id, collectionIds);
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

    public Optional<Artist> update(long id, String name, String sortName, String notes) {
        return update(id, name, sortName, notes, null);
    }

    public Optional<Artist> update(long id, String name, String sortName, String notes, List<String> collectionIds) {
        LOG.infof("Updating artist id=%d name='%s'", id, name);
        String sql = """
                UPDATE artists
                SET name = ?, normalized_name = ?, sort_name = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, Names.normalize(name));
                statement.setString(3, blankToNull(sortName));
                statement.setString(4, blankToNull(notes));
                statement.setLong(5, id);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    connection.rollback();
                    return Optional.empty();
                }
                if (collectionIds != null) {
                    replaceCollections(connection, id, collectionIds);
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

    public Optional<Artist> setCollections(long id, List<String> collectionIds) {
        LOG.infof("Updating artist collections id=%d collections=%s", id, collectionIds);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                replaceCollections(connection, id, collectionIds);
                connection.commit();
                return find(id);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update artist collections " + id, e);
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
                          AND NOT EXISTS (
                              SELECT 1
                              FROM album_artists aa_other
                              JOIN artist_collections ac_other
                                ON ac_other.artist_id = aa_other.artist_id
                               AND ac_other.collection_id = collection_albums.collection_id
                              WHERE aa_other.album_id = collection_albums.album_id
                                AND aa_other.artist_id <> ?
                          )
                        """)) {
                    deleteAlbums.setString(1, normalizedCollectionId);
                    deleteAlbums.setLong(2, id);
                    deleteAlbums.setLong(3, id);
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

    private Optional<Artist> findByNormalizedName(String normalizedName) {
        String sql = selectArtists("WHERE a.normalized_name = ?");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, null);
            statement.setString(2, null);
            statement.setString(3, normalizedName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + normalizedName, e);
        }
    }

    private static String selectArtists(String whereClause) {
        return """
                SELECT a.id, a.name, a.sort_name, a.notes, a.created_at, a.updated_at,
                       count(al.id) AS album_count,
                       coalesce(sum(CASE WHEN al.checked = 1 THEN 1 ELSE 0 END), 0) AS checked_album_count,
                       coalesce(sum(CASE WHEN al.checked = 0 THEN 1 ELSE 0 END), 0) AS unchecked_album_count,
                       coalesce(sum(CASE WHEN EXISTS (
                           SELECT 1 FROM album_local_paths lp
                           WHERE lp.album_id = al.id AND lp.missing_since IS NULL
                             AND (? IS NULL OR lp.collection_id = ?)
                       ) THEN 1 ELSE 0 END), 0) AS local_album_count,
                       (SELECT count(*) FROM artist_provider_links apl WHERE apl.artist_id = a.id) AS provider_link_count,
                       (SELECT apl.provider_id FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_id,
                       (SELECT apl.provider_artist_id FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_id,
                       (SELECT apl.provider_artist_name FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_name,
                       (SELECT apl.provider_url FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_url,
                       (SELECT apl.provider_artist_type FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_type,
                       (SELECT apl.provider_artist_country FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_country,
                       (SELECT apl.provider_artist_disambiguation FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_disambiguation,
                       (SELECT apl.provider_artist_active FROM artist_provider_links apl WHERE apl.artist_id = a.id ORDER BY apl.id LIMIT 1) AS provider_artist_active,
                       (SELECT group_concat(collection_id, ',') FROM (
                           SELECT ac.collection_id
                           FROM artist_collections ac
                           WHERE ac.artist_id = a.id
                           ORDER BY ac.collection_id
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

    private static void replaceCollections(Connection connection, long artistId, List<String> collectionIds)
            throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM artist_collections WHERE artist_id = ?");
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO artist_collections (artist_id, collection_id, local)
                        VALUES (?, ?, 0)
                        """)) {
            delete.setLong(1, artistId);
            delete.executeUpdate();
            for (String collectionId : normalizedCollectionIds(collectionIds)) {
                insert.setLong(1, artistId);
                insert.setString(2, collectionId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<String> normalizedCollectionIds(List<String> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return List.of();
        }
        return collectionIds.stream()
                .map(ArtistRepository::blankToNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static Artist map(ResultSet rs) throws Exception {
        return new Artist(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("sort_name"),
                rs.getString("notes"),
                parseCollectionIds(rs.getString("collection_ids")),
                parseCollectionIds(rs.getString("local_collection_ids")),
                rs.getInt("album_count"),
                rs.getInt("checked_album_count"),
                rs.getInt("unchecked_album_count"),
                rs.getInt("local_album_count"),
                rs.getInt("provider_link_count"),
                rs.getString("provider_id"),
                rs.getString("provider_artist_id"),
                rs.getString("provider_artist_name"),
                rs.getString("provider_url"),
                rs.getString("provider_artist_type"),
                rs.getString("provider_artist_country"),
                rs.getString("provider_artist_disambiguation"),
                nullableBoolean(rs, "provider_artist_active"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
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
