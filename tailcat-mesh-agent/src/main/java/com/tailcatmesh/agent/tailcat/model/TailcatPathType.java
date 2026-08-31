package com.tailcatmesh.agent.tailcat.model;

/** Network path reported by the official Tailcat ping command. */
public enum TailcatPathType {
    DIRECT,
    DERP,
    OFFLINE,
    UNKNOWN
}
