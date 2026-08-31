package com.tailcatmesh.agent.virtual;

/** Failure raised while binding or running the Java Virtual IPv4 SOCKS router. */
public class MeshSocksRouterException extends RuntimeException {

    private final String code;

    public MeshSocksRouterException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MeshSocksRouterException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
