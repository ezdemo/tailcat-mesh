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

/** JDBC persistence boundary for published TCP services. */
@Repository
public class ServiceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ServiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ServiceRecord service) {
        jdbcTemplate.update(
                "INSERT INTO services "
                        + "(id, device_id, name, protocol, target_host, target_port, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                service.id(), service.deviceId(), service.name(), service.protocol(), service.targetHost(),
                service.targetPort(), service.enabled(), Timestamp.from(service.createdAt()),
                Timestamp.from(service.updatedAt())
        );
    }

    public Optional<ServiceRecord> findById(UUID id) {
        return jdbcTemplate.query(baseSelect() + " WHERE s.id = ?", this::map, id)
                .stream().findFirst();
    }

    public List<ServiceRecord> findAll() {
        return jdbcTemplate.query(baseSelect() + " ORDER BY s.created_at", this::map);
    }

    public List<ServiceRecord> findByDeviceId(UUID deviceId) {
        return jdbcTemplate.query(baseSelect() + " WHERE s.device_id = ? ORDER BY s.created_at",
                this::map, deviceId);
    }

    public boolean update(ServiceRecord service) {
        return jdbcTemplate.update(
                "UPDATE services SET device_id = ?, name = ?, protocol = ?, target_host = ?, "
                        + "target_port = ?, enabled = ?, updated_at = ? WHERE id = ?",
                service.deviceId(), service.name(), service.protocol(), service.targetHost(), service.targetPort(),
                service.enabled(), Timestamp.from(service.updatedAt()), service.id()
        ) > 0;
    }

    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM services WHERE id = ?", id) > 0;
    }

    private String baseSelect() {
        return "SELECT s.id, s.device_id, s.name, s.protocol, s.target_host, s.target_port, "
                + "s.enabled, s.created_at, s.updated_at FROM services s";
    }

    private ServiceRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new ServiceRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("device_id")),
                resultSet.getString("name"),
                resultSet.getString("protocol"),
                resultSet.getString("target_host"),
                resultSet.getInt("target_port"),
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
