package com.tailcatmesh.server.mesh;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for per-network Agent Tailcat runtimes. */
@Repository
public class VirtualNetworkRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    public VirtualNetworkRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VirtualNetworkRuntimeRecord> findByNetworkAndDevice(UUID networkId, UUID deviceId) {
        return jdbcTemplate.query(baseSelect()
                        + " WHERE r.network_id = ? AND r.device_id = ?",
                this::map, networkId, deviceId).stream().findFirst();
    }

    public List<VirtualNetworkRuntimeRecord> findByNetworkId(UUID networkId) {
        return jdbcTemplate.query(baseSelect()
                        + " WHERE r.network_id = ? ORDER BY r.device_id",
                this::map, networkId);
    }

    public void upsert(VirtualNetworkRuntimeRecord runtime) {
        int changed = jdbcTemplate.update(
                "UPDATE virtual_network_runtime SET conn_blob = ?, conn_blob_hash = ?, status = ?, "
                        + "error_code = ?, last_error = ?, updated_at = ? "
                        + "WHERE network_id = ? AND device_id = ?",
                runtime.connBlob(), runtime.connBlobHash(), runtime.status(), runtime.errorCode(),
                runtime.lastError(), Timestamp.from(runtime.updatedAt()), runtime.networkId(), runtime.deviceId());
        if (changed == 0) {
            jdbcTemplate.update(
                    "INSERT INTO virtual_network_runtime "
                            + "(network_id, device_id, conn_blob, conn_blob_hash, status, error_code, "
                            + "last_error, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    runtime.networkId(), runtime.deviceId(), runtime.connBlob(), runtime.connBlobHash(),
                    runtime.status(), runtime.errorCode(), runtime.lastError(),
                    Timestamp.from(runtime.updatedAt()));
        }
    }

    public void deleteByNetworkAndDevice(UUID networkId, UUID deviceId) {
        jdbcTemplate.update("DELETE FROM virtual_network_runtime WHERE network_id = ? AND device_id = ?",
                networkId, deviceId);
    }

    public void deleteByNetworkId(UUID networkId) {
        jdbcTemplate.update("DELETE FROM virtual_network_runtime WHERE network_id = ?", networkId);
    }

    private String baseSelect() {
        return "SELECT r.network_id, r.device_id, r.conn_blob, r.conn_blob_hash, r.status, "
                + "r.error_code, r.last_error, r.updated_at FROM virtual_network_runtime r";
    }

    private VirtualNetworkRuntimeRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new VirtualNetworkRuntimeRecord(
                UUID.fromString(resultSet.getString("network_id")),
                UUID.fromString(resultSet.getString("device_id")),
                resultSet.getString("conn_blob"),
                resultSet.getString("conn_blob_hash"),
                resultSet.getString("status"),
                resultSet.getString("error_code"),
                resultSet.getString("last_error"),
                updatedAt == null ? null : updatedAt.toInstant());
    }
}
