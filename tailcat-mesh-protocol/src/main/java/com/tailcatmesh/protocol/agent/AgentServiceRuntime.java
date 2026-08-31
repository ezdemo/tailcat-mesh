package com.tailcatmesh.protocol.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Runtime projection reported by an Agent-owned ServiceBridge. */
public record AgentServiceRuntime(
        UUID serviceId,
        Integer bridgePort,
        String status,
        String lastError
) {
    public AgentServiceRuntime {
        Objects.requireNonNull(serviceId, "serviceId");
        status = status == null || status.isBlank()
                ? "FAILED" : status.trim().toUpperCase(Locale.ROOT);
        if (!switch (status) {
            case "STARTING", "READY", "FAILED", "STOPPED" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("unsupported service runtime status: " + status);
        }
        if (bridgePort != null && (bridgePort < 1 || bridgePort > 65_535)) {
            throw new IllegalArgumentException("bridgePort must be between 1 and 65535");
        }
        if (lastError != null && lastError.length() > 2_000) {
            throw new IllegalArgumentException("lastError is too long");
        }
    }
}
