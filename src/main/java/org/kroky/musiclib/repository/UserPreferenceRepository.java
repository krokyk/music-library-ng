package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.UserPreference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserPreferenceRepository {

    private static final Logger LOG = Logger.getLogger(UserPreferenceRepository.class);

    @Inject
    DataSource dataSource;

    public Optional<UserPreference> find(String key) {
        ensureTable();
        String sql = """
                SELECT key, value, updated_at
                FROM user_preferences
                WHERE key = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find user preference " + key, e);
        }
    }

    public UserPreference save(String key, String value) {
        ensureTable();
        LOG.debugf("Saving user preference key=%s", key);
        String sql = """
                INSERT INTO user_preferences (key, value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
            return find(key).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save user preference " + key, e);
        }
    }

    private void ensureTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS user_preferences (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize user preferences", e);
        }
    }

    private static UserPreference map(ResultSet rs) throws Exception {
        return new UserPreference(
                rs.getString("key"),
                rs.getString("value"),
                rs.getString("updated_at"));
    }
}
