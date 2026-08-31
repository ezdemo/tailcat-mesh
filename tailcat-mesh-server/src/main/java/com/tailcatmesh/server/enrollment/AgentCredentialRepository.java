package com.tailcatmesh.server.enrollment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JDBC persistence boundary for Agent credentials. */
@Repository
public class AgentCredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AgentCredentialRecord credential) {
        jdbcTemplate.update(
                "INSERT INTO agent_credentials "
                        + "(id, device_id, secret_hash, created_at, last_used_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                credential.id(), credential.deviceId(), credential.secretHash(), Timestamp.from(credential.createdAt()),
                timestamp(credential.lastUsedAt()), timestamp(credential.revokedAt())
        );
    }

    public List<AgentCredentialRecord> findActive() {
        return jdbcTemplate.query(
                "SELECT id, device_id, secret_hash, created_at, last_used_at, revoked_at "
                        + "FROM agent_credentials WHERE revoked_at IS NULL",
                this::map
        );
    }

    public void touch(UUID id, Instant lastUsedAt) {
        jdbcTemplate.update(
                "UPDATE agent_credentials SET last_used_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(lastUsedAt), id
        );
    }

    private AgentCredentialRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentCredentialRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("device_id")),
                resultSet.getString("secret_hash"),
                instant(resultSet, "created_at"),
                instant(resultSet, "last_used_at"),
                instant(resultSet, "revoked_at")
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
