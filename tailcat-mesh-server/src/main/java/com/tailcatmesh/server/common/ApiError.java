package com.tailcatmesh.server.common;

import java.time.Instant;

/** Consistent error response returned by the Server REST API. */
public record ApiError(String code, String message, Instant timestamp) {

    public ApiError(String code, String message) {
        this(code, message, Instant.now());
    }
}
