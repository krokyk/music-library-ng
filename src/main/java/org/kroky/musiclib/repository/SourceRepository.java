package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.MusicSource;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SourceRepository {

    private static final Logger LOG = Logger.getLogger(SourceRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    MusicLibraryConfig config;

    @Inject
    MusicRootService musicRootService;

    public List<MusicSource> list() {
        LOG.debug("Listing music sources");
        syncConfiguredSources();
        String sql = """
                SELECT id, name, relative_path, parser, enabled, last_scan_at, last_scan_status, last_scan_message
                FROM music_sources
                ORDER BY name
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<MusicSource> sources = new ArrayList<>();
            while (rs.next()) {
                sources.add(map(rs));
            }
            return sources;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list music sources", e);
        }
    }

    public Optional<MusicSource> find(String id) {
        LOG.tracef("Finding music source id=%s", id);
        syncConfiguredSources();
        String sql = """
                SELECT id, name, relative_path, parser, enabled, last_scan_at, last_scan_status, last_scan_message
                FROM music_sources
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find source " + id, e);
        }
    }

    public void markScanned(String sourceId, String status, String message) {
        LOG.infof("Marking source %s scanned with status %s", sourceId, status);
        String sql = """
                UPDATE music_sources
                SET last_scan_at = CURRENT_TIMESTAMP, last_scan_status = ?, last_scan_message = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, message);
            statement.setString(3, sourceId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark source scanned " + sourceId, e);
        }
    }

    private void syncConfiguredSources() {
        if (config.scanSources() == null) {
            return;
        }
        LOG.debugf("Synchronizing %d configured music sources", config.scanSources().size());
        String sql = """
                INSERT INTO music_sources (id, name, relative_path, parser, enabled)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    relative_path = excluded.relative_path,
                    parser = excluded.parser,
                    enabled = excluded.enabled
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MusicLibraryConfig.ScanSource source : config.scanSources()) {
                statement.setString(1, source.id());
                statement.setString(2, source.name());
                statement.setString(3, source.relativePath());
                statement.setString(4, source.parser());
                statement.setInt(5, source.enabled() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sync configured sources", e);
        }
    }

    private MusicSource map(ResultSet rs) throws Exception {
        return new MusicSource(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("relative_path"),
                resolvedSourcePath(rs.getString("relative_path")),
                sourceExists(rs.getString("relative_path")),
                ParserType.valueOf(rs.getString("parser")),
                rs.getInt("enabled") == 1,
                rs.getString("last_scan_at"),
                rs.getString("last_scan_status"),
                rs.getString("last_scan_message"));
    }

    private String resolvedSourcePath(String relativePath) {
        try {
            return musicRootService.resolveSource(relativePath).toString();
        } catch (IllegalStateException e) {
            LOG.debugf("Cannot resolve source relative path %s: %s", relativePath, e.getMessage());
            return null;
        }
    }

    private boolean sourceExists(String relativePath) {
        try {
            return java.nio.file.Files.isDirectory(musicRootService.resolveSource(relativePath));
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
