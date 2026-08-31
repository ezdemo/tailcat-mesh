package com.tailcatmesh.server.peer;

/** Reachability state reported by an Agent for one mesh Peer. */
public enum PeerStatus {
    ONLINE,
    DEGRADED,
    OFFLINE,
    UNKNOWN,
    STOPPED
}
