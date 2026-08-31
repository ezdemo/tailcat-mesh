package com.tailcatmesh.server.mesh;

import java.time.Instant;
import java.util.UUID;

/** Persisted mesh network projection. */
public record MeshNetworkRecord(
        UUID id,
        String name,
        String slug,
        String cidr,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Compatibility constructor for the M1-M6 control-plane network projection.
     *
     * <p>Older callers only created a control-plane network. The repository
     * assigns the first available virtual-LAN CIDR when this constructor is
     * used, so existing tests and bootstrap code do not silently create
     * overlapping M7 networks.</p>
     */
    public MeshNetworkRecord(UUID id, String name, String slug,
                             Instant createdAt, Instant updatedAt) {
        this(id, name, slug, null, true, createdAt, updatedAt);
    }
}
