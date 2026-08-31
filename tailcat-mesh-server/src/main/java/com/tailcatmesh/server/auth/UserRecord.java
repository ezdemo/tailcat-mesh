package com.tailcatmesh.server.auth;

import java.time.Instant;
import java.util.UUID;

/** Persisted administrator account projection. */
public record UserRecord(
        UUID id,
        String username,
        String passwordHash,
        String role,
        Instant createdAt,
        Instant updatedAt
) {
}
