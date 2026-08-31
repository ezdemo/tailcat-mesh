package com.tailcatmesh.server.forward;

/** Runtime states accepted from an Agent Local Forward. */
public enum ForwardStatus {
    STARTING,
    READY,
    ERROR,
    STOPPED
}
