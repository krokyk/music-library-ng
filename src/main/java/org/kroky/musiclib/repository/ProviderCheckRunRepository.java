package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.kroky.musiclib.model.ProviderCheckEvent;
import org.kroky.musiclib.model.ProviderCheckRun;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckRunRepository {

    @Inject
    DataSource dataSource;

    public long start(Long artistId, Long providerLinkId) {
        String sql = "INSERT INTO provider_check_runs (artist_id, provider_link_id, status) VALUES (?, ?, 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setObject(1, artistId);
            statement.setObject(2, providerLinkId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new IllegalStateException("Provider check run insert returned no id");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start provider check run", e);
        }
    }

    public void finish(long runId, String status, int processedArtists, int foundAlbums, int newAlbums,
            int existingAlbums, int releaseDateConflicts, int errors, String message) {
        String sql = """
                UPDATE provider_check_runs
                SET status = ?, finished_at = CURRENT_TIMESTAMP, processed_artist_count = ?,
                    found_album_count = ?, new_album_count = ?, existing_album_count = ?,
                    release_date_conflict_count = ?, error_count = ?, message = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setInt(2, processedArtists);
            statement.setInt(3, foundAlbums);
            statement.setInt(4, newAlbums);
            statement.setInt(5, existingAlbums);
            statement.setInt(6, releaseDateConflicts);
            statement.setInt(7, errors);
            statement.setString(8, message);
            statement.setLong(9, runId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to finish provider check run " + runId, e);
        }
    }

    public void event(long runId, Long artistId, Long providerLinkId, String level, String message) {
        String sql = """
                INSERT INTO provider_check_events (run_id, artist_id, provider_link_id, level, message)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setObject(2, artistId);
            statement.setObject(3, providerLinkId);
            statement.setString(4, level);
            statement.setString(5, message);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to record provider check event", e);
        }
    }

    public List<ProviderCheckRun> listRecent(int limit) {
        String sql = """
                SELECT id, artist_id, provider_link_id, started_at, finished_at, status,
                       processed_artist_count, found_album_count, new_album_count,
                       existing_album_count, release_date_conflict_count, error_count, message
                FROM provider_check_runs
                ORDER BY started_at DESC, id DESC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = statement.executeQuery()) {
                List<ProviderCheckRun> runs = new ArrayList<>();
                while (rs.next()) {
                    runs.add(mapRun(rs));
                }
                return runs;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider check runs", e);
        }
    }

    public List<ProviderCheckEvent> listEvents(long runId) {
        String sql = """
                SELECT id, run_id, artist_id, provider_link_id, level, message, created_at
                FROM provider_check_events
                WHERE run_id = ?
                ORDER BY id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ProviderCheckEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new ProviderCheckEvent(
                            rs.getLong("id"),
                            rs.getLong("run_id"),
                            nullableLong(rs, "artist_id"),
                            nullableLong(rs, "provider_link_id"),
                            rs.getString("level"),
                            rs.getString("message"),
                            rs.getString("created_at")));
                }
                return events;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider check events", e);
        }
    }

    private static ProviderCheckRun mapRun(ResultSet rs) throws Exception {
        return new ProviderCheckRun(
                rs.getLong("id"),
                nullableLong(rs, "artist_id"),
                nullableLong(rs, "provider_link_id"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("status"),
                rs.getInt("processed_artist_count"),
                rs.getInt("found_album_count"),
                rs.getInt("new_album_count"),
                rs.getInt("existing_album_count"),
                rs.getInt("release_date_conflict_count"),
                rs.getInt("error_count"),
                rs.getString("message"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws Exception {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
