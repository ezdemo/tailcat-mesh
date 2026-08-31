package com.tailcatmesh.agent.tailcat.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Runtime handle for one long-lived official Tailcat SOCKS process. */
public record TailcatPeerProxyHandle(
        UUID peerDeviceId,
        ManagedProcess process,
        String localSocksHost,
        int localSocksPort,
        String connBlob,
        Instant startedAt
) {
    public TailcatPeerProxyHandle {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(process, "process");
        if (localSocksHost == null || localSocksHost.isBlank()) {
            throw new IllegalArgumentException("localSocksHost must not be blank");
        }
        if (localSocksPort < 1 || localSocksPort > 65_535) {
            throw new IllegalArgumentException("localSocksPort must be between 1 and 65535");
        }
        if (connBlob == null || connBlob.isBlank()) {
            throw new IllegalArgumentException("connBlob must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /** Maps the supervised process state to the Agent-facing peer status. */
    public String status() {
        return switch (process.state()) {
            case RUNNING -> "READY";
            case STARTING, STOPPING -> "STARTING";
            case FAILED -> "DEGRADED";
            case NEW, STOPPED -> "STOPPED";
        };
    }

    public int restartCount() {
        return process.restartCount();
    }
}
