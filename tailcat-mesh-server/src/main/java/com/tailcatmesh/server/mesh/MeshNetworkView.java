package com.tailcatmesh.server.mesh;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin projection of an M7 virtual network and its members. */
public record MeshNetworkView(
        UUID id,
        String name,
        String slug,
        String cidr,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        List<MeshNetworkMemberView> members,
        List<MeshNetworkPeerView> peerPaths
) {
    public MeshNetworkView {
        members = members == null ? List.of() : List.copyOf(members);
        peerPaths = peerPaths == null ? List.of() : List.copyOf(peerPaths);
    }

    /** Backward-compatible constructor for callers that only need membership. */
    public MeshNetworkView(UUID id, String name, String slug, String cidr, boolean enabled,
                           Instant createdAt, Instant updatedAt,
                           List<MeshNetworkMemberView> members) {
        this(id, name, slug, cidr, enabled, createdAt, updatedAt, members, List.of());
    }
}
