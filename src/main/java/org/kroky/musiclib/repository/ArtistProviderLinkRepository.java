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
        String sql = baseSelect()
                + " WHERE apl.artist_id = ? ORDER BY apl.provider_id, coalesce(apl.provider_artist_name, apl.provider_url, '')";
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

    public Optional<ArtistProviderLink> findByArtist(long artistId) {
        String sql = baseSelect() + " WHERE apl.artist_id = ? ORDER BY apl.id LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find provider for artist " + artistId, e);
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

    public List<ArtistProviderLink> listEnabledByCollection(String collectionId) {
        String sql = baseSelect() + """
                 JOIN artist_collections ac ON ac.artist_id = apl.artist_id
                 WHERE apl.enabled = 1 AND ac.collection_id = ?
                 ORDER BY ar.name, apl.provider_id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ArtistProviderLink> links = new ArrayList<>();
                while (rs.next()) {
                    links.add(map(rs));
                }
                return links;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list enabled provider links for collection " + collectionId, e);
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
        return upsertForArtist(artistId, providerId, derivedProviderArtistId(providerId, providerUrl), null,
                providerUrl, enabled);
    }

    public ArtistProviderLink upsertForArtist(long artistId, String providerId, String providerArtistId,
            String providerArtistName, String providerUrl, boolean enabled) {
        String sql = """
                INSERT INTO artist_provider_links (
                    artist_id, provider_id, provider_artist_id, provider_artist_name, provider_url, enabled
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(artist_id) DO UPDATE SET
                    provider_id = excluded.provider_id,
                    provider_artist_id = excluded.provider_artist_id,
                    provider_artist_name = excluded.provider_artist_name,
                    provider_url = excluded.provider_url,
                    enabled = excluded.enabled,
                    last_error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, artistId);
            statement.setString(2, providerId);
            statement.setString(3, blankToNull(providerArtistId));
            statement.setString(4, blankToNull(providerArtistName));
            statement.setString(5, blankToNull(providerUrl));
            statement.setInt(6, enabled ? 1 : 0);
            statement.executeUpdate();
            return findByArtist(artistId).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert provider for artist " + artistId, e);
        }
    }

    public Optional<ArtistProviderLink> update(long id, String providerId, String providerUrl, boolean enabled) {
        return update(id, providerId, derivedProviderArtistId(providerId, providerUrl), null, providerUrl, enabled);
    }

    public Optional<ArtistProviderLink> update(long id, String providerId, String providerArtistId,
            String providerArtistName, String providerUrl, boolean enabled) {
        String sql = """
                UPDATE artist_provider_links
                SET provider_id = ?,
                    provider_artist_id = ?,
                    provider_artist_name = ?,
                    provider_url = ?,
                    enabled = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, blankToNull(providerArtistId));
            statement.setString(3, blankToNull(providerArtistName));
            statement.setString(4, blankToNull(providerUrl));
            statement.setInt(5, enabled ? 1 : 0);
            statement.setLong(6, id);
            statement.executeUpdate();
            return find(id);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update provider link " + id, e);
        }
    }

    public void deleteByArtist(long artistId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM artist_provider_links WHERE artist_id = ?")) {
            statement.setLong(1, artistId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to delete provider for artist " + artistId, e);
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
                SELECT apl.id, apl.artist_id, ar.name AS artist_name, apl.provider_id,
                       apl.provider_artist_id, apl.provider_artist_name, apl.provider_url, apl.enabled,
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
                rs.getString("provider_artist_id"),
                rs.getString("provider_artist_name"),
                rs.getString("provider_url"),
                rs.getInt("enabled") == 1,
                rs.getString("last_checked_at"),
                rs.getString("last_success_at"),
                rs.getString("last_error_at"),
                rs.getString("last_error_message"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static String derivedProviderArtistId(String providerId, String providerUrl) {
        if ("musicbrainz".equals(providerId)) {
            return musicBrainzIdFromUrl(providerUrl);
        }
        return blankToNull(providerUrl);
    }

    private static String musicBrainzIdFromUrl(String providerUrl) {
        String value = blankToNull(providerUrl);
        if (value == null) {
            return null;
        }
        int marker = value.indexOf("/artist/");
        if (marker >= 0) {
            String suffix = value.substring(marker + "/artist/".length());
            int slash = suffix.indexOf('/');
            int query = suffix.indexOf('?');
            int end = suffix.length();
            if (slash >= 0) {
                end = Math.min(end, slash);
            }
            if (query >= 0) {
                end = Math.min(end, query);
            }
            return blankToNull(suffix.substring(0, end));
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
