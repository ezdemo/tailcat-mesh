package com.tailcatmesh.server.peer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** JDBC persistence boundary for Agent-reported Peer path state. */
@Repository
public class PeerStatusRepository {

    private final JdbcTemplate jdbcTemplate;

    public PeerStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PeerStatusRecord> findAll() {
        return jdbcTemplate.query(select() + " ORDER BY ps.last_check_at DESC, ps.source_device_id, ps.peer_device_id",
                this::map);
    }

    public List<PeerStatusRecord> findBySourceDeviceId(UUID sourceDeviceId) {
        return jdbcTemplate.query(select() + " WHERE ps.source_device_id = ? ORDER BY ps.peer_device_id",
                this::map, sourceDeviceId);
    }

    public void upsert(PeerStatusRecord status) {
        int changed = jdbcTemplate.update(
                "UPDATE peer_status SET status = ?, path_type = ?, latency_ms = ?, derp_region = ?, "
                        + "direct_endpoint = ?, last_check_at = ?, last_error = ? "
                        + "WHERE source_device_id = ? AND peer_device_id = ?",
                status.status().name(), status.pathType(), status.latencyMs(), status.derpRegion(),
                status.directEndpoint(), Timestamp.from(status.lastCheckAt()), status.lastError(),
                status.sourceDeviceId(), status.peerDeviceId());
        if (changed == 0) {
            jdbcTemplate.update(
                    "INSERT INTO peer_status (source_device_id, peer_device_id, status, path_type, latency_ms, "
                            + "derp_region, direct_endpoint, last_check_at, last_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    status.sourceDeviceId(), status.peerDeviceId(), status.status().name(), status.pathType(),
                    status.latencyMs(), status.derpRegion(), status.directEndpoint(),
                    Timestamp.from(status.lastCheckAt()), status.lastError());
        }
    }

    public void deleteBySourceDeviceId(UUID sourceDeviceId) {
        jdbcTemplate.update("DELETE FROM peer_status WHERE source_device_id = ?", sourceDeviceId);
    }

    private String select() {
        return "SELECT ps.source_device_id, ps.peer_device_id, ps.status, ps.path_type, ps.latency_ms, "
                + "ps.derp_region, ps.direct_endpoint, ps.last_check_at, ps.last_error FROM peer_status ps";
    }

    private PeerStatusRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        double latency = resultSet.getDouble("latency_ms");
        Timestamp lastCheckAt = resultSet.getTimestamp("last_check_at");
        return new PeerStatusRecord(
                UUID.fromString(resultSet.getString("source_device_id")),
                UUID.fromString(resultSet.getString("peer_device_id")),
                PeerStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("path_type"),
                resultSet.wasNull() ? null : latency,
                resultSet.getString("derp_region"),
                resultSet.getString("direct_endpoint"),
                lastCheckAt == null ? null : lastCheckAt.toInstant(),
                resultSet.getString("last_error")
        );
    }
}
