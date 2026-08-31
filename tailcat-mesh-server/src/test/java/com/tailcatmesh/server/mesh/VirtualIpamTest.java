package com.tailcatmesh.server.mesh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualIpamTest {

    private final VirtualIpam ipam = new VirtualIpam(() -> List.of());

    @Test
    void canonicalizesNetworksAndRejectsUnsupportedHostRanges() {
        assertEquals("10.77.1.0/24", ipam.canonicalizeNetworkCidr("10.77.1.42/24"));
        assertThrows(IllegalArgumentException.class,
                () -> ipam.canonicalizeNetworkCidr("10.77.1.0/30"));
        assertThrows(IllegalArgumentException.class,
                () -> ipam.canonicalizeNetworkCidr("not-an-ip/24"));
    }

    @Test
    void detectsOverlapAndLocalCidrConflict() {
        VirtualIpam withLocalNetwork = new VirtualIpam(() -> List.of("192.168.50.0/24"));
        assertThrows(IllegalArgumentException.class,
                () -> withLocalNetwork.ensureNoOverlap("192.168.50.0/25", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ipam.ensureNoOverlap("10.77.2.0/24", List.of("10.77.2.128/25")));
    }

    @Test
    void allocatesMemberAddressesWithoutUsingNetworkOrGatewaySlots() {
        assertEquals("10.77.3.2", ipam.allocateMemberIp(
                "10.77.3.0/24", List.of(), null));
        assertEquals("10.77.3.3", ipam.allocateMemberIp(
                "10.77.3.0/24", List.of("10.77.3.2"), null));
        assertEquals("10.77.3.8", ipam.allocateMemberIp(
                "10.77.3.0/24", List.of(), "10.77.3.8"));
        assertThrows(IllegalArgumentException.class,
                () -> ipam.allocateMemberIp("10.77.3.0/24", List.of(), "10.77.3.1"));
    }

    @Test
    void defaultPoolReturnsNonOverlappingSubnets() {
        assertEquals("10.77.0.0/24", VirtualIpam.nextDefaultCidr(List.of()));
        assertEquals("10.77.1.0/24", VirtualIpam.nextDefaultCidr(List.of("10.77.0.0/24")));
    }
}
