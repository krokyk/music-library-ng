package org.kroky.musiclib.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.ProviderReleaseDateConflict;
import org.kroky.musiclib.model.ProviderReleaseDateConflictSource;
import org.kroky.musiclib.model.ProviderTitleConflict;
import org.kroky.musiclib.model.ProviderTitleConflictSource;
import org.kroky.musiclib.provider.ProviderTitles;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderLinkRepository {

    @Inject
    DataSource dataSource;

    @Inject
    MusicRootService musicRootService;

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
                       apl.provider_url
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
            Map<Long, Optional<String>> onDiskPathCache = new HashMap<>();
            while (rs.next()) {
                long albumId = rs.getLong("album_id");
                String providerReleaseDate = rs.getString("provider_release_date");
                String key = albumId + ":" + releaseYear(providerReleaseDate);
                ConflictAccumulator conflict = conflicts.get(key);
                if (conflict == null) {
                    String localRelativePath = firstOnDiskRelativePath(connection, albumId, onDiskPathCache).orElse(null);
                    conflict = new ConflictAccumulator(
                            albumId,
                            rs.getLong("artist_id"),
                            rs.getString("artist_name"),
                            rs.getString("album_title"),
                            rs.getString("local_release_date"),
                            localRelativePath);
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

    public List<ProviderTitleConflict> listTitleConflicts() {
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
                       apl.provider_url
                FROM album_provider_links apl
                JOIN albums a ON a.id = apl.album_id
                JOIN album_artists aa ON aa.album_id = a.id AND aa.position = 0
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE apl.title_resolution IS NULL
                  AND apl.provider_title IS NOT NULL
                ORDER BY ar.name, a.release_date, a.title, apl.provider_title
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                var rs = statement.executeQuery()) {
            Map<String, TitleConflictAccumulator> conflicts = new LinkedHashMap<>();
            Map<Long, Optional<String>> onDiskPathCache = new HashMap<>();
            while (rs.next()) {
                String albumTitle = rs.getString("album_title");
                String providerTitle = rs.getString("provider_title");
                if (!ProviderTitles.titleConflict(albumTitle, providerTitle)) {
                    continue;
                }
                long albumId = rs.getLong("album_id");
                String key = albumId + ":" + ProviderTitles.clean(providerTitle).toLowerCase();
                TitleConflictAccumulator conflict = conflicts.get(key);
                if (conflict == null) {
                    String localRelativePath = firstOnDiskRelativePath(connection, albumId, onDiskPathCache).orElse(null);
                    conflict = new TitleConflictAccumulator(
                            albumId,
                            rs.getLong("artist_id"),
                            rs.getString("artist_name"),
                            albumTitle,
                            rs.getString("local_release_date"),
                            localRelativePath);
                    conflicts.put(key, conflict);
                }
                conflict.sources().add(new ProviderTitleConflictSource(
                        rs.getLong("provider_link_id"),
                        rs.getString("provider_id"),
                        providerTitle,
                        rs.getString("provider_release_date"),
                        rs.getString("provider_url")));
            }
            return conflicts.values().stream()
                    .map(TitleConflictAccumulator::toConflict)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider title conflicts", e);
        }
    }

    public Optional<AlbumProviderLink> find(long id) {
        String sql = """
                SELECT id, album_id, provider_id, provider_release_group_id,
                       provider_title, provider_release_date, provider_url,
                       release_date_resolution, title_resolution, created_at, updated_at
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
                        rs.getString("title_resolution"),
                        false,
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
                             AND coalesce(album_provider_links.provider_release_date, '') = coalesce(excluded.provider_release_date, '')
                            THEN album_provider_links.release_date_resolution
                        ELSE NULL
                    END,
                    title_resolution = CASE
                        WHEN album_provider_links.album_id = excluded.album_id
                             AND album_provider_links.provider_title = excluded.provider_title
                            THEN album_provider_links.title_resolution
                        ELSE NULL
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            statement.setString(2, providerId);
            statement.setString(3, providerReleaseGroupId);
            statement.setString(4, ProviderTitles.clean(providerTitle));
            statement.setString(5, blankToNull(providerReleaseDate));
            statement.setString(6, blankToNull(providerUrl));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to link album to provider release group", e);
        }
    }

    public void resolveReleaseDateConflict(long id, String resolution) {
        String normalized = normalizeResolution(resolution, "release date");
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
        String normalized = normalizeResolution(resolution, "release date");
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

    public void resolveTitleConflict(long id, String resolution) {
        String normalized = normalizeResolution(resolution, "title");
        String sql = """
                UPDATE album_provider_links
                SET title_resolution = ?, updated_at = CURRENT_TIMESTAMP
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
            throw new IllegalStateException("Unable to resolve album provider title conflict", e);
        }
    }

    public int resolveMatchingTitleConflicts(long albumId, String providerTitle, String resolution) {
        String normalized = normalizeResolution(resolution, "title");
        List<Long> ids = matchingTitleConflictIds(albumId, providerTitle, null);
        return updateTitleResolution(ids, normalized);
    }

    public int resolveAlbumTitleUsingProvider(long albumId, String providerTitle) {
        String selectedTitle = ProviderTitles.clean(providerTitle);
        if (selectedTitle.isBlank()) {
            throw new IllegalArgumentException("Provider title is blank.");
        }
        Map<String, List<Long>> idsByResolution = new LinkedHashMap<>();
        for (TitleResolutionTarget target : titleResolutionTargets(albumId)) {
            String resolution = ProviderTitles.sameProviderTitleText(selectedTitle, target.providerTitle())
                    ? "USE_PROVIDER"
                    : "USE_OTHER_PROVIDER";
            idsByResolution.computeIfAbsent(resolution, ignored -> new ArrayList<>()).add(target.id());
        }
        return updateTitleResolutions(idsByResolution);
    }

    public int resolveAlbumTitleUsingLocal(long albumId, String localTitle) {
        String selectedTitle = ProviderTitles.clean(localTitle);
        if (selectedTitle.isBlank()) {
            throw new IllegalArgumentException("Local title is blank.");
        }
        Map<String, List<Long>> idsByResolution = new LinkedHashMap<>();
        for (TitleResolutionTarget target : titleResolutionTargets(albumId)) {
            String resolution = ProviderTitles.sameTitle(selectedTitle, target.providerTitle())
                    ? null
                    : "KEEP_LOCAL";
            idsByResolution.computeIfAbsent(resolution, ignored -> new ArrayList<>()).add(target.id());
        }
        return updateTitleResolutions(idsByResolution);
    }

    public int resetMatchingKeepLocalTitleConflicts(long albumId, String providerTitle) {
        List<Long> ids = matchingTitleConflictIds(albumId, providerTitle, "KEEP_LOCAL");
        return updateTitleResolution(ids, null);
    }

    private List<Long> matchingTitleConflictIds(long albumId, String providerTitle, String existingResolution) {
        String sql = """
                SELECT id, provider_title, title_resolution
                FROM album_provider_links
                WHERE album_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (var rs = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    String currentResolution = rs.getString("title_resolution");
                    if (existingResolution != null && !existingResolution.equals(currentResolution)) {
                        continue;
                    }
                    if (existingResolution == null && currentResolution != null) {
                        continue;
                    }
                    if (ProviderTitles.sameProviderTitle(providerTitle, rs.getString("provider_title"))) {
                        ids.add(rs.getLong("id"));
                    }
                }
                return ids;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find matching album provider title conflicts", e);
        }
    }

    private List<TitleResolutionTarget> titleResolutionTargets(long albumId) {
        String sql = """
                SELECT id, provider_title
                FROM album_provider_links
                WHERE album_id = ?
                  AND provider_title IS NOT NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (var rs = statement.executeQuery()) {
                List<TitleResolutionTarget> targets = new ArrayList<>();
                while (rs.next()) {
                    String providerTitle = ProviderTitles.clean(rs.getString("provider_title"));
                    if (!providerTitle.isBlank()) {
                        targets.add(new TitleResolutionTarget(rs.getLong("id"), providerTitle));
                    }
                }
                return targets;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album provider title resolution targets", e);
        }
    }

    private int updateTitleResolutions(Map<String, List<Long>> idsByResolution) {
        int updated = 0;
        for (Map.Entry<String, List<Long>> entry : idsByResolution.entrySet()) {
            updated += updateTitleResolution(entry.getValue(), entry.getKey());
        }
        return updated;
    }

    private int updateTitleResolution(List<Long> ids, String resolution) {
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        String sql = """
                UPDATE album_provider_links
                SET title_resolution = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id IN (
                """ + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, resolution);
            for (int index = 0; index < ids.size(); index++) {
                statement.setLong(index + 2, ids.get(index));
            }
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update matching album provider title conflicts", e);
        }
    }

    private static String normalizeResolution(String resolution, String label) {
        String normalized = blankToNull(resolution);
        validateResolution(normalized, resolution, label);
        return normalized;
    }

    private static void validateResolution(String normalized, String raw, String label) {
        if (!"KEEP_LOCAL".equals(normalized)
                && !"USE_PROVIDER".equals(normalized)
                && !("title".equals(label) && "USE_OTHER_PROVIDER".equals(normalized))) {
            throw new IllegalArgumentException("Unknown " + label + " conflict resolution: " + raw);
        }
    }

    private Optional<String> firstOnDiskRelativePath(Connection connection, long albumId,
            Map<Long, Optional<String>> cache) {
        return cache.computeIfAbsent(albumId, id -> firstOnDiskRelativePath(connection, id));
    }

    private Optional<String> firstOnDiskRelativePath(Connection connection, long albumId) {
        String sql = """
                SELECT lp.relative_path, c.relative_path AS collection_relative_path
                FROM album_local_paths lp
                JOIN collections c ON c.id = lp.collection_id
                WHERE lp.album_id = ?
                ORDER BY lp.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    String relativePath = rs.getString("relative_path");
                    String resolvedPath = resolvedPath(rs.getString("collection_relative_path"), relativePath);
                    if (resolvedPath != null && Files.isDirectory(Path.of(resolvedPath))) {
                        return Optional.of(relativePath);
                    }
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve album local paths for conflicts", e);
        }
    }

    private String resolvedPath(String collectionRelativePath, String albumRelativePath) {
        if (collectionRelativePath == null || albumRelativePath == null) {
            return null;
        }
        try {
            return musicRootService.resolveAlbum(collectionRelativePath, albumRelativePath).toString();
        } catch (IllegalStateException e) {
            return null;
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

    private record TitleConflictAccumulator(
            long albumId,
            long artistId,
            String artistName,
            String albumTitle,
            String localReleaseDate,
            String localRelativePath,
            List<ProviderTitleConflictSource> sources) {

        private TitleConflictAccumulator(long albumId, long artistId, String artistName, String albumTitle,
                String localReleaseDate, String localRelativePath) {
            this(albumId, artistId, artistName, albumTitle, localReleaseDate, localRelativePath, new ArrayList<>());
        }

        private ProviderTitleConflict toConflict() {
            ProviderTitleConflictSource first = sources.get(0);
            return new ProviderTitleConflict(
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

    private record TitleResolutionTarget(long id, String providerTitle) {
    }
}
