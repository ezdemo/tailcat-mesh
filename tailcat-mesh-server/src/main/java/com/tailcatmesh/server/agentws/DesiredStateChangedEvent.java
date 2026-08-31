package com.tailcatmesh.server.agentws;

import java.util.Objects;
import java.util.UUID;

/** Published when one device or an entire mesh network needs Desired State sync. */
public record DesiredStateChangedEvent(UUID networkId, UUID deviceId) {

    public DesiredStateChangedEvent {
        Objects.requireNonNull(networkId, "networkId");
    }

    /** Publishes a network-wide membership change. */
    public DesiredStateChangedEvent(UUID networkId) {
        this(networkId, null);
    }
}
