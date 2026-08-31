package com.tailcatmesh.agent.tailcat.model;

import java.nio.file.Path;
import java.util.Objects;

/** Public view of the local Tailcat identities; private key bytes never leave the Agent. */
public record TailcatIdentity(
        Path serverKeyPath,
        Path clientKeyPath,
        String clientPublicKey
) {
    public TailcatIdentity {
        serverKeyPath = Objects.requireNonNull(serverKeyPath, "serverKeyPath").toAbsolutePath().normalize();
        clientKeyPath = Objects.requireNonNull(clientKeyPath, "clientKeyPath").toAbsolutePath().normalize();
        if (clientPublicKey == null || clientPublicKey.isBlank()) {
            throw new IllegalArgumentException("clientPublicKey must not be blank");
        }
    }
}
