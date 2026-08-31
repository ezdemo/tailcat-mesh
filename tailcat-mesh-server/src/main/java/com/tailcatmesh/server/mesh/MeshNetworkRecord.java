package com.tailcatmesh.server.mesh;

import java.time.Instant;
import java.util.UUID;

/** Persisted mesh network projection. */
public record MeshNetworkRecord(
        UUID id,
        String name,
        String slug,
        Instant createdAt,
        Instant updatedAt
) {
}
