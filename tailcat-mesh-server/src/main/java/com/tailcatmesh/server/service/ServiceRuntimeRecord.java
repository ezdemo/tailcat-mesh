package com.tailcatmesh.server.service;

import java.time.Instant;
import java.util.UUID;

/** Last runtime state reported for one ServiceBridge. */
public record ServiceRuntimeRecord(
        UUID serviceId,
        Integer bridgePort,
        String status,
        String lastError,
        Instant updatedAt
) {
}
