package com.tailcatmesh.server.enrollment;

import java.time.Instant;
import java.util.UUID;

/** Persisted Agent credential projection; only the hash is stored. */
public record AgentCredentialRecord(
        UUID id,
        UUID deviceId,
        String secretHash,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {
}
