package com.tailcatmesh.server.common;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/** Stable, JSON-safe failure at a control-plane API boundary. */
public final class ControlPlaneException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ControlPlaneException(String code, HttpStatus status, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
