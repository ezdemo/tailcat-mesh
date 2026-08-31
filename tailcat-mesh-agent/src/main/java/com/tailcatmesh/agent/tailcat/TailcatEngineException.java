package com.tailcatmesh.agent.tailcat;

/** Unchecked failure raised at the Tailcat CLI Engine boundary. */
public class TailcatEngineException extends RuntimeException {

    private final String code;

    public TailcatEngineException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TailcatEngineException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
