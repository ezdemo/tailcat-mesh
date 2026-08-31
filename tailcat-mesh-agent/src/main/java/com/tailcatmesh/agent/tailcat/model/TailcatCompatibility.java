package com.tailcatmesh.agent.tailcat.model;

/** Compatibility result for the Tailcat CLI version policy. */
public enum TailcatCompatibility {
    SUPPORTED,
    UNSUPPORTED_OLDER,
    UNSUPPORTED_NEWER,
    UNKNOWN
}
