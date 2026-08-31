package com.tailcatmesh.server.enrollment;

import java.time.Instant;
import java.util.UUID;

/** Persisted enrollment-token projection; the raw token is never part of it. */
public record EnrollmentTokenRecord(
        UUID id,
        UUID networkId,
        String tokenHash,
        Instant expiresAt,
        int maxUses,
        int usedCount,
        boolean enabled,
        Instant createdAt
) {
}
