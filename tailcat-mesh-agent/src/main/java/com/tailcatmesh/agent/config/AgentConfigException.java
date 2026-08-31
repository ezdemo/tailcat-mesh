package com.tailcatmesh.agent.config;

/** Configuration error reported before the Agent starts any child process. */
public final class AgentConfigException extends RuntimeException {

    private final String code;

    public AgentConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
