package com.tailcatmesh.protocol.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Service configuration delivered to the Agent as part of Desired State. */
public record AgentService(
        UUID serviceId,
        String name,
        String protocol,
        String targetHost,
        int targetPort,
        boolean enabled
) {
    public AgentService {
        Objects.requireNonNull(serviceId, "serviceId");
        name = requiredText(name, "name", 255);
        protocol = requiredText(protocol, "protocol", 16).toUpperCase(Locale.ROOT);
        if (!"TCP".equals(protocol)) {
            throw new IllegalArgumentException("only TCP services are supported");
        }
        targetHost = requiredHost(targetHost);
        if (targetPort < 1 || targetPort > 65_535) {
            throw new IllegalArgumentException("targetPort must be between 1 and 65535");
        }
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be a single short value");
        }
        return value.trim();
    }

    private static String requiredHost(String value) {
        String host = requiredText(value, "targetHost", 255);
        if (host.contains(" ")) {
            throw new IllegalArgumentException("targetHost must not contain spaces");
        }
        return host;
    }
}
