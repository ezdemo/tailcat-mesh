package com.tailcatmesh.protocol.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Runtime projection of one Agent-owned Local Forward listener. */
public record AgentForwardRuntime(
        UUID forwardId,
        String status,
        String errorCode,
        String lastError
) {
    public AgentForwardRuntime {
        Objects.requireNonNull(forwardId, "forwardId");
        status = status == null || status.isBlank()
                ? "ERROR" : status.trim().toUpperCase(Locale.ROOT);
        if (!switch (status) {
            case "STARTING", "READY", "ERROR", "STOPPED" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("unsupported forward runtime status: " + status);
        }
        errorCode = normalize(errorCode, "errorCode", 64);
        lastError = normalize(lastError, "lastError", 2_000);
    }

    private static String normalize(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is too long or invalid");
        }
        return value.trim();
    }
}
