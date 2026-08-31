CREATE TABLE peer_status (
    source_device_id UUID NOT NULL,
    peer_device_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    path_type VARCHAR(32) NOT NULL,
    latency_ms DOUBLE,
    derp_region VARCHAR(128),
    direct_endpoint VARCHAR(255),
    last_check_at TIMESTAMP NOT NULL,
    last_error VARCHAR(2000),
    PRIMARY KEY (source_device_id, peer_device_id),
    CONSTRAINT fk_peer_status_source
        FOREIGN KEY (source_device_id) REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_peer_status_peer
        FOREIGN KEY (peer_device_id) REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT ck_peer_status_not_self
        CHECK (source_device_id <> peer_device_id),
    CONSTRAINT ck_peer_status_latency
        CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_peer_status_status
        CHECK (status IN ('ONLINE', 'DEGRADED', 'OFFLINE', 'UNKNOWN', 'STOPPED')),
    CONSTRAINT ck_peer_status_path
        CHECK (path_type IN ('DIRECT', 'DERP', 'OFFLINE', 'UNKNOWN'))
);

CREATE INDEX idx_peer_status_source ON peer_status (source_device_id);
CREATE INDEX idx_peer_status_peer ON peer_status (peer_device_id);
