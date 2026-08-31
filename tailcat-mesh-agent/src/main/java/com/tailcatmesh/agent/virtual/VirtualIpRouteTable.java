package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable-snapshot lookup from a Mesh virtual IPv4 to one network-scoped peer SOCKS endpoint. */
public final class VirtualIpRouteTable {

    private volatile Map<String, Route> routes = Map.of();

    /** Replaces the complete route snapshot; duplicate virtual IPv4 values fail closed. */
    public void replace(Collection<Route> nextRoutes) {
        Objects.requireNonNull(nextRoutes, "nextRoutes");
        Map<String, Route> replacement = new LinkedHashMap<>();
        for (Route route : nextRoutes) {
            Objects.requireNonNull(route, "route");
            Route previous = replacement.put(route.virtualIpv4(), route);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "virtual IPv4 is mapped more than once: " + route.virtualIpv4());
            }
        }
        routes = Map.copyOf(replacement);
    }

    public void clear() {
        routes = Map.of();
    }

    public Optional<Route> resolve(String virtualIpv4) {
        String canonical = canonicalizeIpv4(virtualIpv4);
        return Optional.ofNullable(routes.get(canonical));
    }

    public List<Route> snapshot() {
        return routes.values().stream()
                .sorted(java.util.Comparator.comparing(Route::virtualIpv4))
                .toList();
    }

    public int size() {
        return routes.size();
    }

    public record Route(
            UUID networkId,
            UUID peerDeviceId,
            String virtualIpv4,
            PeerSocksEndpoint peerSocks
    ) {
        public Route {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(peerDeviceId, "peerDeviceId");
            virtualIpv4 = canonicalizeIpv4(virtualIpv4);
            Objects.requireNonNull(peerSocks, "peerSocks");
        }
    }

    static String canonicalizeIpv4(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("virtual IPv4 is required");
        }
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("virtual IPv4 must contain four decimal octets");
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                throw new IllegalArgumentException("virtual IPv4 contains an invalid octet");
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("virtual IPv4 contains an invalid octet", exception);
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("virtual IPv4 contains an invalid octet");
            }
            if (index > 0) {
                canonical.append('.');
            }
            canonical.append(octet);
        }
        return canonical.toString();
    }
}
