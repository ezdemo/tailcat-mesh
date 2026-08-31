package com.tailcatmesh.agent.tailcat.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Complete command-level configuration for one Tailcat server process. */
public record TailcatServerConfig(
        Path serverKeyPath,
        List<Integer> servedPorts,
        List<String> allowedClientPublicKeys,
        boolean fullAddress,
        String derpMapUrl
) {
    public TailcatServerConfig {
        serverKeyPath = Objects.requireNonNull(serverKeyPath, "serverKeyPath").toAbsolutePath().normalize();
        servedPorts = servedPorts == null ? List.of() : List.copyOf(servedPorts);
        allowedClientPublicKeys = allowedClientPublicKeys == null
                ? List.of()
                : List.copyOf(allowedClientPublicKeys);
        for (Integer port : servedPorts) {
            if (port == null || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("served ports must be between 1 and 65535");
            }
        }
        if (derpMapUrl != null && derpMapUrl.isBlank()) {
            derpMapUrl = null;
        }
    }
}
