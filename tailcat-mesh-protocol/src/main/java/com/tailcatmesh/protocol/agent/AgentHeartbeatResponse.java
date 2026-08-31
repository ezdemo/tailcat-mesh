package com.tailcatmesh.protocol.agent;

import java.util.UUID;

/** Control-plane acknowledgement for an Agent heartbeat. */
public record AgentHeartbeatResponse(UUID deviceId, String status, long desiredRevision, boolean accepted) {
}
