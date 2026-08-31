package com.tailcatmesh.server.mesh;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for mesh networks. */
@Repository
public class MeshNetworkRepository {

    private final JdbcTemplate jdbcTemplate;

    public MeshNetworkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MeshNetworkRecord> findBySlug(String slug) {
        List<MeshNetworkRecord> networks = jdbcTemplate.query(
                "SELECT id, name, slug, created_at, updated_at FROM mesh_networks WHERE slug = ?",
                this::map,
                slug
        );
        return networks.stream().findFirst();
    }

    public Optional<MeshNetworkRecord> findById(UUID id) {
        List<MeshNetworkRecord> networks = jdbcTemplate.query(
                "SELECT id, name, slug, created_at, updated_at FROM mesh_networks WHERE id = ?",
                this::map,
                id
        );
        return networks.stream().findFirst();
    }

    public void insert(MeshNetworkRecord network) {
        jdbcTemplate.update(
                "INSERT INTO mesh_networks (id, name, slug, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                network.id(), network.name(), network.slug(),
                Timestamp.from(network.createdAt()), Timestamp.from(network.updatedAt())
        );
    }

    private MeshNetworkRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new MeshNetworkRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("name"),
                resultSet.getString("slug"),
                createdAt.toInstant(),
                updatedAt.toInstant()
        );
    }
}
