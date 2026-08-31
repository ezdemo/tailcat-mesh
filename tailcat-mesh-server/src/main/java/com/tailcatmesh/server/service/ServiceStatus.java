package com.tailcatmesh.server.service;

/** Runtime states accepted from an Agent ServiceBridge. */
public enum ServiceStatus {
    STARTING,
    READY,
    FAILED,
    STOPPED
}
