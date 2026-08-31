package com.tailcatmesh.server.forward;

import java.time.Instant;
import java.util.UUID;

/** Admin projection combining Local Forward configuration and runtime state. */
public record ForwardView(
        UUID id,
        UUID sourceDeviceId,
        String sourceDeviceName,
        UUID remoteServiceId,
        String remoteServiceName,
        UUID remoteDeviceId,
        String remoteDeviceName,
        String name,
        String localBindHost,
        int localBindPort,
        boolean enabled,
        String status,
        String errorCode,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
