package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.CollectionTitleItem;
import org.kroky.musiclib.model.MetadataSource;
import org.kroky.musiclib.model.ParseStatus;
import org.kroky.musiclib.model.ParsedTitleItem;
import org.kroky.musiclib.model.UpsertResult;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CollectionTitleItemRepository {

    private static final Logger LOG = Logger.getLogger(CollectionTitleItemRepository.class);

    @Inject
    DataSource dataSource;

    public List<CollectionTitleItem> list(String collectionId) {
        LOG.debugf("Listing title items collectionId=%s", collectionId);
        String sql = """
                SELECT id, collection_id, raw_folder_name, relative_path, title, artist_name, year,
                       metadata_source, parse_status, first_seen_at, last_seen_at, missing_since,
                       created_at, updated_at
                FROM collection_title_items
                WHERE collection_id = ?
                ORDER BY normalized_title, coalesce(year, 999999), coalesce(normalized_artist_name, '')
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<CollectionTitleItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(map(rs));
                }
                return items;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list title items for collection " + collectionId, e);
        }
    }

    public Optional<CollectionTitleItem> find(long id) {
        String sql = """
                SELECT id, collection_id, raw_folder_name, relative_path, title, artist_name, year,
                       metadata_source, parse_status, first_seen_at, last_seen_at, missing_since,
                       created_at, updated_at
                FROM collection_title_items
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find title item " + id, e);
        }
    }

    public UpsertResult upsertParsed(ParsedTitleItem parsed) {
        String relativePath = parsed.relativePath().getFileName().toString();
        Optional<CollectionTitleItem> existing = findByPath(parsed.collectionId(), relativePath);
        if (existing.isPresent() && existing.get().metadataSource() == MetadataSource.MANUAL) {
            touchManualItem(existing.get().id(), parsed.rawFolderName());
            return new UpsertResult(existing.get().id(), false);
        }

        String sql = """
                INSERT INTO collection_title_items (
                    collection_id, raw_folder_name, relative_path, title, normalized_title,
                    artist_name, normalized_artist_name, year, metadata_source, parse_status,
                    last_seen_at, missing_since
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, CURRENT_TIMESTAMP, NULL)
                ON CONFLICT(collection_id, relative_path) DO UPDATE SET
                    raw_folder_name = excluded.raw_folder_name,
                    title = excluded.title,
                    normalized_title = excluded.normalized_title,
                    artist_name = excluded.artist_name,
                    normalized_artist_name = excluded.normalized_artist_name,
                    year = excluded.year,
                    metadata_source = 'AUTO',
                    parse_status = excluded.parse_status,
                    last_seen_at = CURRENT_TIMESTAMP,
                    missing_since = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parsed.collectionId());
            statement.setString(2, parsed.rawFolderName());
            statement.setString(3, relativePath);
            statement.setString(4, parsed.title());
            statement.setString(5, Names.normalize(parsed.title()));
            statement.setString(6, blankToNull(parsed.artistName()));
            statement.setString(7, Names.normalize(parsed.artistName()));
            setNullableInt(statement, 8, parsed.year());
            statement.setString(9, parsed.parseStatus().name());
            statement.executeUpdate();
            long id = findByPath(parsed.collectionId(), relativePath).orElseThrow().id();
            return new UpsertResult(id, existing.isEmpty());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert title item " + relativePath, e);
        }
    }

    public Optional<CollectionTitleItem> updateManual(
            String collectionId,
            long id,
            String title,
            String artistName,
            Integer year) {
        LOG.infof("Updating title item collection=%s id=%d title='%s' artist='%s' year=%s",
                collectionId, id, title, artistName, year);
        String sql = """
                UPDATE collection_title_items
                SET title = ?,
                    normalized_title = ?,
                    artist_name = ?,
                    normalized_artist_name = ?,
                    year = ?,
                    metadata_source = 'MANUAL',
                    parse_status = 'MANUAL',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND collection_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            statement.setString(3, blankToNull(artistName));
            statement.setString(4, Names.normalize(artistName));
            setNullableInt(statement, 5, year);
            statement.setLong(6, id);
            statement.setString(7, collectionId);
            int updated = statement.executeUpdate();
            return updated == 0 ? Optional.empty() : find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update title item " + id, e);
        }
    }

    public int markMissingPaths(String collectionId, Set<String> seenPaths) {
        Set<String> normalizedSeen = new HashSet<>(seenPaths);
        String select = """
                SELECT relative_path
                FROM collection_title_items
                WHERE collection_id = ? AND missing_since IS NULL
                """;
        String update = """
                UPDATE collection_title_items
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
            throw new IllegalStateException("Unable to mark missing title paths for " + collectionId, e);
        }
    }

    private Optional<CollectionTitleItem> findByPath(String collectionId, String relativePath) {
        String sql = """
                SELECT id, collection_id, raw_folder_name, relative_path, title, artist_name, year,
                       metadata_source, parse_status, first_seen_at, last_seen_at, missing_since,
                       created_at, updated_at
                FROM collection_title_items
                WHERE collection_id = ? AND relative_path = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            statement.setString(2, relativePath);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find title item by path " + relativePath, e);
        }
    }

    private void touchManualItem(long id, String rawFolderName) {
        String sql = """
                UPDATE collection_title_items
                SET raw_folder_name = ?,
                    last_seen_at = CURRENT_TIMESTAMP,
                    missing_since = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rawFolderName);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to touch manual title item " + id, e);
        }
    }

    private static CollectionTitleItem map(ResultSet rs) throws Exception {
        return new CollectionTitleItem(
                rs.getLong("id"),
                rs.getString("collection_id"),
                rs.getString("raw_folder_name"),
                rs.getString("relative_path"),
                rs.getString("title"),
                rs.getString("artist_name"),
                (Integer) rs.getObject("year"),
                MetadataSource.valueOf(rs.getString("metadata_source")),
                ParseStatus.valueOf(rs.getString("parse_status")),
                rs.getString("first_seen_at"),
                rs.getString("last_seen_at"),
                rs.getString("missing_since"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
