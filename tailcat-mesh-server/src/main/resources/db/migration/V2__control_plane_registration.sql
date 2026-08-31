CREATE TABLE users (
    id UUID NOT NULL PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE mesh_networks (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE devices (
    id UUID NOT NULL PRIMARY KEY,
    network_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    os VARCHAR(64) NOT NULL,
    arch VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    agent_version VARCHAR(64) NOT NULL,
    tailcat_version VARCHAR(64) NOT NULL,
    client_public_key VARCHAR(255) NOT NULL,
    server_conn_blob TEXT,
    server_conn_blob_hash VARCHAR(128),
    last_seen_at TIMESTAMP,
    desired_revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_devices_network
        FOREIGN KEY (network_id) REFERENCES mesh_networks (id)
);

CREATE TABLE agent_credentials (
    id UUID NOT NULL PRIMARY KEY,
    device_id UUID NOT NULL,
    secret_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    CONSTRAINT fk_agent_credentials_device
        FOREIGN KEY (device_id) REFERENCES devices (id)
);

CREATE TABLE enrollment_tokens (
    id UUID NOT NULL PRIMARY KEY,
    network_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    max_uses INT NOT NULL,
    used_count INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enrollment_tokens_network
        FOREIGN KEY (network_id) REFERENCES mesh_networks (id),
    CONSTRAINT ck_enrollment_tokens_uses
        CHECK (max_uses > 0 AND used_count >= 0 AND used_count <= max_uses)
);

CREATE INDEX idx_devices_network ON devices (network_id);
CREATE INDEX idx_devices_status ON devices (status);
CREATE INDEX idx_agent_credentials_device ON agent_credentials (device_id);
CREATE INDEX idx_enrollment_tokens_network ON enrollment_tokens (network_id);
