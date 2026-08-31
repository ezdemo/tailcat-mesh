package com.tailcatmesh.agent.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stable, non-secret local status projection consumed by the Electron shell. */
public record LocalAgentStatus(
        String status,
        String controlPlaneStatus,
        UUID deviceId,
        String deviceName,
        String serverUrl,
        String agentState,
        long pid,
        String tailcatVersion,
        String tailcatState,
        List<LocalNetworkStatus> networks,
        String lastError,
        Instant updatedAt
) {
    public LocalAgentStatus {
        status = status == null || status.isBlank() ? "STARTING" : status;
        controlPlaneStatus = controlPlaneStatus == null || controlPlaneStatus.isBlank()
                ? "UNKNOWN" : controlPlaneStatus;
        deviceName = deviceName == null ? "" : deviceName;
        serverUrl = serverUrl == null ? "" : serverUrl;
        agentState = agentState == null || agentState.isBlank() ? "RUNNING" : agentState;
        tailcatVersion = tailcatVersion == null ? "" : tailcatVersion;
        tailcatState = tailcatState == null || tailcatState.isBlank() ? "UNKNOWN" : tailcatState;
        networks = networks == null ? List.of() : List.copyOf(networks);
        lastError = lastError == null || lastError.isBlank() ? null : lastError;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
