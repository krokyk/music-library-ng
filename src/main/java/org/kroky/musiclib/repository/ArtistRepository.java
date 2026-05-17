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

    public List<Artist> list(String search) {
        LOG.debugf("Listing artists search='%s'", search);
        String sql = """
                SELECT id, name, sort_name, notes, created_at, updated_at
                FROM artists
                WHERE ? IS NULL OR normalized_name LIKE '%' || ? || '%'
                ORDER BY COALESCE(sort_name, name), name
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedSearch = search == null || search.isBlank() ? null : Names.normalize(search);
            statement.setString(1, normalizedSearch);
            statement.setString(2, normalizedSearch);
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
        LOG.tracef("Finding artist id=%d", id);
        String sql = """
                SELECT id, name, sort_name, notes, created_at, updated_at
                FROM artists
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + id, e);
        }
    }

    public UpsertResult upsertByName(String name) {
        LOG.debugf("Upserting artist by name '%s'", name);
        String normalizedName = Names.normalize(name);
        Optional<Artist> existing = findByNormalizedName(normalizedName);
        if (existing.isPresent()) {
            return new UpsertResult(existing.get().id(), false);
        }
        return new UpsertResult(create(name, null, null).id(), true);
    }

    public Artist create(String name, String sortName, String notes) {
        LOG.infof("Creating artist '%s'", name);
        String sql = """
                INSERT INTO artists (name, normalized_name, sort_name, notes)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, Names.normalize(name));
            statement.setString(3, blankToNull(sortName));
            statement.setString(4, blankToNull(notes));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return find(keys.getLong(1)).orElseThrow();
                }
            }
            throw new IllegalStateException("Artist insert returned no id");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create artist " + name, e);
        }
    }

    public Optional<Artist> update(long id, String name, String sortName, String notes) {
        LOG.infof("Updating artist id=%d name='%s'", id, name);
        String sql = """
                UPDATE artists
                SET name = ?, normalized_name = ?, sort_name = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, Names.normalize(name));
            statement.setString(3, blankToNull(sortName));
            statement.setString(4, blankToNull(notes));
            statement.setLong(5, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update artist " + id, e);
        }
    }

    public void delete(long id) {
        LOG.warnf("Deleting artist id=%d and cascading albums", id);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM artists WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete artist " + id, e);
        }
    }

    private Optional<Artist> findByNormalizedName(String normalizedName) {
        String sql = """
                SELECT id, name, sort_name, notes, created_at, updated_at
                FROM artists
                WHERE normalized_name = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find artist " + normalizedName, e);
        }
    }

    private Artist map(ResultSet rs) throws Exception {
        return new Artist(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("sort_name"),
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
