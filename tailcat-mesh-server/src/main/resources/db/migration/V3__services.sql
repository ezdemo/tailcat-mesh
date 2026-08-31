CREATE TABLE services (
    id UUID NOT NULL PRIMARY KEY,
    device_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    protocol VARCHAR(16) NOT NULL DEFAULT 'TCP',
    target_host VARCHAR(255) NOT NULL,
    target_port INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_services_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT ck_services_protocol
        CHECK (protocol = 'TCP'),
    CONSTRAINT ck_services_target_port
        CHECK (target_port BETWEEN 1 AND 65535)
);

CREATE TABLE service_runtime (
    service_id UUID NOT NULL PRIMARY KEY,
    bridge_port INT,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(2000),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_service_runtime_service
        FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE CASCADE,
    CONSTRAINT ck_service_runtime_bridge_port
        CHECK (bridge_port IS NULL OR bridge_port BETWEEN 1 AND 65535)
);

CREATE INDEX idx_services_device ON services (device_id);
CREATE INDEX idx_services_enabled ON services (enabled);
