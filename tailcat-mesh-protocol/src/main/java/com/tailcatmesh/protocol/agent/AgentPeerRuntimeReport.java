package com.tailcatmesh.protocol.agent;

import java.time.Instant;
import java.util.List;

/** Complete peer path snapshot sent by an Agent. */
public record AgentPeerRuntimeReport(
        List<AgentPeerRuntime> peers,
        Instant timestamp
) {
    public AgentPeerRuntimeReport {
        peers = peers == null ? List.of() : List.copyOf(peers);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
