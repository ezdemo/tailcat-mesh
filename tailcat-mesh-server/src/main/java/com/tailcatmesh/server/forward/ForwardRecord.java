package com.tailcatmesh.server.forward;

import java.time.Instant;
import java.util.UUID;

/** Persisted Local Forward configuration owned by one source device. */
public record ForwardRecord(
        UUID id,
        UUID sourceDeviceId,
        UUID remoteServiceId,
        String name,
        String localBindHost,
        int localBindPort,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
