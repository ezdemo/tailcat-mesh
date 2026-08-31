package com.tailcatmesh.agent.tailcat.model;

import java.time.Instant;
import java.util.Objects;

/** Handle returned after a Tailcat server has emitted a valid JSON listen address. */
public record TailcatServerHandle(
        ManagedProcess process,
        String listenAddress,
        Instant startedAt
) {
    public TailcatServerHandle {
        Objects.requireNonNull(process, "process");
        if (listenAddress == null || listenAddress.isBlank()) {
            throw new IllegalArgumentException("listenAddress must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
