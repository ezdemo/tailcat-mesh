CREATE TABLE virtual_network_runtime (
    network_id UUID NOT NULL,
    device_id UUID NOT NULL,
    conn_blob VARCHAR(4096),
    conn_blob_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    last_error VARCHAR(2000),
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (network_id, device_id),
    CONSTRAINT fk_virtual_network_runtime_member
        FOREIGN KEY (network_id, device_id)
        REFERENCES mesh_network_members (network_id, device_id) ON DELETE CASCADE,
    CONSTRAINT ck_virtual_network_runtime_status
        CHECK (status IN ('STARTING', 'READY', 'ERROR', 'STOPPED'))
);

CREATE INDEX idx_virtual_network_runtime_device
    ON virtual_network_runtime (device_id);
