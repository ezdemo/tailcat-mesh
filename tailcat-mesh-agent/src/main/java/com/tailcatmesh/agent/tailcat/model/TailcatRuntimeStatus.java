package com.tailcatmesh.agent.tailcat.model;

/** Snapshot of the local Tailcat server process. */
public record TailcatRuntimeStatus(
        ProcessState state,
        String listenAddress,
        Integer exitCode,
        String stderrTail,
        int restartCount
) {
    public TailcatRuntimeStatus {
        if (state == null) {
            throw new NullPointerException("state");
        }
        stderrTail = stderrTail == null ? "" : stderrTail;
        if (restartCount < 0) {
            throw new IllegalArgumentException("restartCount must be non-negative");
        }
    }
}
