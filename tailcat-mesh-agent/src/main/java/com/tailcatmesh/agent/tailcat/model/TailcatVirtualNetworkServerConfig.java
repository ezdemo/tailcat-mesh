package com.tailcatmesh.agent.tailcat.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Command-level configuration for one isolated virtual-network Tailcat server. */
public record TailcatVirtualNetworkServerConfig(
        Path serverKeyPath,
        List<String> allowedClientPublicKeys,
        boolean fullAddress,
        String derpMapUrl
) {
    public TailcatVirtualNetworkServerConfig {
        serverKeyPath = Objects.requireNonNull(serverKeyPath, "serverKeyPath")
                .toAbsolutePath().normalize();
        allowedClientPublicKeys = allowedClientPublicKeys == null
                ? List.of() : List.copyOf(allowedClientPublicKeys);
        if (derpMapUrl != null && derpMapUrl.isBlank()) {
            derpMapUrl = null;
        }
    }
}
