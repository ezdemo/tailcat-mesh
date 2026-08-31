ALTER TABLE mesh_networks ADD COLUMN cidr VARCHAR(43);
ALTER TABLE mesh_networks ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Existing M1-M6 control-plane networks remain valid M7 networks. New
-- legacy inserts are assigned another free CIDR by MeshNetworkRepository.
UPDATE mesh_networks
SET cidr = '10.77.0.0/24'
WHERE cidr IS NULL;

ALTER TABLE mesh_networks ALTER COLUMN cidr SET NOT NULL;

CREATE TABLE mesh_network_members (
    id UUID NOT NULL PRIMARY KEY,
    network_id UUID NOT NULL,
    device_id UUID NOT NULL,
    virtual_ipv4 VARCHAR(15) NOT NULL,
    joined_at TIMESTAMP NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT fk_mesh_network_members_network
        FOREIGN KEY (network_id) REFERENCES mesh_networks (id) ON DELETE CASCADE,
    CONSTRAINT fk_mesh_network_members_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT uq_mesh_network_members_device
        UNIQUE (network_id, device_id),
    CONSTRAINT uq_mesh_network_members_ipv4
        UNIQUE (network_id, virtual_ipv4)
);

CREATE INDEX idx_mesh_network_members_network
    ON mesh_network_members (network_id, enabled);
CREATE INDEX idx_mesh_network_members_device
    ON mesh_network_members (device_id, enabled);
