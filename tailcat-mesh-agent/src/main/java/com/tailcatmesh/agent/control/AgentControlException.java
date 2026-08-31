package com.tailcatmesh.agent.control;

/** Stable control-plane failure without retaining raw credentials in the message. */
public final class AgentControlException extends RuntimeException {

    private final String code;
    private final int status;

    public AgentControlException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public AgentControlException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
