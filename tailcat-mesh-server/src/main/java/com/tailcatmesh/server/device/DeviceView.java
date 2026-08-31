package com.tailcatmesh.server.device;

import java.time.Instant;
import java.util.UUID;

/** Safe admin projection; the ConnBlob itself is deliberately omitted. */
public record DeviceView(
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
        String serverConnBlobHash,
        Instant lastSeenAt,
        long desiredRevision,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeviceView from(DeviceRecord device) {
        return new DeviceView(
                device.id(), device.networkId(), device.name(), device.hostname(), device.os(), device.arch(),
                device.status(), device.agentVersion(), device.tailcatVersion(), device.clientPublicKey(),
                device.serverConnBlobHash(), device.lastSeenAt(), device.desiredRevision(),
                device.createdAt(), device.updatedAt()
        );
    }
}
