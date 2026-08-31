package com.tailcatmesh.server.forward;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for Local Forward configuration. */
@Repository
public class ForwardRepository {

    private final JdbcTemplate jdbcTemplate;

    public ForwardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ForwardRecord forward) {
        jdbcTemplate.update(
                "INSERT INTO forwards "
                        + "(id, source_device_id, remote_service_id, name, local_bind_host, local_bind_port, "
                        + "enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                forward.id(), forward.sourceDeviceId(), forward.remoteServiceId(), forward.name(),
                forward.localBindHost(), forward.localBindPort(), forward.enabled(),
                Timestamp.from(forward.createdAt()), Timestamp.from(forward.updatedAt())
        );
    }

    public Optional<ForwardRecord> findById(UUID id) {
        return jdbcTemplate.query(baseSelect() + " WHERE f.id = ?", this::map, id)
                .stream().findFirst();
    }

    public List<ForwardRecord> findAll() {
        return jdbcTemplate.query(baseSelect() + " ORDER BY f.created_at", this::map);
    }

    public List<ForwardRecord> findBySourceDeviceId(UUID sourceDeviceId) {
        return jdbcTemplate.query(baseSelect() + " WHERE f.source_device_id = ? ORDER BY f.created_at",
                this::map, sourceDeviceId);
    }

    public boolean update(ForwardRecord forward) {
        return jdbcTemplate.update(
                "UPDATE forwards SET source_device_id = ?, remote_service_id = ?, name = ?, "
                        + "local_bind_host = ?, local_bind_port = ?, enabled = ?, updated_at = ? WHERE id = ?",
                forward.sourceDeviceId(), forward.remoteServiceId(), forward.name(), forward.localBindHost(),
                forward.localBindPort(), forward.enabled(), Timestamp.from(forward.updatedAt()), forward.id()
        ) > 0;
    }

    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM forwards WHERE id = ?", id) > 0;
    }

    private String baseSelect() {
        return "SELECT f.id, f.source_device_id, f.remote_service_id, f.name, f.local_bind_host, "
                + "f.local_bind_port, f.enabled, f.created_at, f.updated_at FROM forwards f";
    }

    private ForwardRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new ForwardRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("source_device_id")),
                UUID.fromString(resultSet.getString("remote_service_id")),
                resultSet.getString("name"),
                resultSet.getString("local_bind_host"),
                resultSet.getInt("local_bind_port"),
                resultSet.getBoolean("enabled"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
