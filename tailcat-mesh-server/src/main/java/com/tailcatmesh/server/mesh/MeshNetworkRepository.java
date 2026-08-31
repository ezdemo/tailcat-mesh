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
                baseSelect() + " WHERE slug = ?",
                this::map,
                slug
        );
        return networks.stream().findFirst();
    }

    public Optional<MeshNetworkRecord> findById(UUID id) {
        List<MeshNetworkRecord> networks = jdbcTemplate.query(
                baseSelect() + " WHERE id = ?",
                this::map,
                id
        );
        return networks.stream().findFirst();
    }

    public List<MeshNetworkRecord> findAll() {
        return jdbcTemplate.query(baseSelect() + " ORDER BY created_at, id", this::map);
    }

    public void insert(MeshNetworkRecord network) {
        String cidr = network.cidr();
        if (cidr == null || cidr.isBlank()) {
            cidr = VirtualIpam.nextDefaultCidr(findAllCidrs());
        }
        jdbcTemplate.update(
                "INSERT INTO mesh_networks (id, name, slug, cidr, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                network.id(), network.name(), network.slug(), cidr, network.enabled(),
                Timestamp.from(network.createdAt()), Timestamp.from(network.updatedAt())
        );
    }

    public void update(MeshNetworkRecord network) {
        int changed = jdbcTemplate.update(
                "UPDATE mesh_networks SET name = ?, cidr = ?, enabled = ?, updated_at = ? WHERE id = ?",
                network.name(), network.cidr(), network.enabled(),
                Timestamp.from(network.updatedAt()), network.id()
        );
        if (changed == 0) {
            throw new IllegalArgumentException("mesh network not found");
        }
    }

    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM mesh_networks WHERE id = ?", id) > 0;
    }

    public List<String> findAllCidrs() {
        return jdbcTemplate.query(
                "SELECT cidr FROM mesh_networks WHERE cidr IS NOT NULL ORDER BY created_at, id",
                (resultSet, rowNum) -> resultSet.getString("cidr")
        );
    }

    private String baseSelect() {
        return "SELECT id, name, slug, cidr, enabled, created_at, updated_at FROM mesh_networks";
    }

    private MeshNetworkRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new MeshNetworkRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("name"),
                resultSet.getString("slug"),
                resultSet.getString("cidr"),
                resultSet.getBoolean("enabled"),
                createdAt.toInstant(),
                updatedAt.toInstant()
        );
    }
}
