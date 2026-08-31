package com.tailcatmesh.agent.virtual;

import java.util.Objects;
import java.util.UUID;

/** One Mesh CIDR route owned by this Agent's Virtual LAN data plane. */
public record OsRoute(UUID networkId, Ipv4Cidr cidr, String interfaceName, Integer interfaceIndex,
                      String nextHop) {

    /** Backwards-compatible constructor for platform routes without a gateway. */
    public OsRoute(UUID networkId, Ipv4Cidr cidr, String interfaceName, Integer interfaceIndex) {
        this(networkId, cidr, interfaceName, interfaceIndex, null);
    }

    public OsRoute {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(cidr, "cidr");
        if (cidr.isDefaultRoute()) {
            throw new IllegalArgumentException("Virtual LAN must never install the default route");
        }
        cidr = new Ipv4Cidr(cidr.networkAddress(), cidr.prefixLength());
        interfaceName = validateInterfaceName(interfaceName);
        if (interfaceIndex != null && interfaceIndex < 1) {
            throw new IllegalArgumentException("interfaceIndex must be positive");
        }
        if (nextHop != null) {
            nextHop = new Ipv4Cidr(nextHop, 32).address();
        }
    }

    public String networkCidr() {
        return cidr.networkValue();
    }

    private static String validateInterfaceName(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("interfaceName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("interfaceName is too long");
        }
        return normalized;
    }
}
