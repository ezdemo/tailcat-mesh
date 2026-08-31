package com.tailcatmesh.agent.virtual;

/** Failure while creating, configuring, or closing the M7 virtual interface. */
public final class TunRuntimeException extends RuntimeException {

    public TunRuntimeException(String message) {
        super(message);
    }

    public TunRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
