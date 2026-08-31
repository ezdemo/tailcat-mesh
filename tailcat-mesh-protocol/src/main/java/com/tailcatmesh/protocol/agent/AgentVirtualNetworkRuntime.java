package com.tailcatmesh.protocol.agent;

import java.util.UUID;

/** Runtime projection for one Device x MeshNetwork Tailcat server. */
public record AgentVirtualNetworkRuntime(
        UUID networkId,
        String status,
        String connBlob,
        String errorCode,
        String lastError
) {
}
