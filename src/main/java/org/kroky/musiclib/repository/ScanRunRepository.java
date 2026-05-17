package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ScanEvent;
import org.kroky.musiclib.model.ScanRun;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ScanRunRepository {

    private static final Logger LOG = Logger.getLogger(ScanRunRepository.class);

    @Inject
    DataSource dataSource;

    public long start(String sourceId) {
        LOG.infof("Starting scan run for source %s", sourceId);
        String sql = "INSERT INTO scan_runs (source_id, status) VALUES (?, 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sourceId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new IllegalStateException("Scan run insert returned no id");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start scan run", e);
        }
    }

    public List<ScanRun> listRecent(int limit) {
        LOG.debugf("Listing recent scan runs limit=%d", limit);
        String sql = """
                SELECT sr.id, sr.source_id, ms.name AS source_name, sr.started_at, sr.finished_at, sr.status,
                       sr.parsed_count, sr.created_count, sr.updated_count, sr.skipped_count, sr.message
                FROM scan_runs sr
                LEFT JOIN music_sources ms ON ms.id = sr.source_id
                ORDER BY sr.started_at DESC, sr.id DESC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = statement.executeQuery()) {
                List<ScanRun> runs = new ArrayList<>();
                while (rs.next()) {
                    runs.add(mapRun(rs));
                }
                return runs;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list scan runs", e);
        }
    }

    public List<ScanEvent> listEvents(long runId) {
        LOG.debugf("Listing scan events for run %d", runId);
        String sql = """
                SELECT id, scan_run_id, level, message, created_at
                FROM scan_events
                WHERE scan_run_id = ?
                ORDER BY id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ScanEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new ScanEvent(
                            rs.getLong("id"),
                            rs.getLong("scan_run_id"),
                            rs.getString("level"),
                            rs.getString("message"),
                            rs.getString("created_at")));
                }
                return events;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list scan events for run " + runId, e);
        }
    }

    public void finish(long runId, String status, int parsed, int created, int updated, int skipped, String message) {
        LOG.infof("Finishing scan run %d status=%s parsed=%d created=%d updated=%d skipped=%d",
                runId, status, parsed, created, updated, skipped);
        String sql = """
                UPDATE scan_runs
                SET status = ?, finished_at = CURRENT_TIMESTAMP, parsed_count = ?, created_count = ?,
                    updated_count = ?, skipped_count = ?, message = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setInt(2, parsed);
            statement.setInt(3, created);
            statement.setInt(4, updated);
            statement.setInt(5, skipped);
            statement.setString(6, message);
            statement.setLong(7, runId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to finish scan run " + runId, e);
        }
    }

    public void event(long runId, String level, String message) {
        LOG.tracef("Recording scan event runId=%d level=%s message=%s", runId, level, message);
        String sql = "INSERT INTO scan_events (scan_run_id, level, message) VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, level);
            statement.setString(3, message);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to record scan event", e);
        }
    }

    private ScanRun mapRun(ResultSet rs) throws Exception {
        return new ScanRun(
                rs.getLong("id"),
                rs.getString("source_id"),
                rs.getString("source_name"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("status"),
                rs.getInt("parsed_count"),
                rs.getInt("created_count"),
                rs.getInt("updated_count"),
                rs.getInt("skipped_count"),
                rs.getString("message"));
    }
}
