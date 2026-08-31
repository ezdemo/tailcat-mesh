package com.tailcatmesh.server.device;

import java.time.Instant;
import java.util.UUID;

/** Persisted device projection used by enrollment and heartbeat flows. */
public record DeviceRecord(
        UUID id,
        UUID networkId,
        String name,
        String hostname,
        String os,
        String arch,
        DeviceStatus status,
        String agentVersion,
        String tailcatVersion,
        String clientPublicKey,
        String serverConnBlob,
        String serverConnBlobHash,
        Instant lastSeenAt,
        long desiredRevision,
        Instant createdAt,
        Instant updatedAt
) {
}
