package com.tailcatmesh.agent.tailcat.model;

import java.util.Objects;

/** Semantic version reported by {@code tailcat --version}. */
public record TailcatVersion(int major, int minor, int patch, String raw) {

    public TailcatVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
        raw = Objects.requireNonNull(raw, "raw").trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("raw version must not be blank");
        }
    }

    public boolean isV03x() {
        return major == 0 && minor == 3;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
