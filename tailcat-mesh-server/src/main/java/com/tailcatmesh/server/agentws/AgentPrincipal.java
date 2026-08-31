package com.tailcatmesh.server.agentws;

import com.tailcatmesh.server.device.DeviceStatus;

import java.util.UUID;

/** Authenticated Agent identity resolved from a bearer credential. */
public record AgentPrincipal(UUID credentialId, UUID deviceId, DeviceStatus deviceStatus) {
}
