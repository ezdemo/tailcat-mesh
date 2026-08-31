package com.tailcatmesh.protocol.agent;

import java.time.Instant;
import java.util.List;

/** Complete runtime snapshot reported by an Agent for its virtual networks. */
public record AgentVirtualNetworkRuntimeReport(
        List<AgentVirtualNetworkRuntime> virtualNetworks,
        Instant timestamp
) {
    public AgentVirtualNetworkRuntimeReport {
        virtualNetworks = virtualNetworks == null ? List.of() : List.copyOf(virtualNetworks);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
