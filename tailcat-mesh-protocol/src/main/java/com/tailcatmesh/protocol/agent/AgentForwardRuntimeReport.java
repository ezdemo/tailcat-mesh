package com.tailcatmesh.protocol.agent;

import java.time.Instant;
import java.util.List;

/** Complete Local Forward runtime snapshot sent by an Agent. */
public record AgentForwardRuntimeReport(
        List<AgentForwardRuntime> forwards,
        Instant timestamp
) {
    public AgentForwardRuntimeReport {
        forwards = forwards == null ? List.of() : List.copyOf(forwards);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
