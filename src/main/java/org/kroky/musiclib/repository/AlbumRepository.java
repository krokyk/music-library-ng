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
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.AlbumStatus;
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

    public List<Album> list(Long artistId, AlbumStatus status, String search) {
        LOG.debugf("Listing albums artistId=%s status=%s search='%s'", artistId, status, search);
        String sql = """
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.status,
                       a.relative_path, a.source_id, ms.name AS source_name, ms.relative_path AS source_relative_path,
                       a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                LEFT JOIN music_sources ms ON ms.id = a.source_id
                WHERE (? IS NULL OR a.artist_id = ?)
                  AND (? IS NULL OR a.status = ?)
                  AND (? IS NULL OR a.normalized_title LIKE '%' || ? || '%' OR ar.normalized_name LIKE '%' || ? || '%')
                ORDER BY ar.name, a.release_year, a.title
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            setNullableLong(statement, 1, artistId);
            setNullableLong(statement, 2, artistId);
            statement.setString(3, status == null ? null : status.name());
            statement.setString(4, status == null ? null : status.name());
            statement.setString(5, normalizedSearch);
            statement.setString(6, normalizedSearch);
            statement.setString(7, normalizedSearch);
            try (ResultSet rs = statement.executeQuery()) {
                List<Album> albums = new ArrayList<>();
                while (rs.next()) {
                    albums.add(map(rs));
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
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.status,
                       a.relative_path, a.source_id, ms.name AS source_name, ms.relative_path AS source_relative_path,
                       a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                LEFT JOIN music_sources ms ON ms.id = a.source_id
                WHERE a.id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album " + id, e);
        }
    }

    public UpsertResult upsertScanned(long artistId, String title, Integer releaseYear, AlbumStatus status,
            String relativePath, String sourceId) {
        LOG.debugf("Upserting scanned album artistId=%d title='%s' year=%s sourceId=%s",
                artistId, title, releaseYear, sourceId);
        Optional<Album> existing = findDuplicate(artistId, title, releaseYear);
        if (existing.isPresent()) {
            update(existing.get().id(), title, releaseYear, status, relativePath, sourceId);
            return new UpsertResult(existing.get().id(), false);
        }
        return new UpsertResult(create(artistId, title, releaseYear, status, relativePath, sourceId).id(), true);
    }

    public Album create(long artistId, String title, Integer releaseYear, AlbumStatus status, String relativePath,
            String sourceId) {
        LOG.infof("Creating album artistId=%d title='%s' year=%s status=%s sourceId=%s",
                artistId, title, releaseYear, status, sourceId);
        String sql = """
                INSERT INTO albums (artist_id, title, normalized_title, release_year, status, relative_path, source_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindAlbum(statement, artistId, title, releaseYear, status, relativePath, sourceId);
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

    public Optional<Album> update(long id, String title, Integer releaseYear, AlbumStatus status, String relativePath,
            String sourceId) {
        LOG.infof("Updating album id=%d title='%s' year=%s status=%s sourceId=%s",
                id, title, releaseYear, status, sourceId);
        String sql = """
                UPDATE albums
                SET title = ?, normalized_title = ?, release_year = ?, status = ?,
                    relative_path = ?, source_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, Names.normalize(title));
            setNullableInt(statement, 3, releaseYear);
            statement.setString(4, status.name());
            statement.setString(5, blankToNull(relativePath));
            statement.setString(6, blankToNull(sourceId));
            statement.setLong(7, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update album " + id, e);
        }
    }

    public void delete(long id) {
        LOG.warnf("Deleting album id=%d", id);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM albums WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete album " + id, e);
        }
    }

    private Optional<Album> findDuplicate(long artistId, String title, Integer releaseYear) {
        String sql = """
                SELECT a.id, a.artist_id, ar.name AS artist_name, a.title, a.release_year, a.status,
                       a.relative_path, a.source_id, ms.name AS source_name, ms.relative_path AS source_relative_path,
                       a.created_at, a.updated_at
                FROM albums a
                JOIN artists ar ON ar.id = a.artist_id
                LEFT JOIN music_sources ms ON ms.id = a.source_id
                WHERE a.artist_id = ? AND a.normalized_title = ?
                  AND ((? IS NULL AND a.release_year IS NULL) OR a.release_year = ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            statement.setString(2, Names.normalize(title));
            setNullableInt(statement, 3, releaseYear);
            setNullableInt(statement, 4, releaseYear);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album duplicate", e);
        }
    }

    private void bindAlbum(PreparedStatement statement, long artistId, String title, Integer releaseYear,
            AlbumStatus status, String relativePath, String sourceId) throws Exception {
        statement.setLong(1, artistId);
        statement.setString(2, title);
        statement.setString(3, Names.normalize(title));
        setNullableInt(statement, 4, releaseYear);
        statement.setString(5, status.name());
        statement.setString(6, blankToNull(relativePath));
        statement.setString(7, blankToNull(sourceId));
    }

    private Album map(ResultSet rs) throws Exception {
        return new Album(
                rs.getLong("id"),
                rs.getLong("artist_id"),
                rs.getString("artist_name"),
                rs.getString("title"),
                (Integer) rs.getObject("release_year"),
                AlbumStatus.valueOf(rs.getString("status")),
                rs.getString("relative_path"),
                resolvedPath(rs.getString("source_relative_path"), rs.getString("relative_path")),
                rs.getString("source_id"),
                rs.getString("source_name"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private String resolvedPath(String sourceRelativePath, String albumRelativePath) {
        if (sourceRelativePath == null || albumRelativePath == null) {
            return null;
        }
        try {
            return musicRootService.resolveAlbum(sourceRelativePath, albumRelativePath).toString();
        } catch (IllegalStateException e) {
            LOG.debugf("Cannot resolve album path source=%s album=%s: %s",
                    sourceRelativePath, albumRelativePath, e.getMessage());
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
