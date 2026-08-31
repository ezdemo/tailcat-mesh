package com.tailcatmesh.protocol.agent;

import java.time.Instant;

/** Periodic runtime state reported by an Agent. */
public record AgentHeartbeatRequest(
        String agentVersion,
        String tailcatVersion,
        long desiredRevision,
        boolean tailcatServerRunning,
        String serverConnBlobHash,
        int servicesUp,
        int forwardsUp,
        Instant timestamp
) {
}
