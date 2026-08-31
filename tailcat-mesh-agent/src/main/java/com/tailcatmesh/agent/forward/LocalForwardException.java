package com.tailcatmesh.agent.forward;

/** Explicit Agent error for one Local Forward operation. */
public final class LocalForwardException extends RuntimeException {

    private final String code;

    public LocalForwardException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public LocalForwardException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String value) {
        if (value == null || value.isBlank() || value.length() > 64
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("code must be a short single-line value");
        }
        return value.trim();
    }
}
