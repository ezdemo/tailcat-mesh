package com.tailcatmesh.agent.tailcat.model;

import java.nio.file.Path;
import java.util.Objects;

/** Local-only configuration for one persistent official Tailcat SOCKS process. */
public record TailcatPeerProxyConfig(Path clientKeyPath, String listenHost, int listenPort) {
    public TailcatPeerProxyConfig {
        clientKeyPath = Objects.requireNonNull(clientKeyPath, "clientKeyPath").toAbsolutePath().normalize();
        if (!"127.0.0.1".equals(listenHost)) {
            throw new IllegalArgumentException("peer SOCKS must listen on 127.0.0.1");
        }
        if (listenPort < 0 || listenPort > 65_535) {
            throw new IllegalArgumentException("listenPort must be 0 or between 1 and 65535");
        }
    }
}
