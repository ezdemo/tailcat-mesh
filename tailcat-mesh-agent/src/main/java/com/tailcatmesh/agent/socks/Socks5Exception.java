package com.tailcatmesh.agent.socks;

/** Stable failure raised by the minimal SOCKS5 CONNECT boundary. */
public final class Socks5Exception extends RuntimeException {

    private final String code;

    public Socks5Exception(String code, String message) {
        super(message);
        this.code = code;
    }

    public Socks5Exception(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
