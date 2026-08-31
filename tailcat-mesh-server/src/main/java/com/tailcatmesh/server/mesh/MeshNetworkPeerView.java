package com.tailcatmesh.server.mesh;

import com.tailcatmesh.server.peer.PeerStatus;

import java.time.Instant;
import java.util.UUID;

/** Admin-safe Peer path projection scoped to the members of one Mesh Network. */
public record MeshNetworkPeerView(
        UUID sourceDeviceId,
        String sourceDeviceName,
        UUID peerDeviceId,
        String peerDeviceName,
        PeerStatus status,
        String pathType,
        Double latencyMs,
        String derpRegion,
        String directEndpoint,
        Instant lastCheckAt,
        String lastError
) {
}
