package com.tailcatmesh.server.peer;

import java.time.Instant;
import java.util.UUID;

/** Safe admin projection for the Connections page. */
public record PeerStatusView(
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
