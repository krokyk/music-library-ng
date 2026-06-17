package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderLinkRepository {

    @Inject
    DataSource dataSource;

    public Optional<Long> findAlbumId(String providerId, String providerReleaseGroupId) {
        String sql = """
                SELECT album_id
                FROM album_provider_links
                WHERE provider_id = ? AND provider_release_group_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, providerReleaseGroupId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("album_id")) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album provider link", e);
        }
    }

    public void linkAlbum(long albumId, String providerId, String providerReleaseGroupId, String providerTitle,
            String providerReleaseDate, String providerUrl, String matchSource) {
        String sql = """
                INSERT INTO album_provider_links (
                    album_id, provider_id, provider_release_group_id,
                    provider_title, provider_release_date, provider_url, match_source
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider_id, provider_release_group_id) DO UPDATE SET
                    album_id = excluded.album_id,
                    provider_title = excluded.provider_title,
                    provider_release_date = excluded.provider_release_date,
                    provider_url = excluded.provider_url,
                    match_source = excluded.match_source,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.setString(2, providerId);
            statement.setString(3, providerReleaseGroupId);
            statement.setString(4, providerTitle);
            statement.setString(5, blankToNull(providerReleaseDate));
            statement.setString(6, blankToNull(providerUrl));
            statement.setString(7, matchSource);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to link album to provider release group", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
