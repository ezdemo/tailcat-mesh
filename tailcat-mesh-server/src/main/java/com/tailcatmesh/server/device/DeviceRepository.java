package com.tailcatmesh.server.device;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for registered devices. */
@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(DeviceRecord device) {
        jdbcTemplate.update(
                "INSERT INTO devices "
                        + "(id, network_id, name, hostname, os, arch, status, agent_version, tailcat_version, "
                        + "client_public_key, server_conn_blob, server_conn_blob_hash, last_seen_at, "
                        + "desired_revision, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                device.id(), device.networkId(), device.name(), device.hostname(), device.os(), device.arch(),
                device.status().name(), device.agentVersion(), device.tailcatVersion(), device.clientPublicKey(),
                device.serverConnBlob(), device.serverConnBlobHash(), timestamp(device.lastSeenAt()),
                device.desiredRevision(), Timestamp.from(device.createdAt()), Timestamp.from(device.updatedAt())
        );
    }

    public Optional<DeviceRecord> findById(UUID id) {
        List<DeviceRecord> devices = jdbcTemplate.query(baseSelect() + " WHERE d.id = ?", this::map, id);
        return devices.stream().findFirst();
    }

    public List<DeviceRecord> findAll() {
        return jdbcTemplate.query(baseSelect() + " ORDER BY d.created_at", this::map);
    }

    public List<DeviceRecord> findByNetworkId(UUID networkId) {
        return jdbcTemplate.query(baseSelect() + " WHERE d.network_id = ? ORDER BY d.created_at",
                this::map, networkId);
    }

    public void incrementDesiredRevisionForNetwork(UUID networkId, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE devices SET desired_revision = desired_revision + 1, updated_at = ? "
                        + "WHERE network_id = ?",
                Timestamp.from(updatedAt), networkId);
    }

    public void incrementDesiredRevisionForNetworkExcept(UUID networkId, UUID excludedDeviceId,
                                                         Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE devices SET desired_revision = desired_revision + 1, updated_at = ? "
                        + "WHERE network_id = ? AND id <> ?",
                Timestamp.from(updatedAt), networkId, excludedDeviceId);
    }

    public void incrementDesiredRevision(UUID deviceId, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE devices SET desired_revision = desired_revision + 1, updated_at = ? "
                        + "WHERE id = ?",
                Timestamp.from(updatedAt), deviceId);
    }

    public void markTimedOut(Instant cutoff, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE devices SET status = 'OFFLINE', updated_at = ? "
                        + "WHERE status = 'ONLINE' AND (last_seen_at IS NULL OR last_seen_at < ?)",
                Timestamp.from(updatedAt), Timestamp.from(cutoff));
    }

    public void recordHeartbeat(UUID deviceId, DeviceStatus status, Instant seenAt) {
        jdbcTemplate.update(
                "UPDATE devices SET status = ?, last_seen_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status <> 'DISABLED'",
                status.name(), Timestamp.from(seenAt), Timestamp.from(seenAt), deviceId
        );
    }

    public void recordRuntime(UUID deviceId, boolean running, String connBlob,
                              String connBlobHash, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE devices SET server_conn_blob = COALESCE(?, server_conn_blob), "
                        + "server_conn_blob_hash = COALESCE(?, server_conn_blob_hash), updated_at = ? "
                        + "WHERE id = ? AND status <> 'DISABLED'",
                running ? connBlob : null,
                running ? connBlobHash : null,
                Timestamp.from(updatedAt), deviceId
        );
    }

    public void approve(UUID deviceId, Instant updatedAt) {
        int changed = jdbcTemplate.update(
                "UPDATE devices SET status = 'OFFLINE', updated_at = ? "
                        + "WHERE id = ? AND status <> 'DISABLED'",
                Timestamp.from(updatedAt), deviceId
        );
        if (changed == 0 && findById(deviceId).isEmpty()) {
            throw new IllegalArgumentException("device not found");
        }
    }

    public void disable(UUID deviceId, Instant updatedAt) {
        int changed = jdbcTemplate.update(
                "UPDATE devices SET status = 'DISABLED', updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), deviceId
        );
        if (changed == 0) {
            throw new IllegalArgumentException("device not found");
        }
    }

    private String baseSelect() {
        return "SELECT d.id, d.network_id, d.name, d.hostname, d.os, d.arch, d.status, "
                + "d.agent_version, d.tailcat_version, d.client_public_key, d.server_conn_blob, "
                + "d.server_conn_blob_hash, d.last_seen_at, d.desired_revision, d.created_at, d.updated_at "
                + "FROM devices d";
    }

    private DeviceRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new DeviceRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("network_id")),
                resultSet.getString("name"),
                resultSet.getString("hostname"),
                resultSet.getString("os"),
                resultSet.getString("arch"),
                DeviceStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("agent_version"),
                resultSet.getString("tailcat_version"),
                resultSet.getString("client_public_key"),
                resultSet.getString("server_conn_blob"),
                resultSet.getString("server_conn_blob_hash"),
                instant(resultSet, "last_seen_at"),
                resultSet.getLong("desired_revision"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
