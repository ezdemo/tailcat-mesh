package com.tailcatmesh.server.mesh;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for M7 Network membership. */
@Repository
public class MeshNetworkMemberRepository {

    private final JdbcTemplate jdbcTemplate;

    public MeshNetworkMemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(MeshNetworkMemberRecord member) {
        jdbcTemplate.update(
                "INSERT INTO mesh_network_members "
                        + "(id, network_id, device_id, virtual_ipv4, joined_at, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                member.id(), member.networkId(), member.deviceId(), member.virtualIpv4(),
                Timestamp.from(member.joinedAt()), member.enabled()
        );
    }

    public Optional<MeshNetworkMemberRecord> findById(UUID id) {
        return jdbcTemplate.query(baseSelect() + " WHERE m.id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<MeshNetworkMemberRecord> findByNetworkAndDevice(UUID networkId, UUID deviceId) {
        return jdbcTemplate.query(baseSelect() + " WHERE m.network_id = ? AND m.device_id = ?",
                        this::map, networkId, deviceId)
                .stream().findFirst();
    }

    public List<MeshNetworkMemberRecord> findByNetworkId(UUID networkId) {
        return jdbcTemplate.query(baseSelect()
                        + " WHERE m.network_id = ? ORDER BY m.joined_at, m.id",
                this::map, networkId);
    }

    public List<MeshNetworkMemberRecord> findByDeviceId(UUID deviceId) {
        return jdbcTemplate.query(baseSelect()
                        + " WHERE m.device_id = ? ORDER BY m.joined_at, m.network_id",
                this::map, deviceId);
    }

    public List<String> findAllVirtualIps(UUID networkId) {
        return jdbcTemplate.query(
                "SELECT virtual_ipv4 FROM mesh_network_members "
                        + "WHERE network_id = ? ORDER BY joined_at, id",
                (resultSet, rowNum) -> resultSet.getString("virtual_ipv4"), networkId);
    }

    public void setEnabled(UUID networkId, UUID deviceId, boolean enabled) {
        int changed = jdbcTemplate.update(
                "UPDATE mesh_network_members SET enabled = ? "
                        + "WHERE network_id = ? AND device_id = ?",
                enabled, networkId, deviceId);
        if (changed == 0) {
            throw new IllegalArgumentException("mesh network member not found");
        }
    }

    public List<MeshNetworkMemberRecord> deleteByNetworkId(UUID networkId) {
        List<MeshNetworkMemberRecord> members = findByNetworkId(networkId);
        jdbcTemplate.update("DELETE FROM mesh_network_members WHERE network_id = ?", networkId);
        return members;
    }

    private String baseSelect() {
        return "SELECT m.id, m.network_id, m.device_id, m.virtual_ipv4, m.joined_at, m.enabled "
                + "FROM mesh_network_members m";
    }

    private MeshNetworkMemberRecord map(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp joinedAt = resultSet.getTimestamp("joined_at");
        return new MeshNetworkMemberRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("network_id")),
                UUID.fromString(resultSet.getString("device_id")),
                resultSet.getString("virtual_ipv4"),
                joinedAt.toInstant(),
                resultSet.getBoolean("enabled")
        );
    }
}
