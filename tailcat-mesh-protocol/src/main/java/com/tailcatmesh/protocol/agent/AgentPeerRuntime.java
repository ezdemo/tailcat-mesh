package com.tailcatmesh.protocol.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Path and reachability state reported for one remote Peer. */
public record AgentPeerRuntime(
        UUID peerDeviceId,
        String status,
        String pathType,
        double latencyMs,
        String derpRegion,
        String directEndpoint,
        String lastError
) {
    public AgentPeerRuntime {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        status = normalizeStatus(status);
        pathType = normalizePathType(pathType);
        if (Double.isNaN(latencyMs) || Double.isInfinite(latencyMs) || latencyMs < -1) {
            throw new IllegalArgumentException("latencyMs must be -1 or non-negative");
        }
        if (lastError != null && lastError.length() > 2_000) {
            throw new IllegalArgumentException("lastError is too long");
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
        if (!switch (normalized) {
            case "ONLINE", "DEGRADED", "OFFLINE", "UNKNOWN", "STOPPED" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("unsupported peer runtime status: " + normalized);
        }
        return normalized;
    }

    private static String normalizePathType(String value) {
        String normalized = value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
        if (!switch (normalized) {
            case "DIRECT", "DERP", "OFFLINE", "UNKNOWN" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("unsupported peer path type: " + normalized);
        }
        return normalized;
    }
}
