package com.tailcatmesh.server.mesh;

import com.tailcatmesh.server.device.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

/** Admin-safe membership projection with the current device status. */
public record MeshNetworkMemberView(
        UUID id,
        UUID networkId,
        UUID deviceId,
        String deviceName,
        String hostname,
        DeviceStatus deviceStatus,
        String virtualIpv4,
        Instant joinedAt,
        boolean enabled
) {
}
