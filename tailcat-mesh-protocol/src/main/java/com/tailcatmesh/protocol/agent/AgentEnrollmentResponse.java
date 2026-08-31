package com.tailcatmesh.protocol.agent;

import java.util.UUID;

/** One-time registration response returned by the control plane. */
public record AgentEnrollmentResponse(UUID deviceId, String agentCredential, String status) {
}
