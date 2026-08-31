package com.tailcatmesh.server.service;

import java.time.Instant;
import java.util.UUID;

/** Admin projection combining static service configuration and runtime state. */
public record ServiceView(
        UUID id,
        UUID deviceId,
        String name,
        String protocol,
        String targetHost,
        int targetPort,
        boolean enabled,
        Integer bridgePort,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
