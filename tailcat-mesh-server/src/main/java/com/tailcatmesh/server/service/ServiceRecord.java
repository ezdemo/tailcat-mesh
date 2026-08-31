package com.tailcatmesh.server.service;

import java.time.Instant;
import java.util.UUID;

/** Persisted service configuration owned by one mesh device. */
public record ServiceRecord(
        UUID id,
        UUID deviceId,
        String name,
        String protocol,
        String targetHost,
        int targetPort,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
