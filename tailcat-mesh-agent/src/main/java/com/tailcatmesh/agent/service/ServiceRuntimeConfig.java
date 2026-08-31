package com.tailcatmesh.agent.service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Validated runtime configuration for one loopback ServiceBridge. */
public record ServiceRuntimeConfig(
        UUID serviceId,
        String bindHost,
        int requestedBridgePort,
        String upstreamHost,
        int upstreamPort,
        Duration connectTimeout,
        Duration idleTimeout
) {
    public ServiceRuntimeConfig {
        Objects.requireNonNull(serviceId, "serviceId");
        bindHost = required(bindHost, "bindHost");
        if (!"127.0.0.1".equals(bindHost)) {
            throw new IllegalArgumentException("ServiceBridge bindHost must be 127.0.0.1");
        }
        if (requestedBridgePort < 0 || requestedBridgePort > 65_535) {
            throw new IllegalArgumentException("requestedBridgePort must be between 0 and 65535");
        }
        upstreamHost = required(upstreamHost, "upstreamHost");
        if (upstreamHost.indexOf(' ') >= 0 || upstreamHost.indexOf('\r') >= 0
                || upstreamHost.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("upstreamHost must be a single value without spaces");
        }
        if (upstreamPort < 1 || upstreamPort > 65_535) {
            throw new IllegalArgumentException("upstreamPort must be between 1 and 65535");
        }
        connectTimeout = positive(connectTimeout, "connectTimeout");
        idleTimeout = positive(idleTimeout, "idleTimeout");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
