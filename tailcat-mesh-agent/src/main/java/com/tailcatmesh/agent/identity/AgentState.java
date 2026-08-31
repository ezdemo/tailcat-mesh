package com.tailcatmesh.agent.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Local registration state; the enrollment token is intentionally not stored. */
public record AgentState(UUID deviceId, String agentCredential, Instant enrolledAt) {
    public AgentState {
        Objects.requireNonNull(deviceId, "deviceId");
        if (agentCredential == null || agentCredential.isBlank()) {
            throw new IllegalArgumentException("agentCredential must not be blank");
        }
        Objects.requireNonNull(enrolledAt, "enrolledAt");
    }
}
