package com.tailcatmesh.agent.virtual;

/** Failure while invoking an operating-system network command. */
public final class OsCommandException extends RuntimeException {

    public OsCommandException(String message) {
        super(message);
    }

    public OsCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
