package com.tailcatmesh.protocol.agent;

import java.time.Instant;

/** Runtime projection for the Agent-owned Tailcat Server process. */
public record AgentRuntimeServerRequest(
        boolean running,
        String listenAddress,
        String connBlob,
        Instant timestamp
) {
}
