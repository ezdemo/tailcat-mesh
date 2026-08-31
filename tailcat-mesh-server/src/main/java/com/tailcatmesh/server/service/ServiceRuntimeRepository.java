package com.tailcatmesh.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for Agent-reported ServiceBridge state. */
@Repository
public class ServiceRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    public ServiceRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ServiceRuntimeRecord> findByServiceId(UUID serviceId) {
        return jdbcTemplate.query(
                        "SELECT service_id, bridge_port, status, last_error, updated_at "
                                + "FROM service_runtime WHERE service_id = ?",
                        this::map, serviceId)
                .stream().findFirst();
    }

    public void upsert(ServiceRuntimeRecord runtime) {
        int changed = jdbcTemplate.update(
                "UPDATE service_runtime SET bridge_port = ?, status = ?, last_error = ?, updated_at = ? "
                        + "WHERE service_id = ?",
                runtime.bridgePort(), runtime.status(), runtime.lastError(), Timestamp.from(runtime.updatedAt()),
                runtime.serviceId());
        if (changed == 0) {
            jdbcTemplate.update(
                    "INSERT INTO service_runtime (service_id, bridge_port, status, last_error, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    runtime.serviceId(), runtime.bridgePort(), runtime.status(), runtime.lastError(),
                    Timestamp.from(runtime.updatedAt())
            );
        }
    }

    public void deleteByServiceId(UUID serviceId) {
        jdbcTemplate.update("DELETE FROM service_runtime WHERE service_id = ?", serviceId);
    }

    private ServiceRuntimeRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        int bridgePort = resultSet.getInt("bridge_port");
        return new ServiceRuntimeRecord(
                UUID.fromString(resultSet.getString("service_id")),
                resultSet.wasNull() ? null : bridgePort,
                resultSet.getString("status"),
                resultSet.getString("last_error"),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }
}
