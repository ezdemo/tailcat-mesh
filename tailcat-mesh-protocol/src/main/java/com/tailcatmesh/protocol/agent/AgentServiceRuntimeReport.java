package com.tailcatmesh.protocol.agent;

import java.time.Instant;
import java.util.List;

/** Complete ServiceBridge runtime snapshot sent by an Agent. */
public record AgentServiceRuntimeReport(
        List<AgentServiceRuntime> services,
        Instant timestamp
) {
    public AgentServiceRuntimeReport {
        services = services == null ? List.of() : List.copyOf(services);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
