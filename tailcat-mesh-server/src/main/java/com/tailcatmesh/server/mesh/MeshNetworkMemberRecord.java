package com.tailcatmesh.server.mesh;

import java.time.Instant;
import java.util.UUID;

/** Persisted membership and stable virtual IPv4 assignment. */
public record MeshNetworkMemberRecord(
        UUID id,
        UUID networkId,
        UUID deviceId,
        String virtualIpv4,
        Instant joinedAt,
        boolean enabled
) {
}
