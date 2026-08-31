package com.tailcatmesh.agent.status;

import java.util.UUID;

/** Non-sensitive projection of one network runtime for the local Desktop UI. */
public record LocalNetworkStatus(
        UUID networkId,
        String name,
        String cidr,
        String virtualIpv4,
        String status,
        String path,
        String lastError
) {
}
