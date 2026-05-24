package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.kroky.musiclib.model.ArtistProviderLink;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderLinkRepository {

    @Inject
    DataSource dataSource;

    public List<ArtistProviderLink> listByArtist(long artistId) {
        String sql = baseSelect() + " WHERE apl.artist_id = ? ORDER BY apl.provider_id, apl.provider_url";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ArtistProviderLink> links = new ArrayList<>();
                while (rs.next()) {
                    links.add(map(rs));
                }
                return links;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider links", e);
        }
    }

    public List<ArtistProviderLink> listEnabled() {
        String sql = baseSelect() + " WHERE apl.enabled = 1 ORDER BY ar.name, apl.provider_id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<ArtistProviderLink> links = new ArrayList<>();
            while (rs.next()) {
                links.add(map(rs));
            }
            return links;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list enabled provider links", e);
        }
    }

    public Optional<ArtistProviderLink> find(long id) {
        String sql = baseSelect() + " WHERE apl.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find provider link " + id, e);
        }
    }

    public ArtistProviderLink create(long artistId, String providerId, String providerUrl, boolean enabled) {
        String sql = """
                INSERT INTO artist_provider_links (artist_id, provider_id, provider_url, enabled)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, artistId);
            statement.setString(2, providerId);
            statement.setString(3, providerUrl);
            statement.setInt(4, enabled ? 1 : 0);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return find(keys.getLong(1)).orElseThrow();
                }
            }
            throw new IllegalStateException("Provider link insert returned no id");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create provider link", e);
        }
    }

    public Optional<ArtistProviderLink> update(long id, String providerId, String providerUrl, boolean enabled) {
        String sql = """
                UPDATE artist_provider_links
                SET provider_id = ?, provider_url = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, providerUrl);
            statement.setInt(3, enabled ? 1 : 0);
            statement.setLong(4, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update provider link " + id, e);
        }
    }

    public void delete(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM artist_provider_links WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete provider link " + id, e);
        }
    }

    public void markSuccess(long id) {
        String sql = """
                UPDATE artist_provider_links
                SET last_checked_at = CURRENT_TIMESTAMP,
                    last_success_at = CURRENT_TIMESTAMP,
                    last_error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        mark(id, sql, null);
    }

    public void markError(long id, String message) {
        String sql = """
                UPDATE artist_provider_links
                SET last_checked_at = CURRENT_TIMESTAMP,
                    last_error_at = CURRENT_TIMESTAMP,
                    last_error_message = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        mark(id, sql, message);
    }

    private void mark(long id, String sql, String message) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (message == null) {
                statement.setLong(1, id);
            } else {
                statement.setString(1, message);
                statement.setLong(2, id);
            }
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark provider link " + id, e);
        }
    }

    private static String baseSelect() {
        return """
                SELECT apl.id, apl.artist_id, ar.name AS artist_name, apl.provider_id, apl.provider_url, apl.enabled,
                       apl.last_checked_at, apl.last_success_at, apl.last_error_at, apl.last_error_message,
                       apl.created_at, apl.updated_at
                FROM artist_provider_links apl
                JOIN artists ar ON ar.id = apl.artist_id
                """;
    }

    private static ArtistProviderLink map(ResultSet rs) throws Exception {
        return new ArtistProviderLink(
                rs.getLong("id"),
                rs.getLong("artist_id"),
                rs.getString("artist_name"),
                rs.getString("provider_id"),
                rs.getString("provider_url"),
                rs.getInt("enabled") == 1,
                rs.getString("last_checked_at"),
                rs.getString("last_success_at"),
                rs.getString("last_error_at"),
                rs.getString("last_error_message"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
