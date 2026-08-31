package com.tailcatmesh.agent.service;

import java.util.Objects;

/** Stable Agent error for ServiceBridge lifecycle failures. */
public final class ServiceBridgeException extends RuntimeException {

    private final String code;

    public ServiceBridgeException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ServiceBridgeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
