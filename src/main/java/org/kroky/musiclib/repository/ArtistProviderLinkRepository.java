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
import org.kroky.musiclib.provider.CountryCodes;

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
                 WHERE apl.enabled = 1
                   AND (EXISTS (
                       SELECT 1
                       FROM collection_albums ca
                       JOIN album_artists aa ON aa.album_id = ca.album_id
                       WHERE aa.artist_id = apl.artist_id
                         AND ca.collection_id = ?
                   ) OR EXISTS (
                       SELECT 1
                       FROM artist_collections ac
                       WHERE ac.artist_id = apl.artist_id
                         AND ac.collection_id = ?
                         AND (ac.local = 1 OR ac.last_local_scan_error_message IS NOT NULL)
                   ))
                 ORDER BY ar.name, apl.provider_id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, collectionId);
            statement.setString(2, collectionId);
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

    public ArtistProviderLink upsertForArtist(long artistId, String providerId, String providerArtistId,
            String providerArtistName, String providerUrl, boolean enabled) {
        return upsertForArtist(artistId, providerId, providerArtistId, providerArtistName, providerUrl,
                null, null, null, enabled);
    }

    public ArtistProviderLink upsertForArtist(long artistId, String providerId, String providerArtistId,
            String providerArtistName, String providerUrl, String providerCountry,
            String providerDisambiguation, Boolean providerActive, boolean enabled) {
        String sql = """
                INSERT INTO artist_provider_links (
                    artist_id,
                    provider_id,
                    provider_artist_id,
                    provider_artist_name,
                    country,
                    disambiguation,
                    active,
                    provider_url,
                    enabled
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(artist_id) DO UPDATE SET
                    provider_id = excluded.provider_id,
                    provider_artist_id = excluded.provider_artist_id,
                    provider_artist_name = excluded.provider_artist_name,
                    country = excluded.country,
                    disambiguation = excluded.disambiguation,
                    active = excluded.active,
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
            statement.setString(5, countryCode(providerCountry));
            statement.setString(6, blankToNull(providerDisambiguation));
            setNullableBoolean(statement, 7, providerActive);
            statement.setString(8, blankToNull(providerUrl));
            statement.setInt(9, enabled ? 1 : 0);
            statement.executeUpdate();
            return findByArtist(artistId).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upsert provider for artist " + artistId, e);
        }
    }

    public void updateProviderMetadata(long id, String providerCountry, Boolean providerActive) {
        String sql = """
                UPDATE artist_provider_links
                SET country = ?,
                    active = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, countryCode(providerCountry));
            setNullableBoolean(statement, 2, providerActive);
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update provider metadata for link " + id, e);
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

    public void markSuccess(long id) {
        String sql = """
                UPDATE artist_provider_links
                SET last_success_at = CURRENT_TIMESTAMP,
                    last_error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        mark(id, sql, null);
    }

    public void markError(long id, String message) {
        String sql = """
                UPDATE artist_provider_links
                SET last_error_at = CURRENT_TIMESTAMP,
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
                       apl.provider_artist_id, apl.provider_artist_name,
                       apl.country, apl.disambiguation, apl.active,
                       apl.provider_url, apl.enabled,
                       apl.last_success_at, apl.last_error_at, apl.last_error_message,
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
                rs.getString("country"),
                rs.getString("disambiguation"),
                nullableBoolean(rs, "active"),
                rs.getString("provider_url"),
                rs.getInt("enabled") == 1,
                rs.getString("last_success_at"),
                rs.getString("last_error_at"),
                rs.getString("last_error_message"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
            return;
        }
        statement.setInt(index, value ? 1 : 0);
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static String countryCode(String value) {
        return CountryCodes.normalize(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
