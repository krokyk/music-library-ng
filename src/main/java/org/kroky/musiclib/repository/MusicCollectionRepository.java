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
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MusicCollectionRepository {

    private static final Logger LOG = Logger.getLogger(MusicCollectionRepository.class);

    @Inject
    DataSource dataSource;

    @Inject
    MusicLibraryConfig config;

    @Inject
    MusicRootService musicRootService;

    public List<MusicCollection> list() {
        LOG.debug("Listing music collections");
        syncConfiguredCollections();
        String sql = """
                SELECT id, name, relative_path, parser, enabled, last_scan_at, last_scan_status, last_scan_message
                FROM collections
                ORDER BY name
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<MusicCollection> collections = new ArrayList<>();
            while (rs.next()) {
                collections.add(map(rs));
            }
            return collections;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list music collections", e);
        }
    }

    public Optional<MusicCollection> find(String id) {
        LOG.tracef("Finding music collection id=%s", id);
        syncConfiguredCollections();
        String sql = """
                SELECT id, name, relative_path, parser, enabled, last_scan_at, last_scan_status, last_scan_message
                FROM collections
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find collection " + id, e);
        }
    }

    public void markScanned(String collectionId, String status, String message) {
        LOG.infof("Marking collection %s scanned with status %s", collectionId, status);
        String sql = """
                UPDATE collections
                SET last_scan_at = CURRENT_TIMESTAMP, last_scan_status = ?, last_scan_message = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, message);
            statement.setString(3, collectionId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to mark collection scanned " + collectionId, e);
        }
    }

    private void syncConfiguredCollections() {
        if (config.collections() == null) {
            return;
        }
        LOG.debugf("Synchronizing %d configured music collections", config.collections().size());
        String sql = """
                INSERT INTO collections (id, name, relative_path, parser, enabled)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    relative_path = excluded.relative_path,
                    parser = excluded.parser,
                    enabled = excluded.enabled
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MusicLibraryConfig.MusicCollectionConfig collection : config.collections()) {
                statement.setString(1, collection.id());
                statement.setString(2, collection.name());
                statement.setString(3, collection.relativePath());
                statement.setString(4, collection.parser());
                statement.setInt(5, collection.enabled() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sync configured collections", e);
        }
    }

    private MusicCollection map(ResultSet rs) throws Exception {
        return new MusicCollection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("relative_path"),
                resolvedCollectionPath(rs.getString("relative_path")),
                collectionExists(rs.getString("relative_path")),
                ParserType.valueOf(rs.getString("parser")),
                rs.getInt("enabled") == 1,
                rs.getString("last_scan_at"),
                rs.getString("last_scan_status"),
                rs.getString("last_scan_message"));
    }

    private String resolvedCollectionPath(String relativePath) {
        try {
            return musicRootService.resolveCollection(relativePath).toString();
        } catch (IllegalStateException e) {
            LOG.debugf("Cannot resolve collection relative path %s: %s", relativePath, e.getMessage());
            return null;
        }
    }

    private boolean collectionExists(String relativePath) {
        try {
            return java.nio.file.Files.isDirectory(musicRootService.resolveCollection(relativePath));
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
