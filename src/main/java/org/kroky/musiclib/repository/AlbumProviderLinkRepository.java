package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.ProviderReleaseDateConflict;
import org.kroky.musiclib.model.ProviderReleaseDateConflictSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderLinkRepository {

    @Inject
    DataSource dataSource;

    public List<ProviderReleaseDateConflict> listReleaseDateConflicts() {
        String sql = """
                SELECT apl.id AS provider_link_id,
                       a.id AS album_id,
                       ar.id AS artist_id,
                       ar.name AS artist_name,
                       a.title AS album_title,
                       a.release_date AS local_release_date,
                       apl.provider_title,
                       apl.provider_release_date,
                       apl.provider_id,
                       apl.provider_url,
                       (
                           SELECT lp.relative_path
                           FROM album_local_paths lp
                           WHERE lp.album_id = a.id
                           ORDER BY lp.id
                           LIMIT 1
                       ) AS local_relative_path
                FROM album_provider_links apl
                JOIN albums a ON a.id = apl.album_id
                JOIN album_artists aa ON aa.album_id = a.id AND aa.position = 0
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE apl.release_date_resolution IS NULL
                  AND a.release_date IS NOT NULL
                  AND apl.provider_release_date IS NOT NULL
                  AND length(a.release_date) >= 4
                  AND length(apl.provider_release_date) >= 4
                  AND substr(a.release_date, 1, 4) <> substr(apl.provider_release_date, 1, 4)
                ORDER BY ar.name, a.release_date, a.title
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                var rs = statement.executeQuery()) {
            Map<String, ConflictAccumulator> conflicts = new LinkedHashMap<>();
            while (rs.next()) {
                long albumId = rs.getLong("album_id");
                String providerReleaseDate = rs.getString("provider_release_date");
                String key = albumId + ":" + releaseYear(providerReleaseDate);
                ConflictAccumulator conflict = conflicts.get(key);
                if (conflict == null) {
                    conflict = new ConflictAccumulator(
                            albumId,
                            rs.getLong("artist_id"),
                            rs.getString("artist_name"),
                            rs.getString("album_title"),
                            rs.getString("local_release_date"),
                            rs.getString("local_relative_path"));
                    conflicts.put(key, conflict);
                }
                conflict.sources().add(new ProviderReleaseDateConflictSource(
                        rs.getLong("provider_link_id"),
                        rs.getString("provider_id"),
                        rs.getString("provider_title"),
                        providerReleaseDate,
                        rs.getString("provider_url")));
            }
            return conflicts.values().stream()
                    .map(ConflictAccumulator::toConflict)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider release date conflicts", e);
        }
    }

    public Optional<AlbumProviderLink> find(long id) {
        String sql = """
                SELECT id, album_id, provider_id, provider_release_group_id,
                       provider_title, provider_release_date, provider_url,
                       release_date_resolution, created_at, updated_at
                FROM album_provider_links
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new AlbumProviderLink(
                        rs.getLong("id"),
                        rs.getLong("album_id"),
                        rs.getString("provider_id"),
                        rs.getString("provider_release_group_id"),
                        rs.getString("provider_title"),
                        rs.getString("provider_release_date"),
                        rs.getString("provider_url"),
                        rs.getString("release_date_resolution"),
                        false,
                        rs.getString("created_at"),
                        rs.getString("updated_at"))) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album provider link " + id, e);
        }
    }

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
            String providerReleaseDate, String providerUrl) {
        String sql = """
                INSERT INTO album_provider_links (
                    album_id, provider_id, provider_release_group_id,
                    provider_title, provider_release_date, provider_url
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider_id, provider_release_group_id) DO UPDATE SET
                    album_id = excluded.album_id,
                    provider_title = excluded.provider_title,
                    provider_release_date = excluded.provider_release_date,
                    provider_url = excluded.provider_url,
                    release_date_resolution = CASE
                        WHEN album_provider_links.album_id = excluded.album_id
                            THEN album_provider_links.release_date_resolution
                        ELSE NULL
                    END,
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
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to link album to provider release group", e);
        }
    }

    public void resolveReleaseDateConflict(long id, String resolution) {
        String normalized = blankToNull(resolution);
        validateResolution(normalized, resolution);
        String sql = """
                UPDATE album_provider_links
                SET release_date_resolution = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setLong(2, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown album provider link: " + id);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve album provider release date conflict", e);
        }
    }

    public int resolveMatchingReleaseDateConflicts(long albumId, String providerReleaseDate, String resolution) {
        String normalized = blankToNull(resolution);
        validateResolution(normalized, resolution);
        String providerYear = releaseYear(providerReleaseDate);
        if (providerYear == null) {
            throw new IllegalArgumentException("Provider release date has no year: " + providerReleaseDate);
        }
        String sql = """
                UPDATE album_provider_links
                SET release_date_resolution = ?, updated_at = CURRENT_TIMESTAMP
                WHERE album_id = ?
                  AND release_date_resolution IS NULL
                  AND provider_release_date IS NOT NULL
                  AND length(provider_release_date) >= 4
                  AND substr(provider_release_date, 1, 4) = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setLong(2, albumId);
            statement.setString(3, providerYear);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve matching album provider release date conflicts", e);
        }
    }

    public int resetMatchingKeepLocalReleaseDateConflicts(long albumId, String providerReleaseDate) {
        String providerYear = releaseYear(providerReleaseDate);
        if (providerYear == null) {
            throw new IllegalArgumentException("Provider release date has no year: " + providerReleaseDate);
        }
        String sql = """
                UPDATE album_provider_links
                SET release_date_resolution = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE album_id = ?
                  AND release_date_resolution = 'KEEP_LOCAL'
                  AND provider_release_date IS NOT NULL
                  AND length(provider_release_date) >= 4
                  AND substr(provider_release_date, 1, 4) = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.setString(2, providerYear);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to reset matching keep-local release date conflicts", e);
        }
    }

    private static void validateResolution(String normalized, String raw) {
        if (!"KEEP_LOCAL".equals(normalized) && !"USE_PROVIDER".equals(normalized)) {
            throw new IllegalArgumentException("Unknown release date conflict resolution: " + raw);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String releaseYear(String releaseDate) {
        String normalized = blankToNull(releaseDate);
        if (normalized == null || normalized.length() < 4) {
            return null;
        }
        return normalized.substring(0, 4);
    }

    private record ConflictAccumulator(
            long albumId,
            long artistId,
            String artistName,
            String albumTitle,
            String localReleaseDate,
            String localRelativePath,
            List<ProviderReleaseDateConflictSource> sources) {

        private ConflictAccumulator(long albumId, long artistId, String artistName, String albumTitle,
                String localReleaseDate, String localRelativePath) {
            this(albumId, artistId, artistName, albumTitle, localReleaseDate, localRelativePath, new ArrayList<>());
        }

        private ProviderReleaseDateConflict toConflict() {
            ProviderReleaseDateConflictSource first = sources.get(0);
            return new ProviderReleaseDateConflict(
                    albumId,
                    first.providerLinkId(),
                    artistId,
                    artistName,
                    albumTitle,
                    localReleaseDate,
                    first.providerTitle(),
                    first.providerReleaseDate(),
                    first.providerId(),
                    first.providerUrl(),
                    localRelativePath,
                    List.copyOf(sources));
        }
    }
}
