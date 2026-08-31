package com.tailcatmesh.server.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for administrator accounts. */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByUsername(String username) {
        List<UserRecord> users = jdbcTemplate.query(
                "SELECT id, username, password_hash, role, created_at, updated_at "
                        + "FROM users WHERE username = ?",
                this::map,
                username
        );
        return users.stream().findFirst();
    }

    public void insert(UserRecord user) {
        jdbcTemplate.update(
                "INSERT INTO users "
                        + "(id, username, password_hash, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                user.id(), user.username(), user.passwordHash(), user.role(),
                Timestamp.from(user.createdAt()), Timestamp.from(user.updatedAt())
        );
    }

    private UserRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        return new UserRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getString("role"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
