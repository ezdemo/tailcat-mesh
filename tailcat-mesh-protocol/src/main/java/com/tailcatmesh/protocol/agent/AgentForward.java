package com.tailcatmesh.protocol.agent;

import java.util.Objects;
import java.util.UUID;

/** Local TCP forward configuration delivered to its source Agent. */
public record AgentForward(
        UUID forwardId,
        String name,
        UUID peerDeviceId,
        UUID remoteServiceId,
        String localBindHost,
        int localBindPort,
        Integer remoteBridgePort,
        boolean enabled
) {
    public AgentForward {
        Objects.requireNonNull(forwardId, "forwardId");
        name = requiredText(name, "name", 255);
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(remoteServiceId, "remoteServiceId");
        localBindHost = requiredBindHost(localBindHost);
        if (localBindPort < 1 || localBindPort > 65_535) {
            throw new IllegalArgumentException("localBindPort must be between 1 and 65535");
        }
        if (remoteBridgePort != null && (remoteBridgePort < 1 || remoteBridgePort > 65_535)) {
            throw new IllegalArgumentException("remoteBridgePort must be between 1 and 65535");
        }
    }

    private static String requiredBindHost(String value) {
        String host = requiredText(value, "localBindHost", 255);
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException("localBindHost must be 127.0.0.1 or ::1");
        }
        return host;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be a single short value");
        }
        return value.trim();
    }
}
