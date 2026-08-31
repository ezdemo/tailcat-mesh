package com.tailcatmesh.agent.tailcat.model;

import java.nio.file.Path;
import java.util.Objects;

/** Paths for the two persistent Tailcat identities owned by an Agent. */
public record TailcatIdentityConfig(Path serverKeyPath, Path clientKeyPath) {

    public TailcatIdentityConfig {
        serverKeyPath = normalize(Objects.requireNonNull(serverKeyPath, "serverKeyPath"));
        clientKeyPath = normalize(Objects.requireNonNull(clientKeyPath, "clientKeyPath"));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
