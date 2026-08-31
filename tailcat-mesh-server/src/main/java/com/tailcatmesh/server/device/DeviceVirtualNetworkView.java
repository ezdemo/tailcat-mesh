package com.tailcatmesh.server.device;

import java.util.UUID;

/** Admin-safe Virtual Network membership projection for a device detail view. */
public record DeviceVirtualNetworkView(
        UUID networkId,
        String networkName,
        String networkSlug,
        String cidr,
        String virtualIpv4,
        boolean networkEnabled,
        boolean memberEnabled
) {
}
