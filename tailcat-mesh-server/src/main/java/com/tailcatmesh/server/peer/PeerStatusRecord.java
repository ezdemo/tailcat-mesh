package com.tailcatmesh.server.peer;

import java.time.Instant;
import java.util.UUID;

/** Persisted Agent-reported path state for a source/peer pair. */
public record PeerStatusRecord(
        UUID sourceDeviceId,
        UUID peerDeviceId,
        PeerStatus status,
        String pathType,
        Double latencyMs,
        String derpRegion,
        String directEndpoint,
        Instant lastCheckAt,
        String lastError
) {
}
