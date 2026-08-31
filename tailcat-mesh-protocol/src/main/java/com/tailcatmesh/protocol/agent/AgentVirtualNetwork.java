package com.tailcatmesh.protocol.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Per-device desired state for one independent virtual Mesh Network runtime. */
public record AgentVirtualNetwork(
        UUID networkId,
        String name,
        String cidr,
        String virtualIpv4,
        boolean enabled,
        List<AgentVirtualNetworkPeer> peers
) {

    public AgentVirtualNetwork {
        Objects.requireNonNull(networkId, "networkId");
        name = requiredText(name, "name", 128);
        cidr = requiredText(cidr, "cidr", 43);
        virtualIpv4 = requiredText(virtualIpv4, "virtualIpv4", 15);
        peers = peers == null ? List.of() : List.copyOf(peers);
        Set<UUID> peerIds = new HashSet<>();
        for (AgentVirtualNetworkPeer peer : peers) {
            if (peer == null || !peerIds.add(peer.peerDeviceId())) {
                throw new IllegalArgumentException("virtual network peers must be non-null and unique");
            }
        }
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be a single short value");
        }
        return value.trim();
    }
}
