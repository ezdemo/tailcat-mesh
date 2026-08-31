CREATE TABLE forwards (
    id UUID NOT NULL PRIMARY KEY,
    source_device_id UUID NOT NULL,
    remote_service_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    local_bind_host VARCHAR(255) NOT NULL DEFAULT '127.0.0.1',
    local_bind_port INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_forwards_source_device
        FOREIGN KEY (source_device_id) REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_forwards_remote_service
        FOREIGN KEY (remote_service_id) REFERENCES services (id) ON DELETE CASCADE,
    CONSTRAINT ck_forwards_local_bind_host
        CHECK (local_bind_host IN ('127.0.0.1', '::1')),
    CONSTRAINT ck_forwards_local_bind_port
        CHECK (local_bind_port BETWEEN 1 AND 65535)
);

CREATE TABLE forward_runtime (
    forward_id UUID NOT NULL PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    last_error VARCHAR(2000),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_forward_runtime_forward
        FOREIGN KEY (forward_id) REFERENCES forwards (id) ON DELETE CASCADE,
    CONSTRAINT ck_forward_runtime_status
        CHECK (status IN ('STARTING', 'READY', 'ERROR', 'STOPPED'))
);

CREATE INDEX idx_forwards_source_device ON forwards (source_device_id);
CREATE INDEX idx_forwards_remote_service ON forwards (remote_service_id);
