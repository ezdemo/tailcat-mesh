package com.tailcatmesh.server.enrollment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for enrollment tokens. */
@Repository
public class EnrollmentTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public EnrollmentTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(EnrollmentTokenRecord token) {
        jdbcTemplate.update(
                "INSERT INTO enrollment_tokens "
                        + "(id, network_id, token_hash, expires_at, max_uses, used_count, enabled, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                token.id(), token.networkId(), token.tokenHash(), Timestamp.from(token.expiresAt()),
                token.maxUses(), token.usedCount(), token.enabled(), Timestamp.from(token.createdAt())
        );
    }

    public Optional<EnrollmentTokenRecord> findForUpdate(UUID id) {
        List<EnrollmentTokenRecord> tokens = jdbcTemplate.query(
                baseSelect() + " WHERE id = ? FOR UPDATE", this::map, id);
        return tokens.stream().findFirst();
    }

    public List<EnrollmentTokenRecord> findAll() {
        return jdbcTemplate.query(baseSelect() + " ORDER BY created_at DESC", this::map);
    }

    public List<EnrollmentTokenRecord> findAllForUpdate() {
        return jdbcTemplate.query(baseSelect() + " FOR UPDATE", this::map);
    }

    public void incrementUsed(UUID id) {
        jdbcTemplate.update("UPDATE enrollment_tokens SET used_count = used_count + 1 WHERE id = ?", id);
    }

    public void disable(UUID id) {
        int changed = jdbcTemplate.update("UPDATE enrollment_tokens SET enabled = FALSE WHERE id = ?", id);
        if (changed == 0) {
            throw new IllegalArgumentException("enrollment token not found");
        }
    }

    private String baseSelect() {
        return "SELECT id, network_id, token_hash, expires_at, max_uses, used_count, enabled, created_at "
                + "FROM enrollment_tokens";
    }

    private EnrollmentTokenRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new EnrollmentTokenRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("network_id")),
                resultSet.getString("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getInt("max_uses"),
                resultSet.getInt("used_count"),
                resultSet.getBoolean("enabled"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
