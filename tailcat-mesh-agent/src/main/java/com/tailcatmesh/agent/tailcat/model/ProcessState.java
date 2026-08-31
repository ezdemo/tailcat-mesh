package com.tailcatmesh.agent.tailcat.model;

/** Lifecycle states exposed by a supervised Tailcat child process. */
public enum ProcessState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
