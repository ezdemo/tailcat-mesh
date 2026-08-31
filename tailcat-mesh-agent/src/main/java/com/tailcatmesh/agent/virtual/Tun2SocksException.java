package com.tailcatmesh.agent.virtual;

/** Failure while starting or supervising the replaceable tun2socks sidecar. */
public final class Tun2SocksException extends RuntimeException {

    public Tun2SocksException(String message) {
        super(message);
    }

    public Tun2SocksException(String message, Throwable cause) {
        super(message, cause);
    }
}
