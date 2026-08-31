package com.tailcatmesh.agent.forward;

/** Loopback endpoint exposed by one supervised Tailcat Peer SOCKS process. */
public record PeerSocksEndpoint(String host, int port) {

    public PeerSocksEndpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        host = host.trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
