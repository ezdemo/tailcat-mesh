package com.tailcatmesh.server.mesh;

import java.time.Instant;
import java.util.UUID;

/** Last runtime state reported for one Device x MeshNetwork Tailcat server. */
public record VirtualNetworkRuntimeRecord(
        UUID networkId,
        UUID deviceId,
        String connBlob,
        String connBlobHash,
        String status,
        String errorCode,
        String lastError,
        Instant updatedAt
) {
}
