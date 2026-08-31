package com.tailcatmesh.server.forward;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for Agent-reported Local Forward state. */
@Repository
public class ForwardRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    public ForwardRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ForwardRuntimeRecord> findByForwardId(UUID forwardId) {
        return jdbcTemplate.query(
                        "SELECT forward_id, status, error_code, last_error, updated_at "
                                + "FROM forward_runtime WHERE forward_id = ?",
                        this::map, forwardId)
                .stream().findFirst();
    }

    public List<ForwardRuntimeRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT forward_id, status, error_code, last_error, updated_at "
                        + "FROM forward_runtime ORDER BY updated_at", this::map);
    }

    public void upsert(ForwardRuntimeRecord runtime) {
        int changed = jdbcTemplate.update(
                "UPDATE forward_runtime SET status = ?, error_code = ?, last_error = ?, updated_at = ? "
                        + "WHERE forward_id = ?",
                runtime.status(), runtime.errorCode(), runtime.lastError(), Timestamp.from(runtime.updatedAt()),
                runtime.forwardId());
        if (changed == 0) {
            jdbcTemplate.update(
                    "INSERT INTO forward_runtime (forward_id, status, error_code, last_error, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    runtime.forwardId(), runtime.status(), runtime.errorCode(), runtime.lastError(),
                    Timestamp.from(runtime.updatedAt())
            );
        }
    }

    public void deleteByForwardId(UUID forwardId) {
        jdbcTemplate.update("DELETE FROM forward_runtime WHERE forward_id = ?", forwardId);
    }

    public void deleteBySourceDeviceId(UUID sourceDeviceId) {
        jdbcTemplate.update(
                "DELETE FROM forward_runtime WHERE forward_id IN "
                        + "(SELECT id FROM forwards WHERE source_device_id = ?)", sourceDeviceId);
    }

    private ForwardRuntimeRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new ForwardRuntimeRecord(
                UUID.fromString(resultSet.getString("forward_id")),
                resultSet.getString("status"),
                resultSet.getString("error_code"),
                resultSet.getString("last_error"),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }
}
