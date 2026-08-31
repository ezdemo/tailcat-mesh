package com.tailcatmesh.server.forward;

import java.time.Instant;
import java.util.UUID;

/** Last runtime state reported for one Local Forward listener. */
public record ForwardRuntimeRecord(
        UUID forwardId,
        String status,
        String errorCode,
        String lastError,
        Instant updatedAt
) {
}
